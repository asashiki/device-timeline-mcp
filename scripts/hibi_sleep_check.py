#!/usr/bin/env python3
"""Record an actual body-now response per five-minute slot for one specified night."""
import argparse
import fcntl
import json
import math
import os
from pathlib import Path
import sqlite3
import tempfile
from contextlib import closing
from datetime import datetime, timedelta, timezone
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener
from zoneinfo import ZoneInfo

from hibi_settings import HEALTH_DEVICE, origin

TZ = ZoneInfo('Asia/Shanghai')
UTC = timezone.utc

FIELDS = ('state', 'sampledAt', 'receivedAt', 'confidence', 'origin', 'timeBasis', 'sessionStart', 'reason')


def instant(value):
    if not isinstance(value, str):
        raise ValueError('Missing timestamp')
    d = datetime.fromisoformat(value.replace('Z','+00:00'))
    if d.tzinfo is None:
        raise ValueError('Timestamp requires time zone')
    return d.astimezone(UTC)


def iso(d):
    return d.astimezone(UTC).isoformat(timespec='milliseconds').replace('+00:00','Z')


def slot(d):
    return math.floor(d.timestamp()/300)


def local(value):
    return instant(value).astimezone(TZ).strftime('%m-%d %H:%M:%S') if value else '未知'


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *unused):
        return None


def measure(token_file, started, deadline):
    row = {'attemptedAt':iso(started)}
    try:
        token = token_file.read_text().strip()
        req = Request(origin("HIBI_COLLECTOR_ORIGIN", internal=True) + "/api/agent/body-now",headers={'Authorization':'Bearer '+token,'Accept':'application/json','Cache-Control':'no-cache'})
        with build_opener(ProxyHandler({}), NoRedirect()).open(req,timeout=8) as response:
            data=response.read(150001)
            if len(data)>150000:
                raise ValueError('Oversized response')
            body=json.loads(data)
        done=datetime.now(UTC)
        when=instant(body['asOf'])
        if not started-timedelta(seconds=2)<=when<=min(done+timedelta(seconds=2),deadline):
            raise ValueError('Snapshot clock mismatch')
        # The nightly check evaluates sensor evidence, including unknowns.
        sleep = dict(body['currentSleep'])
        sleep['state'] = sleep.get('evidenceState', sleep['state'])
        if body.get('deviceId') != HEALTH_DEVICE or sleep.get('state') not in {
                'asleep','likely_asleep','awake','likely_awake','unknown'}:
            raise ValueError('Unexpected snapshot')
        band_metrics = [body.get('heartRate') or {}, body.get('stepsToday') or {}]
        if sleep.get('origin') in {'band_basic_status', 'band_status_event', 'band_minute_steps',
                                   'band_sleep_report', 'band_live_progress', 'band_minute_pattern'}:
            band_metrics.append(sleep)
        last_receipt = max((instant(metric['receivedAt']) for metric in band_metrics
                            if metric.get('receivedAt')), default=None)
        return dict(row,asOf=iso(when),completedAt=iso(done),sleep={k:sleep.get(k) for k in FIELDS},
                    lastEvidenceReceivedAt=iso(last_receipt) if last_receipt else None)
    except (OSError,URLError,HTTPError,ValueError,TypeError,KeyError) as exc:
        row['failure']='HTTP '+str(exc.code) if isinstance(exc,HTTPError) else type(exc).__name__
        return row


def direct_sessions(database,start,through,deadline):
    # A previous night's import or later import supplement can never satisfy this check.
    # Read only original completed sessions received from the authenticated band path.
    if through < start:
        return []
    with closing(sqlite3.connect(database.resolve().as_uri()+'?mode=ro',uri=True,timeout=4)) as db:
        db.execute('PRAGMA query_only=ON')
        rows=db.execute("""SELECT started_at,ended_at,received_at,asleep_minutes FROM band_sleep
                       WHERE device_id=? AND started_at>=? AND started_at<?
                       AND ended_at<=? AND received_at<=? AND received_at>=ended_at
                       AND ended_at>started_at ORDER BY started_at""",
                        (HEALTH_DEVICE,iso(start),iso(deadline),iso(through),iso(through)))
        return [dict(startedAt=a,endedAt=b,receivedAt=r,asleepMinutes=m) for a,b,r,m in rows
                if instant(b).astimezone(TZ).date()==deadline.astimezone(TZ).date()]


