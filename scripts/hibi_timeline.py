"""Render the private chronological record for a Shanghai calendar day."""
from collections import defaultdict
from datetime import datetime, time, timedelta
from math import ceil
from pathlib import Path
import json

from hibi_settings import HEALTH_DEVICE, MAC_DEVICE, legacy_idle_cutoff
from hibi_day import IDLE, IOS_STALE, LOCAL, UTC, activity_evidence, parse_stamp, union_seconds, utc_string


def _stamp(instant, day):
    local = instant.astimezone(LOCAL)
    fmt = '%H:%M:%S' if local.second or local.microsecond else '%H:%M'
    return (local.strftime('%m-%d ') if local.date() != day else '') + local.strftime(fmt)


def _place_interval(events, start, end, begin, finish, text, day):
    left, right = max(start,begin), min(end,finish)
    while left < right:
        hour_end = left.replace(minute=0,second=0,microsecond=0) + timedelta(hours=1)
        stop = min(right,hour_end)
        continued = begin < left or finish > stop
        detail = text
        if continued:
            edge = '24:00' if stop == start + timedelta(days=1) else _stamp(stop,day)
            detail += f'（跨小时，本小时 {_stamp(left,day)}–{edge}）'
        events[left.astimezone(LOCAL).hour].append((left,detail))
        left = stop


def _activity(db, day, start, end, as_of, events):
    states = {r[0]: r[1:] for r in db.execute('SELECT device_id,device_name,platform,last_seen_at FROM device_states')}
    cutoff = legacy_idle_cutoff()
    coverage = defaultdict(list)
    points = defaultdict(int)
    applications = defaultdict(list)
    app_names = {}
    point_apps = defaultdict(set)
    lo, hi = utc_string(start), utc_string(end)
    db_path = db.execute('PRAGMA database_list').fetchone()[2]
    label_paths = [Path(db_path).parent.parent/'config/app-labels.json', Path(__file__).resolve().parents[1]/'config/app-labels.json']
    labels = {}
    for path in label_paths:
        try:
            labels = json.loads(path.read_text())
            break
        except (OSError, ValueError):
            pass
    rows = db.execute("""SELECT device_id,app_id,started_at,ended_at,
         CASE WHEN json_valid(extra_json) THEN json_extract(extra_json,'$.custom_app_name') END,
         CASE WHEN json_valid(extra_json) THEN json_extract(extra_json,'$.endSource') END
         FROM device_activities WHERE started_at < ? AND (started_at>=? OR ended_at>?)
         ORDER BY started_at,device_id,id""", (hi,lo,lo))
    for device, app, raw_start, raw_end, name, end_source in rows:
        device_name, platform, _ = states.setdefault(device, (device, '', None))
        begin = parse_stamp(raw_start)
        finish = parse_stamp(raw_end) if raw_end else None
        app_name = name if isinstance(name,str) and name.strip() else labels.get(app,{}).get('name',app)
        app_name = ' '.join(str(app_name).split())
        description = f'{device_name} · {app_name}'
        if app in IDLE:
            description += ('（旧版空闲标记，可能失准）' if device == MAC_DEVICE and cutoff and begin < cutoff else '（设备空闲标记）')
        kind = activity_evidence(begin, finish, as_of, platform, end_source)
        key = (device, app)
        app_names[key] = (device_name, app_name)
        def point(at, explanation):
            if start <= at < end:
                events[at.astimezone(LOCAL).hour].append((at, f'{_stamp(at,day)}  {description} · {explanation}'))
                points[device] += 1
                if app not in IDLE:
                    point_apps[key].add(at)
        if kind == 'open':
            point(begin, '开始观测，结束未知')
        elif kind in ('point', 'invalid'):
            point(begin, '单次观测' if finish == begin else '观测点，结束时间异常')
        elif kind in ('unmatched','long'):
            point(begin,'开始记录，持续时长未知')
            reason = {'ios_next_open':'下一次打开指令，前一应用的关闭时间未知',
                      'ios_unnamed_close':'收到未指明应用的关闭指令'}.get(end_source,'记录结束，两端之间缺少连续证据')
            if platform == 'ios' and not end_source and finish - begin <= IOS_STALE:
                reason = '后一事件，旧记录未区分关闭来源'
            point(finish,reason)
        else:
            left, right = max(begin,start), min(finish,end)
            if left >= right:
                continue
            coverage[device].append((left,right))
            if app not in IDLE:
                applications[key].append((left,right))
            interval = f'{_stamp(begin,day)}–{_stamp(finish,day)}'
            if platform == 'ios':
                description += '（匹配的打开和关闭指令）'
            _place_interval(events,start,end,begin,finish,f'{interval}  {description}',day)
    lines = []
    for device, (name, _, last_seen) in states.items():
        n = union_seconds(coverage[device])/60
        point_count = points[device]
        if n:
            uncovered = max(0,(end-start).total_seconds()/60 - n)
            text = f'有起止的记录覆盖 {n:.1f} 分钟，余下 {uncovered:.1f} 分钟没有连续记录'
        elif point_count:
            text = '仅有观测点，使用时长未知'
        elif last_seen and lo <= last_seen < hi:
            text = '有设备上报，缺少应用观测'
        else:
            text = '这段时间没有应用观测'
        lines.append(f'{name} · {text}')
    ranked = sorted(applications, key=lambda key: (-union_seconds(applications[key]), key))
    highlight = []
    for key in ranked[:3]:
        spans = applications[key]
        longest = max(spans, key=lambda pair: pair[1]-pair[0])
        device_name, app_name = app_names[key]
        minutes = union_seconds(spans)/60
        a,b = longest
        highlight.append(f'{app_name}（{device_name}） {minutes:.1f} 分钟，最长一段 {_stamp(a,day)}–{_stamp(b,day)}')
    if highlight:
        highlight.insert(0,'主要应用 · 按同设备有起止的前台记录时长排序')
    elif point_apps:
        highlight.append('仅有前台观测点 · 按不同的观测时刻列出，使用时长未知')
        for key in sorted(point_apps,key=lambda k:(-len(point_apps[k]),k))[:3]:
            device_name, app_name = app_names[key]
            highlight.append(f'{app_name}（{device_name}） {len(point_apps[key])} 个时刻')
    else:
        highlight.append('主要应用 · 没有前台应用记录')
    return lines, highlight


