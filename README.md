# device-timeline-mcp

> **English** | [中文](README.zh-CN.md)

Self-hosted, multi-device **activity timeline collector** with a built-in **MCP server**.

It tracks *what app you're using, on which device, right now* across **Android, iOS, Windows and macOS**, stores it in a single SQLite database, and exposes it three ways:

- a **read-only HTTP API** (for your own status pages / frontends),
- a **web console** for eyeballing the data,
- an **MCP server** so an AI assistant (Claude Desktop, Claude Code, …) can answer "what is he doing right now?" / "how much time on B站 today?".

Single-user, self-hosted, no account system. You run one collector; each of your devices reports to it with its own token.

---

## Architecture

```
┌─────────────┐   HTTPS POST /api/devices/report (Bearer token)
│  agents     │ ───────────────────────────────────────────────┐
│ android/ios │                                                 ▼
│ windows/mac │                                         ┌──────────────────┐
└─────────────┘                                         │  collector       │
                                                        │  (this service)  │
┌─────────────┐   GET /api/devices/* (read-only)        │  Fastify+SQLite  │
│ your web UI │ ◀──────────────────────────────────────▶│  + /console      │
└─────────────┘                                         └──────────────────┘
                                                                 ▲
┌─────────────┐   stdio (runs on your laptop)                   │ HTTP
│ Claude /    │ ──▶  src/mcp/server.ts  ────────────────────────┘
│ MCP client  │      (device_status / device_timeline / device_activity_summary)
└─────────────┘
```

The **collector** runs on a server (Docker). The **MCP server** is a thin stdio
process you run wherever your AI client lives; it just calls the collector's
read API.

---

## Quick start (Docker)

```bash
git clone <this-repo> device-timeline-mcp
cd device-timeline-mcp
cp .env.example .env
# edit .env → generate a token per device (see below)
docker compose up -d --build
```

Open `http://<host>:4200/console` — you should see your devices appear as they
start reporting. Health check: `curl http://<host>:4200/health`.

### Without Docker (Node ≥ 22.5)

```bash
npm install
cp .env.example .env   # edit it
npm run build && npm start      # or: npm run dev
```

> **Why Node 22.5+?** The collector uses Node's built-in `node:sqlite` — no
> native module to compile.

---

## Device tokens & multi-device naming

Every device authenticates with its own token. Define them in `.env` under
`DEVICE_TOKENS_JSON` (or point `DEVICE_TOKENS_FILE` at a JSON file).

```jsonc
[
  {"token":"<openssl rand -hex 32>","deviceId":"android-phone","deviceName":"我的手机","platform":"android"},
  {"token":"<openssl rand -hex 32>","deviceId":"android-tablet","deviceName":"平板 Y700","platform":"android"},
  {"token":"<openssl rand -hex 32>","deviceId":"windows-pc","deviceName":"台式机","platform":"windows"},
  {"token":"<openssl rand -hex 32>","deviceId":"mac-laptop","deviceName":"MacBook","platform":"macos"},
  {"token":"<openssl rand -hex 32>","deviceId":"ios-phone","deviceName":"iPhone","platform":"ios"}
]
```

**The key idea for "two of the same platform":** devices are told apart by
**`deviceId`**, *not* by platform. If you have an Android **phone** and an
Android **tablet** (like a typical setup), give them **different `deviceId`s** —
e.g. `android-phone` and `android-tablet` — each with its **own token**. They
both have `platform: "android"` and both land in the same tables; every query
can filter by `deviceId`, and the console shows them as separate cards.

- `deviceId`: kebab-case, unique, stable. This is the join key — don't change it later.
- `deviceName`: free-form display label (shown in the console / MCP output).
- Generate each token with `openssl rand -hex 32`.

---

## Per-platform agent setup

The agents live in [`agents/`](agents/) — each has its own README with build +
setup steps:

| platform | source | notes |
|---|---|---|
| Android | [`agents/android`](agents/android) | timeline-only Kotlin app (foreground service) |
| Windows | [`agents/windows`](agents/windows) | .NET tray app, single-file exe |
| macOS | [`agents/macos`](agents/macos) | Python + launchd daemon |
| iOS | [`agents/ios`](agents/ios) | Shortcuts automations (no installable app) |

The desktop/Android agents do the same thing: sample the foreground app + window
title every ~10s and `POST /api/devices/report` with their Bearer token. All you
configure is the **server URL** and the device **token**.

### Don't want to compile? Grab prebuilt binaries

This repo ships GitHub Actions that build the **APK** and the **Windows exe** for
you:

- push to `main` or run the workflow manually → artifacts appear under the
  workflow run's **Artifacts**.
- push a `v*` tag (`git tag v1.0.0 && git push --tags`) → the built APK + exe are
  **attached to the matching GitHub Release** automatically.

The APK is debug-signed by default (installable as-is). For a release-signed
build, add repo secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`.

### Android (phone & tablet)

Build the APK from [`agents/android`](agents/android) (Android Studio or
`./gradlew assembleRelease`), then:

1. Install the APK on the device.
2. Open it once → grant **Usage Access** (设置 → 应用 → 特殊权限 → 使用情况访问) and
   disable battery optimization for it (so it keeps reporting in the background).
3. In the app's settings, set:
   - **Server URL**: `https://<your-host>` (or `http://host:4200` on LAN)
   - **Token**: the token for this device
