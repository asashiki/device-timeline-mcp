import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { runMigrations } from '../dist/db/migrations.js';
import { readBodyRange } from '../dist/db/body-range.js';

test('one hour uses increments, distinguishes missing and zero, and excludes the next hour', t => {
  const db = new DatabaseSync(':memory:');
  t.after(() => db.close());
  runMigrations(db);
  const from='2026-11-23T05:00:00.000Z', to='2026-11-23T06:00:00.000Z', now=new Date('2026-11-24T00:00:00Z');
  const read=()=>readBodyRange(db,'android-phone',from,to,now);
  assert.equal(read().steps.steps,null);
  const add=db.prepare('INSERT INTO band_samples VALUES(?,?,?,?,?)');
  add.run('android-phone','steps',0,from,to);
  assert.equal(read().steps.steps,0);
  add.run('android-phone','steps',200,'2026-11-23T05:59:00.000Z',to);
  add.run('android-phone','steps',90,to,to);
  const hour=read();
  assert.deepEqual(hour.steps,{steps:200,observedMinutes:2,expectedMinutes:60,coverage:'partial'});
  assert.equal(hour.sleep.asleepMinutes,null);
  db.prepare('INSERT INTO historical_sleep_imports VALUES(?,?,?,?,?,?)')
    .run('android-phone','2026-11-23T04:00:00.000Z',to,100,to,'mi_fitness_local_log');
  assert.equal(read().sleep.recordedSessionOverlapMinutes,60);
  assert.equal(read().sleep.asleepMinutes,null);
});
