import { createHash, timingSafeEqual } from 'node:crypto';
import { createWristMcp } from './wrist-mcp.js';
import { registerWrist } from './wrist-control.js';
import { startEstimateRecovery } from './db/recovered-sleep.js';
import { startSleepChecks } from './db/sleep-checks.js';
import { LIVE_HEALTH_DEVICE_ID } from './db/live-health.js';
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import Fastify from "fastify";
import cors from "@fastify/cors";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { loadConfig } from "./config.js";
import { openDatabase } from "./db/index.js";
import { Repository } from "./db/repo.js";
import { DeviceAuth } from "./device-auth.js";
import { Labels } from "./labels/labels.js";
import { registerConsoleBody } from "./console-body.js";
import { registerNotionNow, notionPairWriter } from "./notion-now.js";
import { registerRoutes } from "./routes.js";
import { createMcpServer } from "./mcp/tools.js";

const here = dirname(fileURLToPath(import.meta.url));
const PURGE_INTERVAL_MS = 24 * 60 * 60 * 1000;

function startRetention(repo: Repository, retentionDays: number): void {
  if (!Number.isFinite(retentionDays) || retentionDays <= 0) {
    console.log("[retention] disabled (RETENTION_DAYS <= 0)");
    return;
  }
  const sweep = () => {
    try {
      const removed = repo.purgeActivitiesOlderThan(retentionDays);
      if (removed > 0) console.log(`[retention] purged ${removed} activity row(s) older than ${retentionDays}d`);
      const removedHealth = repo.purgeHealthOlderThan(retentionDays);
      if (removedHealth > 0) console.log(`[retention] purged ${removedHealth} health row(s) older than ${retentionDays}d`);
    } catch (err) {
      console.error("[retention] sweep failed:", err);
    }
  };
  sweep(); // run once at startup
  const timer = setInterval(sweep, PURGE_INTERVAL_MS);
  timer.unref?.();
  console.log(`[retention] keeping ${retentionDays} days; sweeping every 24h`);
}

async function main() {
  const config = loadConfig();
  const db = openDatabase(config.dbPath);
  const repo = new Repository(db, config.activityGraceSeconds);
  const auth = new DeviceAuth(config.deviceTokens);
  const labels = new Labels(config.labelsPath);

  const app = Fastify({ logger: true, bodyLimit: 1_000_000 });
  await app.register(cors, { origin: config.corsOrigin === "*" ? true : config.corsOrigin.split(",") });

  function loadVerifier(path: string, configuredDigest?: string): Buffer | undefined {
    try {
      const digest = (configuredDigest ?? readFileSync(path, "utf8")).trim();
      return /^[0-9a-f]{64}$/i.test(digest) ? Buffer.from(digest, "hex") : undefined;
    } catch {
      return undefined;
    }
  }
  const verifierDir = dirname(config.labelsPath);
  const bodyReadDigest = loadVerifier(process.env.HIBI_BODY_READ_SHA256_FILE ?? join(verifierDir, "body-read.sha256"));
  const contextReadDigest = loadVerifier(
    process.env.HIBI_CONTEXT_READ_SHA256_FILE ?? join(verifierDir, "context-read.sha256"),
    process.env.AGENT_CONTEXT_READ_TOKEN_SHA256,
  );
  if (!bodyReadDigest) console.warn("[body-now] body-only read capability unavailable");
  if (!contextReadDigest) console.warn("[body-now] Mac context read capability unavailable");
  registerRoutes(app, { repo, auth, labels, rawLabels: labels.all(), retentionDays: config.retentionDays,
    bodyReadDigest, contextReadDigest });

  const wristDigest = loadVerifier(join(verifierDir, 'wrist-control.sha256'));
  registerWrist(app, db, auth, wristDigest);
  if (wristDigest) {
    app.all('/mcp/wrist', { logLevel: 'silent' }, async (req, reply) => {
      const token = /^Bearer ([A-Za-z0-9_-]{32,180})$/i.exec(req.headers.authorization ?? '')?.[1];
      if (!token || !timingSafeEqual(createHash('sha256').update(token).digest(), wristDigest))
        return reply.code(401).header('WWW-Authenticate','Bearer realm="Hibi wrist"').send({error:'Unauthorized'});
      const server = createWristMcp('http://127.0.0.1:' + config.port, token);
      const transport = new StreamableHTTPServerTransport({sessionIdGenerator: undefined});
      reply.hijack();
      reply.raw.on('close', () => { void transport.close(); void server.close(); });
      await server.connect(transport);
      await transport.handleRequest(req.raw, reply.raw, req.body);
    });
  }
  registerConsoleBody(app, repo, db, { bodyReadDigest, contextReadDigest }, notionPairWriter(verifierDir));
  const hasNotion = registerNotionNow(app, verifierDir);

  // Console: a zero-dependency static page that reads the public API.
  const consoleHtml = readFileSync(join(here, "../public/console.html"), "utf8")
    .replace('<body data-notion="unconfigured">', `<body data-notion="${hasNotion ? 'configured' : 'unconfigured'}">`);
  const serveConsole = async (_req: unknown, reply: import("fastify").FastifyReply) =>
    reply.type("text/html; charset=utf-8").send(consoleHtml);
  app.get("/", serveConsole);
  app.get("/console", serveConsole);

  // Remote MCP endpoint (streamable HTTP), so claude.ai / other web clients can
  // connect to https://<your-domain><path>. Stateless: a fresh server+transport
  // per request. Optionally guarded by a bearer token (MCP_HTTP_TOKEN).
  if (config.mcpHttpEnabled) {
    const selfBase = `http://127.0.0.1:${config.port}`;
    app.all(config.mcpHttpPath, async (req, reply) => {
      if (config.mcpHttpToken) {
        const header = req.headers.authorization ?? "";
        const provided = header.startsWith("Bearer ") ? header.slice(7) : "";
        if (provided !== config.mcpHttpToken) {
          return reply.code(401).send({ error: "unauthorized" });
        }
      }
      const server = createMcpServer(selfBase);
      const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined });
      reply.hijack();
      reply.raw.on("close", () => {
        transport.close();
        server.close();
      });
      await server.connect(transport);
      await transport.handleRequest(req.raw, reply.raw, req.body);
    });
  }

  await app.listen({ port: config.port, host: config.host });
  startEstimateRecovery(db, LIVE_HEALTH_DEVICE_ID);
  startSleepChecks(db, LIVE_HEALTH_DEVICE_ID);
  startRetention(repo, config.retentionDays);
  console.log(`[device-timeline-mcp] listening on http://${config.host}:${config.port}`);
  console.log(`[device-timeline-mcp] ${config.deviceTokens.length} device token(s) loaded`);
  if (config.mcpHttpEnabled) {
    console.log(
      `[device-timeline-mcp] MCP HTTP endpoint at ${config.mcpHttpPath}` +
        (config.mcpHttpToken ? " (bearer-protected)" : " (no auth — put it behind your own proxy/auth)"),
    );
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
