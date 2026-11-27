const sheet = `
.day-body-record {
  --body-ink: #1e3029;
  --body-dim: #51615a;
  --body-rule: #d9dfd8;
  --body-accent: #336653;
  color: var(--body-ink);
  font-variant-numeric: tabular-nums;
}
.day-body-record [hidden] { display: none; }
.day-body-record .body-main {
  display: grid;
  grid-template-columns: minmax(0,3fr) minmax(110px,2fr);
  align-items: end;
  gap: 12px 24px;
}
.day-body-record .body-label { color: var(--body-dim); font-size: .93rem; }
.day-body-record .body-digits {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  column-gap: .32em;
  margin: .12em 0;
  font-weight: 610;
  line-height: 1.1;
}
.day-body-record .body-digits strong { color: var(--body-ink); font-size: clamp(1.85rem,3vw,2.5rem); letter-spacing: -.045em; }
.day-body-record .body-digits span { font-size: 1rem; font-weight: 500; }
.day-body-record .body-detail, .day-body-record .body-ticks { color: var(--body-dim); font-size: .85rem; line-height: 1.5; }
.day-body-record .body-bars {
  display: flex; height: 43px; align-items: flex-end; gap: 2px;
  border-bottom: 1px solid var(--body-rule);
}
.day-body-record .body-bar { flex: 1; min-width: 0; background: var(--body-accent); border-radius: 1px 1px 0 0; }
.day-body-record .body-hour-focus .body-bar:not([data-selected]) { background: #a8b8ad; }
.day-body-record .body-ticks { display: flex; justify-content: space-between; margin-top: 4px; }
.day-body-record .body-stats {
  display: grid; grid-template-columns: repeat(auto-fit,minmax(min(100%,7.8rem),1fr));
  gap: 16px 12px; padding: 16px 0 0; margin: 16px 0 0; border-top: 1px solid var(--body-rule);
}
.day-body-record dt { font-size: .91rem; color: var(--body-dim); }
.day-body-record dd { margin: .2em 0 0; }
.day-body-record .body-stat strong { font-size: 1.15rem; font-weight: 600; }
.day-body-record .body-sleep { margin: 16px 0 0; padding-top: 12px; border-top: 1px solid var(--body-rule); }
.day-body-record .body-session + .body-session { margin-top: 8px; }
.day-body-record .body-session time { font-size: .97rem; font-weight: 560; }
.day-body-record .body-session .body-detail { margin-inline-start: .55em; display: inline-block; }
.day-body-record .body-empty { margin: 0; color: var(--body-dim); }
.day-body-record .body-access {
  background: none; color: var(--body-accent); border: 0; padding: 10px 0; font: inherit;
  text-decoration: underline; text-underline-offset: 4px; cursor: pointer; min-height: 42px;
}
@media (max-width:440px) {
  .day-body-record .body-main { grid-template-columns: minmax(0,1fr) minmax(100px,1fr); gap: 12px; }
}
`;

const finite = Number.isFinite;
const numeric = new Intl.NumberFormat('zh-CN', {maximumFractionDigits: 1});

function node(tag, className, text) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text != null) element.textContent = text;
  return element;
}

function sum(rows, field, key) {
  const totals = rows.map(row => row[field]?.[key]).filter(finite);
  return totals.length ? totals.reduce((a, b) => a + b, 0) : null;
}

function duration(minutes) {
  return minutes >= 60 ? `${Math.floor(minutes / 60)} 小时${minutes % 60 ? ' ' + minutes % 60 + ' 分' : ''}` : `${minutes} 分钟`;
}

function chart(hours, selected) {
  const block = node('div', 'body-chart');
  const bars = node('div', 'body-bars');
  bars.setAttribute('role', 'img');
  const values = Array.from({length: 24}, (_, hour) => {
    const data = hours.find(row => row.hour === hour)?.steps;
    return data?.observedMinutes > 0 && finite(data.steps) ? data.steps : null;
  });
  const maximum = Math.max(1, ...values.filter(finite));
  const texts = [];
  for (let h = 0; h < 24; h++) {
    const value = values[h];
    const bar = node('span', 'body-bar');
    if (h === selected) bar.dataset.selected = '';
    if (value != null) {
      // A zero reading gets a short baseline. Missing observations stay blank.
      bar.style.height = `max(2px, ${100 * value / maximum}%)`;
      texts.push(`${h}时 ${value} 步`);
    } else bar.style.height = '0';
    bar.title = value == null ? `${h}时 · 无记录` : `${h}时 · ${value} 步`;
    bars.append(bar);
  }
  bars.setAttribute('aria-label', '每小时已记录步数。' + texts.join('，'));
  const ticks = node('div', 'body-ticks');
  ticks.setAttribute('aria-hidden', 'true');
  ticks.append(node('span', '', '0 时'), node('span', '', '12'), node('span', '', '24'));
  block.append(bars, ticks);
  return block;
}

function metric(label, value, detail) {
  const entry = node('div', 'body-stat');
  const data = node('dd');
  data.append(node('strong', '', value));
  if (detail) data.append(node('div', 'body-detail', detail));
  entry.append(node('dt', '', label), data);
  return entry;
}

