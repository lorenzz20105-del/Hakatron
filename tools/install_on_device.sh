#!/usr/bin/env bash
# Installs the freshly built APK on a connected device.
#
#   tools/install_on_device.sh [release|debug]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# shellcheck source=/dev/null
source "$ROOT/tools/env.sh"

VARIANT="${1:-release}"
APK="$ROOT/release/LinuxDesk-1.0.0-$VARIANT.apk"
if [ ! -f "$APK" ]; then
  APK="$(ls -1t "$ROOT"/release/*.apk 2>/dev/null | head -1 || true)"
fi
if [ -z "${APK:-}" ] || [ ! -f "$APK" ]; then
  echo "no APK found in $ROOT/release - run tools/build_release.sh first" >&2
  exit 1
fi

ADB="${ADB:-$ANDROID_HOME/platform-tools/adb}"

echo "==> installing $APK"
"$ADB" install -r -g "$APK"
echo "==> done"
"$ADB" shell am start -n dev.linuxdesk.app/.MainActivity