def _sleep(db, day, start, end, as_of, tables, events):
    if 'band_sleep' not in tables:
        return []
    if 'private_completed_sleep' in tables:
        query = """SELECT started_at,ended_at,asleep_minutes,source,
            CASE WHEN import_received_at<=:at THEN import_minutes END FROM private_completed_sleep
            WHERE device_id=:device AND started_at<:hi AND ended_at>=:lo AND ended_at<=:at
              AND received_at<=:at AND (superseded_at IS NULL OR superseded_at>:at)"""
    else:
        query = """SELECT started_at,ended_at,asleep_minutes,'hibi_band_direct',NULL FROM band_sleep
            WHERE device_id=:device AND started_at<:hi AND ended_at>=:lo AND ended_at<=:at AND received_at<=:at"""
    if 'private_sleep_history' in tables:
        query=query.replace('FROM private_completed_sleep','FROM private_sleep_history')
    sessions = []
    for begin, finish, minutes, source, supplement in db.execute(query,dict(lo=utc_string(start),hi=utc_string(end),at=utc_string(as_of),device=HEALTH_DEVICE)):
        a,b = parse_stamp(begin),parse_stamp(finish)
        if b <= a:
            continue
        source_label = '小米运动健康本机补录' if source=='mi_fitness_local_log' else '手环直连'
        if minutes is not None:
            actual = f'整段实睡 {minutes} 分钟'
        elif supplement is not None:
            actual = f'整段实睡 {supplement} 分钟（时长由小米运动健康本机补录）'
        else:
            actual = '实睡分钟未知'
        if source=='hibi_band_interval':
            source_label='手环原始时段，未标记结束'
        if source=='band_minute_estimate':
            minutes=round((b-a).total_seconds()/60)
            actual=f'约 {minutes//60} 小时 {minutes%60} 分'
            source_label='按心率和步数估算'
        detail = f'{_stamp(a,day)}–{_stamp(b,day)}  睡眠 · {actual} · {source_label}'
        if b == start:
            events[0].append((start,detail + '（在当天开始时结束）'))
        else:
            _place_interval(events,start,end,a,b,detail,day)
        sessions.append((a, detail))
    return [detail for _,detail in sorted(sessions)]