def hit(row,start,end):
    observed=instant(row['asOf'])
    data=row['sleep']
    state=data['state']
    if state not in ('asleep','likely_asleep') or data.get('confidence')!='fresh':
        return None
    try:
        sample=instant(data['sampledAt'])
        receipt=instant(data['receivedAt'])
    except (TypeError,ValueError):
        return None
    if not start<=sample<=observed<end or observed-sample>timedelta(minutes=5) or receipt>observed:
        return None
    if state=='asleep':
        return state if data.get('origin') in ('band_basic_status','band_status_event') and data.get('timeBasis')=='phone_observed' else None
    if (data.get('reason') == 'personal_sleep_pattern' and data.get('origin') == 'band_minute_pattern'
            and data.get('timeBasis') == 'device_sample'):
        return 'likely_asleep'
    if (data.get('origin'), data.get('timeBasis')) not in (
            ('band_sleep_report','device_sample'),('band_sleep_record','device_sample'),('band_live_progress','phone_observed_growth')):
        return None
    try:
        reported_start=instant(data['sessionStart'])
    except (TypeError,ValueError):
        return None
    # A previous session that is still present on the phone must not qualify.
    return state if abs((reported_start-start).total_seconds())<=300 else None


def interval_result(rows,session):
    start=instant(session['startedAt'])
    end=instant(session['endedAt'])
    inside=[r for r in rows if start<=instant(r.get('asOf') or r['attemptedAt'])<end]
    good=[r for r in inside if 'asOf' in r]
    # These are nominal timer opportunities inside the interval, not a
    # fraction of minutes during which the user was independently known asleep.
    scheduled=set(range(math.ceil(start.timestamp()/300),math.ceil(end.timestamp()/300)))
    attempted={slot(instant(r['attemptedAt'])) for r in inside}
    missed=len(scheduled-attempted)
    failed=len(inside)-len(good)
    # A successful API query can still lack recent upstream band deliveries.
    # This is an evidence gap; it cannot identify a Bluetooth/network failure.
    no_recent_band=sum(not r.get('lastEvidenceReceivedAt') or
                       instant(r['asOf'])-instant(r['lastEvidenceReceivedAt'])>timedelta(minutes=15)
                       for r in good)
    exact=[]; inferred=[]
    for row in good:
        match=hit(row,start,end)
        if match=='asleep': exact.append(row['asOf'])
        if match=='likely_asleep': inferred.append(row['asOf'])
    if exact:
        verdict='当时明确睡着'
    elif inferred:
        verdict='当时推测睡眠'
    elif scheduled and good and not (failed or missed or no_recent_band):
        verdict='只在事后收到'
    else:
        verdict='未可验证'
    following = [r for r in rows if 'asOf' in r and end <= instant(r['asOf']) < end + timedelta(minutes=30)]
    return dict(session,verdict=verdict,scheduledChecks=len(scheduled),successfulChecks=len(good),
                afterEndChecks=len(following),
                afterEndStillAsleep=sum(r['sleep']['state'] in ('asleep','likely_asleep') for r in following),
                failedRequests=failed,missingChecks=missed,explicitHits=len(exact),inferredHits=len(inferred),
                noRecentBandReceiptChecks=no_recent_band,
                firstExplicitAt=min(exact,default=None),firstInferredAt=min(inferred,default=None),
                firstHitAt=min(exact+inferred,default=None),
                unknownChecks=sum(r['sleep']['state']=='unknown' for r in good),
                awakeChecks=sum(r['sleep']['state'] in ('awake','likely_awake') for r in good))


def evaluate(rows,sessions,start,deadline,now):
    through=min(now,deadline)
    within=sorted((r for r in rows if start<=instant(r['attemptedAt'])<=through),key=lambda r:r['attemptedAt'])
    periods=[interval_result(within,s) for s in sessions]
    expected=set(range(math.ceil(start.timestamp()/300),math.ceil(through.timestamp()/300))) if through>start else set()
    attempts={slot(instant(r['attemptedAt'])) for r in within}
    observed=[r for r in within if 'asOf' in r]
    gaps=sum('failure' in r for r in within)
    candidates = [r for r in observed if r['sleep'].get('state') in ('asleep', 'likely_asleep')
                  and r['sleep'].get('confidence') == 'fresh']
    primary=max(periods,key=lambda s:instant(s['endedAt'])-instant(s['startedAt']),default=None)
    return dict(start=iso(start),deadline=iso(deadline),checkedThrough=iso(through),closed=now>=deadline,
                verdict=primary['verdict'] if primary else '未可验证',sessions=periods,
                primarySessionStart=primary['startedAt'] if primary else None,
                firstLiveCandidate=({'at': candidates[0]['asOf'], 'state': candidates[0]['sleep']['state'],
                                     'reason': candidates[0]['sleep'].get('reason')} if candidates else None),
                successfulChecks=len(observed),failedRequests=gaps,missingChecks=len(expected-attempts),
                lastSuccessfulAt=observed[-1]['asOf'] if observed else None)


