#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
build_only=
case ${1:-} in
    "") ;;
    --build-only) [ "$#" -eq 2 ] || { echo "Usage: $0 --build-only output-directory" >&2; exit 2; }; build_only=$2 ;;
    *) echo "Usage: $0 [--build-only output-directory]" >&2; exit 2 ;;
esac
app="$HOME/Applications/Hibi Mac.app"
label="com.asashiki.hibi.mac"
domain="gui/$(id -u)"
plist="$HOME/Library/LaunchAgents/$label.plist"
support="$HOME/Library/Application Support/Hibi"
if [ -n "$build_only" ]; then
    mkdir -p "$build_only"
    [ ! -e "$build_only/Hibi Mac.app" ] || { echo "Hibi Mac.app already exists there." >&2; exit 1; }
    stage=$(mktemp -d "$build_only/.hibi-build.XXXXXXXX")
else
    mkdir -p "$HOME/Applications" "$HOME/Library/LaunchAgents" "$support"
    if [ ! -f "$HOME/.config/hibi/mac.json" ]; then python3 "$root/configure.py"; fi
    stage=$(mktemp -d "$HOME/Applications/.hibi-install.XXXXXXXX")
fi
trap 'rm -rf "$stage"' EXIT
new_app="$stage/Hibi Mac.app"
mkdir -p "$new_app/Contents/MacOS"
nice -n 10 swiftc -parse-as-library "$root/"*.swift -o "$new_app/Contents/MacOS/HibiMac" -framework AppKit -framework ApplicationServices -framework IOKit -framework CoreAudio
cat > "$new_app/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>CFBundleExecutable</key><string>HibiMac</string><key>CFBundleIdentifier</key><string>com.asashiki.hibi.mac</string><key>CFBundleName</key><string>Hibi Mac</string><key>CFBundleVersion</key><string>3</string><key>NSAccessibilityUsageDescription</key><string>读取当前前台窗口标题，供你通过 Hibi 私有接口查询。</string><key>LSUIElement</key><true/><key>NSHighResolutionCapable</key><true/></dict></plist>
PLIST
codesign --force --sign - "$new_app"
codesign --verify "$new_app"
if [ -n "$build_only" ]; then
    mv "$new_app" "$build_only/Hibi Mac.app"
    echo "Built $build_only/Hibi Mac.app"
    exit 0
fi
/usr/bin/python3 - "$stage/agent.plist" "$app" "$support" "$label" <<'PY'
import pathlib, plistlib, sys

dest, app, support, label = sys.argv[1:]
with open(dest, "wb") as f:
    plistlib.dump({
        "Label": label,
        "LimitLoadToSessionType": "Aqua",
        "ProgramArguments": [str(pathlib.Path(app) / "Contents/MacOS/HibiMac"), "--background"],
        "RunAtLoad": True,
        "KeepAlive": {"SuccessfulExit": False},
        "ThrottleInterval": 30,
        "StandardOutPath": str(pathlib.Path(support) / "app.log"),
        "StandardErrorPath": str(pathlib.Path(support) / "app-error.log"),
    }, f)
PY
plutil -lint "$stage/agent.plist"
backup="$HOME/.local/share/hibi/app-backups/install-$(date +%Y%m%dT%H%M%S)-$$"
mkdir -p "$backup"
if [ -d "$app" ]; then ditto "$app" "$backup/Hibi Mac.app"; fi
if [ -f "$plist" ]; then cp -p "$plist" "$backup/agent.plist"; fi
# A pre-existing app started via LaunchServices is outside the old job's PID.
launchctl bootout "$domain/$label" 2>/dev/null || true
oldpid=$(pgrep -x HibiMac || true)
if [ -n "$oldpid" ]; then
    for pid in $oldpid; do
        if [ "$(ps -p "$pid" -o comm=)" = "$app/Contents/MacOS/HibiMac" ]; then
            kill -TERM "$pid"
            for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$pid" 2>/dev/null || break; sleep .2; done
            if kill -0 "$pid" 2>/dev/null; then echo "Existing Hibi app is still running" >&2; exit 1; fi
        fi
    done
fi
if [ -d "$app" ]; then mv "$app" "$stage/prior.app"; fi
mv "$new_app" "$app"
install -m 644 "$stage/agent.plist" "$plist"
if ! launchctl bootstrap "$domain" "$plist"; then
    mv "$app" "$stage/failed.app"
    if [ -d "$stage/prior.app" ]; then mv "$stage/prior.app" "$app"; fi
    if [ -f "$backup/agent.plist" ]; then
        cp -p "$backup/agent.plist" "$plist"
        launchctl bootstrap "$domain" "$plist"
    else
        rm -f "$plist"
    fi
    exit 1
fi
echo "Installed Hibi Mac; previous app and launch settings saved in $backup"
