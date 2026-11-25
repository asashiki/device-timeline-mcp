import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {createHash} from 'node:crypto';
import Fastify from 'fastify';
import {runMigrations} from '../dist/db/migrations.js';
import {Repository} from '../dist/db/repo.js';
import {DeviceAuth} from '../dist/device-auth.js';
import {registerRoutes} from '../dist/routes.js';

test('phone coordinates and Mac media stay in context reads; wearable samples retain source time',async t=>{
  const db=new DatabaseSync(':memory:'); runMigrations(db);
  const repo=new Repository(db,120), app=Fastify();
  t.after(async()=>{await app.close();db.close();});
  const body='b'.repeat(40),context='c'.repeat(40),hash=x=>createHash('sha256').update(x).digest();
  const device=(platform,deviceId,token)=>({platform,deviceId,deviceName:platform,token});
  registerRoutes(app,{repo,auth:new DeviceAuth([device('android','android-phone','phone'),device('macos','mac-laptop','mac')]),
    labels:{name:s=>s},rawLabels:{},retentionDays:30,bodyReadDigest:hash(body),contextReadDigest:hash(context)});
  const call=(method,url,token,payload)=>app.inject({method,url,headers:{authorization:'Bearer '+token},payload});
  const base=Math.floor((Date.now()-120000)/60000)*60000, iso=delta=>new Date(base+delta).toISOString();
  const point={latitude:1,longitude:2,accuracyMeters:100,sampledAt:iso(0)};
  const loc='/api/devices/location';
  assert.equal((await call('POST',loc,'mac',point)).statusCode,403);
  assert.equal((await call('POST',loc,'phone',{...point,latitude:91})).statusCode,400);
  for(const dt of [0,-1000,0]) assert.equal((await call('POST',loc,'phone',{...point,sampledAt:iso(dt)})).statusCode,202);
  assert.equal((await call('POST','/api/devices/playback','mac',{player:'Spotify',state:'paused',track:'fixture',occurredAt:iso(0)})).statusCode,202);
  const records=[{type:'oxygen_saturation',unit:'%',value:98},{type:'distance',unit:'m',value:18}]
    .map(s=>({...s,recordedAt:iso(0),source:'hibi_band_direct'}));
  for(let i=0;i<2;i++) assert.equal((await call('POST','/api/devices/band-health','phone',{records})).statusCode,202);
  const read=async(path,key)=>(await call('GET',path,key)).json();
  const broad=await read('/api/agent/body-now',context), limited=await read('/api/agent/body-now',body);
  assert.equal(broad.mac.lastPlaybackEvent.track,'fixture');
  assert.deepEqual(broad.phoneLocation.sampledAt,iso(0));
  assert.equal(limited.phoneLocation,undefined);assert.equal(limited.mac,undefined);
  assert.equal(limited.oxygenSaturation.percent,98);assert.equal(limited.oxygenSaturation.sampledAt,iso(0));
  const params=new URLSearchParams({from:iso(0),to:iso(60000)});
  const range='/api/agent/body-range?'+params.toString();
  assert.equal((await read(range,context)).phoneLocations.length,1);
  const sample=await read(range,body);
  assert.equal(sample.phoneLocations,undefined);
  assert.deepEqual(sample.distance,{meters:18,observedMinutes:1,lastSampleAt:iso(0)});
  assert.equal((await read('/api/devices/health/summary','none')).metrics.length,0);
});
