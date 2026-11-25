import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import type { DatabaseSync } from 'node:sqlite';
import type { DeviceAuth } from './device-auth.js';
import { createHash, timingSafeEqual } from 'node:crypto';
import { z } from 'zod';
import { LIVE_HEALTH_DEVICE_ID } from './db/live-health.js';

const none=z.object({}).strict(), id=z.number().int().min(1).max(255), hour=z.number().int().min(0).max(23), minute=z.number().int().min(0).max(59);
const title=z.string().trim().min(1).max(64), text=z.string().trim().min(1).max(240), enabled=z.boolean();
const alarm=z.object({hour,minute,repeatMask:z.number().int().min(0).max(127).optional(),enabled:enabled.optional()}).strict();
const reminder=z.object({at:z.string().datetime({offset:true}),title}).strict();
const actions:Record<string,z.ZodTypeAny> = Object.fromEntries([
 'battery','device-info','alarms','reminders','world-clocks','sleep-schedule','wear-state','workout-types','watchfaces','weather-locations',
 'vibration-patterns','screen-items','widgets','notification-screen','camera-status','heart-settings','oxygen-settings','stress-settings','find-stop','sync-clock'
].map(name=>[name,none]));
Object.assign(actions, {
 'buzz':z.object({seconds:z.number().int().min(1).max(6).optional()}).strict(),
 'notify':z.object({title:title.optional(),text}).strict(),
 'alarm-create':alarm,'alarm-edit':alarm.extend({id}),'alarm-delete':z.object({id}).strict(),
 'reminder-create':reminder,'reminder-edit':reminder.extend({id}),'reminder-delete':z.object({id}).strict(),
 'sleep-schedule-set':z.object({enabled,hour,minute,endHour:hour,endMinute:minute}).strict(),
 'camera-remote-set':z.object({enabled}).strict(),'notification-screen-set':z.object({enabled}).strict(),
 'language-set':z.object({code:z.string().regex(/^[a-z]{2}_[a-z]{2}$/)}).strict()
});
const envelope=z.object({action:z.string().max(40),args:z.record(z.unknown()).default({}),
  requestId:z.string().uuid(),deliverAt:z.string().datetime({offset:true}).optional()}).strict();
