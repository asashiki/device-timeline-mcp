import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';
import Fastify from 'fastify';
import { DeviceAuth } from '../dist/device-auth.js';
import { Repository } from '../dist/db/repo.js';
import { runMigrations } from '../dist/db/migrations.js';
import { registerRoutes } from '../dist/routes.js';

test('a read capability gets live status and cannot ingest; a device key cannot read the Agent API', async t => {
  const db = new DatabaseSync(':memory:');
  runMigrations(db);
  const app = Fastify();
  t.after(async () => { await app.close(); db.close(); });
  const auth = new DeviceAuth([{ deviceId:'android-phone', deviceName:'phone', platform:'android', token:'test-write-only' }]);
  const key = 'test-read-only-012345678901234567890123456789';
  const bodyReadDigest = createHash('sha256').update(key).digest();
  registerRoutes(app,{repo:new Repository(db,120),labels:{},rawLabels:{},retentionDays:0,auth,bodyReadDigest});
  const url = '/api/agent/body-now';
  assert.equal((await app.inject({url})).statusCode,401);
  assert.equal((await app.inject({url:url+'?token='+key})).statusCode,401);
  assert.equal((await app.inject({url,headers:{authorization:'Bearer test-write-only'}})).statusCode,401);
  const range = '/api/agent/body-range?from=2026-01-02T05:00:00Z&to=2026-01-02T06:00:00Z';
  assert.equal((await app.inject({url:range})).statusCode,401);
  assert.equal((await app.inject({url:range,headers:{authorization:'Bearer test-write-only'}})).statusCode,401);
  const rangeHeaders = {authorization:'Bearer '+key};
  const history = await app.inject({url:range,headers:rangeHeaders});
  assert.equal(history.statusCode,200);
  assert.match(history.headers['cache-control'],/no-store/);
  for (const invalidRange of [range+'&deviceId=other',range+'&from=2026-01-02T04:00:00Z',
    range.replace('05:00:00','05:00:01'),range.replace('06:00:00','04:00:00'),range.replace('02T06','22T06')]) {
    assert.equal((await app.inject({url:invalidRange,headers:rangeHeaders})).statusCode,400);
  }
  const poll = {source:'hibi_band_direct',type:'sleep_state',state:undefined,unit:'state',value:0,
    recordedAt:new Date(Date.now()-2000).toISOString(),valueJson:{origin:'band_basic_status',timeBasis:'phone_observed'}};
  const post = token => app.inject({method:'POST',url:'/api/devices/band-health',headers:{authorization:'Bearer '+token},payload:{records:[poll]}});
  assert.equal((await post(key)).statusCode,401);
  assert.equal((await post('test-write-only')).statusCode,202);
  const good = await app.inject({url,headers:{authorization:'Bearer '+key}});
  assert.equal(good.statusCode,200);
  assert.match(good.headers['cache-control'],/no-store/);
  assert.equal(good.json().currentSleep.state,'awake');
  assert.equal(good.json().currentSleep.sampledAt,poll.recordedAt);
  assert.equal(good.json().currentSleep.timeBasis,'phone_observed');
  const at = delta => new Date(Date.now() - delta).toISOString();
  const growth = {source:'hibi_band_direct',type:'sleep_progress',unit:'minutes',value:54,recordedAt:at(1000),
    valueJson:{origin:'band_live_progress',timeBasis:'phone_observed_growth',sessionStart:at(4*3600000),
      previousObservedAt:at(301000),previousMinutes:50}};
  const ingest = row => app.inject({method:'POST',url:'/api/devices/band-health',
    headers:{authorization:'Bearer test-write-only'},payload:{records:[row]}});
  assert.equal((await ingest({...growth,value:98})).statusCode,400);
  assert.equal((await ingest(growth)).statusCode,202);
  const live = (await app.inject({url,headers:{authorization:'Bearer '+key}})).json().currentSleep;
  assert.equal(live.state,'asleep');
  assert.equal(live.evidenceState,'likely_asleep');
  assert.equal(live.previousSampleAt,growth.valueJson.previousObservedAt);
  const push = {...poll, value:1, recordedAt:new Date().toISOString(), valueJson:{origin:'band_status_event',timeBasis:'phone_observed'}};
  assert.equal((await ingest(push)).statusCode,202);
  const pushed = (await app.inject({url,headers:{authorization:'Bearer '+key}})).json().currentSleep;
  assert.equal(pushed.state,'asleep');
  assert.equal(pushed.origin,'band_status_event');
  assert.deepEqual((await app.inject({url:'/api/devices/health/summary'})).json().metrics,[]);
});
