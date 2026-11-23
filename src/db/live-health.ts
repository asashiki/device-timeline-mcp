import type { DatabaseSync } from 'node:sqlite';
import { sleepPattern } from './sleep-pattern.js';
import { dayRangeUtc } from '../time.js';

const TIME_ZONE = 'Asia/Shanghai';
const FRESH_MS = 5 * 60_000;
export const LIVE_HEALTH_DEVICE_ID = process.env.HIBI_HEALTH_DEVICE_ID?.trim() || 'android-phone';
const shanghaiDate = new Intl.DateTimeFormat('sv-SE', {
  timeZone: TIME_ZONE, year: 'numeric', month: '2-digit', day: '2-digit',
});

type Confidence = 'fresh' | 'stale' | 'unknown';
interface SampleRow { value: number; recorded_at: string; received_at: string }
interface SleepRow { started_at: string; ended_at: string; asleep_minutes: number | null; received_at: string;
  source: string; import_minutes: number | null; import_received_at: string | null }

function age(recordedAt: string | null, nowMs: number): { ageSeconds: number | null; confidence: Confidence } {
  if (recordedAt === null) return { ageSeconds: null, confidence: 'unknown' };
  const elapsed = nowMs - Date.parse(recordedAt);
  if (!Number.isFinite(elapsed) || elapsed < 0) return { ageSeconds: null, confidence: 'unknown' };
  return { ageSeconds: Math.floor(elapsed / 1000), confidence: elapsed <= FRESH_MS ? 'fresh' : 'stale' };
}

type SleepState = 'asleep' | 'awake' | 'likely_asleep' | 'likely_awake' | 'unknown';
type TimeBasis = 'phone_observed' | 'device_sample' | 'phone_observed_growth';
interface ObservationRow { previous_observed_at: string | null; previous_minutes: number | null; origin: string; time_basis: TimeBasis; session_start: string; observed_at: string; state: string; sleep_minutes: number | null; received_at: string }
interface SleepStatus {
  state: SleepState;
  confidence: Confidence;
  sampledAt: string | null;
  receivedAt: string | null;
  ageSeconds: number | null;
  reason: string;
  sessionStart: string | null;
  previousSampleAt: string | null;
  origin: string | null;
  timeBasis: TimeBasis | null;
}

