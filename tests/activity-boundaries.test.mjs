import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {Repository} from '../dist/db/repo.js';
import {runMigrations} from '../dist/db/migrations.js';

function setup(t, platform) {
  const db = new DatabaseSync(':memory:'); t.after(()=>db.close());
  runMigrations(db);
  const repo = new Repository(db,120), device = {deviceId:'p',deviceName:'phone',platform,token:'test-only'};
  return { db, repo, device };
}
const at = seconds => new Date(Date.UTC(2026,7,11) + seconds*1000).toISOString();
test('old samples cannot shorten intervals, rewind foreground, or close the latest open activity',t=>{
  const {db,repo,device} = setup(t,'android');
  const post = (appId,time) => repo.report({device,appId,occurredAt:at(time),windowTitle:null,extra:null});
  post('A',10);post('A',65);post('B',66);post('A',31);post('C',45);
  assert.deepEqual(db.prepare('select app_id, started_at, ended_at from device_activities order by id').all().map(x=>Object.values(x)),
    [['A',at(10),at(65)],['B',at(66),null]]);
  post('B',99);post('B',69);
  assert.equal(db.prepare("select ended_at from device_activities where app_id='B'").get().ended_at,at(99));
  assert.equal(db.prepare("select app_id from device_states").get().app_id,'B');
});
test('iOS records actual named close separately from anonymous and inferred boundaries',t=>{
  const {db,repo,device}=setup(t,'ios');
  const event=(app,type,time)=>repo.iosAppEvent(device,app,type,at(time));
  event('A','open',1);event('A','open',1);event('A','close',9100);
  event('B','open',9110);event('C','open',9120);event('B','close',9118); // out of order named close
  event('B','close',9130); // mismatch must leave C open
  assert.equal(db.prepare('select app_id from device_states').get().app_id,'C');
  event('D','open',9140); event(null,'close',9150);
  const found=db.prepare("select app_id,ended_at,json_extract(extra_json,'$.endSource') source from device_activities order by id").all().map(x=>Object.values(x));
  assert.deepEqual(found,[['A',at(9100),'ios_named_close'],['B',at(9118),'ios_named_close'],
    ['C',at(9140),'ios_next_open'],['D',at(9150),'ios_unnamed_close']]);
});
test('a pause and resume of the same Android app do not count the gap as usage',t=>{
  const {db,repo,device} = setup(t,'android');
  const send=(time,kind,appId='A')=>repo.report({device,appId,occurredAt:at(time),windowTitle:null,extra:{activityObservation:kind}});
  send(20,'android_resume');send(40,'android_foreground_observation');send(50,'android_pause');
  send(50,'android_no_foreground',null);send(90,'android_resume');send(99,'android_foreground_observation');
  assert.deepEqual(db.prepare('select started_at,ended_at from device_activities order by id').all().map(x=>Object.values(x)),[[at(20),at(50)],[at(90),at(99)]]);
});
