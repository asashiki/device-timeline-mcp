import type { DatabaseSync } from "node:sqlite";
import { onlineWindowSeconds, type DeviceToken, type Platform } from "../config.js";
import { dayRangeUtc, toUtcIso } from "../time.js";

export interface ReportInput {
  device: DeviceToken;
  appId: string | null;
  windowTitle: string | null;
  occurredAt: string; // already normalized to UTC ISO
  extra: Record<string, unknown> | null;
}

export interface DeviceStateOut {
  deviceId: string;
  deviceName: string;
  platform: Platform;
  appId: string | null;
  windowTitle: string | null;
  lastSeenAt: string;
  isOnline: boolean;
  extra: Record<string, unknown> | null;
}

export interface ActivityOut {
  id: number;
  deviceId: string;
  appId: string;
  windowTitle: string | null;
  startedAt: string;
  endedAt: string | null;
  durationSeconds: number | null;
  extra: Record<string, unknown> | null;
}

export interface AppSummaryOut {
  appId: string;
  windowTitle: string | null;
  totalSeconds: number;
  count: number;
}

export interface HealthRecordIn {
  type: string;
  value?: number | null;
  valueJson?: Record<string, unknown> | null;
  unit?: string | null;
  source?: string | null;
  recordedAt: string; // ISO
}

export interface HealthRecordOut {
  deviceId: string;
  type: string;
  value: number | null;
  valueJson: Record<string, unknown> | null;
  unit: string | null;
  source: string | null;
  recordedAt: string;
}

export interface HealthSummaryOut {
  type: string;
  unit: string | null;
  count: number;
  sum: number | null;
  min: number | null;
  max: number | null;
  avg: number | null;
  latest: { value: number | null; valueJson: Record<string, unknown> | null; recordedAt: string };
}

interface ActivityRow {
  id: number;
  device_id: string;
  app_id: string;
  window_title: string | null;
  started_at: string;
  ended_at: string | null;
  extra_json: string | null;
}

function parseExtra(json: string | null): Record<string, unknown> | null {
  if (!json) return null;
  try {
    return JSON.parse(json) as Record<string, unknown>;
  } catch {
    return null;
  }
}

function seconds(startIso: string, endIso: string): number {
  return Math.max(0, Math.round((new Date(endIso).getTime() - new Date(startIso).getTime()) / 1000));
}

export class Repository {
  constructor(
    private readonly db: DatabaseSync,
    private readonly graceSeconds: number,
  ) {}

  report(input: ReportInput): void {
    const { device, appId, windowTitle, occurredAt, extra } = input;
    const extraJson = extra ? JSON.stringify(extra) : null;

    this.db
      .prepare(
        `INSERT INTO device_states
           (device_id, device_name, platform, app_id, window_title, last_seen_at, extra_json, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(device_id) DO UPDATE SET
           device_name = excluded.device_name,
           platform    = excluded.platform,
           app_id      = excluded.app_id,
           window_title= excluded.window_title,
           last_seen_at= excluded.last_seen_at,
           extra_json  = excluded.extra_json,
           updated_at  = excluded.updated_at`,
      )
      .run(
        device.deviceId,
        device.deviceName,
        device.platform,
        appId,
        windowTitle,
        occurredAt,
        extraJson,
        occurredAt,
      );

    if (!appId) return; // no foreground app → state only, no activity row

    const last = this.db
      .prepare(
        `SELECT id, app_id, started_at, ended_at FROM device_activities
         WHERE device_id = ? ORDER BY started_at DESC LIMIT 1`,
      )
      .get(device.deviceId) as
      | { id: number; app_id: string; started_at: string; ended_at: string | null }
      | undefined;

    if (last && last.app_id === appId) {
      const ref = last.ended_at ?? last.started_at;
      if (seconds(ref, occurredAt) <= this.graceSeconds) {
        this.db
          .prepare(`UPDATE device_activities SET ended_at = ?, window_title = ?, extra_json = ? WHERE id = ?`)
          .run(occurredAt, windowTitle, extraJson, last.id);
        return;
      }
    }

    if (last && last.ended_at === null) {
      this.db
        .prepare(`UPDATE device_activities SET ended_at = started_at WHERE id = ?`)
        .run(last.id);
    }

    this.db
      .prepare(
        `INSERT INTO device_activities
           (device_id, app_id, window_title, started_at, ended_at, extra_json, created_at)
         VALUES (?, ?, ?, ?, NULL, ?, ?)`,
      )
      .run(device.deviceId, appId, windowTitle, occurredAt, extraJson, occurredAt);
  }

