import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { runMigrations } from '../dist/db/migrations.js';
import { saveSleepCheck, readSleepChecks } from '../dist/db/sleep-checks.js';
const start=Date.parse('2026-02-14T18:00:00Z'), at=m=>new Date(start+m*60000).toISOString();
test('records actual rounds, rejects late data until received, and keeps old-session changes distinct from a new sleep',t=>{
 const db=new DatabaseSync(':memory:');t.after(()=>db.close());runMigrations(db);
 const save=m=>saveSleepCheck(db,'phone',new Date(at(m)));
 const latest=()=>readSleepChecks(db,'phone').recent[0];
 const closed=(s,e,mins,receipt)=>db.prepare('INSERT OR REPLACE INTO band_sleep VALUES(?,?,?,?,?)').run('phone',at(s),at(e),mins,at(receipt));
 const progress=(s,minute,receipt)=>db.prepare(`INSERT INTO band_sleep_observations
   (device_id,session_start,observed_at,state,sleep_minutes,origin,time_basis,received_at)
   VALUES (?,?,?,'ongoing',?,'band_sleep_report','device_sample',?)`).run('phone',at(s),at(minute),minute-s,at(receipt));
 closed(-100,-40,50,-30);save(0);assert.equal(latest().change,'baseline');
 save(2);assert.equal(readSleepChecks(db,'phone').recent.length,1);
 save(5);assert.equal(latest().change,'unchanged');
 closed(-100,-40,52,6);save(10);assert.equal(latest().change,'corrected');assert.equal(latest().record.minutes,52);
 save(15);assert.equal(latest().change,'unchanged');
 closed(-100,-40,54,17);save(20);assert.equal(latest().change,'corrected');
 progress(21,24,29);save(25);assert.equal(latest().change,'unchanged');save(30);assert.equal(latest().change,'new');assert.equal(latest().record.end,null);
 progress(21,32,32);save(35);assert.equal(latest().change,'progress');
 closed(21,38,15,39);save(40);assert.equal(latest().change,'completed');assert.equal(latest().record.end,at(38));
 progress(-100,44,44);save(45);assert.equal(latest().change,'unchanged');
 assert.equal(readSleepChecks(db,'phone').lastChangedAt,at(40));
});