type Row={id:string;action:string;args:string;due:number;expires:number;claimed:number|null;status:string;detail:string|null};
const pause=(ms:number)=>new Promise<void>(resolve=>setTimeout(resolve,ms));
export function registerWrist(app:FastifyInstance,db:DatabaseSync,auth:DeviceAuth,verifier?:Buffer){
 db.exec(`CREATE TABLE IF NOT EXISTS private_wrist_commands (
  id TEXT PRIMARY KEY, action TEXT NOT NULL,args TEXT NOT NULL,due INTEGER NOT NULL,expires INTEGER NOT NULL,
  claimed INTEGER,status TEXT NOT NULL DEFAULT 'queued',detail TEXT); CREATE INDEX IF NOT EXISTS wrist_due ON private_wrist_commands(status,due);`);
 let lastPhone:{at:number;connected:boolean;note:string;since:number;battery?:number;batteryAt?:number;powerMode?:string}|null=null;
 let wake:(()=>void)|undefined; let pollGeneration=0;
 const opts={logLevel:'silent' as const,bodyLimit:4096};
 function agent(req:FastifyRequest,reply:FastifyReply){
   reply.header('Cache-Control','private, no-store');
   const key=/^Bearer ([A-Za-z0-9_-]{32,180})$/.exec(req.headers.authorization??'')?.[1];
   if(key && verifier?.length===32 && timingSafeEqual(createHash('sha256').update(key).digest(),verifier))return true;
   reply.code(401).send({error:'Wrist control credential required'});return false;
 }
 function phone(req:FastifyRequest,reply:FastifyReply){
   reply.header('Cache-Control','private, no-store');
   const device=auth.resolve(req.headers.authorization);
   if(device?.platform==='android' && device.deviceId===LIVE_HEALTH_DEVICE_ID)return true;
   reply.code(401).send({error:'Paired phone credential required'});return false;
 }
 function clean(now:number){
   db.prepare("UPDATE private_wrist_commands SET status='expired' WHERE status='queued' AND expires<?").run(now);
   db.prepare("UPDATE private_wrist_commands SET status='unconfirmed' WHERE status='claimed' AND claimed<?").run(now-60000);
   db.prepare('DELETE FROM private_wrist_commands WHERE expires<?').run(now-7*86400000);
 }
 const read=(id:string)=>db.prepare('SELECT * FROM private_wrist_commands WHERE id=?').get(id) as Row|undefined;
 const view=(r:Row)=>({id:r.id,action:r.action,status:r.status,detail:r.detail,deliverAt:new Date(r.due).toISOString(),expiresAt:new Date(r.expires).toISOString()});
 app.get('/api/agent/wrist',opts,async(req,reply)=>{
   if(!agent(req,reply))return;
   const now=Date.now();clean(now);
   const battery = db.prepare('SELECT pct,mode,measured_at,received_at FROM wrist_power_samples WHERE device_id=? ORDER BY measured_at DESC LIMIT 1').get(LIVE_HEALTH_DEVICE_ID);
   const phoneOnline=!!lastPhone && now-lastPhone.at<65000;
   return {actions:Object.keys(actions),phoneOnline,link:lastPhone && (phoneOnline ? lastPhone : {...lastPhone,connected:false,since:0,note:'手机离线'}),
     wristBattery:battery ? {percent:battery.pct,powerMode:battery.mode,measuredAt:new Date(Number(battery.measured_at)).toISOString(),receivedAt:new Date(Number(battery.received_at)).toISOString()} : null};
 });
 app.post('/api/agent/wrist',opts,async(req,reply)=>{
   if(!agent(req,reply))return;
   const input=envelope.safeParse(req.body);if(!input.success)return reply.code(400).send({error:'Invalid command envelope'});
   const x=input.data, spec=Object.hasOwn(actions,x.action)?actions[x.action]:undefined;
   const payload=spec?.safeParse(x.args);if(!payload?.success)return reply.code(400).send({error:'Unsupported action or arguments'});
   const now=Date.now();clean(now);
   const existing=read(x.requestId);
   if(existing){
     if(existing.action!==x.action || existing.args!==JSON.stringify(payload.data) ||
        (x.deliverAt && existing.due!==Date.parse(x.deliverAt)))return reply.code(409).send({error:'Request ID used for a different command'});
     return view(existing);
   }
   const due=x.deliverAt?Date.parse(x.deliverAt):now;
   if(due<now-30000 || due>now+30*86400000)return reply.code(400).send({error:'Schedule up to 30 days ahead'});
   const count=db.prepare("SELECT count(*) AS n FROM private_wrist_commands WHERE status IN ('queued','claimed')").get();
   if(Number(count?.n)>=100)return reply.code(429).send({error:'Wrist queue full'});
   db.prepare('INSERT INTO private_wrist_commands(id,action,args,due,expires) VALUES(?,?,?,?,?)').run(x.requestId,x.action,JSON.stringify(payload.data),due,due+(x.deliverAt?300000:90000));
   wake?.();return reply.code(202).send(view(read(x.requestId)!));
 });
 app.get<{Params:{id:string}}>('/api/agent/wrist/:id',opts,async(req,reply)=>{
    if(!agent(req,reply))return;clean(Date.now());const r=read(req.params.id);
    return r?view(r):reply.code(404).send({error:'Unknown command'});
 });
 app.delete<{Params:{id:string}}>('/api/agent/wrist/:id',opts,async(req,reply)=>{
    if(!agent(req,reply))return;const r=read(req.params.id);if(!r)return reply.code(404).send({error:'Unknown command'});
    if(r.status!=='queued')return reply.code(409).send({error:'Already delivered or finished'});
    db.prepare("UPDATE private_wrist_commands SET status='cancelled' WHERE id=?").run(r.id);return view(read(r.id)!);
 });
 const state=z.object({connected:z.boolean(),note:z.string().max(100),since:z.number().int().nonnegative(),battery:z.number().int().min(0).max(100).optional(),batteryAt:z.number().int().positive().optional(),powerMode:z.enum(["charging","discharging","full","unknown"]).optional(),version:z.literal(1)}).strict();
 app.post('/api/devices/wrist/next',opts,async(req,reply)=>{
    if(!phone(req,reply))return;
    const parsed=state.safeParse(req.body);if(!parsed.success)return reply.code(400).send({error:'Invalid link report'});
    const at = Date.now();
    const report = parsed.data;
    if (report.batteryAt !== undefined) {
      if (report.battery === undefined || report.batteryAt > at + 5000 || report.batteryAt < at - 30 * 86400000)
        return reply.code(400).send({error:'Invalid power measurement time'});
      db.prepare(`INSERT OR IGNORE INTO wrist_power_samples(device_id,measured_at,pct,mode,received_at)
        VALUES(?,?,?,?,?)`).run(LIVE_HEALTH_DEVICE_ID, report.batteryAt, report.battery, report.powerMode ?? 'unknown', at);
    }
    lastPhone = {at, ...report};
    clean(at);
    const generation = ++pollGeneration;
    wake?.();
    if(!parsed.data.connected){await pause(12000);return {command:null};}
    for(let i=0;i<5 && !req.raw.socket.destroyed && generation===pollGeneration;i++){
      const now=Date.now();
      const next=db.prepare("SELECT * FROM private_wrist_commands WHERE status='queued' AND due<=? AND expires>=? ORDER BY due LIMIT 1").get(now,now) as Row|undefined;
      if(next && db.prepare("UPDATE private_wrist_commands SET status='claimed',claimed=? WHERE id=? AND status='queued'").run(now,next.id).changes){
        return {command:{id:next.id,action:next.action,args:JSON.parse(next.args),expiresAt:next.expires}};
      }
      await new Promise<void>(resolve=>{
        const finish=()=>{clearTimeout(timer);if(wake===finish)wake=undefined;resolve()};
        const timer=setTimeout(finish,4500);wake?.();wake=finish;
      });
    }
    return {command:null};
 });
 const receipt=z.object({id:z.string().uuid(),status:z.enum(['sent','confirmed','unconfirmed','failed','offline','rejected','expired']),detail:z.string().max(1800)}).strict();
 app.post('/api/devices/wrist/receipt',opts,async(req,reply)=>{
    if(!phone(req,reply))return;const parsed=receipt.safeParse(req.body);if(!parsed.success)return reply.code(400).send({error:'Invalid receipt'});
    const r=read(parsed.data.id);if(!r || r.claimed===null)return reply.code(404).send({error:'Not claimed'});
    if(r.status==='claimed'||r.status==='unconfirmed')db.prepare('UPDATE private_wrist_commands SET status=?,detail=? WHERE id=?').run(parsed.data.status,parsed.data.detail,r.id);
    return {ok:true};
 });
}
