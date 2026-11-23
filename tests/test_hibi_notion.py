"""Validate ambiguous inserts and preservation of existing daily rows."""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import hibi_notion as sync


class FakeNotion:
    def __init__(self):
        self.results = []
        self.writes = []
        self.lose_create_response = False

    def request(self, method, path, data=None, *, create=False):
        if path.endswith('/query'):
            return {'results': self.results, 'has_more': False}
        self.writes.append((method, data))
        if self.lose_create_response and create:
            raise sync.SyncError('connection closed')
        return {'id': '3992e873-b697-469f-aaae-e908e9e24901'}


class WriterTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = Path(tmp.name)
        self.api = FakeNotion()
        self.flat = {'日期': '2021-11-16', 'date:记录日:start': '2021-11-16',
                     'date:最近核验:start': '2021-11-23T01:10:00+08:00', '设备使用': 'Mac 12.4 分钟',
                     '身体记录': '当天无健康记录', '已记录设备': 1, '健康同步': '已停更', '有记录的分钟': 12.4}

    def save(self):
        return sync.save_day(self.api, '00000000-0000-4000-8000-000000000001', self.flat, self.dir)

    def existing(self):
        props = sync.notion_props(self.flat)
        props['留意事项'] = {'rich_text': [{'plain_text': '用户备注'}]}
        return {'id': '3992e873-b697-469f-aaae-e908e9e24901', 'properties': props}

    def test_preserve_manual_notes_and_dont_rewrite_unchanged_data(self):
        self.api.results = [self.existing()]
        self.assertEqual(self.save(), 'unchanged')
        self.assertEqual(self.api.writes, [])
        del self.api.results[0]['properties']['设备使用']
        self.assertEqual(self.save(), 'updated')
        method, payload = self.api.writes[0]
        self.assertEqual(method, 'PATCH')
        self.assertEqual(set(payload['properties']), {'最近核验', '设备使用'})

    def test_ambiguous_create_is_never_duplicated_and_recovers_when_visible(self):
        self.api.lose_create_response = True
        with self.assertRaisesRegex(sync.SyncError, 'connection closed'):
            self.save()
        with self.assertRaisesRegex(sync.SyncError, 'previous create result uncertain'):
            self.save()
        self.assertEqual(len(self.api.writes), 1)
        self.api.results = [self.existing()]
        self.assertEqual(self.save(), 'unchanged')
        self.assertFalse((self.dir / '2021-11-16.pending').exists())

    def test_ambiguous_dates_do_not_write(self):
        self.api.results = [self.existing(), self.existing()]
        with self.assertRaisesRegex(sync.SyncError, 'duplicate date rows'):
            self.save()
        self.api.results.pop()
        self.api.results[0]['properties']['记录日'] = {'date': {'start':'2021-11-17'}}
        with self.assertRaisesRegex(sync.SyncError, 'conflicting date fields'):
            self.save()
        self.assertEqual(self.api.writes, [])

    def test_catch_up_receipt_on_first_complete_day(self):
        row = self.existing()
        row['properties']['最近核验'] = {'date': {'start': '2021-11-16T00:17:00+08:00'}}
        self.api.results = [row]
        self.assertEqual(self.save(), 'updated')
        _, payload = self.api.writes[0]
        self.assertEqual(set(payload['properties']), {'最近核验'})

    def test_steps_do_not_mark_a_missing_night_complete(self):
        checked = sync.parse_stamp('2021-11-23T10:15:00+08:00')
        health = {'samples': 1, 'lastSampleAt': checked.isoformat(), 'reportedSteps': 0}
        day = dict(date='2021-11-23', through=checked.isoformat(), health=health,
                   devices={}, activeMinutes=None, deviceCount=0)
        row = sync.mcp_row(day, checked)['properties']
        self.assertEqual(row['健康同步'], '待补睡眠')
        self.assertIn('睡眠：尚未收到完整时段', row['身体记录'])
        health['sleepSessions'] = [dict(start='2021-11-23T04:00:00+08:00',
                                       end='2021-11-23T09:00:00+08:00', asleepMinutes=None,
                                       source='hibi_band_direct')]
        row = sync.mcp_row(day, checked)['properties']
        self.assertEqual(row['健康同步'], '部分有记录')
        self.assertIn('实睡时长未上报', row['身体记录'])
        health['sleepSessions'][0].update(asleepMinutes=270, asleepMinutesSource='mi_fitness_local_log')
        self.assertEqual(sync.mcp_row(day, checked)['properties']['健康同步'], '含睡眠补录')


if __name__ == '__main__':
    unittest.main()
