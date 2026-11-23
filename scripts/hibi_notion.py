#!/usr/bin/env python3
"""Upsert one dated, structured Notion row per Shanghai day from Hibi SQLite."""
import argparse
import email.utils
import fcntl
import hashlib
import json
import os
import re
import sqlite3
import sys
import tempfile
import time
import uuid
from contextlib import closing
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener

from hibi_day import LOCAL, day_facts, parse_stamp
from hibi_timeline import timeline_sections
from hibi_settings import HEALTH_DEVICE, MAC_DEVICE, legacy_idle_cutoff
from hibi_history import EMPTY, changed_dates, fingerprints

SOURCE = os.getenv('HIBI_NOTION_DATA_SOURCE_ID', '')
NAMES = {HEALTH_DEVICE: '安卓手机', 'android-tablet': '平板', 'ios-phone': 'iPhone',
         MAC_DEVICE: 'Mac', 'windows-pc': 'Windows'}
SCHEMA = {'日期': 'title', '记录日': 'date', '最近核验': 'date', '已记录设备': 'number',
          '健康同步': 'select', '设备使用': 'rich_text', '身体记录': 'rich_text', '有记录的分钟': 'number'}


def readable_usage(facts, old_mac_idle):
    observed, absent = [], []
    for device, value in facts['devices'].items():
        name = NAMES.get(device, device)
        if not value['observed']:
            absent.append(name)
            continue
        duration = value['activeMinutes']
        if duration is None:
            detail = '有记录，前台时长未知'
        elif duration == 0 and value['idleMinutes'] and device == MAC_DEVICE and old_mac_idle:
            detail = '仅有离开标记，使用时长未知'
        else:
            detail = f'{duration:g} 分钟'
        if value['idleMinutes'] and device == MAC_DEVICE and old_mac_idle:
            detail += '（历史离开判断异常）'
        observed.append(f'{name} {detail}')
    segments = [' · '.join(observed)] if observed else []
    if absent:
        segments.append('无当日记录：' + '、'.join(absent))
    if facts['devices'].get('ios-phone', {}).get('uncertainRows', 0):
        segments.append('iPhone 有时长未明的片段，未计入')
    return '。'.join(segments) or '无设备记录'


def mcp_row(facts, checked, old_mac_idle=False):
    health = facts['health']
    latest = parse_stamp(health['lastSampleAt']) if health['lastSampleAt'] else None
    sessions = health.get('sleepSessions', [])
    if not health['samples']:
        health_status = '当日无记录'
    elif not sessions:
        health_status = '待补睡眠'
    elif any(s.get('source') == 'mi_fitness_local_log' or
             s.get('asleepMinutesSource') == 'mi_fitness_local_log' for s in sessions):
        health_status = '含睡眠补录'
    elif any(s['asleepMinutes'] is None for s in sessions) or 'heartRateRangeBpm' not in health or 'reportedSteps' not in health:
        health_status = '部分有记录'
    else:
        health_status = '三项有记录'
    if health['samples']:
        metrics = []
        for session in sessions:
            start = parse_stamp(session['start']).astimezone(LOCAL)
            end = parse_stamp(session['end']).astimezone(LOCAL)
            prefix = '前一晚 ' if start.date() < end.date() else ''
            text = f'睡眠 {prefix}{start:%H:%M}–{end:%H:%M}'
            if session['asleepMinutes'] is not None:
                hours, minutes = divmod(session['asleepMinutes'], 60)
                text += f'（实睡 {hours} 小时 {minutes} 分）'
            else:
                text += '（实睡时长未上报）'
            if session.get('source') == 'mi_fitness_local_log':
                text += '，来源为小米运动健康本机记录补录'
            elif session.get('asleepMinutesSource') == 'mi_fitness_local_log':
                text += '，时长由小米运动健康本机记录补齐'
            metrics.append(text)
        if not sessions:
            metrics.append('睡眠：尚未收到完整时段')
        coverage = facts.get('metricCoverage', {})
        if 'heartRateRangeBpm' in health:
            low, high = health['heartRateRangeBpm']
            text = f'心率 {low:g}–{high:g} 次/分'
            if coverage.get('heartRateLastAt'):
                last_hr = parse_stamp(coverage['heartRateLastAt']).astimezone(LOCAL)
                text += f'（末次 {last_hr:%H:%M}）'
            metrics.append(text)
        else:
            metrics.append('心率：暂无读数')
        if 'reportedSteps' in health:
            text = f"上报步数 {health['reportedSteps']}"
            if 'stepSampleMinutes' in coverage:
                text += f"（{coverage['stepSampleMinutes']} 个有记录的分钟）"
            metrics.append(text)
        else:
            metrics.append('步数：暂无记录')
        body = '\n'.join(metrics)
    else:
        body = '当天无健康记录'
        if latest:
            body += '；最近一条 ' + latest.astimezone(LOCAL).strftime('%m-%d %H:%M')
    day = facts['date']
    return {'properties': {'日期': day, 'date:记录日:start': day, 'date:记录日:is_datetime': 0,
            'date:最近核验:start': checked.astimezone(LOCAL).isoformat(timespec='seconds'), 'date:最近核验:is_datetime': 1,
            '已记录设备': facts['deviceCount'], '健康同步': health_status, '设备使用': readable_usage(facts, old_mac_idle),
            '身体记录': body, '有记录的分钟': facts['activeMinutes']}}


