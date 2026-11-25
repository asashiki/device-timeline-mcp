import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { runMigrations } from '../dist/db/migrations.js';
import { readLiveHealth } from '../dist/db/live-health.js';

const phone = 'android-phone';
const now = '2026-11-23T16:04:00.000Z'; // Nov 24 00:04 Shanghai
const iso = mins => new Date(Date.parse(now) + 60_000 * mins).toISOString();
function setup(t) {
  const db = new DatabaseSync(':memory:');
  t.after(() => db.close());
  runMigrations(db);
  // Test fixtures exercise the private storage contract independent of migration ordering.
  db.exec(`CREATE TABLE IF NOT EXISTS band_sleep_observations(device_id TEXT, session_start TEXT, observed_at TEXT,
    state TEXT, sleep_minutes INTEGER, received_at TEXT, origin TEXT, time_basis TEXT, UNIQUE(device_id,session_start,observed_at,state))`);
  return db;
}
function add(db, kind, at, value, owner = phone, receipt = now) {
  db.prepare('INSERT INTO band_samples(device_id,type,value,recorded_at,received_at) VALUES(?,?,?,?,?)').run(owner, kind, value, at, receipt);
}
function obs(db, state, at, { start = '', minutes = null, owner = phone, receipt = now,
  origin = state === 'ongoing' ? 'band_sleep_report' : 'band_basic_status',
  basis = state === 'ongoing' ? 'device_sample' : 'phone_observed' } = {}) {
  db.prepare(`INSERT INTO band_sleep_observations(device_id,session_start,observed_at,state,sleep_minutes,received_at,origin,time_basis)
    VALUES(?,?,?,?,?,?,?,?)`).run(owner, start, at, state, minutes, receipt, origin, basis);
}
const read = db => readLiveHealth(db, phone, new Date(now));

test('midnight, partial steps, stale HR just uploaded, and a completed historical sleep', t => {
  const db = setup(t);
  add(db, 'heart_rate', iso(-100), 72);
  add(db, 'heart_rate', iso(1), 69);
  add(db, 'heart_rate', now, 80, 'another-device');
  add(db, 'steps', iso(-5), 23);
  add(db, 'steps', iso(-4), 0);
  add(db, 'steps', iso(-3), 2);
  add(db, 'steps', now, 5, 'another-device');
  db.prepare('INSERT INTO band_sleep(device_id,started_at,ended_at,asleep_minutes,received_at) VALUES(?,?,?,?,?)')
    .run(phone, iso(-500), iso(-100), null, now);
  const result = read(db);
  assert.equal(result.stepsToday.date, '2026-11-24'); assert.equal(result.stepsToday.steps, 2);
  assert.equal(result.stepsToday.observedMinutes, 2); assert.equal(result.stepsToday.coverage, 'observed_minutes_only');
  assert.equal(result.heartRate.bpm, 72); assert.equal(result.heartRate.receivedAt, now); assert.equal(result.heartRate.ageSeconds, 6000);
  assert.equal(result.heartRate.confidence, 'stale'); assert.equal(result.stepsToday.confidence, 'fresh');
  assert.equal(result.lastCompletedSleep.elapsedMinutes, 400); assert.equal(result.lastCompletedSleep.asleepMinutes, null);
  assert.equal(result.currentSleep.state, 'unknown'); assert.throws(() => readLiveHealth(db, 'another-device'));
});

test('missing samples vs a real zero and strict five-minute boundary', t => {
  const db = setup(t);
  db.exec('DROP TABLE band_sleep_observations');
  assert.equal(read(db).stepsToday.steps, null); assert.equal(read(db).currentSleep.confidence, 'unknown');
  add(db,'steps',now,0); add(db,'heart_rate',iso(-5),69);
  assert.equal(read(db).stepsToday.steps, 0); assert.equal(read(db).heartRate.confidence, 'fresh');
  assert.equal(readLiveHealth(db,phone,new Date(Date.parse(now)+1)).heartRate.confidence, 'stale');
});

test('a trusted explicit poll has phone-observed time and expires before new upload', t => {
  const db = setup(t);
  obs(db,'awake',iso(-20)); obs(db,'asleep',iso(-3));
  obs(db,'awake',iso(-1),{owner:'tablet'}); obs(db,'awake',iso(-2),{origin:'guessed'});
  const fresh = read(db).currentSleep;
  assert.equal(fresh.state, 'asleep'); assert.equal(fresh.origin, 'band_basic_status');
  assert.equal(fresh.timeBasis, 'phone_observed'); assert.equal(fresh.receivedAt, now); assert.equal(fresh.sessionStart, null);
  const stale = readLiveHealth(db,phone,new Date(iso(4))).currentSleep;
  assert.equal(stale.state,'unknown'); assert.equal(stale.confidence,'stale');
});

test('a start alone, phone-timed reports, and last days samples cannot imply sleep', t => {
  const db = setup(t);
  obs(db,'ongoing',iso(-2),{start:iso(-2),minutes:0}); obs(db,'ongoing',now,{start:iso(-2),basis:'phone_observed',minutes:2});
  assert.equal(read(db).currentSleep.state,'unknown');
  add(db,'heart_rate',now,55); add(db,'steps',now,0); assert.equal(read(db).currentSleep.state,'unknown');
});

