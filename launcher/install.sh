#!/usr/bin/env bash
# Install / update the Attic launcher on a TV over ADB and make it the home app.
# Usage: ./install.sh <adb-serial>      e.g. ./install.sh <tv-ip>:<port>
#
# First install on a TV that still has the ORIGINAL launcher (different signing key):
#   ./install.sh <serial> --replace     (uninstalls the old tv.attic.home first; backup in ../backup/)
set -euo pipefail
cd "$(dirname "$0")"
# adb.exe (Windows, used from WSL) needs a Windows path to the APK
apk_path() { if [[ "$ADB" == *.exe ]]; then wslpath -w "$PWD/$1"; else echo "$PWD/$1"; fi; }
SERIAL="${1:?adb serial}"; MODE="${2:-}"
[[ -z "${ADB:-}" && -f ../.env.local ]] && ADB="$(grep -m1 '^ADB=' ../.env.local | cut -d= -f2-)"
ADB="${ADB:-adb}"   # on WSL you can point this at the Windows adb.exe
A=("$ADB" -s "$SERIAL")

[[ -f build/attic-home.apk ]] || ./build.sh

if [[ "$MODE" == "--replace" ]]; then
  "${A[@]}" uninstall tv.attic.home.dev >/dev/null 2>&1 || true
  "${A[@]}" uninstall tv.attic.home | tr -d '\r'
fi
"${A[@]}" install -r "$(apk_path build/attic-home.apk)" | tr -d '\r' | tail -1
"${A[@]}" shell appops set tv.attic.home SYSTEM_ALERT_WINDOW allow          # pop-up alerts over other apps
"${A[@]}" shell pm grant tv.attic.home android.permission.POST_NOTIFICATIONS || true
"${A[@]}" shell cmd notification allow_listener tv.attic.home/tv.attic.home.NotifListener   # bell shows Android notifications
"${A[@]}" shell cmd package set-home-activity tv.attic.home/.HomeActivity | tr -d '\r'
"${A[@]}" shell am start -n tv.attic.home/.HomeActivity >/dev/null
echo "installed $("${A[@]}" shell dumpsys package tv.attic.home | tr -d '\r' | grep -m1 versionName | xargs) on $SERIAL"
