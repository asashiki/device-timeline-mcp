"""Collect one scheduled sleep check and publish the first result or a meaningful change."""
import argparse
import json
import os
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.request import Request, build_opener, ProxyHandler

from hibi_sleep_check import NoRedirect, TZ, atomic, instant, local, run
from hibi_settings import notion_id

def nightly_window(now, start_hour=20, end_hour=16):
    if not 0 <= end_hour < start_hour <= 23:
        raise ValueError('A nightly window needs 0 <= end hour < start hour <= 23')
    here = now.astimezone(TZ)
    begin = here.replace(hour=start_hour, minute=0, second=0, microsecond=0)
    if here < begin:
        begin -= timedelta(days=1)
    end = (begin + timedelta(days=1)).replace(hour=end_hour)
    return begin.astimezone(timezone.utc), end.astimezone(timezone.utc)


def message(result):
    if not result['sessions']:
        candidate = result.get('firstLiveCandidate')
        if candidate:
            label = '手环报告睡着' if candidate['state'] == 'asleep' else 'Hibi 推测睡着'
            return (f"{local(candidate['at'])}，首次实时查到“{label}”。"
                    + ('截至观察结束，仍缺少这次睡眠的完整报告作对照。' if result['closed'] else
                       '等待手环的整段记录核对入睡与醒来的时间。'))
        if result['closed']:
            return f"截至 {local(result['deadline'])}，手环仍未传回这次睡眠的完整区间，实时识别未能验收。"
        if result['successfulChecks']:
            return 'VPS 已经开始每五分钟实查。手环传回完整睡眠后，这里会自动显示入睡期间的实际结果。'
        return 'VPS 已开始查询，正在等待首次有效回执。'
    lines = []
    for period in result['sessions']:
        label = {'当时明确睡着': '手环在睡眠期间明确报告了睡着',
                 '当时推测睡眠': 'Hibi 在睡眠期间根据手环数据推测已睡着',
                 '只在事后收到': '睡眠期间的查询均未识别出睡着，醒后收到了睡眠记录',
                 '未可验证': '睡眠期间的记录不足，实时识别未能验收'}[period['verdict']]
        lines.append(f"{local(period['startedAt'])} 至 {local(period['endedAt'])}。{label}。")
        lines.append(f"期间查到明确睡着 {period['explicitHits']} 次，推测睡着 {period['inferredHits']} 次，成功查询 {period['successfulChecks']} 次。")
        if result['closed'] and period.get('afterEndChecks'):
            lines.append(f"手环所记醒来时间之后的半小时内，共查 {period['afterEndChecks']} 次，其中 {period['afterEndStillAsleep']} 次仍显示睡着或推测睡着。")
        if period['firstHitAt']:
            lines.append(f"首次查到睡着的时间为 {local(period['firstHitAt'])}。")
        if period['failedRequests'] or period['missingChecks'] or period['noRecentBandReceiptChecks']:
            lines.append(f"请求失败 {period['failedRequests']} 次，漏查 {period['missingChecks']} 次，有 {period['noRecentBandReceiptChecks']} 次缺少近期手环回传。")
    return '\n'.join(lines)


def update_block(token, body, title):
    page_id = notion_id('HIBI_NOTION_PAGE_ID')
    block_id = notion_id('HIBI_NOTION_SLEEP_BLOCK_ID')
    source_id = notion_id('HIBI_NOTION_DATA_SOURCE_ID')
    database_id = notion_id('HIBI_NOTION_DATABASE_ID')
    api = build_opener(ProxyHandler({}), NoRedirect())
    headers = {'Authorization': 'Bearer '+token, 'Content-Type': 'application/json', 'Notion-Version': '2025-09-03'}

    def call(resource, data=None):
        req = Request('https://api.notion.com/v1/'+resource, headers=headers,
                      method='GET' if data is None else 'PATCH', data=None if data is None else json.dumps(data).encode())
        with api.open(req, timeout=12) as response:
            return json.load(response)

    page = call('pages/'+page_id)
    parent = page.get('parent', {})
    expected = {'data_source_id': source_id, 'database_id': database_id}.get(parent.get('type'))
    if (page.get('id') != page_id or not expected or parent.get(parent['type']) != expected
            or page.get('public_url', True) is not None or page.get('archived') or page.get('in_trash')):
        raise ValueError('Unexpected or non-private Notion page')
    block = call('blocks/'+block_id)
    if (block.get('id') != block_id or block.get('type') != 'paragraph' or block.get('archived')
            or block.get('in_trash') or block.get('parent',{}).get('page_id') != page_id):
        raise ValueError('Unexpected Notion paragraph')
    text = '\n'+body
    parts = [{'type':'text','text':{'content':title},'annotations':{'bold':True}}]
    parts.extend({'type':'text','text':{'content':text[i:i+900]}} for i in range(0,len(text),900))
    ack = call('blocks/'+block_id, {'paragraph':{'rich_text':parts}})
    if ack.get('id') != block_id or ack.get('parent',{}).get('page_id') != page_id:
        raise ValueError('Unexpected Notion acknowledgement')


if __name__ == '__main__':
    os.umask(0o077)
    parser=argparse.ArgumentParser()
    parser.add_argument('--start',type=instant)
    parser.add_argument('--deadline',type=instant)
    parser.add_argument('--nightly',action='store_true',help='Use a distinct Shanghai evening window each night')
    parser.add_argument('--state-dir',type=Path,required=True)
    parser.add_argument('--db',type=Path,required=True)
    args=parser.parse_args()
    if args.nightly:
        if args.start or args.deadline:
            parser.error('Choose --nightly or an explicit --start and --deadline')
        args.start, args.deadline = nightly_window(datetime.now(timezone.utc),
            int(os.getenv('HIBI_SLEEP_START_HOUR',20)),int(os.getenv('HIBI_SLEEP_END_HOUR',16)))
        args.state_dir = args.state_dir / args.start.astimezone(TZ).date().isoformat()
    elif not args.start or not args.deadline:
        parser.error('Set --start and --deadline or choose --nightly')
    creds=Path(os.environ['CREDENTIALS_DIRECTORY'])
    args.token_file=creds/'context-read-token'
    args.report=False
    result=run(args)
    text=message(result)
    if result['closed']:
        text+=f"\n实测已结束，共有 {result['successfulChecks']} 次成功查询。"
    cache=args.state_dir/'published.json'
    if not cache.exists() or text!=json.loads(cache.read_text()):
        update_block((creds/'notion-token').read_text().strip(),text, args.start.astimezone(TZ).strftime('%Y-%m-%d 晚 · 睡眠识别实测'))
        atomic(cache,text)
    print('Observations:',result['successfulChecks'],'Closed:',result['closed'],'Verdict:',result['verdict'])