def notion_props(flat):
    props = {}
    for key, kind in SCHEMA.items():
        value = flat['date:' + key + ':start'] if kind == 'date' else flat[key]
        if kind in ('rich_text', 'title'):
            entry = [{'text': {'content': value}}]
        elif kind == 'date':
            entry = {'start': value}
        elif kind == 'select':
            entry = {'name': value}
        else:
            entry = value
        props[key] = {kind: entry}
    return props


def prop_value(prop, kind):
    value = prop.get(kind)
    if kind in ('rich_text', 'title'):
        return ''.join(t.get('plain_text', t.get('text', {}).get('content', '')) for t in value or [])
    if kind == 'date':
        return value.get('start') if value else None
    if kind == 'select':
        return value.get('name') if value else None
    return value


class SyncError(Exception):
    pass


class ApiError(SyncError):
    def __init__(self, method, status, code):
        super().__init__(f'Notion {method} failed: HTTP {status} ({code})')
        self.status = status


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


class Notion:
    def __init__(self, token):
        self.headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json',
                        'Notion-Version': '2025-09-03'}
        self.opener = build_opener(NoRedirect)

    def request(self, method, path, data=None, *, create=False):
        req = Request('https://api.notion.com/v1/' + path, headers=self.headers, method=method,
                      data=json.dumps(data, ensure_ascii=False).encode('utf-8') if data is not None else None)
        for attempt in range(3):
            try:
                with self.opener.open(req, timeout=20) as response:
                    return json.load(response)
            except HTTPError as err:
                try:
                    code = json.load(err).get('code', 'unknown')
                except (ValueError, AttributeError):
                    code = 'unknown'
                if attempt == 2 or (err.code != 429 and (create or err.code not in {500, 502, 503, 504})):
                    raise ApiError(method, err.code, code) from None
                header = err.headers.get('Retry-After', '')
                try:
                    delay = float(header)
                except ValueError:
                    try:
                        delay = email.utils.parsedate_to_datetime(header).timestamp() - time.time()
                    except (TypeError, ValueError):
                        delay = 2 ** attempt
                if delay > 30:
                    raise ApiError(method, err.code, code) from None
                time.sleep(max(1, delay))
            except (URLError, OSError, ValueError):
                # A create may already have been committed. Never replay it blindly.
                if create or attempt == 2:
                    raise SyncError(f'Notion {method}: no usable response') from None
                time.sleep(2 ** attempt)


