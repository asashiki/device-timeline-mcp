#!/usr/bin/env python3
"""Refresh one private Notion paragraph on an authenticated, internal request."""
import hmac
import json
import os
import re
import sys
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler
from pathlib import Path
from socketserver import UnixStreamServer
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener
from zoneinfo import ZoneInfo

from hibi_settings import HEALTH_DEVICE, MAC_DEVICE, notion_id, origin

PAGE = os.getenv('HIBI_NOTION_PAGE_ID', '')
SOURCE = os.getenv('HIBI_NOTION_DATA_SOURCE_ID', '')
DATABASE = os.getenv('HIBI_NOTION_DATABASE_ID', '')
BLOCK = os.getenv('HIBI_NOTION_STATUS_BLOCK_ID', '')
PAIR_BLOCK = os.getenv('HIBI_NOTION_PAIR_BLOCK_ID', '')
NOTION = 'https://api.notion.com/v1/'

LOCAL = ZoneInfo('Asia/Shanghai')
NAMES = {MAC_DEVICE: 'Mac', HEALTH_DEVICE: '安卓手机', 'ios-phone': 'iPhone',
         'android-tablet': '安卓平板', 'windows-pc': 'Windows'}
STATES = {'active': '活跃', 'idle': '空闲', 'locked': '已锁屏', 'paused': '已暂停',
          'stale': '当前未知', 'unknown': '当前未知'}
SLEEP = {'awake': '清醒', 'asleep': '睡眠中', 'likely_asleep': '推测睡着', 'likely_awake': '推测清醒', 'unknown': '手环还没判出来'}


def stamp(text):
    if not isinstance(text, str):
        return None
    try:
        dt = datetime.fromisoformat(text.replace('Z', '+00:00'))
        return dt if dt.tzinfo else None
    except ValueError:
        return None


def seen(text, checked):
    dt = stamp(text)
    if not dt:
        return '采样时间未知'
    seconds = max(0, int((checked - dt).total_seconds()))
    age = (f'{seconds} 秒' if seconds < 60 else f'{seconds // 60} 分钟' if seconds < 3600
           else f'{seconds // 3600} 小时' if seconds < 86400 else f'{seconds // 86400} 天')
    return f'{dt.astimezone(LOCAL):%m-%d %H:%M}（{age}前）'


def display_app(value):
    # Labels only. Reject links, control characters and long free-form strings.
    if isinstance(value, str) and re.fullmatch(r'[\w\u3400-\u9fff .+&/()（）·-]{1,64}', value):
        return value
    return '应用名未提供'


