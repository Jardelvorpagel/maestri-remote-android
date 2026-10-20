#!/bin/bash
# Serialized gate build — machine-wide mkdir lock; PIPESTATUS verdict.
set -u
LOCK=/tmp/maestri-build.lock
if ! mkdir "$LOCK" 2>/dev/null; then
  echo "GATE-BLOCKED: another assembleDebug holds $LOCK"
  exit 97
fi
trap 'rmdir "$LOCK" 2>/dev/null' EXIT
cd ~/Projects/maestri-remote-android || exit 1
./gradlew :app:assembleDebug 2>&1 | tee /tmp/webview-gate.log
S="${PIPESTATUS[0]}"
APK=app/build/outputs/apk/debug/app-debug.apk
if [ "$S" = "0" ] && [ -f "$APK" ]; then
  echo "GATE-EXIT=0"
  echo "GATE-MD5=$(md5 -q "$APK")"
  echo "GATE-SIZE=$(stat -f%z "$APK")"
else
  echo "GATE-EXIT=$S (FAILED)"
fi
