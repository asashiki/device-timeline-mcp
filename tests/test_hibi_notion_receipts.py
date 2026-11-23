"""Regression: late uploads outside eight calendar days eventually reach their original Notion rows."""
import contextlib
import io
import json
import sqlite3
import sys
import tempfile
import unittest
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import hibi_notion as sync


class LongDisconnectionTest(unittest.TestCase):
    def test_corrected_wake_across_midnight_rechecks_old_and_new_day(self):
        now = datetime(2021, 11, 23, 14, tzinfo=timezone.utc)
        with contextlib.closing(sqlite3.connect(':memory:')) as db:
            db.executescript("""
                CREATE TABLE band_sleep(started_at TEXT, ended_at TEXT, received_at TEXT);
                CREATE TABLE historical_sleep_imports(started_at TEXT, ended_at TEXT, received_at TEXT);
                INSERT INTO band_sleep VALUES('2021-09-01T14:00:00.000Z','2021-09-01T16:04:00.000Z','2021-11-23T13:00:00.000Z');
                INSERT INTO historical_sleep_imports VALUES('2021-09-04T14:00:00.000Z','2021-09-04T15:00:00.000Z','2021-11-23T13:00:00.000Z');
            """)
            days = sync.receipt_days(db, now-timedelta(hours=2), now)
            self.assertEqual(days, {date(2021,9,n) for n in (1,2,4,5)})

    def test_late_receipts_budget_retry_and_manual_rows(self):
        with tempfile.TemporaryDirectory() as temp, contextlib.redirect_stdout(io.StringIO()):
            folder = Path(temp)
            requested = []
            first = datetime(2021, 11, 23, 14, 0, tzinfo=timezone.utc)
            class Clock(datetime):
                current = first
                @classmethod
                def now(cls, tz=None):
                    return cls.current.astimezone(tz)
            args = SimpleNamespace(db=folder / 'facts.sqlite', state_dir=folder/'state',
                                   as_of=None, dry_run=False, days=[], source='00000000-0000-4000-8000-000000000001', token_file=folder/'token')
            args.token_file.write_text('test')
            source_dir = args.state_dir / '00000000-0000-4000-8000-000000000001'
            state = source_dir/'receipts.json'
            stamp = lambda instant: instant.isoformat(timespec='milliseconds').replace('+00:00','Z')
            api = SimpleNamespace(request=lambda *a: {'properties': {k: {'type':v} for k,v in sync.SCHEMA.items()}})
            with contextlib.closing(sqlite3.connect(args.db)) as db:
                db.executescript("""
                    CREATE TABLE device_activities(device_id TEXT, app_id TEXT, started_at TEXT, ended_at TEXT);
                    CREATE TABLE band_samples(recorded_at TEXT, received_at TEXT);
                    CREATE TABLE band_sleep(started_at TEXT, ended_at TEXT, received_at TEXT);
                    CREATE TABLE historical_sleep_imports(started_at TEXT, ended_at TEXT, received_at TEXT);
                """)
                old = [date(2021, 9, n) for n in range(1, 12)]
                received = stamp(first - timedelta(hours=1))
                last = stamp(first - timedelta(days=3))
                # Alternating hand-recorded minutes, direct sleep and log backfill
                # wake at 00:15 Shanghai. Identical receipt stamps cross the budget.
                utcday = lambda d: stamp(datetime(d.year,d.month,d.day,16,15,tzinfo=timezone.utc) - timedelta(days=1))
                for n, day in enumerate(old):
                    table = ('band_samples','band_sleep','historical_sleep_imports')[n % 3]
                    if table == 'band_samples':
                        db.execute('INSERT INTO band_samples VALUES(?,?)',(utcday(day),received))
                    else:
                        end = datetime.fromisoformat(utcday(day).replace('Z','+00:00'))
                        db.execute(f'INSERT INTO {table} VALUES(?,?,?)',(stamp(end-timedelta(hours=6)),stamp(end),received))
                db.execute('INSERT INTO band_sleep VALUES(?,?,?)',(utcday(old[1]),utcday(old[2]),received))
                db.execute('INSERT INTO band_samples VALUES(?,?)',(utcday(date(2021,8,1)),last))
                db.commit()

            def write(api, source, flat, guard):
                requested.append(flat['日期'])
                return 'updated'

            def snapshot(conn, day, checked):
                return {'properties': {'日期': day.isoformat()}}

            with patch.object(sync,'timeline_sections',return_value=[]), patch.object(sync,'datetime',Clock),patch.object(sync,'Notion',return_value=api),\
                 patch.object(sync,'day_facts',side_effect=snapshot),patch.object(sync,'mcp_row',side_effect=lambda f,*a:f):
                with patch.object(sync,'save_day',side_effect=sync.SyncError('offline')):
                    with self.assertRaisesRegex(sync.SyncError,'offline'):
                        sync.run(args)
                    self.assertFalse(state.exists())
                    self.assertTrue((source_dir/'receipts-start.json').exists())
                Clock.current = first + timedelta(days=4)
                with patch.object(sync,'save_day',side_effect=write):
                    args.dry_run=True
                    sync.run(args)
                    self.assertFalse(state.exists())
                    self.assertEqual(requested,[])
                    args.dry_run=False
                    args.days=[old[0]]
                    sync.run(args)
                    self.assertFalse(state.exists())
                    requested.clear()
                    args.days=[]
                    sync.run(args)
                    self.assertEqual(len(requested),16)
                    self.assertEqual(requested[:8],[d.isoformat() for d in old[:8]])
                    saved=state.read_bytes()
                    self.assertEqual(json.loads(saved)['pendingDates'],[d.isoformat() for d in old[8:]])
                    requested.clear()
                    args.days=[old[2]]
                    sync.run(args)
                    self.assertEqual(state.read_bytes(),saved)
                    args.days=[]
                    with patch.object(sync,'save_day',side_effect=sync.SyncError('retry')):
                        with self.assertRaisesRegex(sync.SyncError,'retry'):
                            sync.run(args)
                        self.assertEqual(state.read_bytes(),saved)
                    # Receive another old day's samples at exactly the saved boundary.
                    with contextlib.closing(sqlite3.connect(args.db)) as db:
                        db.execute('INSERT INTO band_samples VALUES(?,?)',(utcday(date(2021,9,16)),stamp(Clock.current)))
                        db.commit()
                    Clock.current += timedelta(days=1)
                    requested.clear()
                    sync.run(args)
                    self.assertEqual(requested[:4],[d.isoformat() for d in (*old[8:],date(2021,9,16))])
                    self.assertEqual(json.loads(state.read_bytes())['pendingDates'],[])
                    self.assertNotIn('2021-08-01',requested)


if __name__ == '__main__':
    unittest.main()
