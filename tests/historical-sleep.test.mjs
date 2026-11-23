import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { DatabaseSync } from 'node:sqlite';
import { runMigrations } from '../dist/db/migrations.js';
import { Repository } from '../dist/db/repo.js';
import { readLiveHealth } from '../dist/db/live-health.js';

test('verified import is historical, private, source-labeled and deduplicates against a later band upload', t => {
  const dir = mkdtempSync(join(tmpdir(), 'hibi-import-'));
  const file = join(dir, 'test.db');
  const db = new DatabaseSync(file);
  t.after(() => { db.close(); rmSync(dir, { recursive: true, force: true }); });
  runMigrations(db);
  const phone = 'android-phone';
  const repo = new Repository(db, 120);
  const sleep = { startedAt: '2026-06-22T22:00:00.000Z', endedAt: '2026-06-23T00:30:00.000Z', asleepMinutes: 140 };
  const first = '2026-06-23T02:00:00.000Z', later = '2026-06-24T12:00:00.000Z';
  const get = asOf => readLiveHealth(db, phone, new Date(asOf));
  repo.importVerifiedMiFitnessSleep(phone, sleep, first);
  repo.importVerifiedMiFitnessSleep(phone, sleep, later);
  const after = get(first);
  assert.equal(after.lastCompletedSleep.source, 'mi_fitness_local_log');
  assert.equal(after.lastCompletedSleep.receivedAt, first);
  assert.equal(after.lastCompletedSleep.asleepMinutes,140);
  assert.equal(after.currentSleep.state, 'unknown');
  assert.equal(get(sleep.endedAt).lastCompletedSleep,null);
  assert.equal(db.prepare('SELECT COUNT(*) n FROM device_health').get().n,0);

  function notionText() {
    const python = spawnSync('python3', ['-c', [
      'import json,sys,sqlite3; from datetime import datetime, date',
      'sys.path.insert(0,sys.argv[1]); from hibi_day import day_facts; from hibi_notion import mcp_row',
      'db=sqlite3.connect(sys.argv[2]); db.row_factory=sqlite3.Row; checked=datetime.fromisoformat(sys.argv[3])',
      'f=day_facts(db,date(2026,6,23),checked)',
      'print(json.dumps([f["health"]["samples"], mcp_row(f,checked)["properties"]["身体记录"]]))'
    ].join('\n'),fileURLToPath(new URL('../scripts/',import.meta.url)),file,later],{
      env:{...process.env,PYTHONDONTWRITEBYTECODE:'1'},encoding:'utf8'});
    assert.equal(python.status,0,python.stderr);return JSON.parse(python.stdout);
  }
  assert.equal(notionText()[0],1);
  assert.match(notionText()[1],/小米运动健康/);
  const insert = db.prepare('INSERT OR REPLACE INTO band_sleep(device_id,started_at,ended_at,asleep_minutes,received_at) VALUES(?,?,?,?,?)');
  insert.run(phone,sleep.startedAt,sleep.endedAt,null,later);
  assert.equal(get(later).lastCompletedSleep.asleepMinutesSource,'mi_fitness_local_log');
  assert.equal(get(later).lastCompletedSleep.source,'hibi_band_direct');
  assert.equal(notionText()[0],1);
  assert.match(notionText()[1],/时长由小米运动健康/);
  insert.run(phone,sleep.startedAt,sleep.endedAt,145,later);
  assert.equal(get(first).lastCompletedSleep.source,'mi_fitness_local_log');
  assert.equal(get(later).lastCompletedSleep.source,'hibi_band_direct');
  assert.equal(get(later).lastCompletedSleep.asleepMinutes,145);
  assert.equal(get(later).source,'hibi_band_direct');
  assert.equal(notionText()[0],1);
  assert.doesNotMatch(notionText()[1],/小米运动健康/);
  db.prepare('DELETE FROM band_sleep').run();
  insert.run(phone,'2026-06-22T22:01:00.000Z','2026-06-23T00:29:00.000Z',142,later);
  assert.equal(get(later).lastCompletedSleep.asleepMinutes,142);
  assert.equal(notionText()[0],1);
  assert.throws(()=>repo.importVerifiedMiFitnessSleep(phone,{...sleep,asleepMinutes:151},later));
});
