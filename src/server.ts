import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import cors from "@fastify/cors";
import formbody from "@fastify/formbody";
import {
  LATEST_PROTOCOL_VERSION,
  SUPPORTED_PROTOCOL_VERSIONS,
  createMcpHandler,
  validateHostHeader,
} from "@modelcontextprotocol/server";
import { toNodeHandler } from "@modelcontextprotocol/node";
import Fastify, { type FastifyReply, type FastifyRequest } from "fastify";
import { loadConfig, type AppConfig } from "./config.js";
import { openDatabase } from "./db/index.js";
import { Repository } from "./db/repo.js";
import { DeviceAuth } from "./device-auth.js";
import { Labels } from "./labels/labels.js";
import { createMcpServer } from "./mcp/tools.js";
import { registerOAuth, requestBearer, safeEqualText } from "./oauth.js";
import { ReadAuth } from "./read-auth.js";
import { registerRoutes } from "./routes.js";

const here = dirname(fileURLToPath(import.meta.url));
const PURGE_INTERVAL_MS = 24 * 60 * 60 * 1000;

function startRetention(repo: Repository, retentionDays: number): NodeJS.Timeout | null {
  if (!Number.isFinite(retentionDays) || retentionDays <= 0) return null;
  const sweep = () => {
    try {
      const activities = repo.purgeActivitiesOlderThan(retentionDays);
      const health = repo.purgeHealthOlderThan(retentionDays);
      if (activities > 0 || health > 0) {
        console.log(`[retention] purged ${activities} activities and ${health} health rows older than ${retentionDays}d`);
      }
    } catch (error) {
      console.error("[retention] sweep failed:", error);
    }
  };
  sweep();
  const timer = setInterval(sweep, PURGE_INTERVAL_MS);
  timer.unref?.();
  return timer;
}

function originAllowed(request: FastifyRequest, config: AppConfig): boolean {
  const origin = Array.isArray(request.headers.origin) ? request.headers.origin[0] : request.headers.origin;
  return !origin || config.allowedOrigins.length === 0 || config.allowedOrigins.includes(origin);
}

function mcpError(reply: FastifyReply, status: number, message: string) {
  return reply.code(status).type("application/json").send({
    jsonrpc: "2.0",
    error: { code: -32000, message },
    id: null,
  });
}

export interface TimelineAppOptions {
  logger?: boolean;
  internalApiBase?: string;
}

