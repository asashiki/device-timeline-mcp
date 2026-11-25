import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import { z } from "zod";
import { resolvePrivateReadScope } from "./console-body.js";
import type { Repository } from "./db/repo.js";
import type { DeviceAuth } from "./device-auth.js";
import { MAC_CONTEXT_DEVICE_ID, macSnapshot } from "./mac-context.js";
import type { Labels } from "./labels/labels.js";
import { onlineWindowSeconds } from "./config.js";
import { LATEST_VERSION } from "./db/migrations.js";
import { todayInTz, toUtcIso } from "./time.js";

const reportSchema = z.object({
  appId: z.string().max(256).nullable().optional(),
  windowTitle: z.string().max(512).nullable().optional(),
  occurredAt: z.string().optional(),
  extra: z.record(z.unknown()).nullable().optional(),
});

const playbackSchema = z.object({
  player: z.enum(["Apple Music", "com.apple.Music", "Spotify", "com.spotify.client"])
    .transform((player) => player === "Apple Music" || player === "com.apple.Music" ? "Apple Music" : "Spotify"),
  state: z.enum(["playing", "paused", "stopped"]),
  track: z.string().max(512).optional(),
  artist: z.string().max(512).optional(),
  occurredAt: z.string().max(40).datetime({ offset: true })
    .refine((value) => Number.isFinite(Date.parse(value))),
}).strict();

const playbackQuery = z.object({ limit: z.coerce.number().int().min(1).max(100).default(20) }).strict();

const bandSampleBase = {
  source: z.literal("hibi_band_direct"),
  recordedAt: z.string().max(40).datetime({ offset: true }).refine(v => Number.isFinite(Date.parse(v))),
};
const sessionTimestamp = z.string().max(40).datetime({offset:true});
const nativeProgress = z.object({
  sessionStart:sessionTimestamp,origin:z.literal("band_sleep_report"),timeBasis:z.literal("device_sample")
}).strict();
const observedGrowth = z.object({
  sessionStart:sessionTimestamp, origin:z.literal("band_live_progress"), timeBasis:z.literal("phone_observed_growth"),
  previousObservedAt:sessionTimestamp, previousMinutes:z.number().int().min(0).max(1440),
}).strict();
const bandBatch = z.object({ records: z.array(z.discriminatedUnion("type", [
  z.object({ ...bandSampleBase, type: z.literal("heart_rate"), unit: z.literal("bpm"), value: z.number().int().min(1).max(254) }).strict(),
  z.object({ ...bandSampleBase, type: z.literal("steps"), unit: z.literal("count"), value: z.number().int().min(0).max(16383) }).strict(),
  z.object({ ...bandSampleBase, type: z.literal("sleep_state"), unit: z.literal("state"), value: z.union([z.literal(0), z.literal(1)]),
    valueJson: z.object({origin:z.enum(["band_basic_status","band_status_event"]), timeBasis:z.literal("phone_observed")}).strict() }).strict(),
  z.object({ ...bandSampleBase, type: z.literal("sleep_progress"), unit: z.literal("minutes"), value: z.number().int().min(0).max(1440),
    valueJson: z.discriminatedUnion("origin",[nativeProgress, observedGrowth]) }).strict(),
  z.object({ ...bandSampleBase, type: z.literal("sleep_interval_progress"), unit:z.literal("state"), value:z.literal(1),
    valueJson:nativeProgress }).strict(),
  z.object({ ...bandSampleBase, type: z.literal("sleep_record_sample"), unit:z.literal("state"), value:z.literal(1),
    valueJson:z.object({sessionStart:sessionTimestamp}).strict() }).strict(),
  z.object({ ...bandSampleBase, type: z.literal("sleep_band_interval"), unit:z.literal("minutes"),
    value:z.number().int().min(1).max(1440), valueJson:z.object({endAt:sessionTimestamp}).strict() }).strict(),
  z.object({ ...bandSampleBase, type: z.literal("sleep_session"), unit: z.literal("minutes"),
    value: z.number().int().min(1).max(1440), valueJson:z.object({
      endAt: z.string().max(40).datetime({offset:true}), asleepMinutes:z.number().int().min(1).max(1440).optional()
    }).strict() }).strict(),
]).refine(r => {
  if (r.type === "sleep_progress" || r.type === "sleep_interval_progress") {
    const span = (Date.parse(r.recordedAt) - Date.parse(r.valueJson.sessionStart)) / 60000;
    if (!(span > 0 && span <= 1440)) return false;
    if (r.type === "sleep_interval_progress") return true;
    if (r.value > Math.ceil(span)) return false;
    const proof = r.valueJson;
    if (proof.origin === "band_sleep_report") return true;
    const gap = (Date.parse(r.recordedAt) - Date.parse(proof.previousObservedAt)) / 1000;
    const increase = r.value - proof.previousMinutes;
    return Date.parse(proof.previousObservedAt) >= Date.parse(proof.sessionStart)
      && gap >= 45 && gap <= 600 && increase > 0 && increase <= Math.ceil(gap / 60) + 1;
  }
  if (r.type === "sleep_record_sample") {
    const at = Date.parse(r.recordedAt);
    const span = at-Date.parse(r.valueJson.sessionStart);
    return at<=Date.now() && span>=0 && span<=86400000;
  }
  if (r.type === "sleep_band_interval") {
    const end = Date.parse(r.valueJson.endAt);
    const seconds = (end-Date.parse(r.recordedAt))/1000;
    return end<=Date.now() && seconds>=60 && seconds<=86400 && Math.floor(seconds/60)===r.value;
  }
  if (r.type !== "sleep_session") return true;
  const elapsed = (Date.parse(r.valueJson.endAt) - Date.parse(r.recordedAt)) / 60000;
  return Math.floor(elapsed) === r.value && (r.valueJson.asleepMinutes ?? 0) <= Math.ceil(elapsed);
})).min(1).max(240) }).strict();