  // ── iOS event ingest (Shortcuts automations) ─────────────────────────────
  // iOS has no background agent, so devices report discrete open/close events
  // plus an optional hourly snapshot, rather than a ~10s poll.

  iosAppEvent(
    device: DeviceToken,
    app: string | null,
    action: "open" | "close",
    occurredAt: string,
  ): number | null {
    const STALE_MS = 2 * 60 * 60 * 1000;
    const cutoff = new Date(new Date(occurredAt).getTime() - STALE_MS).toISOString();

    // Force-close stale unfinished activities (covers missed close events).
    this.db
      .prepare(
        `UPDATE device_activities SET ended_at = ?
         WHERE device_id = ? AND ended_at IS NULL AND started_at < ?`,
      )
      .run(occurredAt, device.deviceId, cutoff);

    // Close any currently-open activity.
    this.db
      .prepare(
        `UPDATE device_activities SET ended_at = ?
         WHERE device_id = ? AND ended_at IS NULL`,
      )
      .run(occurredAt, device.deviceId);

    let activityId: number | null = null;
    if (action === "open" && app) {
      const result = this.db
        .prepare(
          `INSERT INTO device_activities
             (device_id, app_id, window_title, started_at, ended_at, extra_json, created_at)
           VALUES (?, ?, NULL, ?, NULL, NULL, ?)`,
        )
        .run(device.deviceId, app, occurredAt, occurredAt);
      activityId = Number(result.lastInsertRowid);
    }

    const existing = this.db
      .prepare(`SELECT extra_json FROM device_states WHERE device_id = ?`)
      .get(device.deviceId) as { extra_json: string | null } | undefined;
    const nextAppId = action === "open" ? app : null;

    this.db
      .prepare(
        `INSERT INTO device_states
           (device_id, device_name, platform, app_id, window_title, last_seen_at, extra_json, updated_at)
         VALUES (?, ?, ?, ?, NULL, ?, ?, ?)
         ON CONFLICT(device_id) DO UPDATE SET
           device_name  = excluded.device_name,
           platform     = excluded.platform,
           app_id       = excluded.app_id,
           window_title = excluded.window_title,
           last_seen_at = excluded.last_seen_at,
           extra_json   = excluded.extra_json,
           updated_at   = excluded.updated_at`,
      )
      .run(
        device.deviceId,
        device.deviceName,
        device.platform,
        nextAppId,
        occurredAt,
        existing?.extra_json ?? null,
        occurredAt,
      );

    return activityId;
  }

  iosSnapshot(
    device: DeviceToken,
    extra: Record<string, unknown>,
    occurredAt: string,
  ): void {
    const existing = this.db
      .prepare(`SELECT app_id, extra_json FROM device_states WHERE device_id = ?`)
      .get(device.deviceId) as { app_id: string | null; extra_json: string | null } | undefined;

    const merged = { ...(parseExtra(existing?.extra_json ?? null) ?? {}), ...extra };

    this.db
      .prepare(
        `INSERT INTO device_states
           (device_id, device_name, platform, app_id, window_title, last_seen_at, extra_json, updated_at)
         VALUES (?, ?, ?, ?, NULL, ?, ?, ?)
         ON CONFLICT(device_id) DO UPDATE SET
           device_name  = excluded.device_name,
           platform     = excluded.platform,
           last_seen_at = excluded.last_seen_at,
           extra_json   = excluded.extra_json,
           updated_at   = excluded.updated_at`,
      )
      .run(
        device.deviceId,
        device.deviceName,
        device.platform,
        existing?.app_id ?? null,
        occurredAt,
        JSON.stringify(merged),
        occurredAt,
      );
  }

