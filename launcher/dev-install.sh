#!/usr/bin/env bash
# Builds a side-by-side test copy (package tv.attic.home.dev) and launches it on a TV.
# Usage: ./dev-install.sh <adb-serial>
set -euo pipefail
cd "$(dirname "$0")"
# adb.exe (Windows, used from WSL) needs a Windows path to the APK
apk_path() { if [[ "$ADB" == *.exe ]]; then wslpath -w "$PWD/$1"; else echo "$PWD/$1"; fi; }
SERIAL="${1:?adb serial, e.g. <tv-ip>:<port>}"
[[ -z "${ADB:-}" && -f ../.env.local ]] && ADB="$(grep -m1 '^ADB=' ../.env.local | cut -d= -f2-)"
ADB="${ADB:-adb}"   # on WSL you can point this at the Windows adb.exe
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"; BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"; JAR="$SDK/platforms/android-34/android.jar"
./build.sh >/dev/null
set -a; source ../.env.local; set +a
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml --rename-manifest-package tv.attic.home.dev \
  -o build/dev-unsigned.apk --min-sdk-version 30 --target-sdk-version 34 build/res/res.zip
(cd build/dex && zip -q -j ../dev-unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/dev-unsigned.apk build/dev-aligned.apk
"$BT/apksigner" sign --ks attic-launcher.jks --ks-key-alias attic --ks-pass "pass:$LAUNCHER_KEYSTORE_PASS" --out build/attic-home-dev.apk build/dev-aligned.apk
"$ADB" -s "$SERIAL" install -r "$(apk_path build/attic-home-dev.apk)" | tr -d '\r' | tail -1
"$ADB" -s "$SERIAL" shell am force-stop tv.attic.home.dev
"$ADB" -s "$SERIAL" shell am start -n tv.attic.home.dev/tv.attic.home.HomeActivity >/dev/null