export interface RouteContext {
  repo: Repository;
  auth: DeviceAuth;
  labels: Labels;
  rawLabels: Record<string, unknown>;
  retentionDays: number;
  bodyReadDigest?: Buffer | undefined;
  contextReadDigest?: Buffer | undefined;
}

export function registerRoutes(app: FastifyInstance, ctx: RouteContext): void {
  const { repo, auth, labels } = ctx;

  const mac = repo.macContext;
  app.addHook("onClose", async () => mac.clear());

  function privateHeaders(reply: FastifyReply) {
    reply.header("Cache-Control", "private, no-store").header("Vary", "Authorization")
      .header("X-Robots-Tag", "noindex, nofollow").header("Referrer-Policy", "no-referrer");
  }

  function authorizeBodyRead(request: FastifyRequest, reply: FastifyReply): "body" | "context" | null {
    privateHeaders(reply);
    const scope = resolvePrivateReadScope(request.headers.authorization, ctx);
    if (scope) return scope;
    reply.code(401).header("WWW-Authenticate",'Bearer realm="Hibi body"').send({error:"unauthorized"});
    return null;
  }

  app.get("/api/agent/body-now", async (request, reply) => {
    const scope = authorizeBodyRead(request, reply);
    if (!scope) return;
    if (Object.keys(request.query ?? {}).length) return reply.code(400).send({error:"no query parameters accepted"});
    const body = repo.liveBodyNow();
    if (scope === "body") return body;
    const now = Date.parse(body.asOf);
    const macSnapshot = mac.read(now);
    const devices = repo.currentStates().map(device => {
      const seenMs = Date.parse(device.lastSeenAt);
      const ageSeconds = Number.isFinite(seenMs) && seenMs <= now ? Math.floor((now - seenMs) / 1000) : null;
      const reportStatus = ageSeconds === null ? "unknown" : device.isOnline ? "recent" : "stale";
      const latest = repo.latestActivityObservation(device.deviceId);
      const recorded = latest && latest.appId === device.appId ? latest : null;
      const sampledAt = recorded ? recorded.endedAt ?? recorded.startedAt : null;
      const elapsed = sampledAt ? now - Date.parse(sampledAt) : NaN;
      const isMac = device.deviceId === macSnapshot.deviceId;
      const obsoleteMacApp = isMac && macSnapshot.state === "stale" && macSnapshot.sampledAt && sampledAt
        && Date.parse(macSnapshot.sampledAt) >= Date.parse(sampledAt);
      const recentActivity = reportStatus === "recent" && elapsed >= 0 && elapsed <= 300_000
        && (device.platform !== "ios" || recorded?.endedAt === null) && !obsoleteMacApp;
      const idle = /^(macos|android|windows)\.(afk|idle)$/.test(device.appId ?? "");
      const reportState = recentActivity ? (idle ? "idle" : "active") : "unknown";
      const privateMac = isMac &&
        macSnapshot.state !== "stale" && macSnapshot.state !== "unknown" ? macSnapshot : null;
      const differing = !!privateMac && recentActivity && (
        (privateMac.state === "active" && (idle || !!privateMac.appId && privateMac.appId !== device.appId)) ||
        (privateMac.state !== "active" && !idle)
      );
      const foreground = privateMac ? {
        source: "mac_context", state: privateMac.state, sampledAt: privateMac.sampledAt,
        appId: privateMac.appId, appName: privateMac.appName,
      } : {
        source: "activity_report", state: reportState, sampledAt,
        appId: reportState === "active" ? device.appId : null,
        appName: reportState === "active" ? labels.name(device.appId) : null,
      };
      return { deviceId: device.deviceId, deviceName: device.deviceName, platform: device.platform,
        lastSeenAt: device.lastSeenAt, ageSeconds, reportStatus, recentWithinSeconds: onlineWindowSeconds(device.platform),
        lastReportedAppId: device.appId, activitySampledAt: sampledAt, foreground,
        signalsDiffer: differing, batteryPercent:device.batteryPercent,charging:device.charging,
        powerSource:device.powerSource,batteryReportedAt:device.batteryReportedAt };
    });
    return { ...body, mac: macSnapshot, devices };
  });

  app.post("/api/devices/mac-context", {
    logLevel: "silent",
    bodyLimit: 8192,
    errorHandler(error, _request, reply) {
      privateHeaders(reply);
      return reply.code(error.statusCode ?? 500).send({error: "invalid Mac snapshot"});
    },
  }, async (request, reply) => {
    privateHeaders(reply);
    const device = auth.resolve(request.headers.authorization);
    if (!device) return reply.code(401).send({error: "invalid device token"});
    if (device.platform !== "macos" || device.deviceId !== MAC_CONTEXT_DEVICE_ID) {
      return reply.code(403).send({error: "wrong device"});
    }
    if (!ctx.contextReadDigest) return reply.code(503).send({error: "context reader not configured"});
    const result = macSnapshot.safeParse(request.body);
    if (!result.success || !mac.record(result.data)) return reply.code(400).send({error: "invalid Mac snapshot"});
    return reply.code(202).send({ok: true});
  });

  const rangeStamp = z.string().max(40).datetime({offset:true});
  const rangeQuery = z.object({from:rangeStamp, to:rangeStamp}).strict();
  app.get("/api/agent/body-range", async (request, reply) => {
    if (!authorizeBodyRead(request, reply)) return;
    const input = rangeQuery.safeParse(request.query);
    if (!input.success) return reply.code(400).send({error:"from and to must be ISO 8601 timestamps with timezone"});
    const start = Date.parse(input.data.from);
    const end = Date.parse(input.data.to);
    const now = new Date();
    if (!Number.isFinite(start) || !Number.isFinite(end) || start % 60000 !== 0 || end % 60000 !== 0 ||
        end <= start || end > now.getTime() || end - start > 7 * 86400000) {
      return reply.code(400).send({error:"use whole minutes, from < to <= now, up to 7 days"});
    }
    return repo.bodyRange(new Date(start).toISOString(), new Date(end).toISOString(), now);
  });

  app.get("/health", async () => ({
    status: "ok",
    service: "device-timeline-mcp",
    schemaVersion: LATEST_VERSION,
    time: new Date().toISOString(),
  }));

  app.get("/api/devices/storage", async () => ({
    retentionDays: Number.isFinite(ctx.retentionDays) && ctx.retentionDays > 0 ? ctx.retentionDays : null,
    ...repo.storageSummary(),
    fetchedAt: new Date().toISOString(),
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

  app.post("/api/devices/playback", { bodyLimit: 12_288 }, async (request, reply) => {
    const device = auth.resolve(request.headers.authorization);
    if (!device) return reply.code(401).send({ error: "invalid device token" });
    const parsed = playbackSchema.safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: "invalid playback event" });
    const { player, state, track, artist, occurredAt } = parsed.data;
    repo.recordPlayback(device.deviceId, {
      player, state, track: track?.trim() || null, artist: artist?.trim() || null,
      occurredAt: new Date(occurredAt).toISOString(),
    });
    return reply.code(202).send({ ok: true });
  });

  app.get("/api/devices/playback", async (request, reply) => {
    reply.header("Cache-Control", "private, no-store");
    const device = auth.resolve(request.headers.authorization);
    if (!device) return reply.code(401).send({ error: "invalid device token" });
    const parsed = playbackQuery.safeParse(request.query);
    if (!parsed.success) return reply.code(400).send({ error: "invalid playback query" });
    return { records: repo.recentPlayback(device.deviceId, parsed.data.limit) };
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

  // ── Health Connect ingest (Android agent, Bearer device token) ────────────
  app.post("/api/devices/band-health", { bodyLimit: 65536 }, async (request, reply) => {
    const device = auth.resolve(request.headers.authorization);
    if (!device) return reply.code(401).send({ error: "invalid device token" });
    if (device.platform !== "android") return reply.code(403).send({ error: "Android device required" });
    const batch = bandBatch.safeParse(request.body);
    if (!batch.success) return reply.code(400).send({ error: "invalid wearable data" });
    repo.saveBandSamples(device.deviceId, batch.data.records);
    return reply.code(202).header("Cache-Control", "no-store").send({ ok: true, accepted: batch.data.records.length });
  });

  const healthRecordSchema = z.object({
    type: z.string().min(1).max(64),
    value: z.number().nullable().optional(),
    valueJson: z.record(z.unknown()).nullable().optional(),
    unit: z.string().max(32).nullable().optional(),
    source: z.string().max(64).nullable().optional(),
    recordedAt: z.string().min(1),
  });
  const healthBatchSchema = z.object({ records: z.array(healthRecordSchema).max(1000) });

  app.post("/api/devices/health", async (request, reply) => {
    const device = auth.resolve(request.headers.authorization);
    if (!device) return reply.code(401).send({ error: "invalid device token" });

    const parsed = healthBatchSchema.safeParse(request.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: "invalid payload", details: parsed.error.flatten() });
    }
    const stored = repo.insertHealth(
      device.deviceId,
      parsed.data.records.map((r) => ({
        type: r.type,
        value: r.value ?? null,
        valueJson: r.valueJson ?? null,
        unit: r.unit ?? null,
        source: r.source ?? null,
        recordedAt: toUtcIso(r.recordedAt),
      })),
    );
    return reply.code(202).send({ ok: true, stored });
  });

  // ── Health read API (public, read-only) ───────────────────────────────────
  const healthQuery = z.object({
    deviceId: z.string().optional(),
    type: z.string().optional(),
    hours: z.coerce.number().positive().max(24 * 90).optional(),
    limit: z.coerce.number().int().positive().max(2000).optional(),
  });
  const sinceFromHours = (hours?: number): string | undefined =>
    hours ? new Date(Date.now() - hours * 3600 * 1000).toISOString() : undefined;

  app.get("/api/devices/health/summary", async (request, reply) => {
    const q = healthQuery.safeParse(request.query);
    if (!q.success) return reply.code(400).send({ error: "invalid query" });
    const sinceIso = sinceFromHours(q.data.hours ?? 24);
    return {
      sinceIso: sinceIso ?? null,
      fetchedAt: new Date().toISOString(),
      metrics: repo.healthSummary({ deviceId: q.data.deviceId, sinceIso }),
    };
  });

  app.get("/api/devices/health/records", async (request, reply) => {
    const q = healthQuery.safeParse(request.query);
    if (!q.success) return reply.code(400).send({ error: "invalid query" });
    const sinceIso = sinceFromHours(q.data.hours ?? 24);
    return {
      sinceIso: sinceIso ?? null,
      fetchedAt: new Date().toISOString(),
      records: repo.healthRecords({
        deviceId: q.data.deviceId,
        type: q.data.type,
        sinceIso,
        limit: q.data.limit ?? 500,
      }),
    };
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