function currentSleep(db: DatabaseSync, deviceId: string, asOf: string, latestEnd: string | undefined): SleepStatus {
  const unknown: SleepStatus = { state: 'unknown', confidence: 'unknown', sampledAt: null, receivedAt: null,
    ageSeconds: null, reason: 'no_explicit_sleep_state_observation', sessionStart: null, previousSampleAt: null, origin: null, timeBasis: null };
  const present = db.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name='band_sleep_observations'").get();
  if (!present) return unknown;
  const trusted = `((state IN ('asleep','awake') AND origin IN ('band_basic_status','band_status_event') AND time_basis='phone_observed')
      OR (state='ongoing' AND session_start!='' AND observed_at>=session_start AND (
        (origin='band_sleep_report' AND time_basis='device_sample') OR
        (origin='band_live_progress' AND time_basis='phone_observed_growth' AND previous_observed_at IS NOT NULL AND previous_minutes IS NOT NULL))))`;
  // Retried growth records include their session start. When the session already
  // finished, even a newer phone poll no longer describes the current night.
  // Filter before selecting the newest timestamp so such a retry cannot hide a
  // valid observation from a different, current session.
  const cutoff = latestEnd ?? '';
  const eligible = `${trusted} AND (state != 'ongoing' OR session_start >= ?)`;
  const latest = db.prepare(`SELECT observed_at FROM band_sleep_observations
    WHERE device_id=? AND observed_at>=? AND observed_at<=? AND received_at<=? AND ${eligible}
    ORDER BY observed_at DESC LIMIT 1`)
    .get(deviceId,cutoff,asOf,asOf,cutoff) as { observed_at: string } | undefined;
  if (!latest) return unknown;
  const observations = db.prepare(`SELECT session_start,observed_at,state,sleep_minutes,received_at,origin,time_basis,previous_observed_at,previous_minutes FROM band_sleep_observations
    WHERE device_id=? AND observed_at=? AND received_at<=? AND ${eligible} ORDER BY received_at DESC LIMIT 4`)
    .all(deviceId, latest.observed_at, asOf, cutoff) as unknown as ObservationRow[];
  const evidence = observations[0];
  if (!evidence) return unknown;
  const timed = age(evidence.observed_at, Date.parse(asOf));
  const base = { ...unknown, ...timed, sampledAt: evidence.observed_at, receivedAt: evidence.received_at,
    sessionStart: evidence.session_start || null, origin: evidence.origin, timeBasis: evidence.time_basis };
  if (timed.confidence !== 'fresh') return { ...base, reason: 'sleep_observation_expired' };
  // Explicit awake exactly at the end of a completed interval is consistent
  // with waking. Other observations at that boundary need fresh evidence.
  if (latestEnd === evidence.observed_at && !observations.every(row => row.state === 'awake')) {
    return { ...base, confidence: 'unknown', reason: 'completed_session_supersedes_observation' };
  }
  if (observations.length > 3 || observations.some(row => row.session_start > row.observed_at)) {
    return { ...base, confidence: 'unknown', reason: 'ambiguous_sleep_observations' };
  }
  const direct = observations.find(row => row.state === 'asleep' || row.state === 'awake');
  if (direct) {
    if (observations.some(row => row.state !== direct.state && (row.state === 'awake' || direct.state === 'awake'))) {
      return { ...base, confidence: 'unknown', reason: 'conflicting_sleep_observations' };
    }
    return { ...base, state: direct.state as 'asleep' | 'awake', receivedAt: direct.received_at,
      reason: 'explicit_device_state', sessionStart: direct.session_start || null, origin: direct.origin, timeBasis: direct.time_basis };
  }
  if (observations.some(row => row.session_start !== evidence.session_start)) {
    return { ...base, confidence: 'unknown', reason: 'ambiguous_sleep_observations' };
  }
  // Persisted 'ongoing' is reserved for a device-timestamped sleep interval
  // extension. Poll time, a frame transfer timestamp or a heart rate pulse is
  // never admissible evidence. A session start alone cannot say it continued.
  if (!(evidence.observed_at > evidence.session_start)) {
    return { ...base, confidence: 'unknown', reason: 'sleep_start_without_progress' };
  }
  // A growing session cannot continue past a known newer completed session.
  if (latestEnd && latestEnd > evidence.session_start) {
    return { ...base, confidence: 'unknown', reason: 'overlapping_completed_session' };
  }
  if (evidence.time_basis === 'phone_observed_growth') {
    const previousAt = evidence.previous_observed_at;
    const previousMinutes = evidence.previous_minutes;
    const deltaSeconds = previousAt ? (Date.parse(evidence.observed_at) - Date.parse(previousAt)) / 1000 : NaN;
    const increase = previousMinutes !== null && evidence.sleep_minutes !== null ? evidence.sleep_minutes - previousMinutes : NaN;
    if (!previousAt || previousAt < evidence.session_start || !(deltaSeconds >= 45 && deltaSeconds <= 600 &&
        increase > 0 && increase <= Math.ceil(deltaSeconds / 60) + 1)) {
      return { ...base, confidence: 'unknown', reason: 'inconsistent_sleep_progress' };
    }
    return { ...base, state: 'likely_asleep', reason: 'cumulative_sleep_increased', previousSampleAt: previousAt };
  }
  const previous = db.prepare(`SELECT observed_at,sleep_minutes FROM band_sleep_observations
    WHERE device_id=? AND session_start=? AND state='ongoing' AND time_basis='device_sample' AND observed_at<? AND received_at<=? AND sleep_minutes IS NOT NULL AND ${trusted}
    ORDER BY observed_at DESC LIMIT 1`).get(deviceId, evidence.session_start, evidence.observed_at, asOf) as
    { observed_at: string; sleep_minutes: number } | undefined;
  if (previous && evidence.sleep_minutes !== null && evidence.sleep_minutes < previous.sleep_minutes) {
    return { ...base, confidence: 'unknown', reason: 'inconsistent_sleep_progress', previousSampleAt: previous.observed_at };
  }
  const growing = previous && evidence.sleep_minutes !== null && evidence.sleep_minutes > previous.sleep_minutes;
  return { ...base, state: 'likely_asleep', reason: growing ? 'cumulative_sleep_increased' : 'device_sleep_interval_extended',
    previousSampleAt: growing ? previous.observed_at : null };
}