# Keep days readable on opening the page. Every generated paragraph has its own
# plain, bold label; only untouched blocks from this integration can be updated.
TIMELINE_TITLE = '当天时间线 · Hibi'
TIMELINE_PART_SIZE = 5800


def timeline_parts(sections):
    # Prefer section boundaries. A very busy hour can span Notion blocks;
    # slicing never drops text, even for a single very long event line.
    chunks = []
    part = ''
    for section in sections:
        if not section:
            continue
        if part and len(part) + 2 + len(section) > TIMELINE_PART_SIZE:
            chunks.append(part)
            part = ''
        if part:
            part += '\n\n'
        while len(section) > TIMELINE_PART_SIZE:
            split = section.rfind('\n', 0, TIMELINE_PART_SIZE)
            split = split + 1 if split >= TIMELINE_PART_SIZE // 2 else TIMELINE_PART_SIZE
            chunks.append(section[:split])
            section = section[split:]
        part += section
    if part:
        chunks.append(part)
    return chunks


def timeline_paragraph(slot, body, label=TIMELINE_TITLE):
    title = label if slot == 1 else f'{label}（续 {slot}）'
    spans = [{'type': 'text', 'text': {'content': title}, 'annotations': {'bold': True}}]
    body = '\n' + body
    # Each Notion text run permits 2000 UTF-16 units; 900 codepoints also
    # accommodates four-byte characters and leaves headroom for API limits.
    spans.extend({'type': 'text', 'text': {'content': body[i:i+900]}} for i in range(0, len(body), 900))
    return {'object': 'block', 'type': 'paragraph', 'paragraph': {'rich_text': spans}}


def children(api, page_id):
    cursor = None
    while True:
        params = {'page_size': 100}
        if cursor:
            params['start_cursor'] = cursor
        response = api.request('GET', f'blocks/{page_id}/children?' + urlencode(params))
        yield from response['results']
        if not response['has_more']:
            return
        new_cursor = response.get('next_cursor')
        if not new_cursor or new_cursor == cursor:
            raise SyncError('Notion returned an invalid block cursor')
        cursor = new_cursor


def timeline_slot(block, bot_id, label=TIMELINE_TITLE):
    if (block.get('type') != 'paragraph' or block.get('has_children') or
            (block.get('created_by') or {}).get('id') != bot_id or
            (block.get('last_edited_by') or {}).get('id') != bot_id):
        return None
    spans = block['paragraph'].get('rich_text', [])
    if not spans or not spans[0].get('annotations', {}).get('bold'):
        return None
    title = spans[0].get('plain_text', spans[0].get('text', {}).get('content', ''))
    match = re.fullmatch(re.escape(label) + r'(?:（续 ([2-9]|[1-9][0-9]+)）)?', title)
    return int(match.group(1) or 1) if match else None


def save_paragraphs(api, page_id, sections, listed=None, label=TIMELINE_TITLE):
    if not getattr(api, 'bot_id', None):
        api.bot_id = str(uuid.UUID(api.request('GET', 'users/me')['id']))
    wanted = [timeline_paragraph(i, body, label) for i, body in enumerate(timeline_parts(sections), 1)]
    current, surplus = {}, []
    for block in (listed if listed is not None else children(api, page_id)):
        slot = timeline_slot(block, api.bot_id, label)
        if slot is None:
            continue
        if slot > len(wanted) or slot in current:
            surplus.append(block['id'])
        else:
            current[slot] = block
    changed = False
    slot = 1
    last_id = None
    while slot <= len(wanted):
        if slot in current:
            block = current[slot]
            last_id = str(uuid.UUID(block['id']))
            value = wanted[slot-1]['paragraph']
            if prop_value(block['paragraph'], 'rich_text') != prop_value(value, 'rich_text'):
                api.request('PATCH', 'blocks/' + last_id, {'paragraph': value})
                changed = True
            slot += 1
            continue
        # Bound both request size and per-day round trips. After a dropped
        # append response, the next invocation enumerates committed blocks
        # before submitting any missing positions.
        end = slot+1
        while end <= len(wanted) and end not in current and end-slot < 8:
            end += 1
        batch = {'children': wanted[slot-1:end-1]}
        if last_id:
            batch['after'] = last_id
        result = api.request('PATCH', f'blocks/{page_id}/children', batch, create=True)
        if len(result.get('results', [])) != end-slot:
            raise SyncError('Notion returned an incomplete block append acknowledgement')
        last_id = str(uuid.UUID(result['results'][-1]['id']))
        changed = True
        slot = end
    # Never archive a user-authored/edited paragraph or a block with children.
    for block_id in surplus:
        api.request('DELETE', 'blocks/' + str(uuid.UUID(block_id)))
        changed = True
    return changed


