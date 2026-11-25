// A local Web Mercator plot. Street tiles are requested only by an explicit click.
const mapStates = new WeakMap();
const worldMeters = 2 * Math.PI * 6378137;
const tileSize = 256;
const degrees = Math.PI / 180;

function element(tag, className, text) {
  const item = document.createElement(tag);
  if (className) item.className = className;
  if (text !== undefined) item.textContent = text;
  return item;
}
function button(text, label, click) {
  const item = element('button', 'hm-button', text);
  item.type = 'button';
  item.setAttribute('aria-label', label);
  item.addEventListener('click', click);
  return item;
}
function installStyles() {
  if (document.getElementById('hibi-day-map-styles')) return;
  const sheet = element('style');
  sheet.id = 'hibi-day-map-styles';
  sheet.textContent = `
    .hibi-map{--map-ink:#244d43;--map-line:#d8e1da;font:inherit;color:#21362f;min-width:0}
    .hibi-map .hm-head,.hibi-map .hm-foot,.hibi-map .hm-actions{display:flex;align-items:center;gap:8px}
    .hibi-map .hm-head,.hibi-map .hm-foot{justify-content:space-between;flex-wrap:wrap}
    .hibi-map .hm-head{padding:0 0 10px}.hibi-map .hm-head strong{font-weight:600;font-size:16px}
    .hibi-map .hm-button,.hibi-map select{font:inherit;font-size:13px;min-height:34px;border:1px solid var(--map-line);background:#fff;color:var(--map-ink);cursor:pointer;border-radius:7px;padding:4px 10px}
    .hibi-map .hm-button:focus-visible,.hibi-map select:focus-visible{outline:2px solid #24715c;outline-offset:2px}
    .hibi-map .hm-button:disabled{opacity:.45;cursor:default}
    .hibi-map .hm-stage{position:relative;isolation:isolate;overflow:hidden;height:clamp(182px,22vw,232px);border-radius:9px;background-color:#eef3ed;background-image:linear-gradient(#cbd8cf66 1px,transparent 1px),linear-gradient(90deg,#cbd8cf66 1px,transparent 1px);background-size:28px 28px;border:1px solid var(--map-line)}
    .hibi-map .hm-tiles,.hibi-map .hm-overlay{position:absolute;inset:0;pointer-events:none}
    .hibi-map .hm-tiles img{position:absolute;max-width:none;width:256px;height:256px;border:0}
    .hibi-map .hm-caption,.hibi-map .hm-scale,.hibi-map .hm-attrib,.hibi-map .hm-north{position:absolute;background:#fffffff2;color:#32483e;font-size:12px;line-height:1.4;padding:2px 6px;border-radius:4px;z-index:2}
    .hibi-map .hm-caption{top:8px;left:8px}.hibi-map .hm-scale{left:8px;bottom:8px;text-align:center}
    .hibi-map .hm-scale::after{display:block;content:"";border:solid currentColor;border-width:0 1px 2px;height:3px}
    .hibi-map .hm-attrib{bottom:5px;right:5px}.hibi-map .hm-north{right:8px;top:8px}
    .hibi-map .hm-attrib a{color:inherit;text-decoration:underline}
    .hibi-map .hm-foot{padding-top:9px}.hibi-map .hm-actions{flex-wrap:wrap}
    .hibi-map .hm-detail,.hibi-map .hm-muted{font-size:14px;line-height:1.5;color:#495e53;font-variant-numeric:tabular-nums}
    .hibi-map .hm-empty{margin:0;display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap;padding:12px 0;color:#52645b;font-size:14px}
    .hibi-map select{max-width:100%;font-variant-numeric:tabular-nums}
    .hibi-map .hm-foot [hidden],.hibi-map [hidden]{display:none}
  `;
  document.head.append(sheet);
}
function distanceLabel(meters) {
  if (meters < 1000) return Math.round(meters) + ' 米';
  return new Intl.NumberFormat('zh-CN', {maximumFractionDigits:1}).format(meters / 1000) + ' 公里';
}
function projected(location, longitudeOrigin) {
  const lat = Math.max(-85.0511, Math.min(85.0511, location.latitude)) * degrees;
  // Express longitudes on the same side of the date line as the latest fix.
  const lon = longitudeOrigin + (((location.longitude - longitudeOrigin + 540) % 360) - 180);
  return {
    x:(lon + 180) / 360,
    y:(1 - Math.asinh(Math.tan(lat)) / Math.PI) / 2,
    radius:location.accuracyMeters / (worldMeters * Math.cos(lat))
  };
}
function frame(points, width, height) {
  let left=Infinity, top=Infinity, right=-Infinity, bottom=-Infinity;
  for (const p of points) {
    left=Math.min(left,p.x-p.radius); right=Math.max(right,p.x+p.radius);
    top=Math.min(top,p.y-p.radius); bottom=Math.max(bottom,p.y+p.radius);
  }
  const scale = Math.min((width-50)/Math.max(right-left,1e-10), (height-64)/Math.max(bottom-top,1e-10));
  return { x:(left+right)/2, y:(top+bottom)/2,
    zoom:Math.max(0, Math.min(17, Math.floor(Math.log2(scale/tileSize)))) };
}