// Minute timestamps identify the recorded activity. A later quiet minute
// leaves earlier walking evidence valid until its five-minute window expires.
function withMovementEvidence(db: DatabaseSync, deviceId: string, now: Date, sleep: SleepStatus, completed?: SleepRow) {
  const asOf = now.toISOString();
  const earliest = new Date(now.getTime() - FRESH_MS).toISOString();
  const rows = db.prepare(`SELECT value,recorded_at,received_at FROM band_samples
    WHERE device_id=? AND type='steps' AND recorded_at>=? AND recorded_at<=? AND received_at<=?
    AND substr(recorded_at,18)='00.000Z' ORDER BY recorded_at DESC LIMIT 6`)
    .all(deviceId,earliest,asOf,asOf) as unknown as SampleRow[];
  const walking = (row: SampleRow) => row.value >= 20 && row.value <= 220;
  let recent: SampleRow | undefined, previous: SampleRow | undefined;
  for (let i=1; i<rows.length; i++) {
    const newer = rows[i-1]!, older = rows[i]!;
    if (walking(newer) && walking(older) && Date.parse(newer.recorded_at)-Date.parse(older.recorded_at) === 60_000) {
      recent = newer;
      previous = older;
      break;
    }
  }
  const movement = recent ? 'sustained_walking' : rows.length ? 'insufficient_walking' : 'no_recent_steps';
  const evidenceRows = recent && previous ? [previous, recent] : rows.slice(0,2).reverse();
  const evidence = {movement, minuteSteps: evidenceRows.map(r=>({sampledAt:r.recorded_at,steps:r.value})),
    wearState: 'unavailable' as const};
  const result = {...sleep,evidence};
  if (movement !== 'sustained_walking' || !recent || !previous || sleep.state === 'awake') return result;
  const first = Date.parse(previous.recorded_at);
  const after = Date.parse(recent.recorded_at) + 60_000;
  const sleepAt = sleep.sampledAt ? Date.parse(sleep.sampledAt) : -Infinity;
  // A completed report overlapping these minute bins or a conflicting live observation
  // makes the movement insufficient to call the wearer awake.
  const overlaps = completed && Date.parse(completed.started_at) < after && Date.parse(completed.ended_at) > first;
  if (sleepAt >= after) return result;
  if (overlaps || sleepAt >= first) {
    return {...result,state:'unknown' as const,confidence:'unknown' as const,reason:'walking_conflicts_with_sleep_evidence'};
  }
  return {...result,state:'likely_awake' as const,reason:'sustained_band_step_increments',
    ...age(recent.recorded_at,now.getTime()),sampledAt:recent.recorded_at,
    receivedAt:previous.received_at>recent.received_at ? previous.received_at : recent.received_at,
    sessionStart:null,previousSampleAt:previous.recorded_at,origin:'band_minute_steps',timeBasis:'device_sample' as const};
}