HOUR_PATTERN = re.compile(r'^([01][0-9]|2[0-3]):00–')
DETAIL_LABEL = '时段明细'


def hour_title(hour, section):
    start = f'{hour:02}:00'
    step = section.split('\n', 1)[0].partition('手环 · ')[2].split(' · ', 1)[0]
    suffix = f'–{hour+1:02}:00' + (f'　{step}' if step else '')
    return [{'type':'text','text':{'content':start},'annotations':{'bold':True}},
            {'type':'text','text':{'content':suffix}}]


def owned_hour(block, bot):
    if block.get('type') != 'toggle' or any((block.get(key) or {}).get('id') != bot
                                            for key in ('created_by','last_edited_by')):
        return None
    parts = block['toggle']['rich_text']
    if not parts or not parts[0].get('annotations',{}).get('bold'):
        return None
    label = prop_value(block['toggle'], 'rich_text')
    match = HOUR_PATTERN.match(label)
    return int(match[1]) if match else None


def append_nested(api, page, rows, after=None):
    # Each request allows 500 KiB, 100 nested siblings per call. Keep batches
    # comfortably under both limits even with non-ASCII escape sequences.
    start = 0
    while start < len(rows):
        batch, size, descendants = [], 0, 0
        for row in rows[start:]:
            cost = len(json.dumps(row,ensure_ascii=False).encode())
            count = 1 + len(row[row['type']].get('children', []))
            if batch and (size+cost > 320000 or descendants+count > 90):
                break
            if cost > 380000 or count > 95:
                raise SyncError('Notion nested batch exceeds budget')
            batch.append(row)
            size += cost
            descendants += count
        payload = {'children':batch}
        if after:
            payload['after'] = after
        result = api.request('PATCH', f'blocks/{page}/children',payload,create=True)
        added = result.get('results', [])
        if len(added) != len(batch):
            raise SyncError('Notion did not confirm every nested block')
        yield from added
        after = str(uuid.UUID(added[-1]['id']))
        start += len(batch)


