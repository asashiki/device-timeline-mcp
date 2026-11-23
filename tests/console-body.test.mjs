import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {DatabaseSync} from 'node:sqlite';
import {tmpdir} from 'node:os';
import {mkdtemp,rm} from 'node:fs/promises';
import {join} from 'node:path';
import Fastify from 'fastify';
import cors from '@fastify/cors';
import {registerConsoleBody} from '../dist/console-body.js';

test('pair via private page, use one browser, refresh and revoke isolated body access', async t=>{
  const app=Fastify();
  await app.register(cors,{origin:true});
  t.after(()=>app.close());
  const origin='https://hibi.example';
  const url='https://app.notion.com/p/'+'1'.repeat(32);
  const keys={'bodyReadDigest':createHash('sha256').update('b'.repeat(32)).digest(),
              'contextReadDigest':createHash('sha256').update('c'.repeat(32)).digest()};
  let now=Date.now(), proof, writes=0, slow=false;
  const db=new DatabaseSync(':memory:'); t.after(()=>db.close());
  registerConsoleBody(app,{liveBodyNow:()=>({asOf:'test',heartRate:{bpm:88},mac:'private-title',devices:['private-device']})},
    db,keys,async x=>{if(slow)throw Error('internal-private');writes++;proof=x;return url;},origin,()=>now);
  const start='/api/console/notion/start', confirm='/api/console/notion/confirm', session='/api/console/session', body='/api/console/body';
  const call=(method,url,headers={},payload)=>app.inject({method,url,headers,payload});
  const cookie=res=>res.headers['set-cookie'].split(';')[0];
  assert.equal((await call('GET',body)).statusCode,401);
  assert.equal((await call('POST',start)).statusCode,403);
  const started=await call('POST',start,{origin});
  assert.equal(started.statusCode,200);
  assert.equal(started.json().notionUrl,url);
  assert.equal(started.json().expiresIn,300);
  assert.equal(started.json().matchCode,proof.slice(-4));
  assert.ok(!started.body.includes(proof));
  assert.match(started.headers['set-cookie'],/HttpOnly; SameSite=Lax/);
  const challenge=cookie(started);
  assert.deepEqual((await call('POST',start,{origin,cookie:challenge})).json(),started.json());
  assert.equal(writes,1);
  assert.equal((await call('POST',start,{origin})).statusCode,429);
  assert.equal((await call('POST',confirm,{origin},{proof})).statusCode,403);
  assert.equal((await call('POST',confirm,{origin,cookie:challenge},{proof:'w'.repeat(43)})).statusCode,403);
  const accepted=await call('POST',confirm,{origin,cookie:challenge},{proof});
  assert.equal(accepted.statusCode,204);
  assert.equal((await call('POST',confirm,{origin,cookie:challenge},{proof})).statusCode,403);
  const field=accepted.headers['set-cookie'].find(x=>x.startsWith('__Host-hibi_body='));
  assert.match(field,/Path=\/; Max-Age=2592000; Secure; HttpOnly; SameSite=Strict/);
  assert.doesNotMatch(field,/Domain=/i);
  const auth=field.split(';')[0];
  for(let i=0;i<2;++i){
    const res=await call('GET',body,{cookie:auth});
    assert.deepEqual(res.json(),{asOf:'test',heartRate:{bpm:88}});
    assert.match(res.headers['cache-control'],/no-store/);
  }
  const cross=await call('GET',body,{origin:'https://other.example',cookie:auth});
  assert.equal(cross.statusCode,403);
  assert.equal(cross.headers['access-control-allow-origin'],undefined);
  const options=await call('OPTIONS',confirm,{origin:'https://other.example','access-control-request-method':'POST'});
  assert.equal(options.headers['access-control-allow-origin'],undefined);
  assert.equal((await call('DELETE',session,{origin,cookie:auth})).statusCode,204);
  assert.equal((await call('GET',body,{cookie:auth})).statusCode,401);
  for(const credential of ['b'.repeat(32),'c'.repeat(32)]){
    const granted=await call('POST',session,{origin,authorization:'Bearer '+credential});
    assert.equal(granted.statusCode,204);
    now+=31*86400000;
    assert.equal((await call('GET',body,{cookie:cookie(granted)})).statusCode,401);
  }
  const expires=await call('POST',start,{origin});
  now+=301000;
  assert.equal((await call('POST',confirm,{origin,cookie:cookie(expires)},{proof})).statusCode,403);
  slow=true;
  assert.equal((await call('POST',start,{origin})).statusCode,503);
});

test('a browser stays authorized through collector rebuilds and logout remains durable',async t=>{
  const dir=await mkdtemp(join(tmpdir(),'hibi-session-test-'));
  t.after(()=>rm(dir,{recursive:true,force:true}));
  const filename=join(dir,'device-timeline.sqlite');
  const token='z'.repeat(40), origin='https://hibi.example';
  const keys={bodyReadDigest:createHash('sha256').update(token).digest()};
  const start=()=>{
    const db=new DatabaseSync(filename),app=Fastify();
    registerConsoleBody(app,{liveBodyNow:()=>({asOf:'test',mac:'not-in-scope'})},db,keys,
      async()=>{throw Error('not used')},origin);
    return {app,db,stop:async()=>{await app.close();db.close()}};
  };
  let service=start();
  const login=await service.app.inject({method:'POST',url:'/api/console/session',headers:{origin,authorization:'Bearer '+token}});
  assert.equal(login.statusCode,204);
  const cookie=login.headers['set-cookie'].split(';')[0];
  const opaque=cookie.slice(cookie.indexOf('=')+1);
  const row=service.db.prepare('SELECT * FROM private_console_body_sessions').get();
  assert.deepEqual(Object.keys(row),['session_sha256','expires_at']);
  assert.equal(row.session_sha256,createHash('sha256').update(opaque).digest('hex'));
  assert.ok(row.expires_at>Date.now() && !JSON.stringify(row).includes(opaque));
  await service.stop();
  service=start();
  const read=()=>service.app.inject({url:'/api/console/body',headers:{cookie}});
  assert.deepEqual((await read()).json(),{asOf:'test'});
  assert.equal((await service.app.inject({method:'DELETE',url:'/api/console/session',headers:{origin,cookie}})).statusCode,204);
  await service.stop();
  service=start();
  assert.equal((await read()).statusCode,401);
  await service.stop();
});
