import type { DatabaseSync } from 'node:sqlite';

const MINUTE = 60_000;
const iso = (ms: number) => new Date(ms).toISOString();
type Reading = {type: string; value: number; recorded_at: string; received_at: string};
type Session = {started_at: string; ended_at: string};
const minuteRows = "device_id=? AND recorded_at>=? AND recorded_at<? AND received_at<=? AND substr(recorded_at,18)='00.000Z'";

function previousProfile(db: DatabaseSync, deviceId: string, windowStart: string) {
  // Use a previous direct band session only after six hours of comparison
  // data have been gathered. Exclude other known sleep from that comparison.
  const cutoff = Date.parse(windowStart);
  const sessions = db.prepare(`SELECT started_at,ended_at FROM band_sleep WHERE device_id=? AND
    ended_at>=? AND ended_at<=? AND received_at<=? AND
    unixepoch(ended_at)-unixepoch(started_at) BETWEEN 10800 AND 54000
    ORDER BY ended_at DESC LIMIT 12`).all(deviceId,iso(cutoff-30*86400_000),iso(cutoff-390*MINUTE),windowStart) as unknown as Session[];
  for (const session of sessions) {
    const duration = (Date.parse(session.ended_at)-Date.parse(session.started_at))/MINUTE;
    const values = db.prepare(`SELECT value FROM band_samples WHERE type='heart_rate' AND value BETWEEN 1 AND 254
      AND ${minuteRows} ORDER BY value`).all(deviceId,session.started_at,session.ended_at,windowStart);
    if (values.length < duration*0.8) continue;
    const from = iso(Date.parse(session.ended_at)+30*MINUTE);
    const to = iso(Date.parse(from)+360*MINUTE);
    const overlap = db.prepare('SELECT 1 FROM band_sleep WHERE device_id=? AND started_at<? AND ended_at>? AND received_at<=? LIMIT 1')
      .get(deviceId,to,from,windowStart);
    if (overlap) continue;
    const counts = db.prepare(`SELECT COUNT(CASE WHEN type='steps' THEN 1 END) AS steps,
      COUNT(CASE WHEN type='heart_rate' AND value BETWEEN 1 AND 254 THEN 1 END) AS heart
      FROM band_samples WHERE ${minuteRows}`).get(deviceId,from,to,windowStart) as {steps:number;heart:number};
    if (counts.steps < 180 || counts.heart < 12 || counts.heart > counts.steps/2) continue;
    const percentile = (q: number) => Number(values[Math.floor((values.length-1)*q)]!.value);
    return {low:percentile(0.10),high:percentile(0.90),endedAt:session.ended_at};
  }
  return null;
}

export function sleepPattern(db: DatabaseSync, deviceId: string, now: Date, lastHeartAt?: string,
    lastStepAt?: string, lastCompletedEnd?: string) {
  const nowMs = now.getTime();
  if (!lastHeartAt || !lastStepAt || nowMs-Date.parse(lastHeartAt)>2*MINUTE || nowMs-Date.parse(lastStepAt)>2*MINUTE) return null;
  const anchor = Math.floor(Date.parse(lastStepAt)/MINUTE)*MINUTE;
  const windowStart = iso(anchor-14*MINUTE);
  if (lastCompletedEnd && lastCompletedEnd>=windowStart) return null;
  const rows = db.prepare(`SELECT type,value,recorded_at,received_at FROM band_samples WHERE ${minuteRows}
    AND recorded_at<=? ORDER BY recorded_at`).all(deviceId,windowStart,iso(anchor+MINUTE),now.toISOString(),now.toISOString()) as unknown as Reading[];
  const steps=rows.filter(r=>r.type==='steps');
  const hearts=rows.filter(r=>r.type==='heart_rate' && r.value>0 && r.value<255);
  const lastHeart=hearts.at(-1);
  if (steps.length!==15 || hearts.length<12 || !lastHeart || Date.parse(lastHeart.recorded_at)<anchor-MINUTE
      || nowMs-Date.parse(lastHeart.recorded_at)>2*MINUTE) return null;
  const total=steps.reduce((sum,r)=>sum+r.value,0);
  if (total>2) return null;
  const profile=previousProfile(db,deviceId,windowStart);
  if (!profile) return null;
  const values=hearts.map(r=>r.value).sort((a,b)=>a-b);
  const median=(values[Math.floor((values.length-1)/2)]!+values[Math.floor(values.length/2)]!)/2;
  if (median<profile.low || median>profile.high) return null;
  const receivedAt=rows.reduce((latest,r)=>r.received_at>latest?r.received_at:latest,'');
  return {sampledAt:lastHeart.recorded_at,receivedAt,windowStart,heartMinutes:hearts.length,
    stepMinutes:steps.length,steps:total,medianBpm:median,profile};
}