def save_timeline(api, page_id, sections, guard_dir=None):
    if not getattr(api,'bot_id',None):
        api.bot_id = str(uuid.UUID(api.request('GET','users/me')['id']))
    page_blocks = list(children(api,page_id))
    if len(sections) < 2 or not all(HOUR_PATTERN.match(s) for s in sections[1:]):
        return save_paragraphs(api,page_id,sections,page_blocks)
    wanted = {int(HOUR_PATTERN.match(s)[1]):s for s in sections[1:]}
    cache_path = guard_dir / ('hours-' + page_id + '.json') if guard_dir else None
    cache = json.loads(cache_path.read_text()) if cache_path and cache_path.exists() else {}
    known = {}
    extras = []
    for block in page_blocks:
        hour = owned_hour(block,api.bot_id)
        if hour is not None:
            if hour in known or hour not in wanted:
                extras.append(block)
            else:
                known[hour] = block
    has_overview = any(timeline_slot(block,api.bot_id) for block in page_blocks)
    initial = not known and not has_overview
    changed = False if initial else save_paragraphs(api,page_id,[sections[0]],page_blocks)
    prefix = ([timeline_paragraph(i,part) for i,part in enumerate(timeline_parts([sections[0]]),1)]
              if initial else [])
    keys = sorted(wanted)
    pos = 0
    previous = None
    while pos < len(keys):
        hour = keys[pos]
        if hour in known:
            block = known[hour]
            title = hour_title(hour,wanted[hour])
            if prop_value(block['toggle'],'rich_text') != prop_value({'rich_text':title},'rich_text'):
                api.request('PATCH','blocks/'+block['id'],{'toggle':{'rich_text':title}})
                changed = True
            signature = hashlib.sha256(wanted[hour].encode()).hexdigest()
            if cache.get(str(hour)) != [block['id'],signature] or not block.get('has_children'):
                changed |= save_paragraphs(api,block['id'],[wanted[hour]],label=DETAIL_LABEL)
                cache[str(hour)] = [block['id'],signature]
            previous = block['id']
            pos += 1
            continue
        end = pos
        batch = list(prefix)
        overflow = set()
        for idx in range(pos,len(keys)):
            h = keys[idx]
            if h in known:
                break
            rich = hour_title(h,wanted[h])
            details = [timeline_paragraph(i,c,DETAIL_LABEL) for i,c in enumerate(timeline_parts([wanted[h]]),1)]
            nested = []
            size = 0
            for detail in details:
                cost = len(json.dumps(detail,ensure_ascii=False).encode())
                if size+cost > 220000 or len(nested)>=70:
                    break
                nested.append(detail)
                size += cost
            if len(nested) != len(details):
                overflow.add(h)
            batch.append({'object':'block','type':'toggle','toggle':{'rich_text':rich,'children':nested}})
            end += 1
        added = list(append_nested(api,page_id,batch,previous))
        new_toggles = added[len(prefix):]
        prefix.clear()
        for h,block in zip(keys[pos:end],new_toggles):
            if h in overflow:
                save_paragraphs(api,block['id'],[wanted[h]],label=DETAIL_LABEL)
            cache[str(h)] = [block['id'],hashlib.sha256(wanted[h].encode()).hexdigest()]
        previous = added[-1]['id']
        pos = end
        changed = True
    for block in extras:
        descendants = list(children(api,block['id']))
        if all(timeline_slot(child,api.bot_id,DETAIL_LABEL) for child in descendants):
            api.request('DELETE','blocks/'+block['id'])
            changed = True
    if cache_path:
        store_checkpoint(cache_path,cache)
    return changed


