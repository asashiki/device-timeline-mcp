import type { FastifyInstance } from "fastify";
import { z } from "zod";
import type { Repository } from "./db/repo.js";
import type { DeviceAuth } from "./device-auth.js";
import type { Labels } from "./labels/labels.js";
import { LATEST_VERSION } from "./db/migrations.js";
import { todayInTz, toUtcIso } from "./time.js";

const reportSchema = z.object({
  appId: z.string().max(256).nullable().optional(),
  windowTitle: z.string().max(512).nullable().optional(),
  occurredAt: z.string().optional(),
  extra: z.record(z.unknown()).nullable().optional(),
});

export interface RouteContext {
  repo: Repository;
  auth: DeviceAuth;
  labels: Labels;
  rawLabels: Record<string, unknown>;
}

export function registerRoutes(app: FastifyInstance, ctx: RouteContext): void {
  const { repo, auth, labels } = ctx;

  app.get("/health", async () => ({
    status: "ok",
    service: "device-timeline-mcp",
    schemaVersion: LATEST_VERSION,
    time: new Date().toISOString(),
  }));

  // ── Ingest (agents POST here with their device Bearer token) ──────────────
  app.post("/api/devices/report", async (request, reply) => {
    const device = auth.resolve(request.headers.authorization);
    if (!device) return reply.code(401).send({ error: "invalid device token" });

    const parsed = reportSchema.safeParse(request.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: "invalid payload", details: parsed.error.flatten() });
    }
    const { appId, windowTitle, occurredAt, extra } = parsed.data;
    repo.report({
      device,
      appId: appId ?? null,
      windowTitle: windowTitle ?? null,
      occurredAt: toUtcIso(occurredAt),
      extra: extra ?? null,
    });
    return reply.code(202).send({ ok: true });
  });

  // ── iOS ingest (Shortcuts personal automations, Bearer device token) ──────
  const iosEventSchema = z.object({
    app: z.string().max(256).optional(),
    action: z.enum(["open", "close"]),
    occurredAt: z.string().optional(),
  });

  app.post("/api/devices/ios/app-event", async (request, reply) => {
    const device = auth.resolve(request.headers.authorization);
    if (!device) return reply.code(401).send({ error: "invalid device token" });

    const parsed = iosEventSchema.safeParse(request.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: "invalid payload", details: parsed.error.flatten() });
    }
    const { action, occurredAt } = parsed.data;
    const appName = parsed.data.app?.trim() || null;
    if (action === "open" && !appName) {
      return reply.code(400).send({ error: "'open' events require an 'app' field" });
    }
    const activityId = repo.iosAppEvent(device, appName, action, toUtcIso(occurredAt));
    return reply.code(202).send({ ok: true, action, app: appName, activityId });
  });

  const iosSnapshotSchema = z.object({
    batteryLevel: z.number().optional(),
    isCharging: z.boolean().optional(),
    isUnlocked: z.boolean().optional(),
    focusMode: z.string().optional(),
    occurredAt: z.string().optional(),
  });

  app.post("/api/devices/ios/snapshot", async (request, reply) => {
    const device = auth.resolve(request.headers.authorization);
    if (!device) return reply.code(401).send({ error: "invalid device token" });

    const parsed = iosSnapshotSchema.safeParse(request.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: "invalid payload", details: parsed.error.flatten() });
    }
    const b = parsed.data;
    const extra: Record<string, unknown> = {};
    if (typeof b.batteryLevel === "number") extra.battery_percent = b.batteryLevel;
    if (typeof b.isCharging === "boolean") extra.battery_charging = b.isCharging;
    if (typeof b.isUnlocked === "boolean") extra.is_unlocked = b.isUnlocked;
    if (b.focusMode && b.focusMode.trim()) extra.focus_mode = b.focusMode.trim();

    repo.iosSnapshot(device, extra, toUtcIso(b.occurredAt));
    return reply.code(202).send({ ok: true, extra });
  });

  // ── Read API (public, read-only — for the console + your own frontends) ───
  app.get("/api/devices/current", async () => {
    const devices = repo.currentStates().map((d) => ({
      ...d,
      appName: labels.name(d.appId),
      live: labels.liveDescription(d.appId, d.windowTitle),
    }));
    return { fetchedAt: new Date().toISOString(), devices };
  });

  const timelineQuery = z.object({
    date: z.string().optional(),
    deviceId: z.string().optional(),
    limit: z.coerce.number().int().positive().max(1000).optional(),
  });

  app.get("/api/devices/timeline-query", async (request, reply) => {
    const q = timelineQuery.safeParse(request.query);
    if (!q.success) return reply.code(400).send({ error: "invalid query" });
    const date = q.data.date ?? todayInTz();
    const activities = repo.timeline(date, q.data.deviceId, q.data.limit ?? 200).map((a) => ({
      ...a,
      appName: labels.name(a.appId),
      live: labels.liveDescription(a.appId, a.windowTitle, ""),
    }));
    return { date, fetchedAt: new Date().toISOString(), activities };
  });

  app.get("/api/devices/activity-summary", async (request, reply) => {
    const q = timelineQuery.safeParse(request.query);
    if (!q.success) return reply.code(400).send({ error: "invalid query" });
    const date = q.data.date ?? todayInTz();
    const { perApp, totalSeconds } = repo.activitySummary(date, q.data.deviceId);
    return {
      date,
      fetchedAt: new Date().toISOString(),
      perApp: perApp.map((p) => ({ ...p, appName: labels.name(p.appId) })),
      totalSeconds,
    };
  });

  // Raw label map, so custom frontends can render names without hardcoding it.
  app.get("/api/app-labels", async () => ctx.rawLabels);
}
