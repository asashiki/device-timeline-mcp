import type { DatabaseSync } from 'node:sqlite';

const MIN = 60_000;
const day = 1440;
const iso = (minute: number) => new Date(minute*MIN).toISOString();
type Point = {hr?: number; steps?: number};
type Period = {start: number; end: number};
type Row = {type:string; value:number; recorded_at:string};
const quantile = (values:number[],q:number) => [...values].sort((a,b)=>a-b)[Math.floor((values.length-1)*q)]!;

function personalLimit(db: DatabaseSync,id: string,before: number,asOf: string):number|null {
  const records = db.prepare(`SELECT started_at,ended_at FROM band_sleep WHERE device_id=? AND ended_at<=?
    AND received_at<=? AND unixepoch(ended_at)-unixepoch(started_at) BETWEEN 10800 AND 54000
    ORDER BY ended_at DESC LIMIT 8`).all(id,iso(before),asOf) as unknown as {started_at:string;ended_at:string}[];
  const hr=db.prepare(`SELECT value FROM band_samples WHERE device_id=? AND type='heart_rate'
    AND recorded_at>=? AND recorded_at<? AND received_at<=? AND value BETWEEN 1 AND 254`);
  for(const record of records){
    const end=Date.parse(record.ended_at)/MIN;
    const sleep=hr.all(id,record.started_at,record.ended_at,asOf).map(x=>Number(x.value));
    if(sleep.length<(end-Date.parse(record.started_at)/MIN)*0.8) continue;
    const other=db.prepare(`SELECT 1 FROM band_sleep WHERE device_id=? AND received_at<=? AND started_at<? AND ended_at>?`).get(id,asOf,iso(end+390),iso(end+30));
    if(other) continue;
    const daytime=hr.all(id,iso(end+30),iso(end+390),asOf).map(x=>Number(x.value));
    if(daytime.length<12 || daytime.length>180) continue;
    return Math.min(quantile(sleep,0.9)+10,quantile(daytime,0.5)-8);
  }
  return null;
}

// Retrospective only. A quiet gap without measured pulse and step coverage is never sleep.
export function nightCandidates(points:Map<number,Point>, first:number, last:number, limit:number):Period[] {
  const values=(start:number,end:number)=>Array.from({length:end-start},(_,i)=>points.get(start+i));
  const quiet=(start:number,end:number)=>values(start,end).every(p=>p?.steps!==undefined && p.steps===0);
  const awake=(start:number,end:number)=>{
    const span=values(start,end);
    if(span.some(p=>p?.steps===undefined)) return false;
    const steps=span.reduce((v,p)=>v+(p?.steps??0),0);
    const pulses=span.flatMap(p=>p?.hr===undefined?[]:[p.hr]);
    return steps>=12 || (pulses.length>=1 && pulses.length<=4) || (pulses.length>=5 && quantile(pulses,.5)>limit);
  };
  const periods:Period[]=[];
  for(let start=Math.ceil(first/5)*5;start+10<=last;start+=5){
    const span=values(start,start+10);
    if(span.some(p=>p?.steps===undefined) || span.reduce((v,p)=>v+(p?.steps??0),0)>2)continue;
    const hr=span.flatMap(p=>p?.hr===undefined?[]:[p.hr]);
    if(hr.length<8 || quantile(hr,.5)>limit)continue;
    const prev=periods.at(-1);
    if(prev && (start<=prev.end || (start-prev.end<=10 && quiet(prev.end,start)))) prev.end=start+10;
    else periods.push({start,end:start+10});
  }
  return periods.filter(p=>p.end-p.start>=90 && p.end-p.start<=900 &&
    p.start-20>=first && p.end+20<=last && awake(p.start-20,p.start) && awake(p.end,p.end+20));
}

export function rebuildSleepEstimates(db:DatabaseSync,id:string,now=new Date()){
  const asOf=now.toISOString(),last=Math.floor(now.getTime()/MIN)+1;
  const first=Math.floor((last-5*day)/day)*day;
  const rows=db.prepare(`SELECT type,value,recorded_at FROM band_samples WHERE device_id=?
    AND recorded_at>=? AND recorded_at<=? AND received_at<=? ORDER BY recorded_at`).all(id,iso(first),asOf,asOf) as unknown as Row[];
  const points=new Map<number,Point>();
  for(const row of rows){
    const m=Date.parse(row.recorded_at)/MIN;
    if(!Number.isInteger(m))continue;
    const point=points.get(m)??{};
    if(row.type==='steps') point.steps=row.value;
    else if(row.type==='heart_rate' && row.value>0 && row.value<255) point.hr=row.value;
    points.set(m,point);
  }
  const keep=new Set<string>();
  db.exec('SAVEPOINT reconstruct_sleep');
  try{
    // Group by wake date; include enough preceding samples for cross-midnight sleep.
    const upsert=db.prepare(`INSERT INTO private_sleep_estimates(device_id,started_at,ended_at,received_at)
      VALUES(?,?,?,?) ON CONFLICT(device_id,started_at) DO UPDATE SET ended_at=excluded.ended_at,received_at=excluded.received_at
      WHERE private_sleep_estimates.ended_at!=excluded.ended_at`);
    const overlaps=db.prepare(`SELECT 1 FROM private_native_sleep_history WHERE device_id=? AND started_at<?
      AND ended_at>? AND received_at<=? AND (superseded_at IS NULL OR superseded_at>?) LIMIT 1`);
    const wakeFloor=first+day;
    for(let window=wakeFloor;window<last;window+=day){
      const limit=personalLimit(db,id,window-day,asOf);
      if(limit===null)continue;
      for(const period of nightCandidates(points,window-day,Math.min(window+day+20,last),limit)){
        if(period.end<window || period.end>=window+day)continue;
        const start=iso(period.start),end=iso(period.end);
        if(overlaps.get(id,end,start,asOf,asOf))continue;
        upsert.run(id,start,end,asOf);
        keep.add(start);
      }
    }
    const previous=db.prepare('SELECT started_at FROM private_sleep_estimates WHERE device_id=? AND ended_at>=?').all(id,iso(wakeFloor));
    const remove=db.prepare('DELETE FROM private_sleep_estimates WHERE device_id=? AND started_at=?');
    for(const row of previous)if(!keep.has(String(row.started_at)))remove.run(id,String(row.started_at));
    db.exec('RELEASE reconstruct_sleep');
  }catch(error){db.exec('ROLLBACK TO reconstruct_sleep; RELEASE reconstruct_sleep');throw error;}
}

export function startEstimateRecovery(db:DatabaseSync,id:string){
  const run=()=>{try{rebuildSleepEstimates(db,id)}catch(error){console.error('[sleep recovery]',error)}};
  run();setInterval(run,15*MIN).unref();
}