def save_day(api, source, flat, guard_dir):
    day = flat['日期']
    desired = notion_props(flat)
    lookup = api.request('POST', f'data_sources/{source}/query', {'page_size': 2, 'filter': {'or': [
        {'property': '日期', 'title': {'equals': day}}, {'property': '记录日', 'date': {'equals': day}}]}})
    results = lookup['results']
    if len(results) > 1 or lookup.get('has_more'):
        raise SyncError(f'{day}: duplicate date rows; resolve before updating')
    pending = guard_dir / (day + '.pending')
    if results:
        existing = results[0]['properties']
        title = prop_value(existing['日期'], 'title')
        record_day = prop_value(existing['记录日'], 'date')
        if (title and title != day) or (record_day and record_day != day):
            raise SyncError(f'{day}: conflicting date fields on matching row')
        pending.unlink(missing_ok=True)
        changed = {key: new for key, new in desired.items() if key != '最近核验' and
                   prop_value(existing.get(key, {}), SCHEMA[key]) != prop_value(new, SCHEMA[key])}
        last = prop_value(existing.get('最近核验', {}), 'date')
        last_day = parse_stamp(last).astimezone(LOCAL).date() if last else None
        today = parse_stamp(flat['date:最近核验:start']).astimezone(LOCAL).date()
        # Update once after closing yesterday, even if its provisional figures did not change.
        # Advance today's timestamp when observations change or once per hour while live.
        should_close = today > date.fromisoformat(day) and (not last_day or last_day <= date.fromisoformat(day))
        should_refresh = (day == today.isoformat() and (not last or
                          parse_stamp(flat['date:最近核验:start']) - parse_stamp(last) >= timedelta(hours=1)))
        if changed or should_close or should_refresh or not last:
            changed['最近核验'] = desired['最近核验']
            api.request('PATCH', 'pages/' + str(uuid.UUID(results[0]['id'])), {'properties': changed})
            status = 'updated'
        else:
            status = 'unchanged'
        if '_timeline' in flat:
            if save_timeline(api, str(uuid.UUID(results[0]['id'])), flat['_timeline'], guard_dir):
                status = 'updated'
        return status
    if pending.exists():
        raise SyncError(f'{day}: previous create result uncertain; verify Notion before clearing pending marker')
    # Survives network timeouts/process crashes, including an acknowledged insert not yet visible to queries.
    with pending.open('x') as marker:
        marker.write(day)
        marker.flush()
        os.fsync(marker.fileno())
    try:
        result = api.request('POST', 'pages', {'parent': {'type': 'data_source_id', 'data_source_id': source},
                                              'properties': desired}, create=True)
        if not result.get('id'):
            raise SyncError(f'{day}: no page ID in create response')
    except ApiError as err:
        if err.status in {400, 401, 403, 404, 429}:
            pending.unlink(missing_ok=True)
        raise
    if '_timeline' in flat:
        save_timeline(api, str(uuid.UUID(result['id'])), flat['_timeline'], guard_dir)
    return 'created'


# Keep the regular eight-day refresh responsive even after a long disconnection.
EXTRA_DAYS_PER_RUN = 8
INITIAL_RECEIPT_WINDOW = timedelta(days=1)
RECEIPT_OVERLAP = timedelta(minutes=5)


def store_checkpoint(path, data):
    temporary = path.with_suffix('.tmp')
    with temporary.open('w', encoding='utf-8') as stream:
        json.dump(data, stream)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)
    directory = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def receipt_days(db, since, through):
    # Sleep belongs to the wake date; a minute sample belongs to its measurement date.
    # The cutoff is server receipt time in standard UTC millisecond notation.
    tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    bounds = [stamp.astimezone(timezone.utc).isoformat(timespec='milliseconds').replace('+00:00', 'Z')
              for stamp in (since, through)]
    days = set()
    # A phone may upload old activity or Health Connect data after being
    # offline. Creation time (rather than measurement time) discovers it.
    for table, columns in (('device_activities', ('started_at', 'ended_at')),
                           ('device_health', ('recorded_at',))):
        if table not in tables or 'created_at' not in {
                row[1] for row in db.execute(f'PRAGMA table_info({table})')}:
            continue
        date_exprs = ','.join(f"date({col}, '+8 hours')" for col in columns)
        for row in db.execute(f'SELECT DISTINCT {date_exprs} FROM {table} WHERE created_at >= ? AND created_at <= ?',
                              bounds):
            days.update(date.fromisoformat(value) for value in row if value and value <= through.astimezone(LOCAL).date().isoformat())
    for table, date_col in (('band_samples', 'recorded_at'), ('band_sleep', 'ended_at'),
                            ('historical_sleep_imports', 'ended_at')):
        if table not in tables:
            continue
        # A session is at most 24h. Direct updates can extend the end past
        # midnight, leaving the previous wake day to clear. A local historical
        # correction can also shorten a session (the old end is not retained).
        columns = [f"date({date_col}, '+8 hours')"]
        if table != 'band_samples':
            columns.append("date(started_at, '+8 hours')")
        if table == 'historical_sleep_imports':
            columns.append("date(started_at, '+8 hours', '+1 day')")
        # Collapse thousands of received minute samples into distinct days in SQLite.
        rows = db.execute(f"""
            SELECT DISTINCT {', '.join(columns)} FROM {table}
            WHERE received_at >= ? AND received_at <= ? AND {date_col} <= ?
            """, (*bounds, bounds[1]))
        for row in rows:
            days.update(date.fromisoformat(value) for value in row)
    days.discard(through.astimezone(LOCAL).date() + timedelta(days=1))
    return days


