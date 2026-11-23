import sqlite3
import sys
import tempfile
import unittest
from datetime import datetime, timedelta
from types import SimpleNamespace
from unittest.mock import patch
from pathlib import Path

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
import hibi_sleep_check as check


class NightCheck(unittest.TestCase):
    def setUp(self):
        self.a=check.instant('2021-11-23T23:55:00+08:00')
        self.b=self.a+timedelta(minutes=15)
        self.end=check.instant('2021-11-24T16:00:00+08:00')
        self.ref=dict(startedAt=check.iso(self.a),endedAt=check.iso(self.b),
                      receivedAt=check.iso(self.b+timedelta(minutes=10)),asleepMinutes=None)

    def poll(self,n,state='unknown',**override):
        when=check.iso(self.a+timedelta(minutes=n))
        sleep={'state':state,'confidence':'fresh','sampledAt':when,'receivedAt':when,
               'timeBasis':'phone_observed','origin':'band_status_event','sessionStart':None,**override}
        return dict(asOf=when,attemptedAt=when,sleep=sleep,lastEvidenceReceivedAt=when)

    def test_forward_pattern_is_recorded_without_declaring_a_session_verified(self):
        point = self.poll(3,'likely_asleep',reason='personal_sleep_pattern',
                          origin='band_minute_pattern',timeBasis='device_sample')
        now = self.a+timedelta(minutes=7)
        pending = check.evaluate([point],[],self.a,self.end,now)
        self.assertEqual(pending['firstLiveCandidate']['at'],point['asOf'])
        self.assertEqual(pending['verdict'],'未可验证')
        matched = check.evaluate([point],[self.ref],self.a,self.end,self.end)
        self.assertEqual(matched['sessions'][0]['inferredHits'],1)

    def test_cross_midnight_hits_use_actual_observation_before_waking(self):
        inferred=self.poll(5,'likely_asleep',origin='band_live_progress',timeBasis='phone_observed_growth',sessionStart=self.ref['startedAt'])
        rows=[self.poll(0),inferred,self.poll(10,'asleep'),self.poll(15,'asleep')]
        result=check.interval_result(rows,self.ref)
        self.assertEqual((result['scheduledChecks'],result['successfulChecks'],result['explicitHits'],result['inferredHits']),(3,3,1,1))
        self.assertEqual(result['firstHitAt'],inferred['asOf'])
        self.assertEqual(result['verdict'],'当时明确睡着')
        self.assertEqual(check.interval_result(rows[:2],self.ref)['verdict'],'当时推测睡眠')

    def test_posthoc_failure_stale_cross_session_and_endpoint(self):
        rows=[self.poll(0), self.poll(5,'likely_asleep',origin='band_live_progress',timeBasis='phone_observed_growth',
                                      sessionStart=check.iso(self.a-timedelta(days=1))),
              self.poll(10,'asleep',sampledAt=check.iso(self.a-timedelta(seconds=1))),self.poll(15,'asleep')]
        self.assertEqual(check.interval_result(rows,self.ref)['verdict'],'只在事后收到')
        rows[0]['lastEvidenceReceivedAt']=None
        self.assertEqual(check.interval_result(rows,self.ref)['verdict'],'未可验证')
        rows=[rows[0], {'attemptedAt':rows[1]['asOf'],'failure':'HTTP 503'}]
        result=check.interval_result(rows,self.ref)
        self.assertEqual((result['failedRequests'],result['missingChecks'],result['noRecentBandReceiptChecks']),(1,1,1))

    def test_only_new_direct_session_received_before_cutoff(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'db'
            with sqlite3.connect(path) as db:
                for table in ('band_sleep','historical_sleep_imports'):
                    db.execute(f'CREATE TABLE {table} (device_id,started_at,ended_at,received_at,asleep_minutes)')
                good=['android-phone',self.ref['startedAt'],self.ref['endedAt'],self.ref['receivedAt'],None]
                db.execute('INSERT INTO band_sleep VALUES (?,?,?,?,?)',good)
                db.execute('INSERT INTO historical_sleep_imports VALUES (?,?,?,?,?)',good[:-1]+[15])
                for start,end,receipt in [(self.a-timedelta(days=1),self.b-timedelta(days=1),self.b), (self.a+timedelta(minutes=30),self.b+timedelta(minutes=30),self.end+timedelta(minutes=1))]:
                    db.execute('INSERT INTO band_sleep VALUES (?,?,?,?,?)',('android-phone',*map(check.iso,(start,end,receipt)),5))
            self.assertEqual(check.direct_sessions(path,self.a-timedelta(hours=2),self.end,self.end),[self.ref])
            self.assertEqual(check.direct_sessions(path,self.a-timedelta(hours=2),self.b,self.end),[])


    def test_once_per_slot_and_idempotent_frozen_report(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            args=SimpleNamespace(start=self.a,deadline=self.end,state_dir=root/'state',db=root/'db',
                                 token_file=root/'token',report=False)
            with sqlite3.connect(args.db) as db:
                db.execute('CREATE TABLE band_sleep(device_id,started_at,ended_at,received_at,asleep_minutes)')
            class Clock(datetime):
                current=self.a + timedelta(seconds=2)
                @classmethod
                def now(cls,tz=None):
                    return cls.current.astimezone(tz)
            with patch.object(check,'datetime',Clock),patch.object(check,'measure',return_value=self.poll(0)) as read:
                check.run(args);check.run(args)
                self.assertEqual(read.call_count,1)
                Clock.current=self.end + timedelta(seconds=1)
                final=check.run(args)
                self.assertTrue(final['closed'])
                self.assertEqual(final['successfulChecks'],1)
                self.assertEqual((args.state_dir/'sleep-check.json').stat().st_mode & 0o777,0o600)
                args.db.unlink()
                self.assertEqual(check.run(args),final)
                self.assertEqual(read.call_count,1)