export async function createTimelineApp(config: AppConfig, options: TimelineAppOptions = {}) {
  const db = openDatabase(config.dbPath);
  const repo = new Repository(db, config.activityGraceSeconds);
  const deviceAuth = new DeviceAuth(config.deviceTokens);
  const readAuth = new ReadAuth(config.readApiToken, config.allowUnauthenticatedReadApi);
  const labels = new Labels(config.labelsPath);
  const app = Fastify({ logger: options.logger ?? true, bodyLimit: 1_000_000, trustProxy: 1 });

  await app.register(formbody, { bodyLimit: 32 * 1024 });
  await app.register(cors, {
    origin(origin, callback) {
      if (!origin || config.allowedOrigins.length === 0 || config.allowedOrigins.includes(origin)) {
        callback(null, true);
        return;
      }
      callback(null, false);
    },
    allowedHeaders: ["Accept", "Authorization", "Content-Type", "Mcp-Method", "Mcp-Name", "Mcp-Protocol-Version"],
  });

  registerRoutes(app, { repo, auth: deviceAuth, readAuth, labels, rawLabels: labels.all() });

  const consoleHtml = readFileSync(join(here, "../public/console.html"), "utf8");
  const serveConsole = async (_request: FastifyRequest, reply: FastifyReply) =>
    reply.type("text/html; charset=utf-8").send(consoleHtml);
  app.get("/", serveConsole);
  app.get("/console", serveConsole);

  const canonicalMcpResource = `${config.publicBaseUrl}${config.mcpHttpPath}`;
  const oauth = registerOAuth(app, {
    baseUrl: config.publicBaseUrl,
    resourceUrl: canonicalMcpResource,
    password: config.mcpAuthPassword,
    tokenSecret: config.mcpAuthTokenSecret,
    scope: config.mcpOAuthScope,
    allowLegacyResourceOmission: config.mcpOAuthAllowLegacyResourceOmission,
  });

  let internalApiBase = options.internalApiBase ?? `http://127.0.0.1:${config.port}`;
  app.addHook("onListen", async () => {
    if (options.internalApiBase) return;
    const address = app.addresses().find((candidate) => candidate.family === "IPv4") ?? app.addresses()[0];
    if (address) internalApiBase = `http://127.0.0.1:${address.port}`;
  });

  const mcpHandler = createMcpHandler(
    () => createMcpServer(internalApiBase, config.readApiToken ?? undefined),
    {
      legacy: "stateless",
      responseMode: "auto",
      onerror: (error) => app.log.error(error, "MCP request failed"),
    },
  );
  const nodeMcpHandler = toNodeHandler(mcpHandler);

  if (config.mcpHttpEnabled) {
    app.all(config.mcpHttpPath, async (request, reply) => {
      const host = validateHostHeader(request.headers.host, config.allowedHosts);
      if (!host.ok) return mcpError(reply, 403, host.message);
      if (!originAllowed(request, config)) return mcpError(reply, 403, "Origin not allowed");

      const bearer = requestBearer(request);
      const staticTokenAccepted = Boolean(
        bearer && config.mcpHttpToken && safeEqualText(bearer, config.mcpHttpToken),
      );
      const oauthTokenAccepted = oauth.authenticate(request.headers.authorization);
      if (!config.mcpAllowUnauthenticated && !staticTokenAccepted && !oauthTokenAccepted) {
        reply.header(
          "WWW-Authenticate",
          oauth.enabled ? oauth.challenge : 'Bearer realm="device-timeline-mcp"',
        );
        return reply.code(401).send({ error: "unauthorized" });
      }

      await nodeMcpHandler(request.raw, reply.raw, request.body);
      return reply;
    });
  }

  app.get("/healthz", async () => ({
    ok: true,
    service: "device-timeline-mcp",
    version: "0.2.0",
    latestProtocolVersion: LATEST_PROTOCOL_VERSION,
    supportedProtocolVersions: SUPPORTED_PROTOCOL_VERSIONS,
    mcpEndpoint: config.mcpHttpEnabled ? canonicalMcpResource : null,
  }));

  app.get("/diagnostics/security", async (_request, reply) => {
    reply.header("Cache-Control", "no-store");
    return {
      credentialDomains: {
        deviceIngest: config.deviceTokens.length,
        readApi: config.readApiToken ? "bearer" : "explicitly-unauthenticated",
        remoteMcp: oauth.enabled
          ? config.mcpHttpToken ? "oauth-or-static-bearer" : "oauth"
          : config.mcpHttpToken ? "static-bearer" : "explicitly-unauthenticated",
      },
      transport: {
        latestProtocolVersion: LATEST_PROTOCOL_VERSION,
        supportedProtocolVersions: SUPPORTED_PROTOCOL_VERSIONS,
        canonicalResource: config.mcpHttpEnabled ? canonicalMcpResource : null,
      },
      protections: {
        hostValidation: true,
        originValidation: true,
        oauthPkceS256: oauth.enabled,
        oauthResourceAudienceBinding: oauth.enabled,
      },
    };
  });

  const retentionTimer = startRetention(repo, config.retentionDays);
  app.addHook("onClose", async () => {
    if (retentionTimer) clearInterval(retentionTimer);
    await mcpHandler.close();
    db.close();
  });

  return app;
}

export async function main() {
  const config = loadConfig({ requirePublicBaseUrl: process.env.NODE_ENV === "production" });
  const app = await createTimelineApp(config);
  await app.listen({ port: config.port, host: config.host });
  console.log(`[device-timeline-mcp] listening on ${config.publicBaseUrl}`);
  console.log(`[device-timeline-mcp] ${config.deviceTokens.length} device token(s) loaded`);
}

const entrypoint = process.argv[1] ? resolve(process.argv[1]) : "";
if (entrypoint && fileURLToPath(import.meta.url) === entrypoint) {
  main().catch((error) => {
    console.error(error);
    process.exit(1);
  });
}
