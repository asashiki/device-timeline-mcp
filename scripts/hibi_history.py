"""Identify Shanghai dates affected by source data, including in-place corrections and deletions."""
import hashlib
import json
from datetime import date

from hibi_day import utc_string

EMPTY = 'empty'
# Increment after changing the interpretation of saved observations.
FORMAT = b'hibi-daily-timeline-history-v2-hour-toggles\n'


def fingerprints(db, checked):
    tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    result = {}
    through = utc_string(checked)
    specs = [
        ('device_activities', 'started_at', 'ended_at', ['device_id','app_id','started_at','ended_at'], 'id'),
        ('device_health', 'recorded_at', None, ['device_id','type','unit','value','recorded_at'], 'id'),
        ('band_samples', 'recorded_at', None, ['device_id','type','value','recorded_at'], 'device_id,type,recorded_at'),
        ('band_sleep_intervals', 'started_at', 'ended_at', ['device_id','started_at','ended_at','received_at'], 'device_id,started_at'),
        ('private_sleep_estimates', 'started_at', 'ended_at', ['device_id','started_at','ended_at','received_at'], 'device_id,started_at'),
        ('band_sleep', 'started_at', 'ended_at', ['device_id','started_at','ended_at','asleep_minutes'], 'device_id,started_at'),
        ('historical_sleep_imports', 'started_at', 'ended_at', ['device_id','started_at','ended_at','asleep_minutes','source'], 'device_id,started_at'),
        ('device_states', 'last_seen_at', None, ['device_id','last_seen_at','device_name','platform'], 'device_id'),
    ]
    for table, start, end, main_fields, order in specs:
        if table not in tables:
            continue
        columns = {r[1] for r in db.execute(f'PRAGMA table_info({table})')}
        if not set(main_fields).issubset(columns):
            continue
        fields = list(main_fields)
        if table == 'device_activities' and 'extra_json' in columns:
            fields.extend(f"CASE WHEN json_valid(extra_json) THEN json_extract(extra_json,'$.{key}') END"
                          for key in ('endSource','startSource','custom_app_name'))
        # Retain receipt timing for direct/import sleep precedence and corrections,
        # without copying any personal observations into the checkpoint.
        if table in ('band_sleep','historical_sleep_imports') and 'received_at' in columns:
            fields.append('received_at')
        if order == 'id' and 'id' not in columns:
            order = 'rowid'
        dates = f"date({start}, '+8 hours')"
        dates += f", CASE WHEN {end} <= :at THEN date({end}, '+8 hours') END" if end else ', NULL'
        query = f"SELECT {dates}, {','.join(fields)} FROM {table} WHERE {start} <= :at ORDER BY {order}"
        table_tag = table.encode() + b':'
        for first, last, *values in db.execute(query, {'at': through}):
            encoded = table_tag + json.dumps(values,ensure_ascii=False,separators=(',',':')).encode() + b'\n'
            # Device/sleep display rules treat >24-hour intervals as endpoint
            # observations. Legitimate intervals touch at most two calendar days.
            for key in (first, last if last != first else None):
                if key:
                    if key not in result:
                        result[key] = hashlib.sha256(FORMAT)
                    result[key].update(encoded)
    return {key: value.hexdigest() for key,value in result.items()}


def changed_dates(current, confirmed):
    return sorted((date.fromisoformat(k) for k in current.keys() | confirmed.keys()
                   if current.get(k, EMPTY) != confirmed.get(k)), reverse=True)