  // Delete activity rows older than `days` days. device_states is one row per
  // device (current snapshot) so it's left untouched. Returns rows removed.
  purgeActivitiesOlderThan(days: number): number {
    if (!Number.isFinite(days) || days <= 0) return 0;
    const cutoff = new Date(Date.now() - days * 24 * 60 * 60 * 1000).toISOString();
    const result = this.db
      .prepare(`DELETE FROM device_activities WHERE started_at < ?`)
      .run(cutoff);
    return Number(result.changes);
  }

  // ── Health Connect ────────────────────────────────────────────────────────
  insertHealth(deviceId: string, records: HealthRecordIn[]): number {
    if (!records.length) return 0;
    const now = new Date().toISOString();
    const stmt = this.db.prepare(
      `INSERT INTO device_health
         (device_id, type, value, value_json, unit, source, recorded_at, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(device_id, type, recorded_at) DO UPDATE SET
         value      = excluded.value,
         value_json = excluded.value_json,
         unit       = excluded.unit,
         source     = excluded.source`,
    );
    let n = 0;
    this.db.exec("BEGIN");
    try {
      for (const r of records) {
        if (!r.type || !r.recordedAt) continue;
        stmt.run(
          deviceId,
          r.type,
          typeof r.value === "number" ? r.value : null,
          r.valueJson ? JSON.stringify(r.valueJson) : null,
          r.unit ?? null,
          r.source ?? null,
          r.recordedAt,
          now,
        );
        n++;
      }
      this.db.exec("COMMIT");
    } catch (err) {
      this.db.exec("ROLLBACK");
      throw err;
    }
    return n;
  }

  purgeHealthOlderThan(days: number): number {
    if (!Number.isFinite(days) || days <= 0) return 0;
    const cutoff = new Date(Date.now() - days * 24 * 60 * 60 * 1000).toISOString();
    const result = this.db.prepare(`DELETE FROM device_health WHERE recorded_at < ?`).run(cutoff);
    return Number(result.changes);
  }

  healthRecords(opts: { deviceId?: string; type?: string; sinceIso?: string; limit: number }): HealthRecordOut[] {
    const params: Array<string | number> = [];
    let sql = `SELECT device_id, type, value, value_json, unit, source, recorded_at FROM device_health WHERE 1=1`;
    if (opts.deviceId) { sql += ` AND device_id = ?`; params.push(opts.deviceId); }
    if (opts.type) { sql += ` AND type = ?`; params.push(opts.type); }
    if (opts.sinceIso) { sql += ` AND recorded_at >= ?`; params.push(opts.sinceIso); }
    sql += ` ORDER BY recorded_at DESC LIMIT ?`;
    params.push(opts.limit);
    const rows = this.db.prepare(sql).all(...params) as Array<{
      device_id: string; type: string; value: number | null; value_json: string | null;
      unit: string | null; source: string | null; recorded_at: string;
    }>;
    return rows.map((r) => ({
      deviceId: r.device_id,
      type: r.type,
      value: r.value,
      valueJson: parseExtra(r.value_json),
      unit: r.unit,
      source: r.source,
      recordedAt: r.recorded_at,
    }));
  }

