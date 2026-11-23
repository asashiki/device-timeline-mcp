import { createHash, randomBytes } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const template = new URL('../.env.example', import.meta.url);
const devices = [
  ['android-phone', 'Phone', 'android'],
  ['android-tablet', 'Tablet', 'android'],
  ['ios-phone', 'iPhone', 'ios'],
  ['mac-laptop', 'Mac', 'macos'],
  ['windows-pc', 'Windows', 'windows'],
];
const freshToken = () => randomBytes(32).toString('hex');
function create(path, data, mode = 0o600) {
  try {
    writeFileSync(path, data, { flag: 'wx', mode });
    console.log('Created ' + path);
    return true;
  } catch (error) {
    if (error.code !== 'EEXIST') throw error;
    return false;
  }
}

mkdirSync('config', { recursive: true });
mkdirSync('private', { recursive: true, mode: 0o700 });
create('config/device-tokens.json', JSON.stringify(devices.map(([deviceId, deviceName, platform]) => ({
  deviceId, deviceName, platform, token: freshToken(),
})), null, 2) + '\n');

for (const scope of ['body', 'context']) {
  const verifier = join('config', scope + '-read.sha256');
  if (existsSync(verifier)) continue;
  const credential = join('private', scope + '-read-token');
  create(credential, freshToken() + '\n');
  const token = readFileSync(credential, 'utf8').trim();
  create(verifier, createHash('sha256').update(token).digest('hex') + '\n');
}
create('.env', readFileSync(template));
