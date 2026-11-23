import test from 'node:test';
import assert from 'node:assert/strict';
import Fastify from 'fastify';
import {DatabaseSync} from 'node:sqlite';
import {runMigrations} from '../dist/db/migrations.js';
import {Repository} from '../dist/db/repo.js';
import {registerRoutes} from '../dist/routes.js';
import {DeviceAuth} from '../dist/device-auth.js';

test('storage reports retained history without changing it, and still ingests device reports',async()=>{
 const db=new DatabaseSync(':memory:');runMigrations(db);const repo=new Repository(db,120);
 const device={token:'test-only-token',deviceId:'test-mac',deviceName:'Test Mac',platform:'macos'};
 const app=Fastify();registerRoutes(app,{repo,auth:new DeviceAuth([device]),labels:{name:x=>x,liveDescription:x=>x},rawLabels:{},retentionDays:0});
 let r=await app.inject({method:'GET',url:'/api/devices/storage'});assert.equal(r.json().retentionDays,null);assert.equal(r.json().activity.oldest,null);
 for(const date of ['2025-01-01T00:00:00Z','2026-11-15T00:00:00Z']){r=await app.inject({method:'POST',url:'/api/devices/report',headers:{authorization:'Bearer test-only-token'},payload:{appId:'com.test.app',occurredAt:date}});assert.equal(r.statusCode,202);}
 r=await app.inject({method:'GET',url:'/api/devices/storage'});assert.equal(r.json().activity.count,2);assert.match(r.json().activity.oldest,/2025-01-01/);
 r=await app.inject({method:'POST',url:'/api/devices/report',payload:{appId:'com.test.app'}});assert.equal(r.statusCode,401);
 r=await app.inject({method:'GET',url:'/api/devices/timeline-query?date=2025-01-01'});assert.equal(r.statusCode,200);assert.equal(r.json().activities.length,1);
 await app.close();db.close();
});