test('in-progress band evidence allows inference, and later completion vetoes it', t => {
  const db = setup(t), start = iso(-100);
  obs(db,'ongoing',iso(-10),{start,minutes:72});
  obs(db,'ongoing',iso(-1),{start,minutes:80});
  const inferred = read(db).currentSleep;
  assert.equal(inferred.state, 'likely_asleep'); assert.equal(inferred.reason,'cumulative_sleep_increased');
  assert.equal(inferred.previousSampleAt,iso(-10)); assert.equal(inferred.timeBasis,'device_sample');
  db.prepare('INSERT INTO band_sleep(device_id,started_at,ended_at,asleep_minutes,received_at) VALUES(?,?,?,?,?)').run(phone,start,now,80,now);
  assert.equal(read(db).currentSleep.state,'unknown');
});

test('two incompatible device state indications at the same instant are unknown', t => {
  const db = setup(t);
  obs(db,'asleep',iso(-1)); obs(db,'awake',iso(-1));
  assert.equal(read(db).currentSleep.reason,'conflicting_sleep_observations');
});

test('an explicitly extended uncompleted sleep interval has a device-sampled source even with no minute counter', t => {
  const db = setup(t);
  obs(db,'ongoing',iso(-1),{start:iso(-120)});
  const state = read(db).currentSleep;
  assert.equal(state.state,'likely_asleep'); assert.equal(state.reason,'device_sleep_interval_extended');
  assert.equal(state.origin,'band_sleep_report');
  assert.equal(state.sampledAt,iso(-1)); assert.equal(state.timeBasis,'device_sample');
  obs(db,'awake',now);
  assert.equal(read(db).currentSleep.state,'awake');
});

test('recent phone-observed counter growth reports both real poll times; late upload/invalid growth cannot indicate sleep', t => {
  const db = setup(t), start = iso(-120);
  const proof = (at, count, prev, old) => db.prepare(`INSERT INTO band_sleep_observations(device_id,session_start,observed_at,state,sleep_minutes,received_at,origin,time_basis,previous_observed_at,previous_minutes)
    VALUES(?,?,?,'ongoing',?,?,'band_live_progress','phone_observed_growth',?,?)`).run(phone,start,at,count,now,prev,old);
  proof(iso(-1),74,iso(-5),70);
  const state = read(db).currentSleep;
  assert.equal(state.state,'likely_asleep'); assert.equal(state.timeBasis,'phone_observed_growth');
  assert.equal(state.previousSampleAt,iso(-5)); assert.equal(state.sampledAt,iso(-1));
  assert.equal(readLiveHealth(db,phone,new Date(iso(5))).currentSleep.state,'unknown');
  proof(now,81,iso(-5),70);
  assert.equal(read(db).currentSleep.state,'unknown');
});

test('a current native sleep record wins independently of step counts and expires at the source-time boundary',t=>{
  const db=setup(t);
  add(db,'steps',iso(-2),40);add(db,'steps',iso(-1),50);
  assert.equal(read(db).currentSleep.state,'unknown');
  obs(db,'ongoing',iso(-1),{start:iso(-1),origin:'band_sleep_record'});
  assert.equal(read(db).currentSleep.reason,'fresh_sleep_record');
  assert.equal(read(db).currentSleep.state,'likely_asleep');
  assert.equal(readLiveHealth(db,phone,new Date(iso(4))).currentSleep.state,'likely_asleep');
  assert.equal(readLiveHealth(db,phone,new Date(Date.parse(iso(4))+1)).currentSleep.state,'unknown');
  obs(db,'awake',now);assert.equal(read(db).currentSleep.state,'awake');
});

test('provisional interval uses its native endpoint; late upload and repeated reads never refresh it',t=>{
  const db=setup(t);
  const addInterval=(start,end,receipt)=>db.prepare('INSERT INTO band_sleep_intervals VALUES(?,?,?,?)')
    .run(phone,iso(start),iso(end),iso(receipt));
  addInterval(-600,-400,0);
  assert.equal(read(db).currentSleep.state,'unknown');
  addInterval(-45,-1,0);
  const recent=read(db).currentSleep;
  assert.equal(recent.reason,'recent_band_sleep_interval');
  assert.equal(recent.sampledAt,iso(-1));
  assert.equal(readLiveHealth(db,phone,new Date(iso(5))).currentSleep.state,'unknown');
  obs(db,'awake',now);assert.equal(read(db).currentSleep.state,'awake');
});

test('a poll for an already completed session cannot hide newer-session sleep evidence or manufacture a same-time conflict',t=>{
  const db=setup(t);
  const previousNight=iso(-700), wake=iso(-360), newNight=iso(-60);
  db.prepare('INSERT INTO band_sleep VALUES(?,?,?,?,?)').run(phone,previousNight,wake,300,iso(-350));
  obs(db,'ongoing',iso(-3),{start:newNight,minutes:45});
  obs(db,'ongoing',iso(-1),{start:previousNight,minutes:301});
  const found=read(db).currentSleep;
  assert.equal(found.state,'likely_asleep');assert.equal(found.sessionStart,newNight);
  assert.equal(found.sampledAt,iso(-3));
  obs(db,'awake',iso(-1));
  assert.equal(read(db).currentSleep.state,'awake');
});

test('an explicit awake indication matches a completed interval ending at that same instant',t=>{
  const db=setup(t);
  db.prepare('INSERT INTO band_sleep VALUES(?,?,?,?,?)').run(phone,iso(-260),iso(-2),245,iso(-1));
  obs(db,'awake',iso(-2));
  assert.equal(read(db).currentSleep.state,'awake');
  obs(db,'asleep',iso(-2));
  assert.equal(read(db).currentSleep.state,'unknown');
});