def receipt_plan(db, guard_dir, checked, dry_run):
    today = checked.astimezone(LOCAL).date()
    regular = {today - timedelta(days=n) for n in range(8)}
    state_path = guard_dir / 'receipts.json'
    if state_path.exists():
        state = json.loads(state_path.read_text())
        since = parse_stamp(state['scannedThrough'])
        backlog = [date.fromisoformat(value) for value in state['pendingDates']]
        if since > checked:
            raise SyncError('Saved receipt checkpoint is newer than the snapshot')
    else:
        # Preserve the first scan boundary across failures/restarts, including a
        # failed inaugural run that is not retried until several days later.
        initial_path = guard_dir / 'receipts-start.json'
        if initial_path.exists():
            since = parse_stamp(json.loads(initial_path.read_text())['since'])
        else:
            since = checked - INITIAL_RECEIPT_WINDOW
            if not dry_run:
                store_checkpoint(initial_path, {'since': since.isoformat()})
        backlog = []
    changed = receipt_days(db, since - RECEIPT_OVERLAP, checked) - regular
    # Existing pending days first so sustained uploads of newer or older history
    # cannot starve a backlog. Never truncate unsaved work to the write budget.
    backlog = list(dict.fromkeys([*(d for d in backlog if d not in regular), *sorted(changed)]))
    extra = backlog[:EXTRA_DAYS_PER_RUN]
    checkpoint = {'scannedThrough': checked.astimezone(timezone.utc).isoformat(timespec='milliseconds'),
                  'pendingDates': [d.isoformat() for d in backlog[EXTRA_DAYS_PER_RUN:]]}
    return sorted(regular | set(extra)), checkpoint


def prepare_rows(args, checked, guard_dir, spool=None):
    history_path = guard_dir / 'history-signatures.json'
    confirmed = json.loads(history_path.read_text()) if history_path.exists() else {}
    with closing(sqlite3.connect(args.db.resolve().as_uri() + '?mode=ro', uri=True, timeout=5)) as db:
        db.row_factory = sqlite3.Row
        db.execute('PRAGMA query_only = ON')
        db.execute('BEGIN')
        observed = fingerprints(db, checked)
        changed = changed_dates(observed, confirmed)
        if args.days:
            days, checkpoint = sorted(set(args.days), reverse=True), None
        elif getattr(args, 'backfill_history', False):
            # Newest (including today's existing page) first. Previously synced
            # dates that lost all observations must also have their row corrected.
            days, checkpoint = changed, None
        elif args.dry_run and args.as_of:
            # Historical previews represent their own time, independent of today's cursor.
            today = checked.astimezone(LOCAL).date()
            days, checkpoint = [today - timedelta(days=n) for n in range(7, -1, -1)], None
        else:
            days, checkpoint = receipt_plan(db, guard_dir, checked, args.dry_run)
            # The receipt queue and full-snapshot checks share one history
            # allowance, leaving room to refresh the ordinary daily rows.
            regular = {checked.astimezone(LOCAL).date() - timedelta(days=i) for i in range(8)}
            scheduled = set(days)
            remaining = max(0, EXTRA_DAYS_PER_RUN - len(scheduled - regular))
            days.extend([d for d in changed if d not in scheduled][:remaining])
        flat = []
        for day in days:
            cutoff = legacy_idle_cutoff()
            old_mac_idle = False
            if cutoff and day <= cutoff.astimezone(LOCAL).date():
                lo = datetime.combine(day, datetime.min.time(), LOCAL).astimezone(timezone.utc)
                upper = min(lo + timedelta(days=1), cutoff)
                stamp = lambda t: t.isoformat(timespec='milliseconds').replace('+00:00', 'Z')
                old_mac_idle = bool(db.execute("""SELECT 1 FROM device_activities
                    WHERE device_id=? AND app_id='macos.afk' AND started_at < ?
                    AND ended_at > ? AND ended_at > started_at LIMIT 1""",
                    (MAC_DEVICE, stamp(upper), stamp(lo))).fetchone())
            entry = mcp_row(day_facts(db, day, checked), checked, old_mac_idle)
            entry['timeline'] = timeline_sections(db, day, checked)
            if spool is None:
                flat.append(entry)
            else:
                entry['_signature'] = observed.get(day.isoformat(), EMPTY)
                json.dump(entry, spool, ensure_ascii=False, separators=(',', ':'), allow_nan=False)
                spool.write('\n')
    return flat, checkpoint, confirmed