def render(body):
    checked = stamp(body.get('asOf'))
    if not checked or 'mac' not in body or not isinstance(body.get('devices'), list):
        raise ValueError('Missing authorized snapshot')
    lines = [f'刷新于 {checked.astimezone(LOCAL):%Y-%m-%d %H:%M:%S}（北京时间）', '', '设备']
    for device in body['devices']:
        name = NAMES.get(device.get('deviceId'), '其他设备')
        fg = device.get('foreground') or {}
        state = fg.get('state')
        # A heartbeat and a foreground observation have independent timestamps.
        desc = STATES.get(state, '当前未知')
        if state == 'active':
            desc += ' · ' + display_app(fg.get('appName'))
            desktop = (body.get('mac') or {}).get('desktop') if fg.get('source') == 'mac_context' else None
            if type(desktop) is int and 1 <= desktop <= 128:
                desc += f' · 桌面 {desktop}'
        sample = fg.get('sampledAt')
        reports = seen(device.get('lastSeenAt'), checked)
        if sample and sample != device.get('lastSeenAt'):
            desc += '；前台采样 ' + seen(sample, checked)
        level = device.get('batteryPercent')
        if type(level) in (int,float) and 0 <= level <= 100:
            mode = '充电中' if device.get('charging') is True else (
                ('已充满，已插电' if level == 100 else '已插电') if device.get('powerSource') == 'ac' else '')
            last = stamp(device.get('batteryReportedAt'))
            old = device.get('reportStatus') != 'recent' or (last is not None and (checked-last).total_seconds()>300)
            desc += f" · {'上次电量' if old else '电量'} {level:g}%" + ('（' + mode + '）' if mode else '')
            if old and last:
                desc += ' ' + last.astimezone(LOCAL).strftime('%m-%d %H:%M')
        lines.append(f'{name}　{desc}；上次上报 {reports}')
    hr, steps = body.get('heartRate') or {}, body.get('stepsToday') or {}
    sleep, night = body.get('currentSleep') or {}, body.get('lastCompletedSleep') or {}
    lines += ['', '身体记录']
    bpm = hr.get('bpm')
    label = '最近一次心率' if hr.get('confidence') != 'fresh' else '心率'
    lines.append(f'{label}　{bpm:g} 次/分 · {seen(hr.get("sampledAt"), checked)}'
                 if type(bpm) in (float, int) and 0 < bpm <= 300 else '心率　尚无读数')
    count = steps.get('steps')
    lines.append(f'今日已记录 {count} 步 · 最近采样 {seen(steps.get("lastSampleAt"), checked)}'
                 if type(count) is int and count >= 0 else '今日步数　尚无记录')
    lines.append('此刻睡眠　' + SLEEP.get(sleep.get('state'), '手环还没判出来')
                 + ('（心率和步数符合以往睡眠记录）' if sleep.get('reason') == 'personal_sleep_pattern' else '（最近有连续步行）' if sleep.get('state') == 'likely_awake' else '')
                 + (' · ' + seen(sleep['sampledAt'], checked) if stamp(sleep.get('sampledAt')) else ''))
    begin, end = stamp(night.get('startedAt')), stamp(night.get('endedAt'))
    if begin and end:
        minutes = night.get('asleepMinutes')
        detail = (f'实睡 {minutes//60} 小时 {minutes%60} 分' if type(minutes) is int and minutes >= 0
                  else '实睡时长未上报')
        source = ('小米运动健康本机补录' if night.get('source') == 'mi_fitness_local_log' else
                  '实睡时长由小米运动健康补录' if night.get('asleepMinutesSource') == 'mi_fitness_local_log'
                  else '手环直连' if night.get('source') == 'hibi_band_direct' else '来源未标明')
        lines.append(f'上次睡眠　{begin.astimezone(LOCAL):%m-%d %H:%M} 至 {end.astimezone(LOCAL):%m-%d %H:%M} · {detail} · {source}')
    else:
        lines.append('上次睡眠　尚无完整报告')
    return '\n'.join(lines)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def request_json(url, token, *, patch=None, deadline):
    headers = {'Authorization': 'Bearer ' + token, 'Accept': 'application/json'}
    if url.startswith(NOTION):
        headers['Notion-Version'] = '2025-09-03'
    payload = None
    if patch is not None:
        headers['Content-Type'] = 'application/json'
        payload = json.dumps(patch, ensure_ascii=False).encode()
    req = Request(url, data=payload, headers=headers, method='GET' if patch is None else 'PATCH')
    remaining = deadline - time.monotonic()
    if remaining < 0.1:
        raise TimeoutError
    with build_opener(ProxyHandler({}), NoRedirect()).open(req, timeout=min(4, remaining)) as resp:
        raw = resp.read(256000)
        if len(raw) == 256000 or resp.status != 200:
            raise ValueError('Unexpected reply')
        return json.loads(raw)


def fixed_page(page):
    parent = page.get('parent', {})
    expected_parent = {'data_source_id': SOURCE, 'database_id': DATABASE}.get(parent.get('type'))
    return (page.get('id') == PAGE and page.get('object') == 'page' and
            'public_url' in page and page['public_url'] is None and
            page.get('archived') is False and not page.get('in_trash') and
            expected_parent and parent.get(parent['type']) == expected_parent)


def fixed_block(block, expected_id=None):
    expected_id = expected_id or BLOCK
    return (block.get('id') == expected_id and block.get('object') == 'block' and block.get('type') == 'paragraph'
            and block.get('archived') is False and not block.get('in_trash')
            and block.get('parent', {}).get('type') == 'page_id'
            and block['parent'].get('page_id') == PAGE)


def refresh(credentials):
    deadline = time.monotonic() + 10.5
    token = credentials['notion-token']
    page = request_json(NOTION + 'pages/' + PAGE, token, deadline=deadline)
    block_url = NOTION + 'blocks/' + BLOCK
    if not fixed_page(page) or not fixed_block(request_json(block_url, token, deadline=deadline)):
        raise ValueError('Target private page or paragraph changed')
    body = request_json(origin('HIBI_COLLECTOR_ORIGIN', internal=True)+'/api/agent/body-now', credentials['context-read-token'], deadline=deadline)
    summary = render(body)
    if len(summary) > 2000:
        raise ValueError('Snapshot exceeds paragraph capacity')
    result = request_json(block_url, token, patch={'paragraph': {'rich_text': [
        {'type': 'text', 'text': {'content': summary}},
        {'type': 'text', 'text': {'content': '\n刷新状态', 'link': {'url': origin('HIBI_CONSOLE_ORIGIN')+'/now'}}}
    ]}}, deadline=deadline)
    if not fixed_block(result) or (result['paragraph']['rich_text'][0].get('plain_text') != summary):
        raise ValueError('Write not confirmed')
    return {'pageId': PAGE, 'checkedAt': body['asOf']}


