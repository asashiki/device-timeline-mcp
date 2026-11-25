import { readSleepChecks } from './sleep-checks.js';
import type { DatabaseSync } from "node:sqlite";
import { onlineWindowSeconds, type DeviceToken, type Platform } from "../config.js";
import { dayRangeUtc, toUtcIso } from "../time.js";
import { LIVE_HEALTH_DEVICE_ID, readLiveHealth } from "./live-health.js";
import { MacContext } from "../mac-context.js";
import { readBodyRange } from "./body-range.js";

export interface ReportInput {
  device: DeviceToken;
  appId: string | null;
  windowTitle: string | null;
  occurredAt: string; // already normalized to UTC ISO
  extra: Record<string, unknown> | null;
}

type DevicePower = {batteryPercent:number|null; charging:boolean|null; powerSource:string|null; batteryReportedAt:string|null};
function readPower(extra: Record<string,unknown> | null, stamp: string | null): DevicePower {
  const percent = extra?.battery_percent ?? extra?.batteryLevel;
  const state = extra?.battery_charging ?? extra?.isCharging;
  const source = extra?.power_source ?? extra?.powerSource;
  const batteryPercent = typeof percent === "number" && Number.isFinite(percent) && percent>=0 && percent<=100 ? percent : null;
  const charging = typeof state === "boolean" ? state : null;
  const powerSource = typeof source === "string" && ["battery","ac","ups","usb","wireless"].includes(source.toLowerCase()) ? source.toLowerCase() : null;
  return { batteryPercent,charging,powerSource,batteryReportedAt:(batteryPercent!==null || charging!==null || powerSource!==null) ? stamp : null };
}

export interface DeviceStateOut extends DevicePower {
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

export interface PlaybackEvent {
  player: "Apple Music" | "Spotify";
  state: "playing" | "paused" | "stopped";
  track: string | null;
  artist: string | null;
  occurredAt: string;
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
  readonly macContext = new MacContext();
  storageSummary() {
    const activity = this.db.prepare("SELECT COUNT(*) AS count, MIN(started_at) AS oldest, MAX(started_at) AS newest FROM device_activities").get();
    const health = this.db.prepare("SELECT COUNT(*) AS count, MIN(recorded_at) AS oldest, MAX(recorded_at) AS newest FROM device_health").get();
    return { activity, health };
  }

  constructor(
    private readonly db: DatabaseSync,
    private readonly graceSeconds: number,
  ) {}

  recordPlayback(deviceId: string, event: PlaybackEvent): void {
    this.db.prepare(`INSERT INTO device_playback (device_id, player, state, track, artist, occurred_at)
        VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(device_id, player, occurred_at) DO NOTHING`)
      .run(deviceId, event.player, event.state, event.track, event.artist, event.occurredAt);
  }

  recentPlayback(deviceId: string, limit: number): PlaybackEvent[] {
    return this.db.prepare(`SELECT player, state, track, artist, occurred_at AS occurredAt
        FROM device_playback WHERE device_id = ? ORDER BY occurred_at DESC, player LIMIT ?`)
      .all(deviceId, limit) as unknown as PlaybackEvent[];
  }

  liveBodyNow() {
    const body = readLiveHealth(this.db, LIVE_HEALTH_DEVICE_ID);
    const observed = body.currentSleep;
    const state = observed.state === 'asleep' || observed.state === 'likely_asleep' ? 'asleep' : 'awake';
    return {
      ...body,
      currentSleep: { ...observed, evidenceState: observed.state, state },
      sleepRecordChecks: readSleepChecks(this.db, LIVE_HEALTH_DEVICE_ID),
    };
  }

  bodyRange(from: string, to: string, now: Date) {
    return readBodyRange(this.db, LIVE_HEALTH_DEVICE_ID, from, to, now);
  }

