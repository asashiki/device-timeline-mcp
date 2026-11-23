import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {createHash} from 'node:crypto';
import Fastify from 'fastify';
import {Repository} from '../dist/db/repo.js';
import {runMigrations} from '../dist/db/migrations.js';
import {DeviceAuth} from '../dist/device-auth.js';
import {registerRoutes} from '../dist/routes.js';
import {registerConsoleBody} from '../dist/console-body.js';

test('click time feeds the same authenticated body reads as the private Notion bridge; polls and locks do not',async t=>{
  const db=new DatabaseSync(':memory:');runMigrations(db);
  const repo=new Repository(db,120), app=Fastify();t.after(async()=>{await app.close();db.close();});
  const mac={deviceId:'mac-laptop',deviceName:'Mac',platform:'macos',token:'write'};
  const auth=new DeviceAuth([mac]), token='c'.repeat(40), body='b'.repeat(40), hash=s=>createHash('sha256').update(s).digest();
  const keys={bodyReadDigest:hash(body),contextReadDigest:hash(token)};
  registerRoutes(app,{repo,auth,...keys,labels:{name:x=>x,liveDescription:()=>''},rawLabels:{},retentionDays:0});
  registerConsoleBody(app,repo,db,keys,async()=>{throw Error('unused')},'https://hibi.example');
  const headers=k=>({authorization:'Bearer '+k}), now=Date.now(), stamp=d=>new Date(now+d).toISOString();
  const post=payload=>app.inject({method:'POST',url:'/api/devices/mac-context',headers:headers(mac.token),payload});
  const read=async key=>(await app.inject({url:'/api/agent/body-now',headers:headers(key)})).json();
  const login=await app.inject({method:'POST',url:'/api/console/session',headers:{...headers(body),origin:'https://hibi.example'}});
  const cookie=login.headers['set-cookie'].split(';')[0];
  const web=async()=>(await app.inject({url:'/api/console/body',headers:{cookie}})).json();
  assert.equal((await post({state:'active',sampledAt:stamp(-8000)})).statusCode,202);
  assert.equal((await read(token)).currentSleep.state,'unknown');
  const captured=stamp(-60000);
  assert.equal((await post({state:'active',sampledAt:stamp(-5000),recentInputAt:captured,windowTitle:'private-title',appName:'secret-app'})).statusCode,202);
  for(const b of [await read(body),await read(token),await web()]){
    assert.equal(b.currentSleep.state,'likely_awake');assert.equal(b.currentSleep.reason,'recent_device_input');
    assert.equal(b.currentSleep.sampledAt,captured);
    assert.doesNotMatch(JSON.stringify(b.currentSleep),/private-title|secret-app/);
  }
  assert.equal((await post({state:'active',sampledAt:stamp(-4000),recentInputAt:stamp(-125000)})).statusCode,400);
  assert.equal((await post({state:'idle',sampledAt:stamp(-3000),recentInputAt:stamp(-5000)})).statusCode,400);
  db.prepare("INSERT INTO band_sleep_observations(device_id,session_start,observed_at,state,origin,time_basis,received_at) VALUES('android-phone','',?,'asleep','band_basic_status','phone_observed',?)").run(stamp(-65000),stamp(-2000));
  assert.equal((await read(token)).currentSleep.reason,'device_input_conflicts_with_sleep');
  db.exec('DELETE FROM band_sleep_observations');
  db.prepare('INSERT INTO band_sleep VALUES(?,?,?,?,?)').run('android-phone',stamp(-3600000),stamp(-30000),50,stamp(-2000));
  assert.equal((await web()).currentSleep.state,'unknown');
  db.exec('DELETE FROM band_sleep');
  assert.equal((await post({state:'locked',sampledAt:stamp(-1000)})).statusCode,202);
  assert.equal((await web()).currentSleep.state,'unknown');
});

test('public and private device summaries retain zero and false, and battery-free iPhone heartbeats preserve source time',async t=>{
  const db=new DatabaseSync(':memory:');runMigrations(db);
  const repo=new Repository(db,120),app=Fastify();t.after(async()=>{await app.close();db.close();});
  const ios={deviceId:'ios2',deviceName:'iPhone',platform:'ios',token:'i'},win={deviceId:'pc',deviceName:'PC',platform:'windows',token:'w'};
  const mac={deviceId:'mac-laptop',deviceName:'Mac',platform:'macos',token:'m'}, android={deviceId:'android-phone',deviceName:'Phone',platform:'android',token:'a'};
  const key='q'.repeat(40);
  registerRoutes(app,{repo,auth:new DeviceAuth([ios,win,mac,android]),rawLabels:{},retentionDays:0,
    labels:{name:k=>k,liveDescription:()=>''},contextReadDigest:createHash('sha256').update(key).digest()});
  const t1=new Date(Date.now()-50000).toISOString(),t2=new Date(Date.now()-1000).toISOString();
  const sendSnapshot=payload=>app.inject({method:'POST',url:'/api/devices/ios/snapshot',headers:{authorization:'Bearer i'},payload});
  assert.equal((await sendSnapshot({batteryLevel:0,isCharging:false,occurredAt:t1})).statusCode,202);
  await sendSnapshot({focusMode:'focus',occurredAt:t2});repo.iosAppEvent(ios,'App','open',t2);
  for(const [device,extra] of [[win,{}],[mac,{battery_percent:100,battery_charging:false,power_source:'ac'}],[android,{battery_percent:61,battery_charging:true}]]){
    repo.report({device,appId:null,extra,occurredAt:t2,windowTitle:null});
  }
  for(const url of ['/api/devices/current','/api/agent/body-now']){
    const res=await app.inject({url,headers:{authorization:'Bearer '+key}});
    const list=res.json().devices, get=id=>list.find(d=>d.deviceId===id);
    assert.equal(get(ios.deviceId).batteryPercent,0);assert.equal(get(ios.deviceId).charging,false);
    assert.equal(get(ios.deviceId).batteryReportedAt,t1);assert.equal(get(ios.deviceId).powerSource,null);
    assert.equal(get(win.deviceId).batteryReportedAt,null);assert.equal(get(win.deviceId).batteryPercent,null);
    assert.equal(get(mac.deviceId).powerSource,'ac');assert.equal(get(mac.deviceId).charging,false);
    assert.equal(get(android.deviceId).batteryPercent,61);
  }
});
