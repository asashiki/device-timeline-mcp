import { readFileSync } from "node:fs";
import { request as httpRequest } from "node:http";
import { join } from "node:path";
import type { FastifyInstance } from "fastify";

const SOCKET = process.env.HIBI_NOTION_SOCKET?.trim() || "/run/hibi-now/refresh.sock";
const FRESH_MS = 45_000;
const RETRY_MS = 5_000;

function pageId(value: unknown): string | null {
  if (typeof value !== "string" || !/^(?:[a-f0-9]{32}|[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12})$/i.test(value)) return null;
  return value.replaceAll("-", "").toLowerCase();
}

function readConfigFile(dir: string, name: string) {
  try { return readFileSync(join(dir, name), "utf8").trim(); }
  catch { return ""; }
}

function readReceipt(socketPath: string, token: string, proof?: string): Promise<string> {
  const payload = proof ? JSON.stringify({proof}) : undefined;
  return new Promise((resolve, reject) => {
    const req = httpRequest({
      socketPath, path: proof ? "/console-pair" : "/refresh", method: "POST", agent: false,
      headers: { Authorization: "Bearer " + token, Accept: "application/json",
        ...(payload ? {"Content-Type": "application/json", "Content-Length": Buffer.byteLength(payload)} : {}) },
      signal: AbortSignal.timeout(12_000),
    }, response => {
      response.on("error", reject);
      if (response.statusCode !== 200) {
        reject(new Error("Unavailable refresh service"));
        response.destroy();
        return;
      }
      const chunks: Buffer[] = [];
      let bytes = 0;
      response.on("data", (chunk: Buffer) => {
        bytes += chunk.length;
        if (bytes > 1024) {
          reject(new Error("Oversize refresh receipt"));
          response.destroy();
          return;
        }
        chunks.push(chunk);
      });
      response.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    });
    req.on("error", reject);
    req.end(payload);
  });
}

export function notionPairWriter(configDir: string, socketPath = SOCKET) {
  const expectedPage = pageId(process.env.HIBI_NOTION_PAGE_ID?.trim() || readConfigFile(configDir, "notion-now-page.id"));
  const token = readConfigFile(configDir, "notion-now-bridge.token");
  return async (proof: string) => {
    if (!expectedPage || !/^[A-Za-z0-9_-]{32,180}$/.test(token)) throw new Error("Pairing unavailable");
    const receipt = JSON.parse(await readReceipt(socketPath, token, proof));
    if (!receipt || Object.keys(receipt).length !== 1 || pageId(receipt.pageId) !== expectedPage)
      throw new Error("Pairing unavailable");
    return "https://app.notion.com/p/" + expectedPage;
  };
}

export function registerNotionNow(app: FastifyInstance, configDir: string, socketPath = SOCKET, clock = Date.now) {
  // Configuration, never user input, selects the target and the only redirect.
  const expectedPage = pageId(process.env.HIBI_NOTION_PAGE_ID?.trim() || readConfigFile(configDir, "notion-now-page.id"));
  const notionUrl = expectedPage ? "https://app.notion.com/p/" + expectedPage : null;
  const token = readConfigFile(configDir, "notion-now-bridge.token");
  let pending: Promise<string | null> | undefined;
  let checkedAt: string | null = null;
  let expires = 0;

  async function refresh(): Promise<string | null> {
    if (!expectedPage || !/^[A-Za-z0-9_-]{32,180}$/.test(token)) return null;
    const receipt = JSON.parse(await readReceipt(socketPath, token));
    if (!receipt || Object.keys(receipt).length !== 2 || pageId(receipt.pageId) !== expectedPage ||
        typeof receipt.checkedAt !== "string" ||
        !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?(?:Z|[+-]\d\d:\d\d)$/.test(receipt.checkedAt)) return null;
    const time = Date.parse(receipt.checkedAt);
    const age = clock() - time;
    return Number.isFinite(time) && age >= -5000 && age < FRESH_MS ? new Date(time).toISOString() : null;
  }

  function snapshotReceipt() {
    if (clock() < expires) return Promise.resolve(checkedAt);
    if (!pending) pending = refresh().catch(() => null).then(value => {
      checkedAt = value;
      expires = value ? Math.min(Date.parse(value), clock()) + FRESH_MS : clock() + RETRY_MS;
      return value;
    }).finally(() => { pending = undefined; });
    return pending;
  }

  const configured = Boolean(expectedPage && /^[A-Za-z0-9_-]{32,180}$/.test(token));

  app.get("/now", {exposeHeadRoute: false, logLevel: "silent"}, async (req, reply) => {
    reply.header("Cache-Control", "private, no-store").header("Vary", "Accept")
      .header("Referrer-Policy", "no-referrer").header("X-Robots-Tag", "noindex, nofollow");
    if (Object.keys(req.query ?? {}).length) return reply.code(400).send({error:"此入口无需参数。"});
    const json = (req.headers.accept ?? "").split(",").some(part => {
      const [type, ...params] = part.trim().toLowerCase().split(";");
      return type?.trim() === "application/json" && !params.some(p => /^\s*q\s*=\s*0(?:\.0*)?\s*$/.test(p));
    });
    const stamp = await snapshotReceipt();
    if (stamp && notionUrl) {
      if (json) return { notionUrl, readWithNotion: true, checkedAt: stamp };
      return reply.redirect(notionUrl, 302);
    }
    reply.code(503).header("Retry-After", "5");
    const error = "暂时未能刷新，页面可能保留上一次的记录。";
    if (json) return { notionUrl, readWithNotion: !!notionUrl, checkedAt: null, error };
    const link = notionUrl ? ` <a href="${notionUrl}">打开私有 Notion 页面</a>` : "";
    return reply.type("text/html; charset=utf-8").send(`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Hibi</title><p>${error}${link}</p></html>`);
  });
  return configured;
}
