import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import Fastify from 'fastify';
import { runMigrations } from '../dist/db/migrations.js';
import { Repository } from '../dist/db/repo.js';
import { registerRoutes } from '../dist/routes.js';
import { DeviceAuth } from '../dist/device-auth.js';
import { personalDay } from '../dist/db/console-day.js';

test('a dated wake endpoint survives retries, stays out of sleep duration and is superseded by native sleep', async t => {
  const db = new DatabaseSync(':memory:');
  runMigrations(db);
  const app = Fastify();
  t.after(async () => { await app.close(); db.close(); });
  const repo = new Repository(db,120), id = 'android-phone';
  registerRoutes(app,{repo,auth:new DeviceAuth([{deviceId:id,deviceName:'Phone',platform:'android',token:'writer'}]),
    labels:{name:s=>s},rawLabels:{},retentionDays:0});
  const day = 86400000, minute = 60000;
  const end = Math.floor(Date.now()/day)*day - 10*minute;
  const iso = n => new Date(n).toISOString();
  const record = (fileStart, endedAt=end) => ({ type:'sleep_wake_marker',source:'hibi_band_direct',unit:'state',
    value:1,recordedAt:iso(endedAt),valueJson:{fileStart:iso(fileStart)} });
  const post = records => app.inject({method:'POST',url:'/api/devices/band-health',headers:{authorization:'Bearer writer'},payload:{records}});
  for (const invalid of [record(end-day),record(end-14*day-1000),record(end-day,Date.now()+3600000)])
    assert.equal((await post([invalid])).statusCode,400);
  const marker = record(end-day-5*3600000);
  for (let i=0; i<2; i++) assert.equal((await post([marker])).statusCode,202);
  const now = new Date(Date.now()+1000);
  const dayFrom = end-23*3600000-50*minute;
  const bounds={date:iso(dayFrom).slice(0,10),from:iso(dayFrom),to:iso(dayFrom+day),asOf:now.toISOString(),timeZone:'UTC'};
  const markerRange=()=>repo.bodyRange(iso(end-minute),iso(end+minute),now).sleep;
  const dayMarkers=()=>personalDay(db,bounds,false).hours.flatMap(h=>h.sleep.wakeMarkers);
  assert.equal(db.prepare('select count(*) as n from private_band_wake_markers').get().n,1);
  assert.equal(repo.liveBodyNow().lastWakeMarker.endedAt,iso(end));
  assert.equal(repo.liveBodyNow().lastCompletedSleep,null);
  assert.equal(repo.liveBodyNow().currentSleep.sampledAt,null);
  assert.equal(markerRange().recordedSessionOverlapMinutes,null);
  assert.equal(markerRange().asleepMinutes,null);
  assert.deepEqual(markerRange().sessions,[]);
  assert.equal(markerRange().wakeMarkers[0].fileStart,marker.valueJson.fileStart);
  assert.equal(dayMarkers().length,1);
  assert.equal(repo.bodyRange(iso(end-minute),iso(end),now).sleep.wakeMarkers.length,0);
  assert.equal((await post([{type:'sleep_session',source:'hibi_band_direct',unit:'minutes',value:60,recordedAt:iso(end-60*minute),valueJson:{endAt:iso(end)}}])).statusCode,202);
  assert.deepEqual(markerRange().wakeMarkers,[]);
  assert.deepEqual(dayMarkers(),[]);
  assert.equal(repo.liveBodyNow().lastWakeMarker,null);
});
