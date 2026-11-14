#!/usr/bin/env bash
# Fail if a release APK/AAB still carries Google's public TEST ad unit publisher id
# (debug builds intentionally use those; Play must ship the real units in ads.xml).
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
./gradlew :androidApp:assembleRelease -q
apk=$(ls androidApp/build/outputs/apk/release/*.apk | head -1)
ids=$(strings "$apk" | grep -o 'ca-app-pub-[0-9~./]*' | sort -u)
echo "$ids"
if echo "$ids" | grep -q 'ca-app-pub-3940256099942544'; then
  echo "error: release package still contains Google's TEST publisher id" >&2
  exit 1
fi
if ! echo "$ids" | grep -q 'ca-app-pub-4887874546732768~'; then
  echo "error: release package missing real AdMob app id" >&2
  exit 1
fi
for unit in 1499915275 4589113040 5766463343; do
  if ! echo "$ids" | grep -q "ca-app-pub-4887874546732768/$unit"; then
    echo "error: release package missing ad unit …/$unit" >&2
    exit 1
  fi
done
echo "ok: release ads use production AdMob ids ($apk)"
