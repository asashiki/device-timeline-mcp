# Android reporter

A minimal, timeline-only Android app: every ~30s it reads the **foreground app**
(via Usage Access) + battery/network and `POST`s to your collector's
`/api/devices/report`. No chat, voice, health, location, or music — just the
device timeline.

It runs as a `specialUse` foreground service with an AlarmManager watchdog and a
boot receiver, tuned for aggressive OEM ROMs (MIUI/HyperOS, vivo/OriginOS, etc.).

## Build

Open `reporters/android` in Android Studio (Giraffe+), or from the CLI:

```bash
cd reporters/android
gradle wrapper          # one-time: generate the gradle wrapper jar/scripts
./gradlew assembleRelease
# unsigned APK → app/build/outputs/apk/release/
```

> The wrapper **jar** is not committed (binary). `gradle wrapper` regenerates it
> using the version pinned in `gradle/wrapper/gradle-wrapper.properties`.
> Android Studio does this automatically on first sync.

Optional signing for a stable update key (so reinstalls don't uninstall first):

```bash
./gradlew assembleRelease \
  -PkeystorePath=/abs/path/key.jks -PkeystorePassword=… -PkeyAlias=… -PkeyPassword=…
```

## Configure (on the device)

1. Install the APK and open it once.
2. Tap **授予 使用情况访问权限 / Grant Usage Access** → enable this app
   (设置 → 应用 → 特殊权限 → 使用情况访问).
3. Tap **关闭电池优化 / Disable battery optimization** so it keeps reporting in
   the background.
4. Fill in:
   - **Server URL**: `https://<your-host>` (or `http://host:4823` on LAN)
   - **Device Token**: the token you minted for this device
5. Toggle **开机自启 / Start on boot** if you want it to survive reboots, then
   tap **启动 / Start**.

The notification shows the current reported app. The screen also shows the last
30 log lines.

## Phone vs. tablet (two Android devices)

Devices are told apart **by token**, not by the app. Install the same APK on
both, and paste the **phone's token** on the phone and the **tablet's token** on
the tablet. In the collector's `DEVICE_TOKENS_JSON` give them different
`deviceId`s (e.g. `android-phone` + `android-tablet`); the collector then files
their samples separately and the console shows them as two cards.

## Payload

| field | value |
|---|---|
| `appId` | package name (e.g. `tv.danmaku.bili`) |
| `windowTitle` | the app's human label (Android has no window titles) |
| `occurredAt` | UTC ISO of the foreground event |
| `extra.battery_percent` / `extra.battery_charging` | when "上报电池" is on |
| `extra.network_type` | `wifi` / `cellular` / `ethernet` / `offline` |

Friendly names + status phrases are resolved **server-side** from
`config/app-labels.json`, so you don't configure per-app overrides on the device.
