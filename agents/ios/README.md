# iOS agent (Shortcuts automations)

iOS has no background agent you can install, so you drive it with **Shortcuts**
personal automations that call the collector over HTTPS. Two automations
(app-open + app-close) give you a live timeline; an optional hourly automation
adds battery / focus state.

All requests use the **device Bearer token** you minted for this iPhone
(`platform: "ios"` in the collector's `DEVICE_TOKENS_JSON`). Because everything
lands in the shared device tables, the same MCP tools and read API cover iOS
automatically.

> Because iOS only reports on events (not a ~10s poll), an iOS device is treated
> as "online" for **65 minutes** after its last event instead of 5.

## A. "App Opened" automation

Fires when you open any tracked app.

1. Shortcuts → **Automation** → **+** → **App** → choose the apps to track →
   **Is Opened** → **Run Immediately** (turn **off** "Ask Before Running").
2. Add action **Get Contents of URL**:
   - **URL**: `https://<your-host>/api/devices/ios/app-event`
   - **Method**: `POST`
   - **Headers**: `Authorization` = `Bearer <ios token>`
   - **Request Body**: **JSON**
     - `app` (Text) = the **Shortcut Input** app name (or a fixed name per
       automation)
     - `action` (Text) = `open`

![App opened automation](../../docs/img/ios-app-opened.png)

## B. "App Closed" automation

Same as **A**, but select **Is Closed** and set `action` = `close`. The `app`
field can be omitted for close events.

![App closed automation](../../docs/img/ios-app-closed.png)

## C. (Optional) hourly snapshot

Time-of-Day automation (e.g. every hour) → **Get Contents of URL**:

- **URL**: `https://<your-host>/api/devices/ios/snapshot`
- **Method**: `POST`, header `Authorization: Bearer <ios token>`
- **JSON body** (all fields optional):
  - `batteryLevel` (Number, 0–100) — from the **Battery Level** action
  - `isCharging` (Boolean)
  - `isUnlocked` (Boolean)
  - `focusMode` (Text)

These merge into the device's `extra` without disturbing the open/close app
state.

## Endpoint reference

| endpoint | body | effect |
|---|---|---|
| `POST /api/devices/ios/app-event` | `{ "app": "B站", "action": "open" }` | opens a new activity, sets current app |
| `POST /api/devices/ios/app-event` | `{ "action": "close" }` | closes the open activity, device goes idle |
| `POST /api/devices/ios/snapshot` | `{ "batteryLevel": 80, "isCharging": true }` | merges battery/focus into `extra` |

Stale safety: any activity left open for >2h is force-closed on the next event,
so a missed "close" automation won't leave a run open forever.

Screenshots referenced above live in `docs/img/` — drop real captures there
(see `docs/img/README.md`).
