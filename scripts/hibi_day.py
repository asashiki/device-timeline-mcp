#!/usr/bin/env python3
"""Daily Hibi facts from read-only SQLite. All dates use Asia/Shanghai.

activeMinutes counts recorded foreground intervals. idleMinutes includes explicit
AFK/idle markers. Gaps, unclosed intervals and questionable durations are not
extrapolated. Device totals can overlap. No window titles or app names are read.
"""
import argparse
import json
import sqlite3
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

LOCAL = ZoneInfo('Asia/Shanghai')
UTC = timezone.utc
IOS_STALE = timedelta(hours=2)  # Same cutoff as Repository.iosAppEvent().
IDLE = {'macos.afk', 'macos.idle', 'windows.afk', 'windows.idle', 'android.afk', 'android.idle'}


def parse_stamp(value):
    return datetime.fromisoformat(value.replace('Z', '+00:00')).astimezone(UTC)


def utc_string(stamp):
    return stamp.astimezone(UTC).isoformat(timespec='milliseconds').replace('+00:00', 'Z')


def union_seconds(intervals):
    result = 0.0
    previous_end = None
    for start, end in sorted(intervals):
        left = max(start, previous_end) if previous_end else start
        result += max(0.0, (end - left).total_seconds())
        previous_end = max(left, end)
    return result


def activity_evidence(begin, finish, as_of, platform, end_source):
    if finish is None or finish > as_of:
        return 'open'
    if finish <= begin:
        return 'point' if finish == begin else 'invalid'
    if platform == 'ios':
        # A named close matching the open is retained even for a long session.
        # Older rows and a subsequent open provide event times, not active minutes.
        return 'interval' if end_source == 'ios_named_close' else 'unmatched'
    return 'long' if finish - begin > timedelta(days=1) else 'interval'