def readable(result):
    lines=[f"睡眠实时验收（北京时间），截至 {local(result['checkedThrough'])}。"]
    if not result['sessions']:
        lines.append('未可验证。尚未收到此次睡眠的手环直连完整时段。')
    for period in result['sessions']:
        lines.append(f"{local(period['startedAt'])} 至 {local(period['endedAt'])}，{period['verdict']}。"
                     f"手环报告于 {local(period['receivedAt'])} 送达。"
                     f"睡眠区间内成功查询 {period['successfulChecks']} 次，明确睡着 {period['explicitHits']} 次、推测睡眠 {period['inferredHits']} 次。")
        if period['firstHitAt']:
            lines.append(f"首次实时命中 {local(period['firstHitAt'])}。")
        if period['failedRequests'] or period['missingChecks']:
            lines.append(f"区间内请求失败 {period['failedRequests']} 次，缺少定点查询 {period['missingChecks']} 次。")
        if period['noRecentBandReceiptChecks']:
            lines.append(f"其中 {period['noRecentBandReceiptChecks']} 次查询时，过去 15 分钟没有可核对的手环数据送达时间。")
    lines.append(f"整个窗口成功查询 {result['successfulChecks']} 次，请求失败 {result['failedRequests']} 次，缺少定点查询 {result['missingChecks']} 次。")
    if result['closed']:
        lines.append('本次观察窗口已结束。')
    return '\n'.join(lines)


def atomic(path,value):
    with tempfile.NamedTemporaryFile('w',dir=path.parent,delete=False,encoding='utf8') as f:
        try:
            json.dump(value,f,ensure_ascii=False,separators=(',',':'))
            f.flush()
            os.fsync(f.fileno())
            os.replace(f.name,path)
        finally:
            Path(f.name).unlink(missing_ok=True)
    fd=os.open(path.parent,os.O_RDONLY)
    try: os.fsync(fd)
    finally: os.close(fd)


def run(args):
    start,deadline=args.start,args.deadline
    if not timedelta(0)<deadline-start<=timedelta(hours=36):
        raise ValueError('Use a finite observation window of at most 36 hours')
    args.state_dir.mkdir(parents=True,exist_ok=True,mode=0o700)
    path=args.state_dir/'sleep-check.json'
    with (args.state_dir/'sleep-check.lock').open('a') as guard:
        fcntl.flock(guard.fileno(),fcntl.LOCK_EX)
        state=json.loads(path.read_text()) if path.exists() else dict(start=iso(start),deadline=iso(deadline),observations={})
        if (state['start'],state['deadline'])!=(iso(start),iso(deadline)):
            raise ValueError('State belongs to another observation window')
        if state.get('final'):
            return state['final']
        now=datetime.now(UTC)
        key=str(slot(now))
        if not args.report and start<=now<deadline and key not in state['observations']:
            state['observations'][key]=measure(args.token_file,now,deadline)
            atomic(path,state)
        now=datetime.now(UTC)
        sessions=direct_sessions(args.db,start,min(now,deadline),deadline)
        result=evaluate(state['observations'].values(),sessions,start,deadline,now)
        if not args.report and not state.get('final') and now>=deadline:
            state['final']=result
            atomic(path,state)
        return state.get('final',result)


if __name__=='__main__':
    os.umask(0o077)
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--start',type=instant,required=True)
    parser.add_argument('--deadline',type=instant,required=True)
    parser.add_argument('--state-dir',type=Path,required=True)
    parser.add_argument('--db',type=Path,required=True)
    parser.add_argument('--token-file',type=Path,default=Path(os.environ.get('CREDENTIALS_DIRECTORY','/etc/hibi'))/'context-read-token')
    parser.add_argument('--report',action='store_true')
    parser.add_argument('--json',action='store_true')
    args=parser.parse_args()
    report=run(args)
    print(json.dumps(report,ensure_ascii=False) if args.json else readable(report))
