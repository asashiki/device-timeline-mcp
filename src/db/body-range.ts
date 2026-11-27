import type { DatabaseSync } from "node:sqlite";
import { LIVE_HEALTH_DEVICE_ID } from "./live-health.js";

type Sleep = {
  started_at: string; ended_at: string; asleep_minutes: number | null; source: string;
  import_minutes: number | null; import_received_at: string | null;
};

export type WakeMarker = {fileStart:string; endedAt:string; receivedAt:string};

// A stale file identifier can span multiple nights. Only the reported wake endpoint
// is a measurement. A later complete native session supersedes that isolated endpoint.
export function readWakeMarkers(db: DatabaseSync, deviceId: string, asOf: string, range?: {from:string; to:string}): WakeMarker[] {
  const window = range ? "AND m.ended_at >= ? AND m.ended_at < ?" : "";
  return db.prepare(`SELECT m.file_start AS fileStart, m.ended_at AS endedAt, m.received_at AS receivedAt
    FROM private_band_wake_markers m
    WHERE m.device_id=? AND m.received_at<=? AND m.ended_at<=? ${window}
      AND NOT EXISTS (SELECT 1 FROM private_native_sleep_history s WHERE s.device_id=m.device_id
        AND s.started_at<=m.ended_at AND s.ended_at>=m.ended_at AND s.ended_at<=? AND s.received_at<=?
        AND (s.superseded_at IS NULL OR s.superseded_at>?))
    ORDER BY m.ended_at DESC ${range ? "" : "LIMIT 1"}`)
    .all(deviceId,asOf,asOf,...(range ? [range.from,range.to] : []),asOf,asOf,asOf) as unknown as WakeMarker[];
}

export function readBodyRange(db: DatabaseSync, deviceId: string, from: string, to: string, now = new Date()) {
  if (deviceId !== LIVE_HEALTH_DEVICE_ID) throw new Error("Device is outside the live health scope");
  const start = Date.parse(from), end = Date.parse(to);
  if (![start,end].every(Number.isFinite) || start % 60000 || end % 60000 ||
      start >= end || end > now.getTime() || end - start > 7 * 86400000) throw new Error("Invalid range");
  const asOf = now.toISOString();
  const args = [deviceId, new Date(start).toISOString(), new Date(end).toISOString(), asOf];
  const window = "device_id=? AND recorded_at>=? AND recorded_at<? AND received_at<=?";
  const steps = db.prepare(`SELECT SUM(value) AS total, COUNT(DISTINCT substr(recorded_at,1,16)) AS minutes
      FROM band_samples WHERE type='steps' AND ${window}`).get(...args)!;
  const hr = db.prepare(`SELECT MIN(value) AS low, MAX(value) AS high, COUNT(*) AS samples
      FROM band_samples WHERE type='heart_rate' AND ${window}`).get(...args)!;
  const latest = db.prepare(`SELECT recorded_at,value FROM band_samples WHERE type='heart_rate' AND ${window}
      ORDER BY recorded_at DESC LIMIT 1`).get(...args);
  const raw = db.prepare(`SELECT * FROM private_sleep_history WHERE device_id=? AND ended_at>?
      AND started_at<? AND ended_at<=? AND received_at<=? AND (superseded_at IS NULL OR superseded_at>?)
      ORDER BY started_at,ended_at`).all(...args,asOf,asOf) as unknown as Sleep[];
  let last = -Infinity, covered = 0, exact: number | null = raw.length ? 0 : null;
  const sessions = raw.map(s => {
    const a = Date.parse(s.started_at), b = Date.parse(s.ended_at);
    const left = Math.max(a,start), right = Math.min(b,end);
    const supplement = s.import_received_at && s.import_received_at<=asOf ? s.import_minutes : null;
    const asleepMinutes = s.asleep_minutes ?? supplement;
    if (left < last || a < start || b > end || asleepMinutes === null) exact = null;
    else if (exact !== null) exact += asleepMinutes;
    covered += Math.max(0,right-Math.max(last,left)); last = Math.max(last,right);
    return {startedAt:s.started_at,endedAt:s.ended_at,source:s.source,sessionAsleepMinutes:asleepMinutes,
      asleepMinutesSource:s.asleep_minutes!==null?s.source:supplement!==null?"mi_fitness_local_log":null,
      overlapMinutes:(right-left)/60000};
  });
  const expected = (end-start)/60000;
  return {deviceId,asOf,from:args[1],to:args[2],endExclusive:true,timeZone:"Asia/Shanghai",
    steps:{steps:steps.total,observedMinutes:steps.minutes,expectedMinutes:expected,
      coverage:steps.minutes===0?"missing":steps.minutes===expected?"complete":"partial"},
    heartRate:{sampleCount:hr.samples,minBpm:hr.low,maxBpm:hr.high,latestBpm:latest?.value??null,
      latestSampledAt:latest?.recorded_at??null},
    sleep:{sessions,wakeMarkers:readWakeMarkers(db,deviceId,asOf,{from,to}),recordedSessionOverlapMinutes:raw.length?covered/60000:null,asleepMinutes:exact}};
}