def day_facts(db, day, as_of):
    """Give a date, an aware as_of datetime and a connection with sqlite3.Row factory."""
    start = datetime.combine(day, time.min, LOCAL).astimezone(UTC)
    end = min(start + timedelta(days=1), as_of.astimezone(UTC))
    if end <= start:
        raise ValueError(f'{day} has not started by the requested as-of time')
    lo, hi = map(utc_string, (start, end))
    device_list = db.execute('SELECT device_id, platform, last_seen_at FROM device_states ORDER BY device_id').fetchall()
    platforms = {d['device_id']: d['platform'] for d in device_list}
    latest_heartbeat = {d['device_id']: d['last_seen_at'] for d in device_list}
    buckets = {}

    def bucket(device_id):
        return buckets.setdefault(device_id, dict(active=[], idle=[], uncertain=0, points=0,
                                                   rows=0, evidence=False, foregroundPoint=False,
                                                   uncertainForeground=False))

    for device_id in platforms:
        bucket(device_id)
    # Use the indexed start times. A previous-day row is relevant only if it has
    # an end on/after the new day. Never extend a NULL end into later days.
    columns = {r[1] for r in db.execute('PRAGMA table_info(device_activities)')}
    provenance = ("CASE WHEN json_valid(extra_json) THEN json_extract(extra_json,'$.endSource') END"
                  if 'extra_json' in columns else 'NULL')
    idle_params = ','.join('?' for _ in IDLE)
    rows = db.execute(f"""SELECT device_id, app_id IN ({idle_params}) AS idle, started_at, ended_at,
        {provenance} AS end_source FROM device_activities
        WHERE started_at < ? AND (started_at >= ? OR ended_at > ?)""", (*sorted(IDLE), hi, lo, lo))
    for row in rows:
        device_id = row['device_id']
        state = bucket(device_id)
        begin = parse_stamp(row['started_at'])
        finish = parse_stamp(row['ended_at']) if row['ended_at'] else None
        idle = bool(row['idle'])
        kind = activity_evidence(begin, finish, as_of, platforms.get(device_id), row['end_source'])
        start_observed = start <= begin < end
        end_observed = finish is not None and finish <= as_of and start <= finish < end
        if kind != 'interval' and not (start_observed or end_observed):
            continue
        state['rows'] += 1
        if start_observed or end_observed:
            state['evidence'] = True
        if kind != 'interval':
            state['points'] += 1
            state['foregroundPoint'] |= not idle
            state['uncertain'] += int(kind != 'point')
            continue
        inside = max(start, begin), min(end, finish)
        if inside[0] < inside[1]:
            state['idle' if idle else 'active'].append(inside)
            state['evidence'] = True

    result = {}
    total = 0.0
    has_duration = False
    for device_id, state in sorted(buckets.items()):
        active = union_seconds(state['active'])
        coverage = union_seconds(state['active'] + state['idle'])
        idle = coverage - active  # Overlapping foreground/idle must count only once.
        duration_known = active > 0 or (idle > 0 and not state['foregroundPoint'] and not state['uncertainForeground'])
        heartbeat = latest_heartbeat.get(device_id)
        seen_today = bool(heartbeat and lo <= heartbeat < hi)
        result[device_id] = dict(activeMinutes=round(active / 60, 1) if duration_known else None,
                                 idleMinutes=round(idle / 60, 1) if coverage else None,
                                 coveredMinutes=round(coverage / 60, 1), rows=state['rows'],
                                 pointRows=state['points'], uncertainRows=state['uncertain'],
                                 observed=state['evidence'] or seen_today)
        has_duration |= duration_known
        total += active

    have_band = db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='band_samples'").fetchone()
    args = dict(lo=lo, hi=hi, at=utc_string(as_of))
    # Old exported databases may not include ingestion timestamps.
    health_columns = {r[1] for r in db.execute('PRAGMA table_info(device_health)')}
    band_columns = {r[1] for r in db.execute('PRAGMA table_info(band_samples)')}
    health_cutoff = ' AND created_at<=:at' if 'created_at' in health_columns else ''
    band_cutoff = ' AND received_at<=:at' if 'received_at' in band_columns else ''
    source = """SELECT device_id,type,unit,value,recorded_at,0 AS direct FROM device_health
                WHERE recorded_at>=:lo AND recorded_at<:hi""" + health_cutoff
    if have_band:
        source += f""" AND NOT EXISTS (SELECT 1 FROM band_samples b
                WHERE b.device_id=device_health.device_id AND b.type=device_health.type
                AND b.recorded_at=device_health.recorded_at {band_cutoff})
            UNION ALL SELECT device_id,type,CASE type WHEN 'heart_rate' THEN 'bpm' ELSE 'count' END,
                             value,recorded_at,1 FROM band_samples
            WHERE recorded_at>=:lo AND recorded_at<:hi {band_cutoff}"""
    health_rows = db.execute(f"""SELECT type,unit,COUNT(*) n,COUNT(value) n_values,MAX(recorded_at) last_at,
              COUNT(DISTINCT CASE WHEN direct THEN SUBSTR(recorded_at,1,16) END) direct_minutes,
              SUM(CASE WHEN direct THEN value END) direct_total,SUM(value) total,MIN(value) least,MAX(value) greatest,
              COUNT(DISTINCT device_id) n_devices FROM ({source}) GROUP BY type,unit""",args).fetchall()
    last_sample = db.execute('SELECT MAX(recorded_at) FROM device_health WHERE recorded_at<:hi'+health_cutoff,args).fetchone()[0]
    if have_band:
        band_last = db.execute('SELECT MAX(recorded_at) FROM band_samples WHERE recorded_at<:hi'+band_cutoff,args).fetchone()[0]
        last_sample = max((v for v in (last_sample, band_last) if v), default=None)
    health = dict(samples=sum(r['n'] for r in health_rows), lastSampleAt=last_sample)
    if db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='band_sleep'").fetchone():
        # Assign a complete sleep session to its waking date. Display separate
        # sessions (including naps); do not infer sleep stages or time actually asleep.
        has_imports = db.execute("SELECT 1 FROM sqlite_master WHERE type='view' AND name='private_completed_sleep'").fetchone()
        sleep_source = ('private_completed_sleep' if has_imports else
                        "(SELECT *, 'hibi_band_direct' AS source, NULL AS superseded_at, NULL AS import_minutes, "
                        "NULL AS import_received_at FROM band_sleep)")
        if db.execute("SELECT 1 FROM sqlite_master WHERE type='view' AND name='private_sleep_history'").fetchone():
            sleep_source='private_sleep_history'
        # Query as of the snapshot time, not the end of the requested calendar
        # day. A later backfill of yesterday's sleep belongs to yesterday.
        as_of_iso = utc_string(as_of)
        eligible = 'received_at <= ? AND (superseded_at IS NULL OR superseded_at > ?)'
        sleeps = db.execute(f'''SELECT started_at, ended_at, asleep_minutes,source,
                                 CASE WHEN import_received_at <= ? THEN import_minutes END AS supplement
                               FROM {sleep_source}
                               WHERE ended_at >= ? AND ended_at < ? AND {eligible} ORDER BY started_at''',
                            (as_of_iso, lo, hi, as_of_iso, as_of_iso)).fetchall()
        if sleeps:
            sessions = []
            for row in sleeps:
                supplement = row['asleep_minutes'] is None and row['supplement'] is not None
                sessions.append(dict(start=row['started_at'], end=row['ended_at'],
                                     asleepMinutes=row['supplement'] if supplement else row['asleep_minutes'],
                                     source=row['source'], asleepMinutesSource='mi_fitness_local_log' if supplement else row['source']))
            health['sleepSessions'] = sessions
            health['samples'] += len(sleeps)
        end_sleep = db.execute(f'SELECT MAX(ended_at) FROM {sleep_source} WHERE ended_at < ? AND {eligible}',
                               (hi, as_of_iso, as_of_iso)).fetchone()[0]
        if end_sleep:
            health['lastSampleAt'] = max(health['lastSampleAt'] or '', end_sleep)
    metric_coverage = {}
    for stat in health_rows:
        if not stat['n_values'] or stat['n_values'] != stat['n']:
            continue
        if (stat['type'], stat['unit']) == ('heart_rate', 'bpm') and stat['least'] > 0:
            health['heartRateRangeBpm'] = [stat['least'], stat['greatest']]
            metric_coverage['heartRateLastAt'] = stat['last_at']
        if (stat['type'], stat['unit']) == ('steps', 'count') and stat['n_devices'] == 1 and stat['least'] >= 0:
            # Steps samples are stored by start date; input has no interval end or
            # original Health Connect source. This is a *reported* sum only.
            # Health Connect supplies aggregates without interval ends. Mixing them
            # with direct minute increments can count the same steps twice.
            health['reportedSteps'] = int(stat['direct_total'] if stat['direct_minutes'] else stat['total'])
            if stat['direct_minutes']:
                metric_coverage['stepSampleMinutes'] = stat['direct_minutes']

    return dict(date=day.isoformat(), through=end.astimezone(LOCAL).isoformat(timespec='seconds'),
                activeMinutes=round(total / 60, 1) if has_duration else None,
                deviceCount=sum(d['observed'] for d in result.values()), devices=result, health=health,
                metricCoverage=metric_coverage)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--db', type=Path, required=True)
    parser.add_argument('--as-of', type=parse_stamp, default=None, help='snapshot time, ISO 8601 with UTC offset')
    parser.add_argument('days', metavar='YYYY-MM-DD', type=date.fromisoformat, nargs='+')
    args = parser.parse_args()
    as_of = args.as_of or datetime.now(UTC)
    db = sqlite3.connect(args.db.resolve().as_uri() + '?mode=ro', uri=True, timeout=5)
    db.row_factory = sqlite3.Row
    try:
        db.execute('PRAGMA query_only = ON')
        db.execute('BEGIN')
        facts = [day_facts(db, day, as_of) for day in sorted(set(args.days))]
    finally:
        db.close()
    print(json.dumps(facts, ensure_ascii=False, separators=(',', ':'), allow_nan=False))


if __name__ == '__main__':
    main()