  /** Local administrative import of a verified Mi Fitness log session. Replays are idempotent. */
  importVerifiedMiFitnessSleep(deviceId: string, sleep: { startedAt: string; endedAt: string; asleepMinutes: number },
                               receivedAt = new Date().toISOString()): void {
    if (deviceId !== LIVE_HEALTH_DEVICE_ID) throw new Error("Unexpected device for Mi Fitness import");
    const start = new Date(sleep.startedAt).toISOString();
    const end = new Date(sleep.endedAt).toISOString();
    const received = new Date(receivedAt).toISOString();
    const duration = (Date.parse(end) - Date.parse(start)) / 60_000;
    if (duration <= 0 || duration > 1440 || received < end || !Number.isInteger(sleep.asleepMinutes)
        || sleep.asleepMinutes < 1 || sleep.asleepMinutes > Math.ceil(duration)) {
      throw new Error("Invalid verified sleep session");
    }
    this.db.prepare(`INSERT INTO historical_sleep_imports (device_id, started_at, ended_at, asleep_minutes, received_at, source)
      VALUES (?, ?, ?, ?, ?, 'mi_fitness_local_log')
      ON CONFLICT(device_id, started_at) DO UPDATE SET ended_at=excluded.ended_at,
        asleep_minutes=excluded.asleep_minutes, received_at=excluded.received_at
      WHERE excluded.received_at >= received_at AND (ended_at != excluded.ended_at OR asleep_minutes != excluded.asleep_minutes)`)
      .run(deviceId, start, end, sleep.asleepMinutes, received);
  }

  saveBandSamples(deviceId: string, records: ReadonlyArray<{
    type: 'steps' | 'heart_rate'; value: number; recordedAt: string;
  } | {type: 'sleep_session'; value: number; recordedAt: string; valueJson:{endAt:string;asleepMinutes?:number|undefined}}
    | {type: 'sleep_band_interval'; value: number; recordedAt: string; valueJson:{endAt:string}}
    | {type: 'sleep_state'; value: number; recordedAt: string; valueJson:{origin:'band_basic_status'|'band_status_event';timeBasis:'phone_observed'}}
    | {type: 'sleep_progress' | 'sleep_interval_progress'; value: number; recordedAt: string;
       valueJson: {sessionStart:string;origin:'band_sleep_report';timeBasis:'device_sample'} |
           {sessionStart:string;origin:'band_live_progress';timeBasis:'phone_observed_growth';previousObservedAt:string;previousMinutes:number}}
  >): void {
    const stmt = this.db.prepare(`INSERT INTO band_samples
      (device_id, type, value, recorded_at, received_at) VALUES (?, ?, ?, ?, ?)
      ON CONFLICT(device_id, type, recorded_at) DO UPDATE SET value=excluded.value, received_at=excluded.received_at
      WHERE value != excluded.value`);
    const receivedAt = new Date().toISOString();
    const observation = this.db.prepare(`INSERT INTO band_sleep_observations
      (device_id,session_start,observed_at,state,sleep_minutes,origin,time_basis,received_at,previous_observed_at,previous_minutes)
      VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING`);
    const interval = this.db.prepare(`INSERT INTO band_sleep_intervals(device_id,started_at,ended_at,received_at)
      VALUES(?,?,?,?) ON CONFLICT(device_id,started_at) DO UPDATE SET
      ended_at=excluded.ended_at,received_at=excluded.received_at WHERE excluded.ended_at>ended_at`);
    const sleep = this.db.prepare(`INSERT INTO band_sleep
      (device_id,started_at,ended_at,asleep_minutes,received_at) VALUES(?,?,?,?,?)
      ON CONFLICT(device_id,started_at) DO UPDATE SET ended_at=excluded.ended_at,
        asleep_minutes=CASE WHEN ended_at=excluded.ended_at THEN COALESCE(excluded.asleep_minutes,asleep_minutes)
                            ELSE excluded.asleep_minutes END, received_at=excluded.received_at
      WHERE excluded.ended_at > ended_at OR (excluded.ended_at=ended_at AND excluded.asleep_minutes IS NOT NULL AND excluded.asleep_minutes IS NOT asleep_minutes)`);
    this.db.exec('BEGIN');
    try {
      for (const record of records) {
        const start = new Date(record.recordedAt).toISOString();
        if (record.type === 'sleep_band_interval') {
          interval.run(deviceId, start, new Date(record.valueJson.endAt).toISOString(), receivedAt);
        } else if (record.type === 'sleep_session') {
          sleep.run(deviceId, start, new Date(record.valueJson.endAt).toISOString(), record.valueJson.asleepMinutes ?? null, receivedAt);
        } else if (record.type === 'sleep_state') {
          observation.run(deviceId, '', start, record.value === 1 ? 'asleep' : 'awake', null,
                          record.valueJson.origin, record.valueJson.timeBasis, receivedAt, null, null);
        } else if (record.type === 'sleep_progress' || record.type === 'sleep_interval_progress') {
          const proof = record.valueJson;
          const phoneGrowth = proof.origin === 'band_live_progress';
          observation.run(deviceId, new Date(proof.sessionStart).toISOString(), start, 'ongoing',
                          record.type === 'sleep_progress' ? record.value : null, proof.origin, proof.timeBasis, receivedAt,
                          phoneGrowth ? new Date(proof.previousObservedAt).toISOString() : null, phoneGrowth ? proof.previousMinutes : null);
        } else {
          stmt.run(deviceId, record.type, record.value, start, receivedAt);
        }
      }
      this.db.exec('COMMIT');
    } catch (error) {
      this.db.exec('ROLLBACK');
      throw error;
    }
  }