def timeline_sections(db, day, as_of):
    """Return ordered sections. Caller provides a read-only sqlite snapshot and an aware clock."""
    if as_of.tzinfo is None:
        raise ValueError('需要带时区的截止时间')
    start = datetime.combine(day,time.min,LOCAL).astimezone(UTC)
    end = min(start+timedelta(days=1),as_of.astimezone(UTC))
    if end <= start:
        raise ValueError('该日期尚未开始')
    tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type IN ('table','view')")}
    events, steps, rates = defaultdict(list),defaultdict(dict),defaultdict(list)
    coverage, highlight = _activity(db,day,start,end,as_of,events)
    sleep_sessions = _sleep(db,day,start,end,as_of,tables,events)
    if 'band_samples' in tables:
        args = (HEALTH_DEVICE,utc_string(start),utc_string(end),utc_string(as_of))
        for kind,value,raw in db.execute("""SELECT type,value,recorded_at FROM band_samples
            WHERE device_id=? AND type IN ('steps','heart_rate')
            AND recorded_at>=? AND recorded_at<? AND received_at<=? ORDER BY recorded_at""",args):
            local = parse_stamp(raw).astimezone(LOCAL)
            if kind=='steps':
                steps[local.hour][local.minute] = int(value)
            else:
                rates[local.hour].append(f'{_stamp(local,day)} {int(value)}')
    # Health Connect step rows lack the original interval end. Keep those reported
    # counts separate from minute-resolved direct band increments.
    older = defaultdict(list)
    legacy_counts = defaultdict(int)
    legacy_sleep = []
    units = {'steps': ' 步（报告合计，覆盖区间未知）', 'heart_rate':' 次/分（心率）',
             'oxygen_saturation':'%（血氧）','sleep':' 分钟睡眠（只有记录时间，起止未知）'}
    if 'device_health' in tables:
        without_band = (""" AND NOT EXISTS (SELECT 1 FROM band_samples b WHERE b.device_id=h.device_id
            AND b.type=h.type AND b.recorded_at=h.recorded_at AND b.received_at<=:at)""" if 'band_samples' in tables else '')
        for kind, val, raw in db.execute("""SELECT h.type,h.value,h.recorded_at FROM device_health h
            WHERE ((h.type,h.unit) IN (('steps','count'),('heart_rate','bpm'),('oxygen_saturation','percent'),('sleep','minutes')))
            AND h.recorded_at>=:lo AND h.recorded_at<:hi AND h.created_at<=:at AND h.value IS NOT NULL"""
            +without_band+' ORDER BY h.recorded_at',dict(lo=utc_string(start),hi=utc_string(end),at=utc_string(as_of),device=HEALTH_DEVICE)):
            local=parse_stamp(raw).astimezone(LOCAL)
            item = f'{_stamp(local,day)} {val:g}' + units[kind]
            older[local.hour].append(item)
            legacy_counts[kind] += 1
            if kind == 'sleep':
                legacy_sleep.append(item)
    observed_minutes = sum(len(values) for values in steps.values())
    expected_minutes = ceil((end-start).total_seconds()/60)
    readings = sum(len(samples) for samples in rates.values())
    if sleep_sessions:
        highlight.extend(sleep_sessions)
    elif legacy_sleep:
        highlight.append('睡眠 · 健康连接记录 '+ '、'.join(legacy_sleep))
    else:
        highlight.append('睡眠 · 没有覆盖这一天的完整睡眠报告')
    if observed_minutes or readings:
        steps_text = f'{sum(sum(v.values()) for v in steps.values())} 步' if observed_minutes else '步数缺测'
        highlight.append(f'手环 · {steps_text}，覆盖 {observed_minutes}/{expected_minutes} 分钟，心率实测 {readings} 次')
    if legacy_counts:
        names = {'heart_rate':'心率','oxygen_saturation':'血氧','steps':'步数合计','sleep':'睡眠分钟'}
        highlight.append('健康连接 · '+'、'.join(f'{names[k]} {v} 条' for k,v in sorted(legacy_counts.items())))
    if not (observed_minutes or readings or legacy_counts):
        highlight.append('当天没有步数或心率样本')
    until='24:00' if end==start+timedelta(days=1) else _stamp(end,day)
    first = [f'{day.isoformat()} · 北京时间 00:00–{until}', *highlight, '', '设备记录覆盖', *coverage,
             '各设备可同时使用。Mac 和 Windows 显示前台采样的首末时间，安卓显示最近使用记录，iPhone 显示快捷指令事件。空档未知。']
    sections=['\n'.join(first)]
    hour=start
    while hour < end:
        right=min(hour+timedelta(hours=1),end)
        h=hour.astimezone(LOCAL).hour
        expected=ceil((right-hour).total_seconds()/60)
        minute_values=steps[h]
        step_text=(f'{sum(minute_values.values())} 步（{len(minute_values)}/{expected} 分钟有样本）'
                   if minute_values else f'步数缺测（0/{expected} 分钟）')
        hr_text='心率（次/分） '+ '、'.join(rates[h]) if rates[h] else '心率缺测'
        edge='24:00' if right==start+timedelta(days=1) else _stamp(right,day)
        lines=[f'{h:02}:00–{edge}  |  手环 · {step_text} · {hr_text}']
        if not events[h]:
            lines.append('本小时没有设备应用或睡眠区间记录。')
        lines.extend(text for _,text in sorted(events[h],key=lambda entry:entry[0]))
        if older[h]:
            lines.append('健康连接另有记录 · '+'、'.join(older[h]))
        sections.append('\n'.join(lines))
        hour=right
    return sections