export function renderMap(host, {locations=[],locationAccess=false,hour=null,from,timeZone='Asia/Shanghai',onUnlock}={}) {
  const old=mapStates.get(host);
  old?.resize?.disconnect();
  host.replaceChildren();
  installStyles();
  const root=element('div','hibi-map');
  host.append(root);
  const hourFormat=new Intl.DateTimeFormat('en-GB',{timeZone,hour:'2-digit',hourCycle:'h23'});
  const clock=new Intl.DateTimeFormat('zh-CN',{timeZone,hour:'2-digit',minute:'2-digit',hourCycle:'h23'});
  const dateFormat=new Intl.DateTimeFormat('en-CA',{timeZone,year:'numeric',month:'2-digit',day:'2-digit'});
  const day=Number.isFinite(Date.parse(from)) ? dateFormat.format(new Date(from)) : null;
  const samples=(locationAccess && Array.isArray(locations) ? locations : []).filter(p=>{
    const t=Date.parse(p?.sampledAt);
    return Number.isFinite(t) && Number.isFinite(p.latitude) && Math.abs(p.latitude)<=90
      && Number.isFinite(p.longitude) && Math.abs(p.longitude)<=180 && Number.isFinite(p.accuracyMeters)
      && p.accuracyMeters>=0 && (!day || dateFormat.format(t)===day)
      && (hour==null || Number(hourFormat.format(t))===hour);
  }).sort((a,b)=>Date.parse(a.sampledAt)-Date.parse(b.sampledAt));
  if (!samples.length) {
    mapStates.delete(host);
    const row=element('div','hm-empty');
    const empty = !locationAccess ? '解锁后可查看手机位置' : hour==null ? '当天没有定位记录' : '这个小时没有定位记录';
    row.append(element('span',null,empty));
    if (!locationAccess && typeof onUnlock==='function') row.append(button('解锁位置','解锁位置',onUnlock));
    root.append(row);
    return;
  }
  const sameDay=old?.day===day;
  const state={day,tiles:sameDay && old.tiles,selectedAt:(sameDay && old.hour===hour ? old.selectedAt : null),hour,resize:null};
  mapStates.set(host,state);
  let index=samples.findIndex(p=>p.sampledAt===state.selectedAt);
  if(index<0) index=samples.length-1;
  const points=samples.map(s=>projected(s,samples[samples.length-1].longitude));
  let focus=false, zoomDelta=0;
  const head=element('div','hm-head');
  head.append(element('strong',null,'手机位置'));
  const tilesButton=button('','',()=>{state.tiles=!state.tiles;draw();});
  head.append(tilesButton);
  const stage=element('div','hm-stage');
  const tiles=element('div','hm-tiles');
  const overlay=element('canvas','hm-overlay');
  overlay.setAttribute('aria-hidden','true');
  const caption=element('span','hm-caption');
  const north=element('span','hm-north','↑ 北');
  const scale=element('span','hm-scale');
  const attrib=element('span','hm-attrib');
  const credit=element('a',null,'© OpenStreetMap 贡献者');
  credit.href='https://www.openstreetmap.org/copyright'; credit.target='_blank'; credit.rel='noopener noreferrer';
  attrib.append(credit);
  stage.append(tiles,overlay,caption,north,scale,attrib);
  const foot=element('div','hm-foot');
  const detail=element('span','hm-detail');
  const actions=element('div','hm-actions');
  const picker=element('select');
  picker.setAttribute('aria-label','选择定位采样时间');
  for (const [i,s] of samples.entries()) {
    const opt=element('option',null,clock.format(new Date(s.sampledAt)));
    opt.value=String(i); picker.append(opt);
  }
  picker.value=String(index);
  picker.addEventListener('change',()=>{index=Number(picker.value);state.selectedAt=samples[index].sampledAt;focus=true;zoomDelta=0;draw();});
  const zoomOut=button('−','缩小地图',()=>{zoomDelta--;draw();});
  const zoomIn=button('＋','放大地图',()=>{zoomDelta++;draw();});
  const reset=button('全览','显示所有采样点',()=>{focus=false;zoomDelta=0;draw();});
  actions.append(picker,zoomOut,zoomIn,reset);
  foot.append(detail,actions);
  root.append(head,stage,foot);
  let drawID=0;
  function draw() {
    if(!root.isConnected) return;
    const width=stage.clientWidth,height=stage.clientHeight;
    if(width<60 || height<70) return;
    const current=++drawID, p=points[index], sample=samples[index];
    state.selectedAt=sample.sampledAt;
    const fit=frame(focus?[p]:points,width,height);
    const zoom=Math.max(0,Math.min(19,fit.zoom+zoomDelta)), world=tileSize*Math.pow(2,zoom);
    const x0=fit.x*world-width/2, y0=fit.y*world-height/2;
    zoomOut.disabled=zoom===0; zoomIn.disabled=zoom===19;
    reset.hidden=!focus && zoomDelta===0; picker.hidden=samples.length<2;
    detail.textContent=clock.format(new Date(sample.sampledAt))+' 采样 · 误差约 '+distanceLabel(sample.accuracyMeters);
    caption.textContent=samples.length+' 次定位';
    tilesButton.textContent=state.tiles ? '收起街道底图' : '显示街道底图';
    tilesButton.setAttribute('aria-label',tilesButton.textContent);
    tilesButton.title='街道底图由 OpenStreetMap 提供';
    tilesButton.setAttribute('aria-pressed',String(!!state.tiles));
    attrib.hidden=!state.tiles;
    tiles.replaceChildren();
    if(state.tiles) {
      const count=Math.pow(2,zoom);
      for(let y=Math.max(0,Math.floor(y0/tileSize));y<=Math.min(count-1,Math.floor((y0+height-1)/tileSize));y++) {
        for(let x=Math.floor(x0/tileSize);x<=Math.floor((x0+width-1)/tileSize);x++) {
          const img=element('img'); img.alt=''; img.draggable=false;
          img.referrerPolicy='origin';
          img.style.left=(x*tileSize-x0)+'px'; img.style.top=(y*tileSize-y0)+'px';
          img.addEventListener('error',()=>{
            if(current!==drawID) return;
            img.hidden=true; caption.textContent='部分街道底图暂未加载';
          });
          img.src='https://tile.openstreetmap.org/'+zoom+'/'+(((x%count)+count)%count)+'/'+y+'.png';
          tiles.append(img);
        }
      }
    }
    const dpr=Math.min(2,window.devicePixelRatio||1);
    overlay.width=Math.round(width*dpr);overlay.height=Math.round(height*dpr);
    overlay.style.width=width+'px';overlay.style.height=height+'px';
    const ctx=overlay.getContext('2d');
    if(!ctx) return;
    ctx.scale(dpr,dpr);
    function circle(point, radius, fill, outline, lineWidth) {
      ctx.beginPath();ctx.arc(point.x*world-x0,point.y*world-y0,radius,0,2*Math.PI);
      ctx.fillStyle=fill;ctx.fill();
      if(outline) {ctx.strokeStyle=outline;ctx.lineWidth=lineWidth;ctx.stroke();}
    }
    points.forEach((point,i)=>{
      if(i!==index) circle(point,3,'#577c6a88',null,0);
    });
    circle(p, Math.max(1,p.radius*world),'#29836329','#39846a',1.4);
    circle(p,5,'#185c47','#fff',2);
    const metersPerPx=worldMeters/(world*Math.cosh(Math.PI*(1-2*fit.y)));
    const maxMeters=metersPerPx*Math.min(82,width*.22);
    const order=Math.pow(10,Math.floor(Math.log10(maxMeters)));
    const mark=(maxMeters>=5*order?5:maxMeters>=2*order?2:1)*order;
    scale.style.width=(mark/metersPerPx)+'px';scale.textContent=distanceLabel(mark);
  }
  draw();
  state.resize=new ResizeObserver(draw);
  state.resize.observe(stage);
}