  report(input: ReportInput): void {
    const { device, appId, windowTitle, occurredAt, extra } = input;
    const extraJson = extra ? JSON.stringify(extra) : null;
    // Liveness (last_seen / online) uses the SERVER receive time, never the
    // client-supplied occurredAt — a device with a wrong clock would otherwise
    // look permanently offline even while actively reporting.
    const receivedAt = new Date().toISOString();

    const last = this.db
      .prepare(
        `SELECT id, app_id, started_at, ended_at FROM device_activities
         WHERE device_id = ? ORDER BY started_at DESC, id DESC LIMIT 1`,
      )
      .get(device.deviceId) as
      | { id: number; app_id: string; started_at: string; ended_at: string | null }
      | undefined;

    // Late uploads may add evidence in the past but cannot rewind the current
    // state, shorten a finished interval, or close the most recent open row.
    if (last && occurredAt < (last.ended_at ?? last.started_at)) {
      this.db.prepare("UPDATE device_states SET last_seen_at=? WHERE device_id=?").run(receivedAt, device.deviceId);
      if (appId) {
        this.db.prepare(`INSERT INTO device_activities
          (device_id,app_id,window_title,started_at,ended_at,extra_json,created_at)
          SELECT ?,?,?,?,?,?,? WHERE NOT EXISTS (
            SELECT 1 FROM device_activities WHERE device_id=?
              AND started_at<=? AND COALESCE(ended_at,started_at)>=?)`)
          .run(device.deviceId,appId,windowTitle,occurredAt,occurredAt,extraJson,receivedAt,
               device.deviceId,occurredAt,occurredAt);
      }
      return;
    }

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
        receivedAt,
        extraJson,
        receivedAt,
      );

    if (!appId) return; // no foreground app → state only, no activity row

