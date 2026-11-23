import json
import sqlite3
import sys
import unittest
from datetime import date, datetime
from pathlib import Path

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from hibi_timeline import timeline_sections


class TimelineTest(unittest.TestCase):
    def test_timestamps_overlap_open_intervals_missing_vs_zero_and_privacy(self):
        db=sqlite3.connect(':memory:')
        self.addCleanup(db.close)
        db.executescript('''
        CREATE TABLE device_states(device_id,device_name,platform,last_seen_at);
        INSERT INTO device_states VALUES('mac','Mac','macos',NULL),('ios','iPhone','ios',NULL),('pc','PC','windows',NULL);
        CREATE TABLE device_activities(id INTEGER PRIMARY KEY,device_id,app_id,started_at,ended_at,extra_json,window_title);
        CREATE TABLE band_samples(device_id,type,value,recorded_at,received_at);
        CREATE TABLE private_completed_rows(device_id,started_at,ended_at,asleep_minutes,source,import_minutes,import_received_at,received_at,superseded_at);
        CREATE VIEW private_completed_sleep AS SELECT * FROM private_completed_rows;
        CREATE TABLE band_sleep(device_id,started_at,ended_at,asleep_minutes,received_at);
        ''')
        def row(device,app,b,e,extra={}):
            db.execute('INSERT INTO device_activities(device_id,app_id,started_at,ended_at,extra_json,window_title) VALUES(?,?,?,?,?,?)',
                       (device,app,b,e,json.dumps(extra),'PRIVATE-TITLE'))
        row('mac','Finder','2021-11-22T15:58:00.000Z','2021-11-22T16:02:00.000Z')
        row('mac','Game','2021-11-22T16:10:00.000Z',None)
        row('ios','Voice','2021-11-22T16:05:00.000Z','2021-11-22T16:22:00.000Z',{'endSource':'ios_next_open'})
        row('ios','Maps','2021-11-22T17:00:00.000Z','2021-11-22T20:00:00.000Z')
        row('ios','Long call','2021-11-22T20:05:00.000Z','2021-11-22T23:05:00.000Z',
            {'startSource':'ios_open','endSource':'ios_named_close'})
        row('ios','Legacy short','2021-11-22T23:10:00.000Z','2021-11-22T23:30:00.000Z')
        db.execute("INSERT INTO band_samples VALUES('android-phone','steps',0,'2021-11-22T16:00:00.000Z','2021-11-22T16:10:00.000Z')")
        db.execute("INSERT INTO band_samples VALUES('android-phone','heart_rate',88,'2021-11-22T16:03:00.000Z','2021-11-22T16:10:00.000Z')")
        db.execute("INSERT INTO band_samples VALUES('android-phone','heart_rate',174,'2021-11-22T16:06:00.000Z','2022-01-01T00:00:00.000Z')")
        db.execute("""INSERT INTO private_completed_rows VALUES('android-phone','2021-11-22T15:58:00.000Z','2021-11-23T00:02:00.000Z',
                      NULL,'hibi_band_direct',360,'2021-11-23T02:30:00.000Z','2021-11-23T01:00:00.000Z',NULL)""")
        sections=timeline_sections(db,date(2021,11,23),datetime.fromisoformat('2021-11-23T10:33:10+08:00'))
        text='\n'.join(sections)
        overview=sections[0]
        self.assertIn('Long call（iPhone） 180.0 分钟，最长一段 04:05–07:05',overview)
        self.assertNotIn('Legacy short',overview)
        self.assertIn('覆盖 1/634 分钟，心率实测 1 次',overview)
        self.assertIn('睡眠 · 整段实睡 360 分钟',overview)
        from hibi_day import day_facts
        db.row_factory=sqlite3.Row
        db.execute('CREATE TABLE device_health(device_id,type,unit,value,recorded_at,created_at)')
        facts=day_facts(db,date(2021,11,23),datetime.fromisoformat('2021-11-23T10:33:10+08:00'))
        self.assertEqual(facts['devices']['ios']['activeMinutes'],180)
        self.assertEqual(facts['devices']['mac']['activeMinutes'],2)
        self.assertEqual(facts['activeMinutes'],182)
        self.assertEqual(facts['health']['heartRateRangeBpm'],[88,88])
        for expected in ['11-22 23:58–00:02  Mac · Finder','00:10  Mac · Game · 开始观测，结束未知','下一次打开指令','两端之间缺少连续证据',
                         'PC · 这段时间没有应用观测','实睡 360 分钟（时长由小米运动健康本机补录）','0 步（1/60 分钟有样本）',
                         '步数缺测（0/34 分钟）','00:03 88','Mac · 有起止的记录覆盖 2.0 分钟']:
            self.assertIn(expected,text)
        # The closed 04:05–07:05 app and overnight sleep must remain visible
        # when only 05:00 is expanded. Each still contributes once to totals.
        hour_five=sections[6]
        self.assertIn('04:05–07:05  iPhone · Long call',hour_five)
        self.assertIn('跨小时，本小时 05:00–06:00',hour_five)
        self.assertIn('睡眠 · 整段实睡 360 分钟',hour_five)
        self.assertNotIn('Long call',sections[9])
        self.assertIn('本小时没有设备应用或睡眠区间记录',sections[10])
        self.assertNotIn('PRIVATE-TITLE',text)
        self.assertNotIn('174',text)
        self.assertNotIn('01:00–04:00',text)
        self.assertLess(text.index('00:05  iPhone'),text.index('00:10  Mac'))
