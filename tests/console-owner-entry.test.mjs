import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash, randomBytes} from 'node:crypto';
import {DatabaseSync} from 'node:sqlite';
import Fastify from 'fastify';
import {registerConsoleBody} from '../dist/console-body.js';
import {runMigrations} from '../dist/db/migrations.js';
import {Repository} from '../dist/db/repo.js';
import {registerRoutes} from '../dist/routes.js';
import {registerWrist} from '../dist/wrist-control.js';
import {DeviceAuth} from '../dist/device-auth.js';

const origin = 'https://hibi.example';
const day = '/api/console/day?date=2025-02-04';
const entry = '/api/console/view';
const digest = text => createHash('sha256').update(text).digest();
const newKey = () => randomBytes(32).toString('base64url');
const cookie = result => result.headers['set-cookie'].split(';')[0];

function fixture(t, configured = true) {
  const db = new DatabaseSync(':memory:');
  runMigrations(db);
  const view = newKey(), body = newKey();
  const keys = {bodyReadDigest:digest(body), contextReadDigest:digest(newKey())};
  if (configured) keys.browserViewDigest = digest(view);
  const repo = new Repository(db, 120), auth = new DeviceAuth([]), wristDigest = digest(newKey());
  function start() {
    const app = Fastify();
    registerConsoleBody(app, repo, db, keys, async () => { throw Error('This entry needs no confirmation'); }, origin);
    registerRoutes(app, {repo, auth, labels:{name:id=>id}, rawLabels:{}, retentionDays:30, ...keys});
    registerWrist(app, db, auth, wristDigest);
    return app;
  }
  let app = start();
  t.after(async () => { await app.close(); db.close(); });
  return {
    view, body,
    inject: options => app.inject(options),
    restart: async () => { await app.close(); app = start(); },
  };
}

test('a private timeline link enables map browsing in an existing body-only browser and survives restart', async t => {
  const site = fixture(t);
  const bodyLogin = await site.inject({method:'POST', url:'/api/console/session',
    headers:{origin, authorization:'Bearer ' + site.body}});
  assert.equal(bodyLogin.statusCode, 204);
  const oldCookie = cookie(bodyLogin);
  const before = await site.inject({url:day, headers:{cookie:oldCookie}});
  assert.equal(before.json().locationAccess, false);
  const login = await site.inject({method:'POST', url:entry, headers:{origin, cookie:oldCookie}, payload:{key:site.view}});
  assert.equal(login.statusCode, 204);
  const renewed = cookie(login);
  assert.notEqual(renewed, oldCookie);
  assert.equal((await site.inject({url:day, headers:{cookie:oldCookie}})).statusCode, 401);
  await site.restart();
  const after = await site.inject({url:day, headers:{cookie:renewed}});
  assert.equal(after.statusCode, 200);
  assert.equal(after.json().locationAccess, true);
  for (const url of ['/api/agent/body-now', '/api/agent/wrist']) {
    assert.equal((await site.inject({url, headers:{authorization:'Bearer ' + site.view, cookie:renewed}})).statusCode, 401);
  }
});

test('an unconfigured or incorrect entry cannot create a session; a valid entry works in a fresh browser', async t => {
  const off = fixture(t, false), on = fixture(t);
  for (const [site, headers, key] of [
    [off, {origin}, off.view],
    [on, {origin}, newKey()],
    [on, {}, on.view],
    [on, {origin:'https://other.example'}, on.view],
  ]) {
    const response = await site.inject({method:'POST', url:entry, headers, payload:{key}});
    assert.ok(response.statusCode >= 400);
    assert.equal(response.headers['set-cookie'], undefined);
  }
  const response = await on.inject({method:'POST', url:entry, headers:{origin}, payload:{key:on.view}});
  assert.equal(response.statusCode, 204);
  assert.equal((await on.inject({url:day, headers:{cookie:cookie(response)}})).json().locationAccess, true);
});
