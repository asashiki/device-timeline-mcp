import type { DatabaseSync } from 'node:sqlite';

const FIVE_MIN = 300_000;
type RecordSeen = {
  start: string;
  end: string | null;
  progressAt: string | null;
  minutes: number | null;
  source: string;
  receivedAt: string;
};
type Change = 'baseline' | 'empty' | 'unchanged' | 'new' | 'progress' | 'completed' | 'corrected';
type Check = { slot: number; checked_at: string; record_json: string | null; change: Change; last_changed_at: string | null; latest_sample_at: string | null };
const decode = (row: Check): RecordSeen | null => row.record_json ? JSON.parse(row.record_json) : null;

function latestRecord(db: DatabaseSync, deviceId: string, asOf: string): RecordSeen | null {
  const closed = db.prepare(`SELECT started_at AS start, ended_at AS end, NULL AS progressAt,
    COALESCE(asleep_minutes, CASE WHEN import_received_at<=? THEN import_minutes END) AS minutes,
    source, received_at AS receivedAt FROM private_native_sleep_history WHERE device_id=?
    AND ended_at<=? AND received_at<=? AND (superseded_at IS NULL OR superseded_at>?)
    ORDER BY ended_at DESC, started_at DESC LIMIT 1`).get(asOf, deviceId, asOf, asOf, asOf) as RecordSeen | undefined;
  const open = db.prepare(`SELECT session_start AS start,NULL AS end,observed_at AS progressAt,
    sleep_minutes AS minutes,origin AS source,received_at AS receivedAt FROM band_sleep_observations
    WHERE device_id=? AND state='ongoing' AND session_start>=? AND observed_at>session_start
    AND observed_at<=? AND received_at<=? ORDER BY observed_at DESC, received_at DESC LIMIT 1`)
    .get(deviceId, closed?.end ?? '', asOf, asOf) as RecordSeen | undefined;
  return open ?? closed ?? null;
}

function compare(previous: RecordSeen | null, record: RecordSeen | null): Change {
  if (!record) return 'empty';
  if (!previous || (record.start > previous.start && (!previous.end || record.start >= previous.end))) return 'new';
  if (previous.start !== record.start) return 'corrected';
  if (!previous.end && record.end) return 'completed';
  if (!previous.end && !record.end && record.progressAt && previous.progressAt && record.progressAt > previous.progressAt) return 'progress';
  if (previous.end !== record.end || previous.minutes !== record.minutes || previous.source !== record.source) return 'corrected';
  return 'unchanged';
}

export function saveSleepCheck(db: DatabaseSync, deviceId: string, now = new Date()) {
  const time = now.getTime(), slot = Math.floor(time/FIVE_MIN), asOf = now.toISOString();
  const previous = db.prepare('SELECT * FROM private_sleep_checks WHERE device_id=? ORDER BY slot DESC LIMIT 1')
    .get(deviceId) as Check | undefined;
  if (previous && previous.slot >= slot) return;
  const record = latestRecord(db, deviceId, asOf);
  const change = previous ? compare(decode(previous), record) : record ? 'baseline' : 'empty';
  const changed = ['new','progress','completed','corrected'].includes(change)
    ? asOf : previous?.last_changed_at ?? null;
  const sample = db.prepare(`SELECT MAX(recorded_at) AS at FROM band_samples
    WHERE device_id=? AND recorded_at<=? AND received_at<=?`).get(deviceId,asOf,asOf);
  db.prepare(`INSERT OR IGNORE INTO private_sleep_checks
    (device_id,slot,checked_at,record_json,change,last_changed_at,latest_sample_at) VALUES(?,?,?,?,?,?,?)`)
    .run(deviceId,slot,asOf,record ? JSON.stringify(record) : null,change,changed,sample?.at ?? null);
  db.prepare('DELETE FROM private_sleep_checks WHERE device_id=? AND slot<?').run(deviceId,slot-575);
}

export function readSleepChecks(db: DatabaseSync, deviceId: string) {
  const checks = db.prepare('SELECT * FROM private_sleep_checks WHERE device_id=? ORDER BY slot DESC LIMIT 5')
    .all(deviceId) as unknown as Check[];
  const last = checks[0];
  if (!last) return null;
  return {
    checkedAt:last.checked_at, lastChangedAt:last.last_changed_at, latestBandSampleAt:last.latest_sample_at,
    recent:checks.map(row => ({ checkedAt:row.checked_at, change:row.change, record:decode(row) })),
  };
}

export function startSleepChecks(db: DatabaseSync, deviceId: string) {
  function tick() {
    try { saveSleepCheck(db,deviceId); }
    catch (error) { console.error('[sleep-check] could not record this check', error); }
    setTimeout(tick,FIVE_MIN - Date.now()%FIVE_MIN).unref();
  }
  tick();
}
