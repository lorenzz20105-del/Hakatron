#!/usr/bin/env bash
# Builds the signed APK of LinuxDesk.
#
#   tools/build_release.sh              the release APK
#   tools/build_release.sh --debug      the debug APK
#
# The script creates the signing key on the first run, builds the APK, puts
# it into release/ and verifies the signature with apksigner.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# The toolchain of this machine lives outside of the home directory.
# shellcheck source=/dev/null
source "$ROOT/tools/env.sh"

VARIANT="release"
if [ "${1:-}" = "--debug" ]; then
  VARIANT="debug"
fi

KEYSTORE="$ROOT/app/keystore/linuxdesk.jks"
KEY_ALIAS="linuxdesk"

if [ ! -f "$KEYSTORE" ]; then
  echo "==> creating the signing key"
  mkdir -p "$(dirname "$KEYSTORE")"
  "$JAVA_HOME/bin/keytool" -genkeypair \
    -keystore "$KEYSTORE" \
    -storepass linuxdesk \
    -keypass linuxdesk \
    -alias "$KEY_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=LinuxDesk, OU=Development, O=LinuxDesk, L=Manila, ST=Metro Manila, C=PH"
fi

echo "==> building the $VARIANT APK"
# lintVital needs a lot of memory, this machine has two gigabytes.
gradle --no-daemon --console=plain -x lintVitalRelease ":app:assemble$VARIANT"

APK="$ROOT/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
if [ ! -f "$APK" ]; then
  echo "no APK at $APK" >&2
  exit 1
fi

VERSION="$(grep -m1 'versionName' "$ROOT/app/build.gradle.kts" | sed -E 's/.*"([^"]+)".*/\1/')"
mkdir -p "$ROOT/release"
cp "$APK" "$ROOT/release/LinuxDesk-$VERSION-$VARIANT.apk"

APKSIGNER="$ANDROID_HOME/build-tools/34.0.0/apksigner"
if [ -x "$APKSIGNER" ]; then
  echo "==> verifying the signature"
  "$APKSIGNER" verify --print-certs "$ROOT/release/LinuxDesk-$VERSION-$VARIANT.apk" | head -12
fi

echo "==> done"
ls -lh "$ROOT/release"
