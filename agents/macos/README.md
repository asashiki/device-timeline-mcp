# macOS agent

Samples the foreground app (bundle id + window title) and battery every ~10s and
`POST`s to the collector's `/api/devices/report`, mirroring the Windows agent so
macOS lands in the same `device_states` / `device_activities` tables and is
covered by the same MCP tools.

## One-time setup

**1. Mint a device token** in the collector's `DEVICE_TOKENS_JSON`, e.g.
`{"token":"…","deviceId":"mac-laptop","deviceName":"MacBook","platform":"macos"}`
(`openssl rand -hex 32` for the token).

**2. Install Python deps**

```bash
cd agents/macos
pip3 install -r requirements.txt
```

**3. Grant Accessibility permission**

System Settings → Privacy & Security → **Accessibility** → enable for Terminal
(or whatever runs `python3`). Without it AppleScript can't read window titles
(app id still works).

**4. Write config.json**

```bash
cp config.example.json config.json   # edit serverUrl + token
```

## Run (foreground)

```bash
python3 agent.py
```

You should see lines like:

```
2026-05-23 18:00:11 [INFO] → com.apple.Safari  GitHub
2026-05-23 18:00:21 [INFO] → com.microsoft.VSCode  agent.py
```

Verify server-side: `curl -s https://<host>/api/devices/current`.

## Background daemon (launchd)

```bash
cp com.devicetimeline.agent.plist.example ~/Library/LaunchAgents/com.devicetimeline.agent.plist
# edit the two REPLACE_ME paths to agent.py's absolute path
launchctl load -w ~/Library/LaunchAgents/com.devicetimeline.agent.plist
```

Auto-starts on login, restarts on exit. Logs at
`/tmp/devicetimeline-mac-agent.{out,err}.log` and `agent.log` (daily rotation).
Uninstall with `launchctl unload -w …` then `rm` the plist.

## Fields

| field | value |
|---|---|
| `appId` | bundle id (e.g. `com.apple.Safari`), falls back to process/app name |
| `windowTitle` | foreground window title, truncated to 256 |
| `occurredAt` | UTC ISO (server converts to display tz) |
| `extra.battery_percent` / `extra.battery_charging` | from `psutil` |
| `extra.custom_app_name` / `extra.custom_description` | when a `customApps` rule matches |

AFK (idle ≥ `afkThresholdSeconds`) reports `appId="macos.afk"`; no foreground →
`macos.idle`. Find a bundle id with `osascript -e 'id of app "Safari"'`.
`{title}` / `{appId}` / `{app}` placeholders work in `customDescription`.
