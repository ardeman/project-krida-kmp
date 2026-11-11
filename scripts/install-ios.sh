#!/bin/bash
# Build the debug app and install + launch it on a connected iPhone.
# Usage: ./scripts/install-ios.sh [--no-build]
set -euo pipefail

cd "$(dirname "$0")/.."
BUNDLE_ID="com.ardeman.krida"
DERIVED="ios/build/device"
APP="$DERIVED/Build/Products/Debug-iphoneos/Krida.app"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

command -v jq >/dev/null || { echo "jq not found (brew install jq)"; exit 1; }

# First paired physical iOS device (devicectl also lists simulators).
xcrun devicectl list devices --json-output "$TMP/devices.json" >/dev/null
DEVICE=$(jq -r '[.result.devices[]
    | select(.hardwareProperties.reality == "physical"
             and .hardwareProperties.platform == "iOS"
             and .connectionProperties.pairingState == "paired")][0]
    | .hardwareProperties.udid // empty' "$TMP/devices.json")
if [ -z "$DEVICE" ]; then
    echo "No paired iPhone found. Plug it in (or put it on the same Wi-Fi) and trust this Mac."
    exit 1
fi
NAME=$(jq -r --arg u "$DEVICE" '.result.devices[] | select(.hardwareProperties.udid == $u) | .deviceProperties.name' "$TMP/devices.json")
echo "Device: $NAME ($DEVICE)"

if [ "${1:-}" != "--no-build" ]; then
    # The shared framework build step runs Gradle, which needs JDK 17–21.
    if [ -d /opt/homebrew/opt/openjdk@21 ]; then
        export JAVA_HOME=/opt/homebrew/opt/openjdk@21
    fi
    (cd ios && xcodegen)
    xcodebuild -project ios/Krida.xcodeproj -scheme Krida -configuration Debug \
        -destination "id=$DEVICE" -derivedDataPath "$DERIVED" \
        -allowProvisioningUpdates -quiet build
fi

[ -d "$APP" ] || { echo "App not found: $APP"; exit 1; }

xcrun devicectl device install app --device "$DEVICE" "$APP"
xcrun devicectl device process launch --device "$DEVICE" --terminate-existing "$BUNDLE_ID" \
    || echo "Installed, but launch failed (is the phone locked?). Open Krida by hand."

echo "Done."
