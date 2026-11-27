import { createHash, randomBytes, timingSafeEqual } from "node:crypto";
import type { FastifyInstance, FastifyRequest, FastifyReply } from "fastify";
import type { DatabaseSync } from "node:sqlite";
import type { Repository } from "./db/repo.js";

import { consoleDate, dayQuery, personalDay } from "./db/console-day.js";

type ReadScope = "body" | "context";
type ReadKeys = { bodyReadDigest?: Buffer; contextReadDigest?: Buffer; browserViewDigest?: Buffer };
const COOKIE = "__Host-hibi_body";
const PAIR_COOKIE = "__Secure-hibi_pair";
const PAIR_TTL = 300_000;
const SESSION_SECONDS = 30 * 24 * 60 * 60;

export function resolvePrivateReadScope(header: string | undefined, keys: ReadKeys): "body" | "context" | null {
  const token = /^Bearer ([A-Za-z0-9_-]{32,180})$/i.exec(header ?? "")?.[1];
  if (!token) return null;
  const digest = createHash("sha256").update(token).digest();
  // In case the same verifier is misconfigured twice, preserve the narrow scope.
  if (keys.bodyReadDigest?.length === 32 && timingSafeEqual(digest, keys.bodyReadDigest)) return "body";
  if (keys.contextReadDigest?.length === 32 && timingSafeEqual(digest, keys.contextReadDigest)) return "context";
  return null;
}

function cookieKey(req: FastifyRequest, name = COOKIE): string | null {
  const values = (req.headers.cookie ?? "").split(";").map(value => value.trim()).filter(value => value.startsWith(name + "="));
  if (values.length !== 1) return null;
  const session = values[0]!.slice(name.length + 1);
  return /^[A-Za-z0-9_-]{43}$/.test(session) ? sessionHash(session) : null;
}

function sessionHash(value: string): string {
  return createHash("sha256").update(value).digest("hex");
}

function cookie(value: string, maxAge: number): string {
  return `${COOKIE}=${value}; Path=/; Max-Age=${maxAge}; Secure; HttpOnly; SameSite=Strict`;
}

