import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {createHash} from 'node:crypto';
import Fastify from 'fastify';
import {runMigrations} from '../dist/db/migrations.js';
import {Repository} from '../dist/db/repo.js';
import {dayRangeUtc} from '../dist/time.js';
import {DeviceAuth} from '../dist/device-auth.js';
import {registerRoutes} from '../dist/routes.js';
import {registerConsoleBody} from '../dist/console-body.js';

test('calendar days use both timezone boundaries during DST changes',()=>{
  assert.deepEqual(dayRangeUtc('2026-03-08','America/New_York'),{
    start:'2026-03-08T05:00:00.000Z',end:'2026-03-09T04:00:00.000Z'});
  assert.deepEqual(dayRangeUtc('2026-11-01','America/New_York'),{
    start:'2026-11-01T04:00:00.000Z',end:'2026-11-02T05:00:00.000Z'});
});

test('full public day preserves cross-device overlap and keeps unmatched observations out of durations',async t=>{
  const app=Fastify(), db=new DatabaseSync(':memory:');runMigrations(db);
  t.after(async()=>{await app.close();db.close()});
  registerRoutes(app,{repo:new Repository(db,120), auth:new DeviceAuth([]),labels:{name:id=>id},rawLabels:{}, retentionDays:60});
  const q='/api/devices/day?date=2025-02-04';
  const day=(await app.inject(q)).json();
  const base=Date.parse(day.from), ts=delta=>new Date(base+delta).toISOString();
  const states=db.prepare("INSERT INTO device_states(device_id,device_name,platform,last_seen_at,updated_at) VALUES(?,?,?,?,?)");
  for(const platform of ['macos','ios']) states.run(platform,platform,platform,ts(0),ts(0));
  const insert=db.prepare('INSERT INTO device_activities(device_id,app_id,started_at,ended_at,extra_json,created_at) VALUES(?,?,?,?,?,?)');
  const add=(device,name,a,b,extra=null)=>insert.run(device,name,ts(a),b===null?null:ts(b),JSON.stringify(extra),ts(a));
  add('macos','cross',-8000,2000); add('ios','cross',-1000,4000,{endSource:'ios_named_close'});
  add('ios','unmatched',1000,8000,{endSource:'ios_next_open'}); add('macos','open',12000,null);
  add('macos','empty',-1000,0); add('macos','tomorrow',86400000,86400001);
  for(let i=0;i<1001;i++)add('macos','many',20000+i,20000+i);
  const result=(await app.inject(q)).json();
  const pick=name=>result.activities.filter(a=>a.appId===name);
  assert.deepEqual(pick('cross').map(a=>a.durationSeconds),[2,4]);
  assert.ok(pick('cross').every(a=>a.startedAt===ts(0)));
  assert.deepEqual(pick('unmatched').map(a=>[a.kind,a.startedAt,a.durationSeconds]),[
    ['point',ts(1000),null],['point',ts(8000),null]]);
  assert.equal(pick('open')[0].kind,'point');
  assert.equal(pick('empty').length+pick('tomorrow').length,0);
  assert.equal(pick('many').length,1001);
  assert.equal((await app.inject('/api/devices/day?date=2025-02-29')).statusCode,400);
});

test('old and body-only sessions retain limited access; owner pairing and context login can read map coordinates',async t=>{
  const db=new DatabaseSync(':memory:');runMigrations(db);
  const app=Fastify();t.after(async()=>{await app.close();db.close()});
  const q='/api/console/day?date=2025-02-04',origin='https://hibi.example';
  const base=Date.parse(dayRangeUtc('2025-02-04').start), now=base+86400000, ts=d=>new Date(base+d).toISOString();
  const body='b'.repeat(40),context='c'.repeat(40),old='x'.repeat(43), hash=t=>createHash('sha256').update(t).digest();
  db.exec('CREATE TABLE private_console_body_sessions(session_sha256 TEXT PRIMARY KEY,expires_at INTEGER NOT NULL) WITHOUT ROWID');
  db.prepare('INSERT INTO private_console_body_sessions VALUES(?,?)').run(hash(old).toString('hex'),now+99999);
  const source='android-phone', save=db.prepare('INSERT INTO band_samples VALUES(?,?,?,?,?)');
  save.run(source,'steps',0,ts(100),ts(100));save.run(source,'heart_rate',80,ts(3599000),ts(3600000));
  save.run(source,'heart_rate',88,ts(3600000),ts(3600000));
  db.prepare('INSERT INTO private_phone_location VALUES(?,?,?,?,?,?)').run(source,ts(12),10,13,2000,ts(20));
  db.prepare('INSERT INTO band_sleep_intervals VALUES(?,?,?,?)').run(source,ts(-900000),ts(600000),ts(650000));
  let proof;
  registerConsoleBody(app,new Repository(db,120),db,{bodyReadDigest:hash(body),contextReadDigest:hash(context)},
    async p=>{proof=p;return origin+'/private'},origin,()=>now);
  const run=(method,url,headers={},payload)=>app.inject({method,url,headers,payload});
  const cookie=x=>x.split(';')[0];
  const login=async key=>cookie((await run('POST','/api/console/session',{origin,authorization:'Bearer '+key})).headers['set-cookie']);
  const bodyCookie=await login(body);
  for(const c of [bodyCookie,'__Host-hibi_body='+old]){
    const res=await run('GET',q,{cookie:c});
    assert.equal(res.statusCode,200);
    const data=res.json();
    assert.equal(data.hours.length,24);
    assert.deepEqual(data.hours[0].steps,{steps:0,observedMinutes:1});
    assert.deepEqual(data.hours[1].steps,{steps:null,observedMinutes:0});
    assert.equal(data.hours[0].heartRate.latestBpm,80);assert.equal(data.hours[1].heartRate.latestBpm,88);
    assert.equal(data.hours[0].sleep.recordedSessionOverlapMinutes,10);
    assert.equal(data.locationAccess,false);assert.equal(data.locations,null);
  }
  const challenge=await run('POST','/api/console/notion/start',{origin});
  const pair=await run('POST','/api/console/notion/confirm',{origin,cookie:cookie(challenge.headers['set-cookie'])},{proof});
  for(const c of [await login(context),cookie(pair.headers['set-cookie'].find(header=>header.startsWith('__Host-hibi_body=')))]){
    const res=await run('GET',q,{cookie:c}); assert.match(res.headers['cache-control'],/no-store/);
    const data=res.json();assert.equal(data.locationAccess,true);assert.equal(data.locations[0].sampledAt,ts(12));
    assert.equal((await run('GET',q,{cookie:c,origin:'https://other.example'})).statusCode,403);
  }
  assert.equal((await run('GET',q)).statusCode,401);
  assert.equal((await run('GET',q+'&debug=true',{cookie:bodyCookie})).statusCode,400);
});