def show_browser_request(credentials, active, proof):
    """The link works only with the initiating browser's separate HttpOnly cookie."""
    deadline = time.monotonic() + 10
    token = credentials['notion-token']
    endpoint = NOTION + 'blocks/' + PAIR_BLOCK
    if not fixed_page(request_json(NOTION + 'pages/' + PAGE, token, deadline=deadline)):
        raise ValueError('Private page unavailable')
    if not fixed_block(request_json(endpoint, token, deadline=deadline), PAIR_BLOCK):
        raise ValueError('Browser request paragraph unavailable')
    now = time.monotonic()
    recent = [(p, t) for p, t in active if now - t < 300 and p != proof]
    recent.append((proof, now))
    recent = recent[-3:]
    rich = [{'type': 'text', 'text': {'content': '浏览器授权'}, 'annotations': {'bold': True}},
            {'type': 'text', 'text': {'content': '\n在刚才打开 Hibi 的浏览器中点下方链接。确认链接五分钟内有效。'}}]
    for value, _ in reversed(recent):
        rich.append({'type': 'text', 'text': {'content': '\n在刚才的浏览器显示身体数据 · ' + value[-4:],
                    'link': {'url': origin('HIBI_CONSOLE_ORIGIN')+'/#hibi-confirm=' + value}}})
    answer = request_json(endpoint, token, patch={'paragraph': {'rich_text': rich}}, deadline=deadline)
    if not fixed_block(answer, PAIR_BLOCK):
        raise ValueError('Notion did not confirm the update')
    active[:] = recent


class Bridge(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, value):
        data = json.dumps(value).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        if self.path not in ('/refresh', '/console-pair'):
            return self.reply(404, {})
        if self.headers.get('Transfer-Encoding'):
            return self.reply(400, {})
        provided = self.headers.get('Authorization', '').encode()
        expected = ('Bearer ' + self.server.credentials['bridge-token']).encode()
        if not hmac.compare_digest(provided, expected):
            return self.reply(401, {})
        if self.path == '/console-pair':
            size = self.headers.get('Content-Length', '')
            if not re.fullmatch(r'[0-9]{1,3}', size) or not 1 <= int(size) <= 256:
                return self.reply(400, {})
            try:
                data = json.loads(self.rfile.read(int(size)))
                if (not isinstance(data, dict) or set(data) != {'proof'} or not isinstance(data['proof'], str)
                        or not re.fullmatch(r'[A-Za-z0-9_-]{32,96}', data['proof'])):
                    return self.reply(400, {})
            except (ValueError, UnicodeError):
                return self.reply(400, {})
            try:
                show_browser_request(self.server.credentials, self.server.pairs, data['proof'])
            except (OSError, ValueError, KeyError):
                return self.reply(503, {})
            return self.reply(200, {'pageId': PAGE})
        if self.headers.get('Content-Length', '0') != '0':
            return self.reply(400, {})
        now = time.monotonic()
        if now < self.server.until:
            return self.reply(200 if self.server.receipt else 503, self.server.receipt or {})
        try:
            receipt = refresh(self.server.credentials)
        except Exception as exc:
            # Never log payloads, token values or detailed Notion error bodies.
            print('Refresh failed:', type(exc).__name__, getattr(exc, 'code', ''), file=sys.stderr, flush=True)
            self.server.receipt = None
            self.server.until = time.monotonic() + 20
            return self.reply(503, {})
        self.server.receipt = receipt
        # The public handler rejects receipts aged 45 seconds. Count from
        # refresh start, not from Notion's eventual write acknowledgement.
        self.server.until = now + 35
        self.reply(200, receipt)


class PrivateHTTPServer(UnixStreamServer):
    def get_request(self):
        sock, addr = super().get_request()
        sock.settimeout(4)
        return sock, addr


def main():
    global PAGE, SOURCE, DATABASE, BLOCK, PAIR_BLOCK
    PAGE, SOURCE, DATABASE, BLOCK, PAIR_BLOCK = (notion_id(key) for key in ('HIBI_NOTION_PAGE_ID', 'HIBI_NOTION_DATA_SOURCE_ID', 'HIBI_NOTION_DATABASE_ID', 'HIBI_NOTION_STATUS_BLOCK_ID', 'HIBI_NOTION_PAIR_BLOCK_ID'))
    origin("HIBI_CONSOLE_ORIGIN")
    origin("HIBI_COLLECTOR_ORIGIN", internal=True)
    directory = Path(os.environ['CREDENTIALS_DIRECTORY'])
    credentials = {k: (directory/k).read_text().strip() for k in ('bridge-token','context-read-token','notion-token')}
    if any(not 32 <= len(v) <= 512 or not v.isascii() or not v.isprintable() or ' ' in v for v in credentials.values()):
        raise ValueError('Invalid credential format')
    socket_path = Path('/run/hibi-now/refresh.sock')
    if socket_path.is_socket():
        socket_path.unlink()
    with PrivateHTTPServer(str(socket_path), Bridge) as server:
        socket_path.chmod(0o600)
        server.credentials, server.until, server.receipt, server.pairs = credentials, 0, None, []
        server.serve_forever()


if __name__ == '__main__':
    main()
