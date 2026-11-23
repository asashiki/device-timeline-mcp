import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { runMigrations } from '../dist/db/migrations.js';
import { readLiveHealth } from '../dist/db/live-health.js';
const phone='android-phone',now=new Date('2026-11-24T16:02:00Z'),at=m=>new Date(now.getTime()+m*60000).toISOString();
function setup(t) {
  const db=new DatabaseSync(':memory:'); t.after(()=>db.close());runMigrations(db);
  const insert=db.prepare('INSERT INTO band_samples(device_id,type,value,recorded_at,received_at) VALUES(?,?,?,?,?)');
  const sample=(kind,m,v,receipt=m)=>insert.run(phone,kind,v,at(m),at(receipt));
  db.prepare('INSERT INTO band_sleep VALUES(?,?,?,?,?)').run(phone,at(-1700),at(-1520),null,at(-1490));
  for(let i=-1700;i<-1520;i++) sample('heart_rate',i,48+(i+1700)%11);
  for(let i=-1490;i<-1130;i++) {
    sample('steps',i,0);
    if(i%8===0)sample('heart_rate',i,72);
  }
  for(let i=-14;i<=0;i++){
    sample('steps',i,0);
    if(i!==-6 && i!==-11)sample('heart_rate',i,53);
  }
  const read=(input=null)=>readLiveHealth(db,phone,now,input).currentSleep;
  return {db,read};
}

test('independent past on-band profile, daytime contrast, measured quiet minutes and dense current HR jointly support a bounded inference',t=>{
  const {db,read}=setup(t);
  const result=read();
  assert.equal(result.state,'likely_asleep');assert.equal(result.reason,'personal_sleep_pattern');
  assert.equal(result.origin,'band_minute_pattern');assert.equal(result.sampledAt,at(0));assert.equal(result.previousSampleAt,at(-14));
  assert.equal(result.sessionStart,null);
  assert.equal(result.evidence.sleepPattern.heartMinutes,13);
  assert.equal(result.evidence.sleepPattern.profile.endedAt,at(-1520));
  assert.equal(read({observedAt:at(-1),receivedAt:at(0)}).reason,'recent_device_input');
  db.prepare("INSERT INTO band_sleep_observations(device_id,session_start,observed_at,state,origin,time_basis,received_at) VALUES(?,'',?,'awake','band_basic_status','phone_observed',?)").run(phone,at(-4),at(0));
  assert.equal(read().state,'awake');
  db.exec('DELETE FROM band_sleep_observations');
  db.prepare("UPDATE band_samples SET value=35 WHERE type='steps' AND recorded_at>=?").run(at(-1));
  assert.equal(read().reason,'sustained_band_step_increments');
});

test('late data, incomplete step coverage, out-of-range HR, a recently ended night and missing daytime contrast stay unknown',t=>{
  const {db,read}=setup(t);
  function rejected(sql,...args){
    db.exec('SAVEPOINT variant');db.prepare(sql).run(...args);
    assert.equal(read().state,'unknown',sql);
    db.exec('ROLLBACK TO variant; RELEASE variant');
  }
  rejected("UPDATE band_samples SET received_at=? WHERE recorded_at>=? AND type='heart_rate'",at(1),at(-14));
  rejected("UPDATE band_samples SET value=75 WHERE recorded_at>=? AND type='heart_rate'",at(-14));
  rejected("DELETE FROM band_samples WHERE recorded_at=? AND type='steps'",at(-8));
  rejected("UPDATE band_samples SET value=3 WHERE recorded_at=? AND type='steps'",at(-4));
  rejected("UPDATE band_sleep SET received_at=?",at(-1));
  rejected("DELETE FROM band_samples WHERE type='heart_rate' AND recorded_at>=? AND recorded_at<?",at(-1490),at(-1130));
  rejected("INSERT INTO band_sleep VALUES(?,?,?,?,?)",phone,at(-60),at(-6),47,at(-3));
  db.exec('DELETE FROM band_sleep');
  db.prepare('INSERT INTO historical_sleep_imports VALUES(?,?,?,?,?,?)').run(phone,at(-1700),at(-1520),170,at(-1480),'mi_fitness_local_log');
  assert.equal(read().state,'unknown');
});