  healthSummary(opts: { deviceId?: string; sinceIso?: string }): HealthSummaryOut[] {
    const params: Array<string> = [];
    let where = `WHERE 1=1`;
    if (opts.deviceId) { where += ` AND device_id = ?`; params.push(opts.deviceId); }
    if (opts.sinceIso) { where += ` AND recorded_at >= ?`; params.push(opts.sinceIso); }
    const aggRows = this.db.prepare(
      `SELECT type,
              MAX(unit) AS unit,
              COUNT(*) AS count,
              SUM(value) AS sum,
              MIN(value) AS min,
              MAX(value) AS max,
              AVG(value) AS avg
         FROM device_health ${where}
        GROUP BY type`,
    ).all(...params) as Array<{
      type: string; unit: string | null; count: number;
      sum: number | null; min: number | null; max: number | null; avg: number | null;
    }>;

    const latestStmt = this.db.prepare(
      `SELECT value, value_json, recorded_at FROM device_health
       ${where} AND type = ? ORDER BY recorded_at DESC LIMIT 1`,
    );
    return aggRows.map((a) => {
      const lr = latestStmt.get(...params, a.type) as
        { value: number | null; value_json: string | null; recorded_at: string } | undefined;
      return {
        type: a.type,
        unit: a.unit,
        count: Number(a.count),
        sum: a.sum,
        min: a.min,
        max: a.max,
        avg: a.avg,
        latest: {
          value: lr?.value ?? null,
          valueJson: parseExtra(lr?.value_json ?? null),
          recordedAt: lr?.recorded_at ?? "",
        },
      };
    });
  }

  currentStates(): DeviceStateOut[] {
    const rows = this.db
      .prepare(`SELECT * FROM device_states ORDER BY last_seen_at DESC`)
      .all() as Array<{
      device_id: string;
      device_name: string;
      platform: Platform;
      app_id: string | null;
      window_title: string | null;
      last_seen_at: string;
      extra_json: string | null;
    }>;

    const now = Date.now();
    return rows.map((r) => ({
      deviceId: r.device_id,
      deviceName: r.device_name,
      platform: r.platform,
      appId: r.app_id,
      windowTitle: r.window_title,
      lastSeenAt: r.last_seen_at,
      isOnline: (now - new Date(r.last_seen_at).getTime()) / 1000 <= onlineWindowSeconds(r.platform),
      extra: parseExtra(r.extra_json),
    }));
  }

  private activitiesForDay(date: string, deviceId?: string): ActivityRow[] {
    const { start, end } = dayRangeUtc(date);
    const params: Array<string | number | null> = [start, end];
    let sql = `SELECT * FROM device_activities WHERE started_at >= ? AND started_at < ?`;
    if (deviceId) {
      sql += ` AND device_id = ?`;
      params.push(deviceId);
    }
    sql += ` ORDER BY started_at ASC`;
    return this.db.prepare(sql).all(...params) as unknown as ActivityRow[];
  }

  timeline(date: string, deviceId: string | undefined, limit: number): ActivityOut[] {
    const rows = this.activitiesForDay(date, deviceId);
    const sliced = limit > 0 ? rows.slice(-limit) : rows;
    return sliced.map((r) => ({
      id: r.id,
      deviceId: r.device_id,
      appId: r.app_id,
      windowTitle: r.window_title,
      startedAt: r.started_at,
      endedAt: r.ended_at,
      durationSeconds: r.ended_at ? seconds(r.started_at, r.ended_at) : null,
      extra: parseExtra(r.extra_json),
    }));
  }

  activitySummary(
    date: string,
    deviceId?: string,
  ): { perApp: AppSummaryOut[]; totalSeconds: number } {
    const rows = this.activitiesForDay(date, deviceId);
    const byApp = new Map<string, AppSummaryOut>();
    let totalSeconds = 0;
    for (const r of rows) {
      const dur = r.ended_at ? seconds(r.started_at, r.ended_at) : 0;
      totalSeconds += dur;
      const existing = byApp.get(r.app_id);
      if (existing) {
        existing.totalSeconds += dur;
        existing.count += 1;
        if (r.window_title) existing.windowTitle = r.window_title;
      } else {
        byApp.set(r.app_id, {
          appId: r.app_id,
          windowTitle: r.window_title,
          totalSeconds: dur,
          count: 1,
        });
      }
    }
    const perApp = [...byApp.values()].sort((a, b) => b.totalSeconds - a.totalSeconds);
    return { perApp, totalSeconds };
  }
}