export function registerConsoleBody(app: FastifyInstance, repo: Pick<Repository,"liveBodyNow">, db: DatabaseSync, keys: ReadKeys,
    writePair: (proof: string) => Promise<string>,
    origin = process.env.HIBI_CONSOLE_ORIGIN?.trim() || `http://localhost:${process.env.PORT || 4823}`, clock = Date.now) {
  db.exec(`CREATE TABLE IF NOT EXISTS private_console_body_sessions (
    session_sha256 TEXT PRIMARY KEY NOT NULL CHECK(length(session_sha256) = 64),
    expires_at INTEGER NOT NULL
  ) WITHOUT ROWID`);
  // Existing cookies authenticated body readings only; keep that scope after upgrading.
  const columns = db.prepare("PRAGMA table_info(private_console_body_sessions)").all();
  if (!columns.some(column => column.name === "scope")) {
    db.exec("ALTER TABLE private_console_body_sessions ADD COLUMN scope TEXT NOT NULL DEFAULT 'body' CHECK(scope IN ('body','context'))");
  }
  const grant = db.prepare("INSERT INTO private_console_body_sessions (session_sha256, expires_at, scope) VALUES (?,?,?)");
  const revoke = db.prepare("DELETE FROM private_console_body_sessions WHERE session_sha256=?");
  const purge = db.prepare("DELETE FROM private_console_body_sessions WHERE expires_at<=?");
  const valid = db.prepare("SELECT scope FROM private_console_body_sessions WHERE session_sha256=? AND expires_at>?");
  const sessionUrl = "/api/console/session";
  const bodyUrl = "/api/console/body";
  const dayUrl = "/api/console/day";
  const options = {
    config: { cors: false as const },
    logLevel: "silent" as const,
    bodyLimit: 2048,
    onRequest: async (req: FastifyRequest, reply: FastifyReply) => {
      reply.header("Cache-Control", "private, no-store").header("Vary", "Origin, Cookie")
        .header("Referrer-Policy", "no-referrer").header("X-Robots-Tag","noindex, nofollow")
        .header("Cross-Origin-Resource-Policy", "same-origin");
      if ((req.headers.origin !== undefined && req.headers.origin !== origin)
          || ["cross-site","same-site"].includes(req.headers["sec-fetch-site"] as string)) {
        return reply.code(403).send({error:"same-origin only"});
      }
      if (req.routeOptions.url !== dayUrl && Object.keys(req.query ?? {}).length) return reply.code(400).send({error:"unexpected query"});
    },
  };

  type Pair = { expires: number; proofDigest: Buffer; matchCode: string; notionUrl: string | null };
  const pairs = new Map<string, Pair>();
  let nextStartAt = 0;
  const pairUrl = "/api/console/notion";
  const challengeCookie = (value: string, seconds: number) =>
    `${PAIR_COOKIE}=${value}; Path=${pairUrl}; Max-Age=${seconds}; Secure; HttpOnly; SameSite=Lax`;
  const expirePairs = (now: number) => {
    for (const [key, pair] of pairs) if (pair.expires <= now) pairs.delete(key);
  };
  function issue(req: FastifyRequest, reply: FastifyReply, scope: ReadScope): string {
    const now = clock();
    const old = cookieKey(req);
    const session = randomBytes(32).toString("base64url");
    db.exec("SAVEPOINT grant_console_session");
    try {
      purge.run(now);
      if (old) revoke.run(old);
      grant.run(sessionHash(session), now + SESSION_SECONDS * 1000, scope);
      db.exec("RELEASE grant_console_session");
    } catch (err) {
      db.exec("ROLLBACK TO grant_console_session; RELEASE grant_console_session");
      throw err;
    }
    reply.code(204);
    return cookie(session, SESSION_SECONDS);
  }
  const originAllowed = (req: FastifyRequest) => req.headers.origin === origin;
  app.addHook("onClose", async () => pairs.clear());
  for (const url of [sessionUrl,bodyUrl,dayUrl,pairUrl+"/start",pairUrl+"/confirm"]) {
    app.options(url, options, async (_req,reply) => reply.code(204).send());
  }

  app.post("/api/console/view", options, async (req,reply) => {
    if (!originAllowed(req)) return reply.code(403).send({error:"same-origin only"});
    const key = (req.body as {key?: unknown} | null)?.key;
    const digest = keys.browserViewDigest;
    if (!digest || digest.length !== 32 || typeof key !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(key)
      || !timingSafeEqual(createHash("sha256").update(key).digest(), digest)) {
      return reply.code(401).send({error:"private timeline link required"});
    }
    return reply.header("Set-Cookie", issue(req, reply, "context")).send();
  });

  app.post(sessionUrl, options, async (req,reply) => {
    if (!originAllowed(req)) return reply.code(403).send({error:"same-origin only"});
    const scope = resolvePrivateReadScope(req.headers.authorization, keys);
    if (!scope) return reply.code(401).send({error:"read credential required"});
    return reply.header("Set-Cookie",issue(req,reply,scope)).send();
  });

  app.post(pairUrl+"/start", options, async (req,reply) => {
    if (!originAllowed(req)) return reply.code(403).send({error:"same-origin only"});
    const now = clock();
    expirePairs(now);
    const key = cookieKey(req,PAIR_COOKIE);
    const pending = key ? pairs.get(key) : undefined;
    if (pending?.notionUrl) return {notionUrl:pending.notionUrl,
      expiresIn:Math.ceil((pending.expires-now)/1000),matchCode:pending.matchCode};
    // The three shared slots also bound the rate of writes to the private page.
    if (pending || pairs.size >= 3 || now < nextStartAt) {
      const retry = Math.max(1,Math.ceil(((pairs.size >= 3 ? Math.min(...Array.from(pairs.values(),p=>p.expires)) : nextStartAt)-now)/1000));
      return reply.header("Retry-After",String(retry)).code(429).send({error:"pairing busy"});
    }
    nextStartAt = now + 5_000;
    const challenge = randomBytes(32).toString("base64url");
    const proof = randomBytes(32).toString("base64url");
    const id = sessionHash(challenge);
    const pair: Pair = {expires:now+PAIR_TTL, proofDigest:createHash("sha256").update(proof).digest(),
      matchCode:proof.slice(-4),notionUrl:null};
    pairs.set(id,pair);
    try {
      pair.notionUrl = await writePair(proof);
    } catch {
      pairs.delete(id);
      return reply.code(503).send({error:"Notion confirmation unavailable"});
    }
    const expiresIn = Math.ceil((pair.expires-clock())/1000);
    if (expiresIn <= 0) { pairs.delete(id); return reply.code(503).send({error:"request expired"}); }
    reply.header("Set-Cookie",challengeCookie(challenge,expiresIn));
    return {notionUrl:pair.notionUrl,expiresIn,matchCode:pair.matchCode};
  });

  app.post(pairUrl+"/confirm", options, async (req,reply) => {
    if (!originAllowed(req)) return reply.code(403).send({error:"same-origin only"});
    expirePairs(clock());
    const key = cookieKey(req,PAIR_COOKIE);
    const pending = key ? pairs.get(key) : undefined;
    const payload = req.body as {proof?: unknown} | null;
    const proof = payload?.proof;
    if (!key || !pending?.notionUrl || !payload || Object.keys(payload).length !== 1 ||
        typeof proof !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(proof) ||
        !timingSafeEqual(pending.proofDigest,createHash("sha256").update(proof).digest())) {
      return reply.code(403).send({error:"confirmation expired or belongs to another browser"});
    }
    const issuedCookie = issue(req,reply,keys.contextReadDigest ? "context" : "body");
    pairs.delete(key);
    return reply.header("Set-Cookie",[challengeCookie("",0),issuedCookie]).send();
  });

  app.delete(sessionUrl, options, async (req,reply) => {
    if (req.headers.origin !== origin) return reply.code(403).send({error:"same-origin only"});
    const old = cookieKey(req);
    if (old) revoke.run(old);
    return reply.header("Set-Cookie",cookie("",0)).code(204).send();
  });

  app.get(dayUrl, { ...options, exposeHeadRoute:false }, async (req,reply) => {
    const key = cookieKey(req);
    const session = key ? valid.get(key, clock()) : undefined;
    if (!session) return reply.code(401).send({error:"browser authorization required"});
    const query = dayQuery.safeParse(req.query);
    if (!query.success) return reply.code(400).send({error:"date must be YYYY-MM-DD"});
    const day = consoleDate(query.data.date, new Date(clock()));
    return personalDay(db, day, session.scope === "context");
  });

  app.get(bodyUrl, { ...options, exposeHeadRoute: false }, async (req,reply) => {
    const key = cookieKey(req);
    if (!key || !valid.get(key, clock())) {
      return reply.code(401).send({error:"browser authorization required"});
    }
    try {
      const { asOf, timeZone, freshWithinSeconds, source, heartRate, stepsToday, lastCompletedSleep, currentSleep, sleepRecordChecks, wristBattery, oxygenSaturation, distance, lastWakeMarker } = repo.liveBodyNow();
      return { asOf, timeZone, freshWithinSeconds, source, heartRate, stepsToday, lastCompletedSleep, currentSleep, sleepRecordChecks, wristBattery, oxygenSaturation, distance, lastWakeMarker };
    } catch {
      return reply.code(503).send({error:"body readings unavailable"});
    }
  });
}
