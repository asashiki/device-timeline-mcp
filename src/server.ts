import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import Fastify from "fastify";
import cors from "@fastify/cors";
import { loadConfig } from "./config.js";
import { openDatabase } from "./db/index.js";
import { Repository } from "./db/repo.js";
import { DeviceAuth } from "./device-auth.js";
import { Labels } from "./labels/labels.js";
import { registerRoutes } from "./routes.js";

const here = dirname(fileURLToPath(import.meta.url));

async function main() {
  const config = loadConfig();
  const db = openDatabase(config.dbPath);
  const repo = new Repository(db, config.activityGraceSeconds);
  const auth = new DeviceAuth(config.deviceTokens);
  const labels = new Labels(config.labelsPath);

  const app = Fastify({ logger: true, bodyLimit: 1_000_000 });
  await app.register(cors, { origin: config.corsOrigin === "*" ? true : config.corsOrigin.split(",") });

  registerRoutes(app, { repo, auth, labels, rawLabels: labels.all() });

  // Console: a zero-dependency static page that reads the public API.
  const consoleHtml = readFileSync(join(here, "../public/console.html"), "utf8");
  const serveConsole = async (_req: unknown, reply: import("fastify").FastifyReply) =>
    reply.type("text/html; charset=utf-8").send(consoleHtml);
  app.get("/", serveConsole);
  app.get("/console", serveConsole);

  await app.listen({ port: config.port, host: config.host });
  console.log(`[device-timeline-mcp] listening on http://${config.host}:${config.port}`);
  console.log(`[device-timeline-mcp] ${config.deviceTokens.length} device token(s) loaded`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
