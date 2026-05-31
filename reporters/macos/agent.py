#!/usr/bin/env python3
"""
macOS device-timeline agent

Mirrors the Windows agent (Program.cs) on macOS using AppleScript + ioreg.
Polls the foreground app every N seconds and POSTs to /api/devices/report
with the exact same payload shape as the Windows agent, so device_states /
device_activities / MCP tools cover macOS the same way they cover Windows.

Setup:
    pip install -r requirements.txt
    cp config.example.json config.json && edit it
    python3 agent.py
    # then optionally install as a launchd agent — see README.md

Required system permission (one-time):
    System Settings → Privacy & Security → Accessibility
    → enable for Terminal (or whatever runs python3)
    Without this, AppleScript can't read window titles.
"""

from __future__ import annotations

import json
import logging
import logging.handlers
import re
import signal
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

try:
    import requests
except ImportError:
    print("missing dependency: pip install -r requirements.txt", file=sys.stderr)
    sys.exit(1)

try:
    import psutil  # for battery, optional
except ImportError:
    psutil = None  # type: ignore[assignment]


BASE_DIR = Path(__file__).resolve().parent
CONFIG_PATH = BASE_DIR / "config.json"
LOG_PATH = BASE_DIR / "agent.log"


# ── Logging ────────────────────────────────────────────────────────────────

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[
        logging.StreamHandler(sys.stdout),
        logging.handlers.TimedRotatingFileHandler(
            LOG_PATH, when="midnight", backupCount=3, encoding="utf-8"
        ),
    ],
)
log = logging.getLogger("device-timeline-mac-agent")


# ── Config ─────────────────────────────────────────────────────────────────

@dataclass
class AgentConfig:
    server_url: str = ""
    token: str = ""
    device_name: str = "Mac"
    report_interval_seconds: int = 10
    heartbeat_interval_seconds: int = 60
    afk_threshold_seconds: int = 300
    custom_apps: list[dict[str, str]] = field(default_factory=list)


def load_config() -> AgentConfig:
    if not CONFIG_PATH.exists():
        log.error("config not found: %s — copy config.example.json", CONFIG_PATH)
        sys.exit(2)
    raw = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    cfg = AgentConfig(
        server_url=str(raw.get("serverUrl", "")).rstrip("/"),
        token=str(raw.get("token", "")),
        device_name=str(raw.get("deviceName") or "Mac"),
        report_interval_seconds=int(raw.get("reportIntervalSeconds", 10)),
        heartbeat_interval_seconds=int(raw.get("heartbeatIntervalSeconds", 60)),
        afk_threshold_seconds=int(raw.get("afkThresholdSeconds", 300)),
        custom_apps=list(raw.get("customApps") or []),
    )
    if not cfg.server_url or not cfg.token:
        log.error("config.json missing serverUrl or token")
        sys.exit(2)
    return cfg


# ── Foreground app detection (AppleScript) ─────────────────────────────────

_FRONT_APP_SCRIPT = """\
tell application "System Events"
    set frontProc to first application process whose frontmost is true
    set bid to ""
    try
        set bid to bundle identifier of frontProc
    end try
    set appName to name of frontProc
    set winTitle to ""
    try
        set winTitle to name of front window of frontProc
    end try
    return bid & "|SEP|" & appName & "|SEP|" & winTitle
end tell
"""


def get_foreground() -> tuple[str, str, str] | None:
    """Return (bundle_id, app_name, window_title) or None."""
    try:
        result = subprocess.run(
            ["osascript", "-e", _FRONT_APP_SCRIPT],
            capture_output=True, text=True, timeout=5,
        )
    except (subprocess.TimeoutExpired, FileNotFoundError):
        return None
    if result.returncode != 0:
        return None
    parts = result.stdout.strip().split("|SEP|")
    if len(parts) < 3:
        return None
    bid, app_name, win_title = parts[0].strip(), parts[1].strip(), parts[2].strip()
    if not app_name:
        return None
    return bid, app_name, win_title


# ── Idle detection (ioreg HIDIdleTime) ─────────────────────────────────────

_HID_IDLE_RE = re.compile(r"\"HIDIdleTime\"\s*=\s*(\d+)")


def get_idle_seconds() -> float:
    try:
        result = subprocess.run(
            ["ioreg", "-c", "IOHIDSystem", "-d", "4"],
            capture_output=True, text=True, timeout=5,
        )
    except (subprocess.TimeoutExpired, FileNotFoundError):
        return 0.0
    m = _HID_IDLE_RE.search(result.stdout)
    if not m:
        return 0.0
    # HIDIdleTime is in nanoseconds.
    return int(m.group(1)) / 1_000_000_000


# ── Battery ────────────────────────────────────────────────────────────────

