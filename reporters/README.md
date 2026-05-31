# Reporters

Each device runs a small **reporter** that samples its foreground app and
`POST`s to the collector with a per-device Bearer token. They all target the
same `/api/devices/*` ingest API, so every platform lands in the shared tables
and is covered by the same console / read API / MCP tools.

> Naming note: "reporter" = the per-device program that *reports* activity to the
> collector. It is **not** an "agent" — in this project "agent" is reserved for
> the LLM side (Claude / GPT) that consumes the MCP tools.

| platform | folder | how it runs |
|---|---|---|
| Android | [`android/`](android) | Kotlin foreground service (`/api/devices/report`) |
| Windows | [`windows/`](windows) | .NET system-tray app (`/api/devices/report`) |
| macOS | [`macos/`](macos) | Python + launchd daemon (`/api/devices/report`) |
| iOS | [`ios/`](ios) | Shortcuts automations (`/api/devices/ios/*`) |

Two devices of the same platform (e.g. an Android phone + tablet) are told apart
**by token / `deviceId`**, not by the app — see each folder's README and the
root README's "Device tokens & multi-device naming" section.
