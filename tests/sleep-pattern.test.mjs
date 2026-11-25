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
  const read=()=>readLiveHealth(db,phone,now).currentSleep;
  return {db,read};
}

test('dense low heart rate, stillness and a personal historical profile alone never mark the wearer asleep',t=>{
  const {db,read}=setup(t);
  assert.equal(read().state,'unknown');
  db.prepare("INSERT INTO band_sleep_observations(device_id,session_start,observed_at,state,origin,time_basis,received_at) VALUES(?,'',?,'awake','band_basic_status','phone_observed',?)").run(phone,at(-4),at(0));
  assert.equal(read().state,'awake');
});