def run(args):
    if args.as_of and not args.dry_run:
        raise SyncError('--as-of is for dry-run previews only')
    if getattr(args, 'backfill_history', False) and args.days:
        raise SyncError('Choose --backfill-history or a list of dates')
    if not args.source and not args.dry_run:
        raise SyncError('Set HIBI_NOTION_DATA_SOURCE_ID or pass --source')
    source = str(uuid.UUID(args.source)) if args.source else 'preview'
    guard_dir = args.state_dir / source
    if args.dry_run:
        rows, _, _ = prepare_rows(args, args.as_of or datetime.now(timezone.utc), guard_dir)
        print(json.dumps(rows, ensure_ascii=False, allow_nan=False))
        return

    guard_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (guard_dir / 'writer.lock').open('a') as lock, tempfile.TemporaryFile(mode='w+', encoding='utf-8') as spool:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise SyncError('A daily sync is already running') from None
        checked = datetime.now(timezone.utc)
        _, checkpoint, confirmed = prepare_rows(args, checked, guard_dir, spool)
        spool.seek(0)
        token = args.token_file.read_text().strip()
        if not token:
            raise SyncError('Empty Notion token file')
        api = Notion(token)
        schema = api.request('GET', 'data_sources/' + source)['properties']
        wrong = [key for key, kind in SCHEMA.items() if schema.get(key, {}).get('type') != kind]
        if wrong:
            raise SyncError('Missing or incompatible Notion fields: ' + ', '.join(wrong))
        for line in spool:
            entry = json.loads(line)
            result = save_day(api, source, {**entry['properties'], '_timeline': entry['timeline']}, guard_dir)
            key, signature = entry['properties']['日期'], entry['_signature']
            if (signature != EMPTY or key in confirmed) and confirmed.get(key) != signature:
                confirmed[key] = signature
                store_checkpoint(guard_dir / 'history-signatures.json', confirmed)
            print(key, result, flush=True)
        if checkpoint is not None:
            store_checkpoint(guard_dir / 'receipts.json', checkpoint)


if __name__ == '__main__':
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--db', type=Path, required=True)
    parser.add_argument('--token-file', type=Path, default=Path('/etc/hibi/notion-token'))
    parser.add_argument('--source', default=SOURCE)
    parser.add_argument('--state-dir', type=Path, default=Path.home() / '.local/state/hibi-notion')
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--backfill-history', action='store_true',
                        help='处理全部有记录但未写入、或后来订正的历史日期，逐日保存进度')
    parser.add_argument('--as-of', type=parse_stamp)
    parser.add_argument('days', type=date.fromisoformat, nargs='*')
    try:
        run(parser.parse_args())
    except (SyncError, OSError, ValueError, sqlite3.Error, KeyError) as exc:
        sys.exit(str(exc))
