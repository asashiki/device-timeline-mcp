import json
import sqlite3
import sys
import unittest
from datetime import date, datetime, timezone
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import hibi_history as history
import hibi_notion as sync


class HistoricalCorrections(unittest.TestCase):
    def test_corrections_removals_and_confirmation_after_each_success(self):
        with TemporaryDirectory() as tmp:
            base = Path(tmp)
            db = sqlite3.connect(base/'db')
            self.addCleanup(db.close)
            db.executescript("""
                CREATE TABLE device_activities(id INTEGER PRIMARY KEY,device_id,app_id,started_at,ended_at,extra_json,created_at);
                CREATE TABLE device_health(id INTEGER PRIMARY KEY,device_id,type,unit,value,recorded_at,created_at);
                INSERT INTO device_activities VALUES(1,'ios-phone','map','2021-09-15T15:59:00Z','2021-09-15T16:02:00Z','{}','2021-09-15T16:02:00Z');
                INSERT INTO device_health VALUES(1,'android-phone','heart_rate','bpm',84,'2021-10-01T04:00:00Z','2021-10-01T04:00:00Z');
            """)
            now = datetime(2021,11,23,10,tzinfo=timezone.utc)
            initial = history.fingerprints(db,now)
            self.assertEqual(sorted(initial),['2021-09-15','2021-09-16','2021-10-01'])
            self.assertEqual(history.changed_dates(initial,initial),[])
            db.execute("""UPDATE device_activities SET extra_json='{"endSource":"ios_named_close"}'""")
            self.assertEqual({d.isoformat() for d in history.changed_dates(history.fingerprints(db,now), initial)},
                             {'2021-09-15','2021-09-16'})
            db.execute("UPDATE device_health SET value=86")
            self.assertEqual(len(history.changed_dates(history.fingerprints(db,now), initial)),3)
            db.commit()
            args=SimpleNamespace(db=base/'db',days=[],as_of=None,dry_run=False,source='00000000-0000-4000-8000-000000000001',
                                 state_dir=base/'state',token_file=base/'token',backfill_history=True)
            args.token_file.write_text('example')
            api=SimpleNamespace(request=lambda *a:{'properties':{k:{'type':v} for k,v in sync.SCHEMA.items()}})
            seen=[]
            def save(api,source,flat,guard):
                seen.append(flat['日期'])
                if len(seen)==2:
                    raise sync.SyncError('interrupted')
                return 'updated'
            with patch.object(sync,'timeline_sections',return_value=[]),patch.object(sync,'day_facts',side_effect=lambda db,d,t:d),patch.object(sync,'mcp_row',side_effect=lambda d,*a:{'properties':{'日期':d.isoformat()}}),patch.object(sync,'Notion',return_value=api),patch.object(sync,'save_day',side_effect=save):
                with self.assertRaisesRegex(sync.SyncError,'interrupted'):
                    sync.run(args)
                confirmed=args.state_dir/'00000000-0000-4000-8000-000000000001'/'history-signatures.json'
                self.assertEqual(list(json.loads(confirmed.read_text())), ['2021-10-01'])
                sync.run(args)
                self.assertEqual(len(json.loads(confirmed.read_text())),3)
                n=len(seen);sync.run(args);self.assertEqual(n,len(seen))
                db.execute('DELETE FROM device_health');db.commit()
                sync.run(args)
                self.assertEqual(seen[-1],'2021-10-01')
                self.assertEqual(json.loads(confirmed.read_text())['2021-10-01'],history.EMPTY)
                n=len(seen);sync.run(args);self.assertEqual(n,len(seen))
                args.days=[date(2021,9,15)];args.backfill_history=False
                db.execute("UPDATE device_activities SET ended_at='2021-09-15T16:12:00Z'");db.commit()
                sync.run(args)
                self.assertEqual(json.loads(confirmed.read_text())['2021-09-15'],history.fingerprints(db,now)['2021-09-15'])
