import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { createHash, randomUUID } from 'node:crypto';
import Fastify from 'fastify';
import { runMigrations } from '../dist/db/migrations.js';
import { DeviceAuth } from '../dist/device-auth.js';
import { registerWrist } from '../dist/wrist-control.js';

test('scheduled commands survive restart, can be cancelled and are delivered at most once', async t => {
  const db = new DatabaseSync(':memory:');
  runMigrations(db);
  const secret = 'w'.repeat(40), phone = 'p'.repeat(40);
  const device = { deviceId: 'android-phone', deviceName: 'Phone', platform: 'android', token: phone };
  function service() {
    const app = Fastify();
    registerWrist(app, db, new DeviceAuth([device]), createHash('sha256').update(secret).digest());
    return app;
  }
  let app = service();
  t.after(async () => { await app.close(); db.close(); });
  const api = (method, url, token, payload) => app.inject({method, url, headers: {authorization: 'Bearer ' + token}, payload});
  const wrist = '/api/agent/wrist';
  const next = '/api/devices/wrist/next';
  const cancel = { action: 'buzz', requestId: randomUUID(), deliverAt: new Date(Date.now() + 80000).toISOString() };
  assert.equal((await api('POST', wrist, phone, cancel)).statusCode, 401);
  assert.equal((await api('POST', wrist, secret, cancel)).statusCode, 202);
  const immediate = {action: 'notify', requestId: randomUUID(), args: {text: 'Example'}};
  await api('POST', wrist, secret, immediate);
  await app.close();
  app = service();
  assert.equal((await api('DELETE', wrist + '/' + cancel.requestId, secret)).json().status, 'cancelled');
  const measured = Date.now() - 20000;
  const link = {version:1,connected:true,note:'connected',since:measured,battery:0,batteryAt:measured,powerMode:'charging'};
  assert.equal((await api('POST', next, secret, link)).statusCode, 401);
  assert.equal((await api('POST', next, phone, link)).json().command.id, immediate.requestId);
  assert.equal((await api('POST', wrist, secret, immediate)).json().status, 'claimed');
  assert.equal((await api('POST', wrist, secret, {...immediate,args:{text:'different'}})).statusCode,409);
  await api('POST', '/api/devices/wrist/receipt', phone, {id:immediate.requestId,status:'sent',detail:'sent'});
  assert.equal((await api('GET', wrist + '/' + immediate.requestId, secret)).json().status,'sent');
  assert.equal((await api('DELETE', wrist + '/' + immediate.requestId, secret)).statusCode,409);
  const status = (await api('GET', wrist, secret)).json();
  assert.equal(status.phoneOnline,true);
  assert.equal(status.wristBattery.percent,0);
  assert.equal(status.wristBattery.measuredAt,new Date(measured).toISOString());
  await app.close();
  app=service();
  const restarted = (await api('GET', wrist, secret)).json();
  assert.equal(restarted.phoneOnline,false);
  assert.equal(restarted.wristBattery.percent,0);
  const expiry = {action:'buzz',requestId:randomUUID()};
  await api('POST', wrist, secret, expiry);
  db.prepare('UPDATE private_wrist_commands SET expires=? WHERE id=?').run(Date.now()-1,expiry.requestId);
  assert.equal((await api('GET',wrist+'/'+expiry.requestId,secret)).json().status,'expired');
});

