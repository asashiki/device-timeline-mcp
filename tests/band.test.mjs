import test from 'node:test';
import assert from 'node:assert/strict';
import Fastify from 'fastify';
import {DatabaseSync} from 'node:sqlite';
import {runMigrations} from '../dist/db/migrations.js';
import {Repository} from '../dist/db/repo.js';
import {DeviceAuth} from '../dist/device-auth.js';
import {registerRoutes} from '../dist/routes.js';

test('direct wearable samples keep source time, require a device token and stay out of public health reads', async t => {
  const db = new DatabaseSync(':memory:');
  runMigrations(db);
  const app = Fastify();
  t.after(async () => { await app.close(); db.close(); });
  registerRoutes(app, {repo: new Repository(db, 120), auth:new DeviceAuth([
    {token:'test-android',deviceId:'phone',deviceName:'phone',platform:'android'},
    {token:'test-mac',deviceId:'mac',deviceName:'mac',platform:'macos'}]),labels:{},rawLabels:{},retentionDays:0});
  const records = [{source:'hibi_band_direct',type:'heart_rate',value:88,unit:'bpm',recordedAt:'2026-11-22T23:00:00Z'}];
  const post = (token, list) => app.inject({method:'POST',url:'/api/devices/band-health',
    headers: token ? {authorization:'Bearer ' + token} : {}, payload:{records:list}});
  assert.equal((await post('',records)).statusCode,401);
  assert.equal((await post('test-mac',records)).statusCode,403);
  assert.equal((await post('test-android',[...records,{...records[0],unit:'count'}])).statusCode,400);
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM band_samples').get().n,0);
  assert.equal((await post('test-android',records)).statusCode,202);
  assert.equal((await post('test-android',records)).json().accepted,1);
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM band_samples').get().n,1);
  assert.equal(db.prepare('SELECT recorded_at FROM band_samples').get().recorded_at,'2026-11-22T23:00:00.000Z');
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM device_health').get().n,0);
  assert.deepEqual((await app.inject({url:'/api/devices/health/summary'})).json().metrics,[]);
  const sleep = {type:'sleep_session',source:'hibi_band_direct',unit:'minutes',recordedAt:'2026-11-22T23:00:00+08:00',
    value:450,valueJson:{endAt:'2026-11-23T06:30:00+08:00',asleepMinutes:420}};
  const interval={...sleep,type:'sleep_band_interval',valueJson:{endAt:sleep.valueJson.endAt}};
  assert.equal((await post('test-android',[{...interval,value:999}])).statusCode,400);
  assert.equal((await post('test-android',[{...interval,valueJson:sleep.valueJson}])).statusCode,400);
  for (let i=0;i<2;i++) assert.equal((await post('test-android',[interval])).statusCode,202);
  const visible=()=>db.prepare("SELECT source,asleep_minutes FROM private_sleep_history WHERE superseded_at IS NULL").all();
  assert.equal(visible().length,1);assert.equal(visible()[0].source,'hibi_band_interval');
  assert.equal(visible()[0].asleep_minutes,null);
  assert.equal(db.prepare('SELECT count(*) AS n FROM band_sleep').get().n,0);
  assert.equal((await post('test-android',[{...sleep,value:999}])).statusCode,400);
  assert.equal((await post('test-android',[sleep])).statusCode,202);
  assert.equal((await post('test-android',[{...sleep,valueJson:{endAt:sleep.valueJson.endAt}}])).statusCode,202);
  assert.equal(db.prepare('SELECT asleep_minutes FROM band_sleep').get().asleep_minutes,420);
  assert.equal(db.prepare('SELECT count(*) AS n FROM band_sleep').get().n,1);
  assert.equal(visible().length,1);assert.equal(visible()[0].source,'hibi_band_direct');
});