    // A new Android RESUMED following PAUSED starts another visit even if the
    // same app returns within the normal heartbeat merge window.
    const androidResume = device.platform === "android" && extra?.activityObservation === "android_resume"
      && last && occurredAt > last.started_at;
    if (last && last.app_id === appId && !androidResume) {
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
    const latest = this.db.prepare(`SELECT id, app_id, started_at, ended_at FROM device_activities
      WHERE device_id=? ORDER BY started_at DESC,id DESC LIMIT 1`).get(device.deviceId) as
      {id:number; app_id:string; started_at:string; ended_at:string|null} | undefined;
    if (action === "open" && app && latest?.app_id === app && latest.started_at === occurredAt) return latest.id;
    if (latest && occurredAt < (latest.ended_at ?? latest.started_at)) {
      // A matching close can arrive after the next open. Tighten only that
      // inferred interval; receipt order never changes the newer foreground.
      if (action === "close" && app) {
        this.db.prepare(`UPDATE device_activities SET ended_at=?,
          extra_json=json_set(extra_json,'$.endSource','ios_named_close')
          WHERE id=(SELECT id FROM device_activities WHERE device_id=? AND app_id=?
            AND started_at<=? AND ended_at>=? AND json_extract(extra_json,'$.endSource')='ios_next_open'
            ORDER BY started_at DESC,id DESC LIMIT 1)`).run(occurredAt,device.deviceId,app,occurredAt,occurredAt);
      }
      return null;
    }
    if (action === "close" && app && (!latest || latest.ended_at !== null || latest.app_id !== app)) return null;
    if (latest && latest.ended_at === null) {
      const source = action === "open" ? "ios_next_open" : app ? "ios_named_close" : "ios_unnamed_close";
      this.db.prepare(`UPDATE device_activities SET ended_at=?,
        extra_json=json_set(COALESCE(extra_json,'{}'),'$.endSource',?) WHERE id=?`).run(occurredAt,source,latest.id);
    }

    let activityId: number | null = null;
    if (action === "open" && app) {
      const result = this.db
        .prepare(
          `INSERT INTO device_activities
             (device_id, app_id, window_title, started_at, ended_at, extra_json, created_at)
           VALUES (?, ?, NULL, ?, NULL, '{"startSource":"ios_open"}', ?)`,
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
        new Date().toISOString(),
        existing?.extra_json ?? null,
        new Date().toISOString(),
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

    const prior = parseExtra(existing?.extra_json ?? null) ?? {};
    const merged = {...prior,...extra};
    const power = readPower(extra, occurredAt);
    // An app-open or focus-only snapshot can copy earlier iPhone extras; retain
    // the original battery snapshot, and never stamp those cached fields anew.
    if (power.batteryReportedAt && (typeof prior.hibi_battery_at !== "string" || power.batteryReportedAt >= prior.hibi_battery_at)) {
      merged.hibi_battery_at = power.batteryReportedAt;
      merged.hibi_battery_percent = power.batteryPercent;
      merged.hibi_battery_charging = power.charging;
      merged.hibi_power_source = power.powerSource;
    }

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
        new Date().toISOString(),
        JSON.stringify(merged),
        new Date().toISOString(),
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

  latestActivityObservation(deviceId: string) {
    // A heartbeat or a queued upload can arrive long after an app was observed.
    // Preserve the source timestamps without reading titles or extra data.
    return this.db.prepare(`SELECT app_id AS appId, started_at AS startedAt, ended_at AS endedAt
      FROM device_activities WHERE device_id=? ORDER BY started_at DESC, id DESC LIMIT 1`)
      .get(deviceId) as { appId: string; startedAt: string; endedAt: string | null } | undefined;
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
      updated_at: string;
      extra_json: string | null;
    }>;

    const now = Date.now();
    return rows.map((r) => {
      const extra = parseExtra(r.extra_json);
      const stamped = r.platform === "ios" && typeof extra?.hibi_battery_at === "string";
      const power = stamped ? readPower({battery_percent:extra!.hibi_battery_percent,battery_charging:extra!.hibi_battery_charging,
        power_source:extra!.hibi_power_source},extra!.hibi_battery_at as string) : readPower(extra,r.platform === "ios" ? null : r.updated_at);
      return { ...power,
      deviceId: r.device_id,
      deviceName: r.device_name,
      platform: r.platform,
      appId: r.app_id,
      windowTitle: r.window_title,
      lastSeenAt: r.last_seen_at,
      isOnline: (now - new Date(r.last_seen_at).getTime()) / 1000 <= onlineWindowSeconds(r.platform),
      extra,
    }; });
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
