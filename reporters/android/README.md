# Android reporter

Hibi reports Android foreground activity, battery level and network type to your collector. The reporting interval defaults to 30 seconds. Health Connect and supported Xiaomi band collection are optional.

It runs as a `specialUse` foreground service with an AlarmManager watchdog and a
boot receiver, tuned for aggressive OEM ROMs (MIUI/HyperOS, vivo/OriginOS, etc.).

## Build and install

Use the Android APK from the release for this source revision. Its application ID is `com.devicetimeline.agent`. Android accepts an upgrade when the package name and signing certificate match the existing installation. A file ending in `-debug.apk` uses the CI runner's temporary debug certificate. Keep your own signing key for repeatable updates.

For a local build, use JDK 17 or 21, Gradle 8.11.1 and Android SDK Platform 36. From this directory run:

```sh
gradle :app:assembleDebug
```

Open `app/build/outputs/apk/debug/app-debug.apk` on the phone and confirm installation in Android. The CI workflow builds the same app and includes the band module by default. For signed release builds, configure the four signing secrets named in the workflow. Android 9 or later supports the activity reporter. The band reader requires Android 12 or later.

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

In 设置, choose which of phone activity, band records and location to collect. The optional phone location uses the system network and passive providers, with a 15-minute minimum interval and roughly 100 metres of movement. Records use the time and accuracy of the original fix. The phone queues missed uploads while offline.

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

## Xiaomi Smart Band 9 Pro

This experimental reader targets the Chinese `miwear.watch.n67cn` model. Pair your own band with Android first. In **手环监测**, choose **导入我的手环配对文件** and select a JSON file exported from your own authorized pairing setup. Supply `model` (`miwear.watch.n67cn`), `mac` (Bluetooth address) and `authKey` (the 32 hexadecimal digits for that pairing). Hibi validates the file and encrypts its contents using Android Keystore. The source file stays wherever you saved it.

Save the collector URL and device token in **连接**. Grant **附近设备** when prompted and turn on **开启手环监测**. Hibi polls roughly every five minutes, keeps measurements locally until the collector acknowledges them, and shows their measurement times. Some firmware returns historical sleep after waking. A missing or expired sleep signal appears as unknown.

## License and corresponding source

The combined Android app is distributed under AGPL-3.0-only. The original Hibi activity reporter components retain their MIT notices. The linked `:healthapp-core` and `:hibi-band` modules, their modifications, build scripts, and the application source are included under `reporters/android/` in the same release tag. The APK contains the AGPL license and upstream attribution. See [HealthAPP source and revision](healthapp-core/UPSTREAM.md) and [the license](hibi-band/src/main/assets/hibi-band/LICENSE-AGPL-3.0.txt).
