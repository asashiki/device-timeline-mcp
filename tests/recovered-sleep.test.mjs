import assert from 'node:assert/strict';
import test from 'node:test';
import {DatabaseSync} from 'node:sqlite';
import {runMigrations} from '../dist/db/migrations.js';
import {nightCandidates,rebuildSleepEstimates} from '../dist/db/recovered-sleep.js';
import {readBodyRange} from '../dist/db/body-range.js';
import {readLiveHealth} from '../dist/db/live-health.js';

const MIN=60000,iso=m=>new Date(m*MIN).toISOString(),id='android-phone';
function samplePoints(first,end) {
  const points=new Map();
  for(let m=first-30;m<end+30;m++) {
    const inside=m>=first && m<end;
    points.set(m,{steps:inside?0:5,hr:inside?57:(m%10===0?82:undefined)});
  }
  return points;
}
test('history recovery requires measured boundaries and distinguishes a data gap from sleep',()=>{
  const rows=samplePoints(0,150);
  assert.deepEqual(nightCandidates(rows,-30,180,65),[{start:0,end:150}]);
  assert.deepEqual(nightCandidates(rows,-30,150,65),[]);
  for(let i=64;i<87;i++)rows.delete(i);
  assert.deepEqual(nightCandidates(rows,-30,180,65),[]);
});
test('recovered night is labeled, repeatable, and superseded by the original band sleep',t=>{
  const db=new DatabaseSync(':memory:');t.after(()=>db.close());runMigrations(db);
  const start=Date.parse('2026-02-18T22:00:00Z')/MIN,end=start+240,ref=start-4*1440;
  const insert=db.prepare('INSERT INTO band_samples VALUES(?,?,?,?,?)');
  const closed=db.prepare('INSERT INTO band_sleep VALUES(?,?,?,?,?)');
  const now=new Date(iso(end+40));
  closed.run(id,iso(ref),iso(ref+240),null,iso(ref+250));
  for(let m=ref;m<ref+240;m++)insert.run(id,'heart_rate',55+(m%5),iso(m),iso(m+1));
  for(let m=ref+270;m<ref+630;m+=10)insert.run(id,'heart_rate',83,iso(m),iso(m+1));
  for(const [m,p] of samplePoints(start,end)){
    insert.run(id,'steps',p.steps,iso(m),iso(m+1));if(p.hr!==undefined)insert.run(id,'heart_rate',p.hr,iso(m),iso(m+1));
  }
  rebuildSleepEstimates(db,id,now);rebuildSleepEstimates(db,id,now);
  const range=()=>readBodyRange(db,id,iso(start),iso(end+30),new Date(iso(end+45))).sleep;
  assert.equal(range().sessions.length,1);assert.equal(range().sessions[0].source,'band_minute_estimate');
  assert.equal(range().asleepMinutes,null);assert.equal(range().recordedSessionOverlapMinutes,240);
  const estimated=readLiveHealth(db,id,now).lastCompletedSleep;
  assert.equal(estimated.source,'band_minute_estimate');assert.equal(estimated.asleepMinutes,null);
  closed.run(id,iso(start+1),iso(end-2),222,iso(end+41));
  assert.equal(range().sessions.length,1);assert.equal(range().sessions[0].source,'hibi_band_direct');
  rebuildSleepEstimates(db,id,new Date(iso(end+45)));
  assert.equal(db.prepare('SELECT COUNT(*) n FROM private_sleep_estimates').get().n,0);
});