/** Exposes direct, private samples for the credential's bound phone. The caller owns HTTP authentication. */
export function readLiveHealth(db: DatabaseSync, deviceId: string, now = new Date()) {
  if (deviceId !== LIVE_HEALTH_DEVICE_ID) throw new Error('Device is outside the live health scope');
  const asOf = now.toISOString();
  const day = shanghaiDate.format(now);
  const { start } = dayRangeUtc(day, TIME_ZONE);
  const latest = (type: 'heart_rate' | 'steps', lowerBound: string) => db.prepare(`
    SELECT value,recorded_at,received_at FROM band_samples
    WHERE device_id=? AND type=? AND recorded_at>=? AND recorded_at<=? AND received_at<=?
    ORDER BY recorded_at DESC LIMIT 1`).get(deviceId, type, lowerBound, asOf, asOf) as SampleRow | undefined;
  const hr = latest('heart_rate', '');
  const lastStep = latest('steps', start);
  // Only scan this device's samples in today's window. Records are keyed on source time;
  // a retry, even one received today for an older day, cannot increase today's count.
  const steps = db.prepare(`SELECT SUM(value) AS total, COUNT(*) AS samples,
      COUNT(DISTINCT substr(recorded_at,1,16)) AS minutes, MIN(recorded_at) AS first_at
      FROM band_samples WHERE device_id=? AND type='steps' AND recorded_at>=? AND recorded_at<=? AND received_at<=?`)
    .get(deviceId, start, asOf, asOf) as {total: number | null; samples: number; minutes: number; first_at: string | null};
  const hasImports = db.prepare("SELECT 1 FROM sqlite_master WHERE type='view' AND name='private_completed_sleep'").get();
  const sleepSource = hasImports ? 'private_completed_sleep' :
    "(SELECT *, 'hibi_band_direct' AS source, NULL AS superseded_at, NULL AS import_minutes, NULL AS import_received_at FROM band_sleep)";
  const completed = db.prepare(`SELECT started_at,ended_at,asleep_minutes,received_at,source,import_minutes,import_received_at FROM ${sleepSource}
      WHERE device_id=? AND ended_at<=? AND received_at<=? AND ended_at>started_at
        AND (superseded_at IS NULL OR superseded_at>?)
      ORDER BY ended_at DESC, started_at DESC LIMIT 1`).get(deviceId, asOf, asOf, asOf) as SleepRow | undefined;
  const supplement = completed?.asleep_minutes === null && completed.import_received_at !== null &&
    completed.import_received_at <= asOf ? completed.import_minutes : null;
  const primary = withMovementEvidence(db, deviceId, now,
    currentSleep(db, deviceId, asOf, completed?.ended_at), completed);
  const freshConflict = primary.reason === 'walking_conflicts_with_sleep_evidence' ||
    (primary.sampledAt && age(primary.sampledAt,now.getTime()).confidence === 'fresh');
  const pattern = primary.state === 'unknown' && !freshConflict
    ? sleepPattern(db, deviceId, now, hr?.recorded_at, (lastStep ?? latest('steps',''))?.recorded_at, completed?.ended_at) : null;
  const current = pattern ? {...primary,state:'likely_asleep' as const,reason:'personal_sleep_pattern',
    ...age(pattern.sampledAt,now.getTime()),sampledAt:pattern.sampledAt,receivedAt:pattern.receivedAt,
    previousSampleAt:pattern.windowStart,sessionStart:null,origin:'band_minute_pattern',timeBasis:'device_sample' as const,
    evidence:{...primary.evidence,sleepPattern:pattern}} : primary;
  return {
    deviceId, asOf, timeZone: TIME_ZONE, freshWithinSeconds: FRESH_MS / 1000,
    source: completed?.source === 'mi_fitness_local_log' || supplement !== null ? 'mixed' : 'hibi_band_direct',
    heartRate: { bpm: hr?.value ?? null, sampledAt: hr?.recorded_at ?? null, receivedAt: hr?.received_at ?? null,
      ...age(hr?.recorded_at ?? null, now.getTime()) },
    stepsToday: { date: day, steps: steps.total, sampleCount: steps.samples, observedMinutes: steps.minutes,
      firstSampleAt: steps.first_at, lastSampleAt: lastStep?.recorded_at ?? null, receivedAt: lastStep?.received_at ?? null,
      coverage: 'observed_minutes_only' as const, ...age(lastStep?.recorded_at ?? null, now.getTime()) },
    lastCompletedSleep: completed ? { startedAt: completed.started_at, endedAt: completed.ended_at,
      elapsedMinutes: Math.floor((Date.parse(completed.ended_at) - Date.parse(completed.started_at)) / 60_000),
      asleepMinutes: completed.asleep_minutes ?? supplement, receivedAt: completed.received_at, source: completed.source,
      asleepMinutesSource: supplement !== null ? 'mi_fitness_local_log' : completed.asleep_minutes === null ? null : completed.source,
      ...age(completed.ended_at, now.getTime()) } : null,
    currentSleep: current,
  };
}
