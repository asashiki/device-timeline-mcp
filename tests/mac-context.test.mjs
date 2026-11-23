import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { Writable } from 'node:stream';
import { DatabaseSync } from 'node:sqlite';
import Fastify from 'fastify';
import { DeviceAuth } from '../dist/device-auth.js';
import { Repository } from '../dist/db/repo.js';
import { runMigrations } from '../dist/db/migrations.js';
import { registerRoutes } from '../dist/routes.js';
import { MacContext, MAC_CONTEXT_TTL_MS } from '../dist/mac-context.js';

test('context grants are isolated and a window never enters logs or the public timeline', async t => {
  const db = new DatabaseSync(':memory:'); runMigrations(db);
  let logs = '';
  const stream = new Writable({write(chunk,_enc,done){logs+=chunk;done();}});
  const app = Fastify({logger:{stream}});
  t.after(async()=>{await app.close();db.close();});
  const key = {body:'t'.repeat(47), context:'q'.repeat(47), phone:'phone-write-key', mac:'mac-write-key'};
  const auth = new DeviceAuth([
    {deviceId:'android-phone',platform:'android',token:key.phone,deviceName:'phone'},
    {deviceId:'mac-laptop',platform:'macos',token:key.mac,deviceName:'Mac'},
  ]);
  const digest = x => createHash('sha256').update(x).digest();
  registerRoutes(app,{repo:new Repository(db,120), auth, labels:{}, rawLabels:{}, retentionDays:0,
    bodyReadDigest:digest(key.body), contextReadDigest:digest(key.context)});
  const headers=token=>({authorization:'Bearer '+token});
  const read=token=>app.inject({url:'/api/agent/body-now',headers:headers(token)});
  const post=(token,payload)=>app.inject({method:'POST',url:'/api/devices/mac-context',headers:headers(token),payload});
  const now=Date.now();
  const stamp=n=>new Date(now+n).toISOString();
  const title='private-window-sentinel';
  const active={sampledAt:stamp(-1000),state:'active',appName:'Notes',appId:'com.apple.Notes',windowTitle:title,desktop:2};
  assert.equal((await read(key.context)).json().mac.state,'unknown');
  assert.equal((await post(key.context,active)).statusCode,401);
  assert.equal((await post(key.phone,active)).statusCode,403);
  assert.equal((await read(key.mac)).statusCode,401);
  assert.equal((await post(key.mac,active)).statusCode,202);
  const granted=await read(key.context);
  assert.match(granted.headers['cache-control'],/private, no-store/);
  assert.equal(granted.json().mac.windowTitle,title);
  assert.equal(granted.json().mac.desktop,2);
  assert.ok(granted.json().heartRate);
  assert.equal('mac' in (await read(key.body)).json(),false);
  assert.equal('devices' in (await read(key.body)).json(),false);
  const range=await app.inject({url:'/api/agent/body-range?from=2026-01-02T05:00:00Z&to=2026-01-02T06:00:00Z',
    headers:headers(key.context)});
  assert.equal(range.statusCode,200);
  assert.equal('mac' in range.json(),false);
  assert.equal((await post(key.mac,{state:'paused',sampledAt:stamp(0),windowTitle:title})).statusCode,400);
  assert.equal((await post(key.mac,{state:'paused',sampledAt:stamp(0)})).statusCode,202);
  await post(key.mac,active); // reordered in-flight POST after pause.
  const paused=(await read(key.context)).json().mac;
  assert.equal(paused.state,'paused');assert.equal(paused.windowTitle,null);assert.equal(paused.appName,null);
  const visible=await app.inject({url:'/api/devices/timeline-query?date=2026-01-02'});
  assert.equal(visible.statusCode,200);
  assert.ok(!visible.body.includes(title));
  assert.equal(db.prepare('SELECT count(*) AS n FROM device_activities').get().n,0);
  assert.ok(!logs.includes(title));
});

