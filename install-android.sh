#!/bin/bash
# Build the debug app and install + launch it on every connected Android device.
# Usage: ./install-android.sh [--no-build]
set -euo pipefail

cd "$(dirname "$0")"
APP_ID="com.ardeman.krida"
APK="androidApp/build/outputs/apk/debug/androidApp-debug.apk"

command -v adb >/dev/null || { echo "adb not found (brew install android-platform-tools)"; exit 1; }

# Ready devices only (skip "unauthorized" / "offline").
DEVICES=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
if [ -z "$DEVICES" ]; then
    echo "No Android device ready. Plug it in, enable USB debugging and accept the prompt."
    adb devices
    exit 1
fi

if [ "${1:-}" != "--no-build" ]; then
    # Gradle needs JDK 17–21 (see README).
    if [ -d /opt/homebrew/opt/openjdk@21 ]; then
        export JAVA_HOME=/opt/homebrew/opt/openjdk@21
    fi
    ./gradlew :androidApp:assembleDebug
fi

[ -f "$APK" ] || { echo "APK not found: $APK"; exit 1; }

for serial in $DEVICES; do
    echo "Installing on $serial ($(adb -s "$serial" shell getprop ro.product.model | tr -d '\r'))"
    adb -s "$serial" install -r "$APK"
    adb -s "$serial" shell am force-stop "$APP_ID"
    adb -s "$serial" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
done

echo "Done."