4. For a **phone + tablet**, install on both, and paste the **phone token** on the
   phone and the **tablet token** on the tablet. That's the whole distinction.

### iOS — Shortcuts automations

iOS has no background agent; you drive it with two **Personal Automations** in the
**Shortcuts** app plus an hourly snapshot.

**A. "App Opened" automation** (fires when you open any tracked app):

- New Automation → **App** → choose the apps to track → **Is Opened**.
- Action: **Get Contents of URL** → `POST https://<host>/api/devices/ios/app-event`
  with header `Authorization: Bearer <ios token>` and JSON body
  `{"app":"<App Name>","action":"open"}`.
- Turn **off** "Ask Before Running".

![iOS App Opened automation](https://picture-img.leqazwsxedc.workers.dev/image_2026-05-31_06-17-49.png)

**B. "App Closed" automation** — same as above with `"action":"close"`.

![iOS App Closed automation](https://picture-img.leqazwsxedc.workers.dev/image_2026-05-31_06-17-56.png)

Because iOS only reports on open/close events (plus an optional hourly Time-of-Day
snapshot), an iOS device is considered "online" for **65 minutes** after its last
event, not 5.

See [`agents/ios`](agents/ios) for the full Shortcuts walkthrough and the
`/api/devices/ios/app-event` + `/api/devices/ios/snapshot` body shapes.

### Windows

1. Drop the agent `.exe` on the machine (single-file, self-contained).
2. First run → tray icon → **Settings**:
   - **Server URL**: `https://<host>`
   - **Token**: the `windows-pc` token
3. Enable **Start with Windows** (writes an `HKCU\…\Run` entry).

### macOS

1. `pip3 install -r requirements.txt` (or use the packaged build once ported).
2. Grant **Accessibility** permission to the terminal/app running it
   (System Settings → Privacy & Security → Accessibility) — needed to read window titles.
3. Edit `config.json` → `serverUrl` + `token` (the `mac-laptop` token).
4. Install as a launchd agent for auto-start (see `agents/mac/README` after port).

---

## Connect an MCP client

Run the MCP server where your client lives, pointed at the collector:

```jsonc
// Claude Desktop: claude_desktop_config.json
{
  "mcpServers": {
    "device-timeline": {
      "command": "node",
      "args": ["/abs/path/device-timeline-mcp/dist/mcp/server.js"],
      "env": { "MCP_API_BASE": "https://<your-host>" }
    }
  }
}
```

Tools exposed:

| tool | what it answers |
|---|---|
| `device_status` | what every device is doing right now (online, foreground app, battery) |
| `device_timeline` | chronological activity for a day (filterable by `deviceId`) |
| `device_activity_summary` | per-app screen-time totals for a day |

---

## Read API (for your own frontends)

CORS is enabled (`CORS_ORIGIN`, default `*`). All timestamps are **UTC ISO**;
`date=` means a calendar day in `DISPLAY_TZ` (default `Asia/Shanghai`).

| endpoint | purpose |
|---|---|
| `GET /health` | liveness + schema version |
| `GET /api/devices/current` | latest state per device (+ `appName`, `live` text) |
| `GET /api/devices/timeline-query?date=&deviceId=&limit=` | activity list |
| `GET /api/devices/activity-summary?date=&deviceId=` | per-app totals |
| `GET /api/app-labels` | the raw appId → {name, desc} map |
| `POST /api/devices/report` | **ingest** (Android/desktop agents, Bearer token) |
| `POST /api/devices/ios/app-event` | **ingest** iOS open/close (Bearer token) |
| `POST /api/devices/ios/snapshot` | **ingest** iOS battery/focus snapshot (Bearer token) |

`current` / `timeline` responses include server-computed `appName` and `live`
(a natural-language phrase), so frontends don't need to reimplement the label
logic. The `/console` page is a working example.

---

## Customizing app names

`config/app-labels.json` maps an `appId` (Android bundle id, Windows process
name, macOS bundle id, or iOS app name) to a friendly name + status phrase:

```json
{ "tv.danmaku.bili": { "name": "哔哩哔哩", "desc": "正在刷 B站~" } }
```

Edit the file and **save** — the collector **hot-reloads** it (no restart, no
rebuild), because it's read from a mounted volume (`./config`). Unknown apps fall
back to a capitalized last path segment.

---

## Data model

Two tables (see `src/db/migrations.ts`). Migrations are versioned via
`PRAGMA user_version`.

- **`device_states`** — one row per device: current foreground app, last seen, `extra` JSON.
- **`device_activities`** — append-only runs of "same app, contiguous in time"
  (consecutive samples within `ACTIVITY_GRACE_SECONDS` are merged into one row).

The SQLite file lives on the `./data` volume.

## Backups

The DB is a single file on the `./data` volume, so the simplest backup is
copying it (ideally `sqlite3 db '.backup ...'` or `VACUUM INTO` for a consistent
snapshot while running). A built-in scheduled-backup feature is **planned but not
yet implemented** — there's a reserved hook in `src/db/index.ts`.

## License

MIT.