test('delayed, expired, restarted or incomplete observations do not retain a previous window', () => {
  const cache=new MacContext();
  const now=Date.UTC(2026,1,1);
  const snapshot=n=>({state:'active',sampledAt:new Date(now+n).toISOString(),appName:'Editor',windowTitle:'old title'});
  try {
    cache.record(snapshot(-MAC_CONTEXT_TTL_MS-1),now);
    assert.equal(cache.read(now).state,'unknown');
    cache.record(snapshot(-1500),now);
    assert.equal(cache.read(now+50).windowTitle,'old title');
    const expires=now-1500+MAC_CONTEXT_TTL_MS;
    assert.equal(cache.read(expires).state,'stale');
    assert.equal(cache.read(expires).windowTitle,null);
    assert.equal(new MacContext().read(now).windowTitle,null);
    cache.record(snapshot(500),now+500);
    cache.record({state:'active',sampledAt:new Date(now+1000).toISOString(),appName:'Finder'},now+1050);
    assert.equal(cache.read(now+1100).appName,'Finder');
    assert.equal(cache.read(now+1100).windowTitle,null);
  } finally {cache.clear();}
});

test('unified device list uses observation time rather than a replay, identifies disagreement and omits activity titles', async t => {
  const db=new DatabaseSync(':memory:');runMigrations(db);
  const app=Fastify();t.after(async()=>{await app.close();db.close();});
  const repo=new Repository(db,120);
  const mac={deviceId:'mac-laptop',deviceName:'Mac',platform:'macos',token:'mac-write'};
  const phone={deviceId:'android-phone',deviceName:'Phone',platform:'android',token:'android-write'};
  const ios={deviceId:'my-ios',deviceName:'iPhone',platform:'ios',token:'ios-write'};
  const win={deviceId:'another-pc',deviceName:'PC',platform:'windows',token:'win-write'};
  const reader='unified-device-read-test'.repeat(3);
  registerRoutes(app,{repo,auth:new DeviceAuth([mac,phone,ios,win]),labels:{name:id=>id},rawLabels:{},retentionDays:0,
    contextReadDigest:createHash('sha256').update(reader).digest()});
  const now=Date.now();
  const at=offset=>new Date(now+offset).toISOString();
  function report(device,appId,offset){
    repo.report({device,appId,occurredAt:at(offset),windowTitle:'must-never-appear-on-agent-devices',extra:{test:'hidden'}});
  }
  // Fresh delivery with an old phone observation and an older iOS app left
  // open across a newer hourly device heartbeat.
  report(phone,'phone-app',-650000);
  report(mac,'old-mac-app',-2000);
  repo.iosAppEvent(ios,'old-ios-app','open',at(-2400000));
  repo.iosSnapshot(ios,{},at(-500));
  report(win,'idle-win-app',-650000);
  db.prepare('UPDATE device_states SET last_seen_at=? WHERE device_id=?').run(at(-610000),win.deviceId);
  const posted=await app.inject({method:'POST',url:'/api/devices/mac-context',headers:{authorization:'Bearer '+mac.token},
    payload:{state:'active',sampledAt:at(-1000),appId:'new-mac-app',appName:'New Mac App',windowTitle:'specific-private-title'}});
  assert.equal(posted.statusCode,202);
  const response=await app.inject({url:'/api/agent/body-now',headers:{authorization:'Bearer '+reader}});
  assert.equal(response.statusCode,200);
  const list=response.json().devices;
  assert.equal(list.length,4);
  const d=id=>list.find(x=>x.deviceId===id);
  assert.equal(d(phone.deviceId).reportStatus,'recent');
  assert.equal(d(phone.deviceId).foreground.state,'unknown');
  assert.equal(d(phone.deviceId).activitySampledAt,at(-650000));
  assert.equal(d(ios.deviceId).reportStatus,'recent');
  assert.equal(d(ios.deviceId).foreground.appId,null);
  assert.equal(d(win.deviceId).reportStatus,'stale');
  assert.equal(d(win.deviceId).foreground.state,'unknown');
  assert.equal(d(mac.deviceId).foreground.appId,'new-mac-app');
  assert.equal(d(mac.deviceId).lastReportedAppId,'old-mac-app');
  assert.equal(d(mac.deviceId).signalsDiffer,true);
  assert.equal(response.json().mac.windowTitle,'specific-private-title');
  assert.ok(!response.body.includes('must-never-appear-on-agent-devices'));
  assert.ok(!JSON.stringify(list).includes('specific-private-title'));
  assert.ok(!JSON.stringify(list).includes('hidden'));
});