function sleepRows(rows, zone, from) {
  const sessions = new Map();
  for (const row of rows) {
    for (const session of row.sleep?.sessions ?? []) {
      if (!finite(Date.parse(session.startedAt)) || !finite(Date.parse(session.endedAt))) continue;
      const key = session.startedAt + '/' + session.endedAt;
      const existing = sessions.get(key);
      if (!existing || (!finite(existing.sessionAsleepMinutes) && finite(session.sessionAsleepMinutes))) {
        sessions.set(key, session);
      }
    }
  }
  const wakes = rows.flatMap(row => row.sleep?.wakeMarkers ?? [])
    .filter(marker => finite(Date.parse(marker.endedAt)));
  if (!sessions.size && !wakes.length) return null;
  const clock = new Intl.DateTimeFormat('zh-CN', {timeZone: zone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23'});
  const date = new Intl.DateTimeFormat('zh-CN', {timeZone: zone, month: 'numeric', day: 'numeric'});
  const anchor = date.format(new Date(from));
  function timeElement(iso) {
    const when = new Date(iso);
    const monthDay = date.format(when);
    const t = node('time', '', (monthDay === anchor ? '' : monthDay + ' ') + clock.format(when));
    t.dateTime = iso;
    return t;
  }
  const list = node('dl', 'body-sleep');
  list.append(node('dt', '', '睡眠时段'));
  for (const session of [...sessions.values()].sort((a, b) => Date.parse(a.startedAt) - Date.parse(b.startedAt))) {
    const line = node('dd', 'body-session');
    line.append(timeElement(session.startedAt), document.createTextNode(' — '), timeElement(session.endedAt));
    const facts = [];
    if (session.source === 'band_minute_estimate') facts.push('估算');
    if (finite(session.sessionAsleepMinutes)) facts.push('整段实睡 ' + duration(Math.round(session.sessionAsleepMinutes)));
    if (facts.length) line.append(node('span', 'body-detail', facts.join(' · ')));
    list.append(line);
  }
  for (const marker of wakes) {
    const line = node('dd', 'body-session');
    line.append(document.createTextNode('手环记录的结束时间 '), timeElement(marker.endedAt),
      node('span', 'body-detail', '入睡时间缺失'));
    list.append(line);
  }
  return list;
}

/** Render recorded observations for the day or a selected hour. Missing minute records are never zero-filled. */
export function renderBody(host, {hours = [], hour = null, from, timeZone = 'Asia/Shanghai', authorized, onUnlock} = {}) {
  if (!document.getElementById('day-body-record-styles')) {
    const style = node('style', '', sheet);
    style.id = 'day-body-record-styles';
    document.head.append(style);
  }
  const block = node('div', 'day-body-record');
  host.replaceChildren(block);
  if (!authorized) {
    block.append(node('p', 'body-empty', '授权后可看这一天的手环记录。'));
    if (typeof onUnlock === 'function') {
      const unlock = node('button', 'body-access', '查看身体记录');
      unlock.type = 'button';
      unlock.addEventListener('click', onUnlock);
      block.append(unlock);
    }
    return;
  }

  const all = Array.isArray(hours) ? hours : [];
  const selected = Number.isInteger(hour) ? hour : null;
  const rows = selected === null ? all : all.filter(row => row.hour === selected);
  const stepMinutes = sum(rows, 'steps', 'observedMinutes') ?? 0;
  const steps = stepMinutes > 0 ? sum(rows, 'steps', 'steps') : null;
  const hr = rows.map(row => row.heartRate).filter(point => point?.sampleCount > 0);
  const oxygen = rows.map(row => row.oxygenSaturation)
    .filter(point => finite(point?.percent) && finite(Date.parse(point.sampledAt)))
    .sort((a, b) => Date.parse(b.sampledAt) - Date.parse(a.sampledAt))[0];
  const distMinutes = sum(rows, 'distance', 'observedMinutes') ?? 0;
  const meters = distMinutes > 0 ? sum(rows, 'distance', 'meters') : null;
  const sleep = sleepRows(rows, timeZone, from);

  if (steps == null && !hr.length && !oxygen && meters == null && !sleep) {
    block.append(node('p', 'body-empty', selected === null ? '这一天还没有手环记录。' : '这个小时没有手环记录。'));
    return;
  }
  if (steps != null) {
    const main = node('div', selected === null ? 'body-main' : 'body-main body-hour-focus');
    const summary = node('div');
    const digits = node('div', 'body-digits');
    digits.append(node('strong', '', numeric.format(steps)), node('span', '', '步'));
    summary.append(node('div', 'body-label', selected === null ? '全天已记录' : '本小时已记录'),
      digits, node('div', 'body-detail', `${numeric.format(stepMinutes)} 分钟有步数记录`));
    main.append(summary, chart(all, selected));
    block.append(main);
  }
  const stats = node('dl', 'body-stats');
  if (hr.length) {
    const lows = hr.map(point => point.minBpm).filter(finite);
    const highs = hr.map(point => point.maxBpm).filter(finite);
    if (lows.length && highs.length) {
      const low = Math.min(...lows), high = Math.max(...highs);
      const samples = hr.reduce((n, point) => n + point.sampleCount, 0);
      stats.append(metric('心率', (low === high ? low : `${low}–${high}`) + ' 次/分', `共 ${numeric.format(samples)} 次采样`));
    }
  }
  if (oxygen) {
    const at = new Intl.DateTimeFormat('zh-CN', {timeZone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23'}).format(new Date(oxygen.sampledAt));
    stats.append(metric('最近血氧', numeric.format(oxygen.percent) + '%', at + ' 采样'));
  }
  if (meters != null) stats.append(metric('已记录距离',
    meters >= 1000 ? (meters / 1000).toLocaleString('zh-CN', {maximumFractionDigits: 2}) + ' 公里' : numeric.format(meters) + ' 米',
    `${numeric.format(distMinutes)} 分钟有记录`));
  if (stats.childElementCount) block.append(stats);
  if (sleep) block.append(sleep);
}