def get_battery_extra() -> dict[str, Any]:
    if psutil is None:
        return {}
    try:
        battery = psutil.sensors_battery()
    except Exception:
        return {}
    if battery is None:
        return {}
    return {
        "battery_percent": int(battery.percent),
        "battery_charging": bool(battery.power_plugged),
    }


# ── Reporter ───────────────────────────────────────────────────────────────

class Reporter:
    def __init__(self, cfg: AgentConfig) -> None:
        self.cfg = cfg
        self.endpoint = f"{cfg.server_url}/api/devices/report"
        self.session = requests.Session()
        self.session.headers.update({
            "Authorization": f"Bearer {cfg.token}",
            "Content-Type": "application/json",
            "User-Agent": "device-timeline-mac-agent/1.0.0",
        })
        self._last_key: str = ""
        self._last_sent_at: float = 0.0
        self._consecutive_failures = 0

    def maybe_send(self, app_id: str, window_title: str, extra: dict[str, Any]) -> None:
        key = f"{app_id}|{window_title}"
        now = time.time()
        elapsed = now - self._last_sent_at
        force_heartbeat = elapsed >= self.cfg.heartbeat_interval_seconds
        if key == self._last_key and not force_heartbeat:
            return

        body: dict[str, Any] = {
            "appId": app_id,
            "windowTitle": window_title[:256],
            "occurredAt": _utc_iso(),
        }
        if extra:
            body["extra"] = extra

        try:
            resp = self.session.post(self.endpoint, json=body, timeout=10)
        except requests.RequestException as exc:
            self._on_failure(f"network error: {exc}")
            return

        if 200 <= resp.status_code < 300:
            self._last_key = key
            self._last_sent_at = now
            self._consecutive_failures = 0
            log.info("→ %s  %s", app_id, window_title[:80])
            return

        self._on_failure(
            f"HTTP {resp.status_code}: {resp.text[:200]}"
        )

    def _on_failure(self, msg: str) -> None:
        self._consecutive_failures += 1
        log.warning("report failed (%d): %s", self._consecutive_failures, msg)
        # Mild back-off so we don't hammer a sick server.
        time.sleep(min(self._consecutive_failures, 5) * 2)


def _utc_iso() -> str:
    # Match Windows agent's DateTime.UtcNow.ToString("o").
    return time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime())


# ── Custom-app rule application ────────────────────────────────────────────

def apply_custom_rule(
    rules: list[dict[str, str]], app_id: str, window_title: str
) -> dict[str, Any]:
    """Return extra fields to merge in: custom_app_name + custom_description."""
    for rule in rules:
        key = str(rule.get("appId", "")).strip()
        if not key:
            continue
        if key.lower() in app_id.lower():
            extra: dict[str, Any] = {}
            name = rule.get("customAppName")
            desc = rule.get("customDescription")
            if name:
                extra["custom_app_name"] = name
            if desc:
                extra["custom_description"] = (
                    desc.replace("{title}", window_title)
                        .replace("{appId}", app_id)
                        .replace("{app}", name or app_id)
                )
            return extra
    return {}


# ── Main loop ──────────────────────────────────────────────────────────────

_running = True


def _handle_sigterm(*_: Any) -> None:
    global _running
    log.info("shutting down")
    _running = False


def main() -> None:
    signal.signal(signal.SIGTERM, _handle_sigterm)
    signal.signal(signal.SIGINT, _handle_sigterm)

    cfg = load_config()
    reporter = Reporter(cfg)
    log.info(
        "Asashiki Mac Agent up — server=%s interval=%ds heartbeat=%ds afk=%ds",
        cfg.server_url, cfg.report_interval_seconds,
        cfg.heartbeat_interval_seconds, cfg.afk_threshold_seconds,
    )

    while _running:
        try:
            tick(cfg, reporter)
        except Exception:
            log.exception("tick crashed")
        # sleep in 0.5s slices so SIGTERM is responsive
        slept = 0.0
        while _running and slept < cfg.report_interval_seconds:
            time.sleep(0.5)
            slept += 0.5


def tick(cfg: AgentConfig, reporter: Reporter) -> None:
    idle = get_idle_seconds()

    if idle >= cfg.afk_threshold_seconds:
        app_id = "macos.afk"
        window_title = f"AFK ({int(idle)}s)"
        extra = get_battery_extra()
        reporter.maybe_send(app_id, window_title, extra)
        return

    front = get_foreground()
    if front is None:
        # AppleScript blocked / no permission / no foreground.
        reporter.maybe_send("macos.idle", "", get_battery_extra())
        return

    bid, app_name, window_title = front
    # Prefer bundle ID as appId (stable across locales), fall back to app name.
    app_id = bid or app_name or "macos.unknown"

    extra = get_battery_extra()
    extra.update(apply_custom_rule(cfg.custom_apps, app_id, window_title))
    # Window title can be empty for menu-bar-only apps; that's fine.
    reporter.maybe_send(app_id, window_title, extra)


if __name__ == "__main__":
    main()
