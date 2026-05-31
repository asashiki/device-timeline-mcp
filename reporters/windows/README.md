# Windows reporter

Samples the foreground window + process every ~10s and `POST`s to the
collector's `/api/devices/report` with a Bearer token. Lives in the system tray
with a settings dialog and an optional "start with Windows" registry entry.

## Build

Requires the .NET 9 SDK.

```powershell
dotnet publish -c Release
# single-file, self-contained exe lands in bin/Release/net9.0-windows/win-x64/publish/
```

## Configure

First run drops a tray icon. Right-click → **设置 / Settings**:

- **Server URL**: `https://<your-host>` (or `http://host:4200` on LAN)
- **Device Token**: the token you minted for this PC (`windows-pc` in the
  collector's `DEVICE_TOKENS_JSON`)
- **设备显示名 / Device name**: free-form label
- **上报间隔 / Report interval**, **心跳 / Heartbeat**, **AFK 阈值 / AFK threshold**
- **开机自动启动 / Start with Windows** — writes `HKCU\…\Run`

Settings persist to `appsettings.json` next to the exe. You can also pre-seed it
from `appsettings.example.json`.

## Payload

`appId` = lowercased process name (e.g. `chrome`, `code`), `windowTitle` = the
foreground window title (truncated to 256). When idle ≥ AFK threshold it reports
`appId="windows.afk"`; with no foreground window, `windows.idle`. `customApps`
rules rewrite the displayed name/description client-side via `extra`.