test('wake-at stages the alarm before its target, ignores legacy phones and never arms late', async t => {
  const db = new DatabaseSync(':memory:');
  runMigrations(db);
  const app = Fastify();
  t.after(async () => { await app.close(); db.close(); });
  const secret = 'w'.repeat(40), phone = 'p'.repeat(40);
  const device = {deviceId: 'android-phone', deviceName: 'Phone', platform: 'android', token: phone};
  registerWrist(app, db, new DeviceAuth([device]), createHash('sha256').update(secret).digest());
  const wrist = '/api/agent/wrist', next = '/api/devices/wrist/next';
  const api = (payload, url=wrist, token=secret, headers={}) => app.inject({
    method:'POST', url, headers:{authorization:'Bearer '+token,...headers}, payload
  });
  const time = new Date(Math.floor(Date.now()/60000)*60000+2*86400000).toISOString();
  const cmd = {action:'wake-at',requestId:randomUUID(),args:{at:time}};
  const future = (await api(cmd)).json();
  assert.equal(future.wakeAt,time);
  assert.equal(Date.parse(future.deliverAt),Date.parse(time)-23*3600000);
  assert.equal(Date.parse(future.expiresAt),Date.parse(time)-15000);
  assert.equal((await api(cmd)).json().id,future.id);
  assert.equal((await api({...cmd,deliverAt:time})).statusCode,400);
  assert.equal((await api({...cmd,args:{at:time.replace('00.000Z','15.000Z')},requestId:randomUUID()})).statusCode,400);
  const near = {action:'wake-at',requestId:randomUUID(),args:{at:new Date(Date.parse(time)-1.5*86400000).toISOString()}};
  assert.ok(Date.parse((await api(near)).json().deliverAt) <= Date.now());
  const battery = {action:'battery',requestId:randomUUID()};
  await api(battery);
  const link = {version:1,connected:true,note:'connected',since:Date.now()-10000};
  assert.equal((await api(link,next,phone)).json().command.id,battery.requestId);
  assert.equal((await api(link,next,phone,{'x-hibi-wrist-version':'2'})).json().command.id,near.requestId);
  const receipt = {id:near.requestId, status:'confirmed', detail:'on the band', wakeMode:'reminder'};
  await api(receipt,'/api/devices/wrist/receipt',phone);
  await api(receipt,'/api/devices/wrist/receipt',phone);
  const get = async id => (await app.inject({url:wrist+'/'+id,headers:{authorization:'Bearer '+secret}})).json();
  const confirmed = await get(near.requestId);
  assert.equal(confirmed.status,'confirmed');
  const backup = await get(confirmed.followUpId);
  assert.equal(backup.action,'wake-ring');
  assert.equal(backup.deliverAt,near.args.at);
  assert.equal(db.prepare("SELECT count(*) AS n FROM private_wrist_commands WHERE action='wake-ring'").get().n,1);
  assert.equal((await app.inject({url:wrist+'/'+backup.id,method:'DELETE',headers:{authorization:'Bearer '+secret}})).json().status,'cancelled');
  const removeZero = {action:'reminder-delete',requestId:randomUUID(),args:{id:0}};
  assert.equal((await api(removeZero)).statusCode,202);
  const lateId = randomUUID();
  db.prepare("INSERT INTO private_wrist_commands(id,action,args,due,expires) VALUES(?,?,?,?,?)")
    .run(lateId,'wake-at',JSON.stringify(near.args),Date.now()-60000,Date.now()-1);
  const expired = await app.inject({url:wrist+'/'+lateId,headers:{authorization:'Bearer '+secret}});
  assert.equal(expired.json().status,'expired');
});

test('a standby phone can claim a command while reporting its band link as disconnected', async t => {
  const db = new DatabaseSync(':memory:'); runMigrations(db);
  const app = Fastify(), key = 's'.repeat(40), token = 'p'.repeat(40);
  const auth = new DeviceAuth([{deviceId:'android-phone',deviceName:'Phone',platform:'android',token}]);
  registerWrist(app,db,auth,createHash('sha256').update(key).digest());
  t.after(async()=>{await app.close();db.close();});
  const post=(url,credential,payload,extra={})=>app.inject({method:'POST',url,
    headers:{authorization:'Bearer '+credential,...extra},payload});
  const id=randomUUID();
  await post('/api/agent/wrist',key,{action:'battery',requestId:id});
  const reply=await post('/api/devices/wrist/next',token,{version:1,connected:false,standby:true,since:0,note:'standby'},
    {'x-hibi-wrist-version':'3'});
  assert.equal(reply.json().command.id,id);
  const status=(await app.inject({url:'/api/agent/wrist',headers:{authorization:'Bearer '+key}})).json();
  assert.equal(status.link.connected,false);assert.equal(status.link.standby,true);
});
