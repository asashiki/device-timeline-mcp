"""Regression checks for cross-midnight, stale iOS close, AFK and missing samples."""
import importlib.util
import sqlite3
import unittest
from datetime import date, datetime, timezone
from pathlib import Path

MODULE = Path(__file__).resolve().parents[1] / 'scripts' / 'hibi_day.py'
SPEC = importlib.util.spec_from_file_location('hibi_day', MODULE)
hibi_day = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(hibi_day)


class DailyFactsTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.row_factory = sqlite3.Row
        self.db.executescript('''
            CREATE TABLE device_states (device_id,platform,last_seen_at);
            CREATE TABLE device_activities (device_id,app_id,started_at,ended_at);
            CREATE TABLE device_health (device_id,type,unit,value,recorded_at);
        ''')
        self.db.executemany('INSERT INTO device_states VALUES (?,?,?)', [
            ('mac', 'macos', '2021-11-22T15:59:59.000Z'),
            ('iphone', 'ios', '2021-11-22T16:00:00.000Z'),
            ('pc', 'windows', '2021-11-20T22:00:00.000Z'),
        ])

    def facts(self, day=22, as_of='2021-11-24T12:00:00+00:00'):
        return hibi_day.day_facts(self.db, date(2021, 11, day), datetime.fromisoformat(as_of))

    def test_midnight_intersection_idle_overlap_and_snapshot(self):
        self.db.executemany('INSERT INTO device_activities VALUES (?,?,?,?)', [
            ('mac','first','2021-11-21T15:55:00.000Z','2021-11-21T16:15:00.000Z'),
            ('mac','second','2021-11-21T16:12:00.000Z','2021-11-21T16:25:00.000Z'),
            ('mac','macos.afk','2021-11-21T16:20:00.000Z','2021-11-21T16:35:00.000Z'),
            ('mac','open','2021-11-22T15:30:00.000Z',None),
        ])
        f = self.facts(); m = f['devices']['mac']
        self.assertEqual((m['activeMinutes'],m['idleMinutes'],m['coveredMinutes']),(25,10,35))
        self.assertEqual(m['pointRows'],1)
        self.assertEqual(self.facts(day=21)['devices']['mac']['activeMinutes'],5)
        f = self.facts(as_of='2021-11-21T16:17:00+00:00')
        self.assertEqual(f['through'],'2021-11-22T00:17:00+08:00')
        self.assertEqual(f['devices']['mac']['activeMinutes'],15)

    def test_stale_ios_missing_day_and_idle_only(self):
        self.db.executemany('INSERT INTO device_activities VALUES (?,?,?,?)', [
            ('iphone','first','2021-11-20T06:00:00.000Z','2021-11-22T01:00:00.000Z'),
            ('mac','macos.afk','2021-11-21T16:00:00.000Z','2021-11-21T17:00:00.000Z'),
        ])
        f = self.facts(); m, ip, pc = (f['devices'][k] for k in ('mac','iphone','pc'))
        self.assertEqual(m['activeMinutes'],0)
        self.assertEqual(m['idleMinutes'],60)
        self.assertTrue(ip['observed']); self.assertEqual(ip['uncertainRows'],1)
        self.assertIsNone(ip['activeMinutes']); self.assertIsNone(pc['activeMinutes'])
        self.assertEqual(f['deviceCount'],2)
        self.assertFalse(self.facts(day=21)['devices']['iphone']['observed'])
        self.assertIsNone(self.facts(day=21)['activeMinutes'])

    def test_health_missing_vs_zero_and_chronological_cutoff(self):
        self.db.executemany('INSERT INTO device_health VALUES (?,?,?,?,?)', [
            ('phone','steps','count',0,'2021-11-21T17:00:00.000Z'),
            ('phone','heart_rate','bpm',80,'2021-11-22T15:59:00.000Z'),
            ('phone','steps','count',999,'2021-11-22T16:00:00.000Z'),
        ])
        self.assertNotIn('stepSampleMinutes',self.facts()['metricCoverage'])
        self.assertEqual(self.facts(day=21)['health'],{'samples':0,'lastSampleAt':None})
        self.assertEqual(self.facts()['health'],{'samples':2,'lastSampleAt':'2021-11-22T15:59:00.000Z',
                                               'reportedSteps':0,'heartRateRangeBpm':[80,80]})

    def test_direct_band_is_in_private_daily_facts_once(self):
        self.db.execute('CREATE TABLE band_samples(device_id,type,value,recorded_at)')
        stamp = '2021-11-22T01:00:00.000Z'
        self.db.execute('INSERT INTO device_health VALUES (?,?,?,?,?)', ('phone','steps','count',2,stamp))
        self.db.executemany('INSERT INTO band_samples VALUES (?,?,?,?)', [
            ('phone','steps',3,stamp),('phone','heart_rate',80,stamp)])
        self.assertEqual(self.facts()['health'], {'samples':2,'lastSampleAt':stamp,
                         'reportedSteps':3,'heartRateRangeBpm':[80,80]})


if __name__ == '__main__':
    unittest.main()
