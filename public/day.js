import {renderMap} from './day-map.js';
import {renderBody} from './day-body.js';

const $ = id => document.getElementById(id);
const pad = n => String(n).padStart(2,'0');
const urlParams = new URLSearchParams(location.search);
const calendarDate = (date, zone) => new Intl.DateTimeFormat('sv-SE',{timeZone:zone}).format(date);
let zone = 'Asia/Shanghai';
let today = () => calendarDate(new Date(),zone);
let date = /^\d{4}-\d\d-\d\d$/.test(urlParams.get('date') || '') ? urlParams.get('date') : today();
let hour = /^(?:[0-9]|1[0-9]|2[0-3])$/.test(urlParams.get('hour') ?? '') ? Number(urlParams.get('hour')) : null;
let dataset = null, personal = null, device = '', limit=15, request=0, privateFailed=false;
const el = (tag,cls,text) => { const n=document.createElement(tag); if(cls)n.className=cls; if(text!==undefined)n.textContent=text; return n; };
let clockZone, clockFormat;
function clock(date) {
  if (clockZone !== zone) {
    clockFormat = new Intl.DateTimeFormat('en-GB',{timeZone:zone,hour:'2-digit',minute:'2-digit'});
    clockZone = zone;
  }
  return clockFormat.format(date);
}
const hourAt = millis => Number(clock(millis).slice(0,2));
const isIdle = app => /(^|[.])(afk|idle|locked|lockscreen)$/.test(app);
const duration = seconds => seconds<60 ? Math.max(1,Math.round(seconds))+' 秒' : seconds<3600 ? Math.floor(seconds/60)+' 分钟' : Math.floor(seconds/3600)+' 小时 '+Math.floor(seconds%3600/60)+' 分';
const label = row => isIdle(row.appId) ? '设备空闲' : (row.appName || row.appId || '应用未提供');
const deviceName = id => dataset?.devices.find(d=>d.deviceId===id)?.deviceName || id;
const fmtHour = h => pad(h)+':00';
const sliceLabel = () => hour===null?'全天记录':fmtHour(hour)+' — '+fmtHour(hour+1);
const entryPage = document.body.dataset.entryPage;
function rememberDay() {
  try { localStorage.removeItem('hibi-return-live');localStorage.setItem('hibi-return-day', JSON.stringify({date,hour,savedAt:Date.now()})); } catch {}
}
function restoreDay() {
  try {
    const saved = JSON.parse(localStorage.getItem('hibi-return-day'));
    localStorage.removeItem('hibi-return-day');
    if (urlParams.has('date') || !saved || Date.now()-saved.savedAt > 15*60000) return;
    if (/^\d{4}-\d{2}-\d{2}$/.test(saved.date)) {
      date=saved.date;
      hour=Number.isInteger(saved.hour)&&saved.hour>=0&&saved.hour<24?saved.hour:null;
      reflectUrl();
    }
  } catch {}
}
function openFallback() {
  const paired = document.body.dataset.notion === 'configured';
  $('notion-pair').hidden = !paired;
  $('manual').open = !paired;
  $('auth').showModal();
}
function entryLink() {
  if (entryPage) {
    const link=el('a','private-entry','从 Notion 打开 ↗');
    link.href=entryPage;
    link.onclick=rememberDay;
    return link;
  }
  const link=el('button','private-entry','查看私人记录');
  link.type='button';link.onclick=openFallback;
  return link;
}
function reflectUrl(){
  const u=new URL(location.href);
  u.searchParams.set('date',date);
  if(hour===null)u.searchParams.delete('hour');else u.searchParams.set('hour',String(hour));
  history.replaceState(null,'',u.pathname+u.search);
}
function changeHour(h){
  hour=h;limit=15;reflectUrl();render();
}
function pickDate(d){
  if(!/^\d{4}-\d{2}-\d{2}$/.test(d)||d>today())return;
  date=d;limit=15;reflectUrl();load();
}
function dayOffset(delta){
  const stamp = new Date(date+'T12:00:00Z');
  stamp.setUTCDate(stamp.getUTCDate()+delta);
  pickDate(stamp.toISOString().slice(0,10));
}
function title(){
  const noon=new Date(date+'T12:00:00Z');
  if(!Number.isFinite(noon.getTime())) { date=today();return title(); }
  $('day-title').textContent=new Intl.DateTimeFormat('zh-CN',{month:'long',day:'numeric',timeZone:'UTC'}).format(noon);
  $('weekday').textContent=date.slice(0,4)+' 年　·　'+new Intl.DateTimeFormat('zh-CN',{weekday:'long',timeZone:'UTC'}).format(noon);
  $('pick-date').value=date;$('pick-date').max=today();$('next').disabled=date>=today();
  $('scope-title').textContent=sliceLabel();
}
function selectDevices(){
  const filter=$('filter');
  filter.replaceChildren(new Option('全部设备',''));
  for(const d of dataset?.devices||[])filter.add(new Option(d.deviceName,d.deviceId));
  if (![...filter.options].some(o=>o.value===device))device='';
  filter.value=device;
}
function bounds(){
  const start=Date.parse(dataset.from),end=Date.parse(dataset.to);
  const begin=hour===null?start:start+hour*3600000;
  return [begin,hour===null?end:Math.min(end,begin+3600000)];
}
function records(){
  const [from,to]=bounds();
  return dataset.activities.filter(row=>!device||row.deviceId===device).flatMap(row=>{
    const a=Date.parse(row.startedAt);
    if (row.kind==='point')return a>=from&&a<to?[{row,a,b:null}]:[];
    const b=Date.parse(row.endedAt);
    return a<to&&b>from?[{row,a:Math.max(from,a),b:Math.min(to,b)}]:[];
  }).sort((x,y)=>y.a-x.a||x.row.deviceId.localeCompare(y.row.deviceId));
}
function renderLanes(){
  const host=$('ruler');host.replaceChildren();
  const [base,end]=[Date.parse(dataset.from),Date.parse(dataset.to)];
  const span=end-base;
  const axis=el('div','axis');axis.append(el('div'));
  const scale=el('div','axis-scale');for(const h of [0,6,12,18,24])scale.append(el('span','',pad(h)+':00'));
  axis.append(scale);host.append(axis);
  const lanes=dataset.devices.filter(d=>!device||device===d.deviceId);
  const rowsByDevice=new Map();
  for(const row of dataset.activities){
    if(!rowsByDevice.has(row.deviceId))rowsByDevice.set(row.deviceId,[]);
    rowsByDevice.get(row.deviceId).push(row);
  }
  for(const item of lanes) {
    const lane=el('div','lane'), track=el('div','track');
    lane.append(el('span','lane-name',item.deviceName),track);
    const points=new Set();
    for(const r of rowsByDevice.get(item.deviceId)||[]){
      const left=(Date.parse(r.startedAt)-base)/span;
      if(r.kind==='point'){
        // Coalesce markers in the same displayed minute only. The list retains every sample.
        const bucket=Math.floor(left*1440);if(points.has(bucket))continue;points.add(bucket);
      }
      const bar=el('i',r.kind==='point'?'pin':isIdle(r.appId)?'bar idle':'bar');
      bar.style.left=Math.max(0,Math.min(99.9,left*100))+'%';
      if(r.kind==='interval')bar.style.width=Math.min((1-left)*100,(Date.parse(r.endedAt)-Date.parse(r.startedAt))/span*100)+'%';
      track.append(bar);
    }
    if(hour!==null){const focus=el('i','focus-hour');focus.style.left=(hour/24*100)+'%';track.append(focus);}
    track.onclick=event=>{const rect=track.getBoundingClientRect();changeHour(Math.max(0,Math.min(23,Math.floor(24*(event.clientX-rect.left)/rect.width))));};
    host.append(lane);
  }
  const selector=el('div','hour-buttons');
  for(let h=0;h<24;h++){
    const b=el('button','',pad(h));b.type='button';b.setAttribute('aria-label','查看 '+h+' 点的记录');b.setAttribute('aria-pressed',String(h===hour));b.onclick=()=>{changeHour(h);$('ruler').querySelector('[aria-pressed="true"]')?.focus({preventScroll:true});};selector.append(b);
  }
  host.append(selector);
  const active=lanes.filter(d=>rowsByDevice.get(d.deviceId)?.length).length;
  $('ruler-summary').textContent=active?active+' 台设备有记录':'这一天没有设备活动记录';
}
function renderList(){
  const rows=records(), box=$('entries');box.replaceChildren();
  const shown=rows.slice(0,limit);let prev=-1;
  for(const {row,a,b} of shown){
    const h=hourAt(a);if(h!==prev&&hour===null)box.append(el('div','hour-head',fmtHour(h)));prev=h;
    const line=el('div','record'), desc=el('div');
    const stamp=el('time','',clock(a));stamp.dateTime=new Date(a).toISOString();
    desc.append(el('strong','',label(row)),el('div','meta',deviceName(row.deviceId)+(row.windowTitle&&row.windowTitle!==label(row)?' · '+row.windowTitle:'')));
    line.append(stamp,desc,el('span','length',b===null?'单次记录':clock(b)+' 结束'));box.append(line);
  }
  if(!rows.length)box.append(el('p','empty',hour===null?'当天没有这台设备的记录。':'这一个小时没有设备记录。'));
  $('more').hidden=rows.length<=shown.length;
  const sums=new Map();for(const {row,a,b} of rows)if(b!==null&&!isIdle(row.appId))sums.set(label(row),(sums.get(label(row))||0)+(b-a)/1000);
  const ranks=$('rank-list');ranks.replaceChildren();
  for(const [name,sec] of [...sums].sort((a,b)=>b[1]-a[1]).slice(0,8)){const row=el('div','rank-row');row.append(el('span','',name),el('span','',duration(sec)));ranks.append(row);}
  if(!sums.size)ranks.textContent='这个时段没有可计算时长的应用记录。';
  $('export').disabled=!rows.length;
}
function privatePanels(){
  if(privateFailed){
    $('body-section').hidden=false;
    $('map-title').textContent='位置记录';
    document.querySelector('.jumps a[href="#body-section"]').hidden=false;
    for (const id of ['map','body']) { const retry=el('button','retry','重新读取');retry.onclick=load;$(id).replaceChildren(el('p','empty','暂时无法读取这一天的私人记录。'),retry); }
    return;
  }
  const args={from:dataset.from,timeZone:zone,hour};
  const hasLocation=!!personal?.locationAccess;
  $('body-section').hidden=!personal;
  document.querySelector('.jumps a[href="#body-section"]').hidden=!personal;
  $('map-title').textContent=personal?'位置记录':'位置与身体记录';
  $('signout').hidden=!personal;
  if (personal) renderBody($('body'),{...args,hours:personal.hours,authorized:true});
  else $('body').replaceChildren();
  if (hasLocation) renderMap($('map'),{...args,locations:personal.locations||[],locationAccess:true});
  else {
    renderMap($('map'),{...args,locations:[],locationAccess:false});
    $('map').replaceChildren(entryLink());
  }
}
function render(){
  title();if(!dataset)return;
  renderLanes();renderList();privatePanels();
  $('clear-hour').disabled=hour===null;
  $('mobile-hour').value=hour===null?'':String(hour);
  $('zone').textContent=zone==='Asia/Shanghai'?'北京时间 · UTC+8':zone;
}
async function call(path,options={}){
  const r=await fetch(path,{mode:'same-origin',redirect:'error',credentials:'same-origin',cache:'no-store',signal:AbortSignal.timeout(14000),...options});
  if(!r.ok)throw Object.assign(Error('HTTP '+r.status),{status:r.status});
  return r.status===204?null:r.json();
}
async function load(background=false){
  const quiet=background===true && dataset?.date===date;
  const seq=++request;
  if(!quiet){
  dataset=null;personal=null;privateFailed=false;
  title();$('status').hidden=true;$('export').disabled=true;$('clear-hour').disabled=true;$('more').hidden=true;
  for(const id of ['ruler','map','body','entries','rank-list'])$(id).replaceChildren();
  $('ruler-summary').textContent='读取当天记录…';
  }
  const q='?date='+encodeURIComponent(date);
  const [dayResult,privateResult]=await Promise.allSettled([call('/api/devices/day'+q),call('/api/console/day'+q)]);
  if(seq!==request)return;
  if(dayResult.status!=='fulfilled'){
    if(quiet)return;
    $('status').hidden=false;const retry=el('button','retry','重新读取');retry.onclick=load;
    $('status').replaceChildren(document.createTextNode('当天的时间线未能读取。 '),retry);$('ruler-summary').textContent='暂时无法连接';return;
  }
  dataset=dayResult.value;zone=dataset.timeZone||zone;
  personal=privateResult.status==='fulfilled'?privateResult.value:null;
  privateFailed=privateResult.status==='rejected'&&privateResult.reason.status!==401;
  selectDevices();render();
}
$('filter').onchange=e=>{device=e.target.value;limit=15;render();};
const mobileHour=$('mobile-hour');
for(let h=0;h<24;h++)mobileHour.add(new Option(fmtHour(h)+' – '+fmtHour(h+1),String(h)));
mobileHour.onchange=()=>changeHour(mobileHour.value===''?null:Number(mobileHour.value));
$('clear-hour').onclick=()=>changeHour(null);
$('prev').onclick=()=>dayOffset(-1);$('next').onclick=()=>dayOffset(1);$('today').onclick=()=>pickDate(today());$('pick-date').onchange=e=>pickDate(e.target.value);
$('more').onclick=()=>{limit+=25;renderList();};
$('export').onclick=()=>{
  const exported=records().map(({row,a,b})=>({...row,startedAt:new Date(a).toISOString(),endedAt:b===null?null:new Date(b).toISOString(),durationSeconds:b===null?null:(b-a)/1000}));
  const blob=new Blob([JSON.stringify({date,timeZone:zone,hour,activities:exported},null,2)],{type:'application/json'});
  const link=el('a');link.href=URL.createObjectURL(blob);link.download='hibi-'+date+(hour===null?'':'-'+pad(hour))+'.json';link.click();setTimeout(()=>URL.revokeObjectURL(link.href),1000);
};
$('key-form').onsubmit=async e=>{
  e.preventDefault();const token=$('key').value.trim();$('key').value='';
  try{await call('/api/console/session',{method:'POST',headers:{Authorization:'Bearer '+token}});$('auth-state').textContent='';$('auth').close();await load();}
  catch{$('auth-state').textContent='授权未成功，请检查只读凭据。';}
};
$('notion-pair').onclick=async()=>{
  try{rememberDay();
    const {notionUrl}=await call('/api/console/notion/start',{method:'POST'});
    const target=new URL(notionUrl);if(target.origin!=='https://app.notion.com'||target.username||target.password)throw Error();location.assign(target.href);
  }catch{$('auth-state').textContent='暂时无法打开 Notion，请重试。';}
};
$('signout').onclick=async()=>{
  try{await call('/api/console/session',{method:'DELETE'});await load();}
  catch{$('entry-error').hidden=false;$('entry-error').textContent='未能退出，请重试。';}
};
async function start(){
  const proof=window.hibiConfirmation;delete window.hibiConfirmation;
  if(proof){
    let confirmed=false;
    try{await call('/api/console/notion/confirm',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({proof})});confirmed=true;}
    catch{$('auth-state').textContent='这次授权未成功，请重新通过 Notion 授权。';}
    if(confirmed) restoreDay();
    else { $('entry-error').hidden=false;$('entry-error').textContent='链接已失效，请重新打开私人时间线。'; }
  }
  if(window.hibiViewReady){
    const accepted=await window.hibiViewReady;
    delete window.hibiViewReady;
    if(accepted){
      try {
        const returnLive=Number(localStorage.getItem('hibi-return-live'));
        localStorage.removeItem('hibi-return-live');
        if(returnLive && Date.now()-returnLive<15*60000 && !urlParams.has('date')){
          location.replace('/live#health-section');
          return;
        }
      } catch {}
      restoreDay();
    }
    else { $('entry-error').hidden=false;$('entry-error').textContent='链接未能打开，请从 Notion 重新进入。'; }
  }
  await load();
}
start();
setInterval(()=>{
  if(document.visibilityState==='visible' && date===today() && !$('auth').open && !document.activeElement?.closest('#map,#body,#ruler,#more,#filter,#ranking'))load(true);
}, 120000);
