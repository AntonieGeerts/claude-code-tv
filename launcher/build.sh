#!/usr/bin/env bash
# Builds the Attic launcher APK with plain SDK tools (no Gradle).
# Reads READ_TOKEN / LAUNCHER_KEYSTORE_PASS from ../.env.local (gitignored).
# Output: build/attic-home.apk
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
JAR="$SDK/platforms/android-34/android.jar"
ENV_FILE="${ENV_FILE:-../.env.local}"
KEYSTORE="attic-launcher.jks"

# Values given on the command line (STATUS_URL=… READ_TOKEN=… ./build.sh) win over the env file.
_cli_url="${STATUS_URL:-}"; _cli_token="${READ_TOKEN:-}"
[[ -f "$ENV_FILE" ]] && set -a && source "$ENV_FILE" && set +a
[[ -n "$_cli_url" ]] && STATUS_URL="$_cli_url"
[[ -n "$_cli_token" ]] && READ_TOKEN="$_cli_token"
: "${STATUS_URL:?STATUS_URL missing (expected in ../.env.local, e.g. https://<project>.vercel.app/api/status)}"
: "${READ_TOKEN:?READ_TOKEN missing (expected in ../.env.local)}"

if [[ ! -f "$KEYSTORE" ]]; then
  if [[ -z "${LAUNCHER_KEYSTORE_PASS:-}" ]]; then
    LAUNCHER_KEYSTORE_PASS="$(openssl rand -hex 16)"
    printf 'LAUNCHER_KEYSTORE_PASS=%s\n' "$LAUNCHER_KEYSTORE_PASS" >> "$ENV_FILE"
  fi
  keytool -genkeypair -keystore "$KEYSTORE" -alias attic -keyalg RSA -keysize 3072 -validity 36500 \
    -storepass "$LAUNCHER_KEYSTORE_PASS" -keypass "$LAUNCHER_KEYSTORE_PASS" \
    -dname "CN=Attic Home" >/dev/null 2>&1
  echo "created signing key $KEYSTORE (keep it: every future update must be signed with it)"
fi

rm -rf build && mkdir -p build/gen/tv/attic/home build/classes build/dex build/res

# Generated config (token never committed)
cat > build/gen/tv/attic/home/Config.java <<EOF
package tv.attic.home;
final class Config {
    static final String STATUS_URL = "$STATUS_URL";
    static final String READ_TOKEN = "$READ_TOKEN";
    private Config() {}
}
EOF

"$BT/aapt2" compile --dir res -o build/res/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -o build/unsigned.apk \
  --java build/gen --min-sdk-version 30 --target-sdk-version 34 build/res/res.zip

javac -Xlint:-options --release 11 -encoding UTF-8 -classpath "$JAR" -d build/classes \
  $(find src build/gen -name '*.java')

"$BT/d8" --release --min-api 30 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q -j ../unsigned.apk classes.dex)

"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias attic --ks-pass "pass:$LAUNCHER_KEYSTORE_PASS" \
  --out build/attic-home.apk build/aligned.apk
"$BT/apksigner" verify build/attic-home.apk
ls -la build/attic-home.apk
