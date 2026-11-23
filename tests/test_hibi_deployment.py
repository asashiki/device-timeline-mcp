"""Independent installations and nightly windows use only the supplied deployment."""
import json
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
import hibi_settings as config
from hibi_sleep_check import instant
from hibi_sleep_check_notion import nightly_window, update_block


class DeploymentTest(unittest.TestCase):
    def test_scoped_origin(self):
        with patch.dict(os.environ, {'HIBI_CONSOLE_ORIGIN':'https://journal.example.org/'}):
            self.assertEqual(config.origin('HIBI_CONSOLE_ORIGIN'),'https://journal.example.org')
        with patch.dict(os.environ, {'HIBI_CONSOLE_ORIGIN':'https://secret@journal.example.org/a'}):
            with self.assertRaises(ValueError): config.origin('HIBI_CONSOLE_ORIGIN')

    def test_window_before_after_midnight_and_daytime(self):
        bounds = ('2021-02-02T12:00:00Z','2021-02-03T08:00:00Z')
        for clock in ('2021-02-02T15:59:59Z','2021-02-02T16:00:00Z','2021-02-03T10:00:00Z'):
            self.assertEqual(nightly_window(instant(clock)),tuple(map(instant,bounds)))
        self.assertNotEqual(nightly_window(instant('2021-02-03T12:00:00Z')),tuple(map(instant,bounds)))

    def test_no_write_to_published_or_moved_target(self):
        ids={name: '00000000-0000-4000-8000-%012d'%index for index,name in enumerate(
            ['HIBI_NOTION_DATA_SOURCE_ID','HIBI_NOTION_DATABASE_ID','HIBI_NOTION_PAGE_ID','HIBI_NOTION_SLEEP_BLOCK_ID'],1)}
        page=dict(id=ids['HIBI_NOTION_PAGE_ID'],public_url=None,in_trash=False,archived=False,
                  parent=dict(type='data_source_id',data_source_id=ids['HIBI_NOTION_DATA_SOURCE_ID']))
        block=dict(id=ids['HIBI_NOTION_SLEEP_BLOCK_ID'],type='paragraph',archived=False,in_trash=False,
                   parent=dict(type='page_id',page_id=ids['HIBI_NOTION_PAGE_ID']))
        class Connection:
            def __init__(self, results): self.results,self.calls=results,[]
            def open(self, req,timeout):
                import io
                self.calls.append(req.get_method())
                return io.BytesIO(json.dumps(self.results.pop(0)).encode())
        with patch.dict(os.environ,ids):
            for replies in ([dict(page,public_url='https://example.org')],[page,dict(block,parent={})]):
                conn=Connection(replies)
                with patch('hibi_sleep_check_notion.build_opener',return_value=conn):
                    with self.assertRaises(ValueError): update_block('fixture','sample','sample')
                self.assertNotIn('PATCH',conn.calls)
            conn=Connection([page,block,block])
            with patch('hibi_sleep_check_notion.build_opener',return_value=conn):
                update_block('fixture','a'*4000,'night')
            self.assertEqual(conn.calls,['GET','GET','PATCH'])
