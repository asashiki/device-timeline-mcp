import copy
import sqlite3
import sys
import unittest
import uuid
from datetime import date, datetime, timezone, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import hibi_notion as sync


class Blocks:
    def __init__(self):
        self.id, self.page = str(uuid.uuid4()), str(uuid.uuid4())
        self.blocks, self.writes, self.pages = [], [], []
        self.lose_response = False

    def make(self, data, author=None):
        block = copy.deepcopy(data)
        block.update(id=str(uuid.uuid4()), created_by={'id': author or self.id},
                     last_edited_by={'id': author or self.id}, has_children=False)
        return block

    def request(self, method, path, data=None, **kwargs):
        if path == 'users/me':
            return {'id': self.id}
        if method == 'GET':
            start = int(path.split('start_cursor=')[1]) if 'start_cursor=' in path else 0
            self.pages.append(start)
            stop = start + 3
            return {'results': self.blocks[start:stop], 'has_more': stop < len(self.blocks),
                    'next_cursor': str(stop) if stop < len(self.blocks) else None}
        self.writes.append((method, path))
        if path.endswith('/children'):
            rows = [self.make(block) for block in data['children']]
            after = data.get('after')
            i = [b['id'] for b in self.blocks].index(after)+1 if after else len(self.blocks)
            self.blocks[i:i] = rows
            if self.lose_response:
                self.lose_response = False
                raise sync.SyncError('write acknowledgement lost')
            return {'results': rows}
        block = next(b for b in self.blocks if b['id'] == path.split('/')[-1])
        if method == 'DELETE':
            self.blocks.remove(block)
        else:
            block.update(data)
        return {}


class TimelineSyncTest(unittest.TestCase):
    def test_long_unicode_page_pagination_and_recovery(self):
        api = Blocks()
        other = str(uuid.uuid4())
        notes = [api.make(sync.timeline_paragraph(1, '保留自己的正文'), other) for _ in range(5)]
        api.blocks += notes
        sections = ['第%d小时 ' % n + '💡走路\n睡眠' * 1000 for n in range(10)]
        api.lose_response = True
        with self.assertRaisesRegex(sync.SyncError, 'acknowledgement'):
            sync.save_timeline(api, api.page, sections)
        sync.save_timeline(api, api.page, sections)
        owned = [r for r in api.blocks if sync.timeline_slot(r,api.id)]
        self.assertGreater(len(owned), 8)
        self.assertEqual(api.blocks[:5], notes)
        content = [sync.prop_value(r['paragraph'],'rich_text').split('\n',1)[1] for r in owned]
        self.assertEqual(content, sync.timeline_parts(sections))
        self.assertTrue(all(len(t['text']['content'].encode('utf-16-le'))//2 <= 2000
                            for r in owned for t in r['paragraph']['rich_text']))
        api.writes.clear()
        self.assertFalse(sync.save_timeline(api, api.page, sections))
        self.assertFalse(api.writes)
        self.assertTrue(any(c > 3 for c in api.pages))
        owned[0]['last_edited_by']={'id':other}
        owned[1]['has_children']=True
        sync.save_timeline(api, api.page, ['只剩一段'])
        self.assertIn(owned[0],api.blocks)
        self.assertIn(owned[1],api.blocks)
        self.assertTrue(all(b in api.blocks for b in notes))
        self.assertEqual(sum(bool(sync.timeline_slot(b,api.id)) for b in api.blocks),1)

    def test_late_device_activity_and_health_refresh_their_measurement_days(self):
        db = sqlite3.connect(':memory:')
        self.addCleanup(db.close)
        db.executescript("""
            CREATE TABLE device_activities(started_at, ended_at, created_at);
            CREATE TABLE device_health(recorded_at, created_at);
            INSERT INTO device_activities VALUES('2021-10-08T15:58:00Z','2021-10-08T16:03:00Z','2021-11-23T08:00:00.000Z');
            INSERT INTO device_health VALUES('2021-10-01T00:00:00Z','2021-11-23T08:00:00.000Z');
        """)
        now = datetime(2021,11,23,9,tzinfo=timezone.utc)
        self.assertEqual(sync.receipt_days(db, now-timedelta(hours=2),now),
                         {date(2021,10,d) for d in (1,8,9)})


if __name__ == '__main__':
    unittest.main()

class NativeHourTest(unittest.TestCase):
    def test_native_nested_batch_resume_then_update_only_one_hour(self):
        from tempfile import TemporaryDirectory
        class Native(Blocks):
            def __init__(self):
                super().__init__()
                self.by_parent={self.page:self.blocks}
                self.reads=[]
            def request(self,method,path,data=None,**kwargs):
                if path=='users/me':
                    return {'id':self.id}
                key=path.split('/')[1]
                if method=='GET':
                    self.reads.append(key)
                    return {'results':self.by_parent[key],'has_more':False}
                self.writes.append((method,path))
                if path.endswith('/children'):
                    target=self.by_parent[key]
                    added=[]
                    for row in data['children']:
                        block=self.make(row)
                        kids=[self.make(c) for c in block[block['type']].pop('children',[])]
                        self.by_parent[block['id']]=kids
                        block['has_children']=bool(kids)
                        added.append(block)
                    pos=next((i+1 for i,b in enumerate(target) if b['id']==data.get('after')),len(target))
                    target[pos:pos]=added
                    if self.lose_response:
                        self.lose_response=False
                        raise sync.SyncError('write acknowledgement lost')
                    return {'results':added}
                owner,target=next((rows,b) for rows in self.by_parent.values() for b in rows if b['id']==key)
                if method=='DELETE':
                    owner.remove(target)
                else:
                    target.update(data)
                return target
        api=Native()
        lines=['当日概览']+[f'{h:02}:00–{h+1:02}:00  |  手环 · 0 步（60/60 分钟有样本） · 心率缺测\n旧记录' for h in range(24)]
        with TemporaryDirectory() as tmp:
            directory=Path(tmp); api.lose_response=True
            with self.assertRaisesRegex(sync.SyncError,'acknowledgement'):
                sync.save_timeline(api,api.page,lines,directory)
            self.assertEqual(len(api.writes),1)
            self.assertEqual([b['type'] for b in api.blocks],['paragraph']+['toggle']*24)
            self.assertFalse(sync.save_timeline(api,api.page,lines,directory))
            api.reads.clear();api.writes.clear()
            self.assertFalse(sync.save_timeline(api,api.page,lines,directory))
            self.assertEqual(api.reads,[api.page]);self.assertEqual(api.writes,[])
            ninth=api.blocks[10]['id']
            direct=api.by_parent[ninth]
            note=api.make(sync.timeline_paragraph(1,'保留说明'),str(uuid.uuid4()))
            direct.append(note)
            lines[10]=lines[10].replace('旧记录','补传的新记录')
            self.assertTrue(sync.save_timeline(api,api.page,lines,directory))
            self.assertEqual(api.reads[-1],ninth)
            self.assertEqual(api.writes,[('PATCH','blocks/'+direct[0]['id'])])
            self.assertIn(note,direct)
            self.assertIn('补传的新记录',sync.prop_value(direct[0]['paragraph'],'rich_text'))
