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

# First paired physical iPhone (devicectl also lists simulators). Newer Xcode may leave
# reality null on a real phone; treat anything that isn't "simulated" as physical, and
# prefer a wired / connected one when several are paired.
xcrun devicectl list devices --json-output "$TMP/devices.json" >/dev/null
DEVICE=$(jq -r '
  [.result.devices[]
    | select((.hardwareProperties.reality // "physical") != "simulated"
             and .hardwareProperties.platform == "iOS"
             and .connectionProperties.pairingState == "paired")]
  | sort_by(
      (if .connectionProperties.transportType == "wired" then 0 else 1 end),
      (if (.connectionProperties.tunnelState // "") == "connected" then 0 else 1 end)
    )
  | .[0].hardwareProperties.udid // empty
' "$TMP/devices.json")
if [ -z "$DEVICE" ]; then
    echo "No paired iPhone found. Plug it in (or put it on the same Wi-Fi) and trust this Mac."
    exit 1
fi
NAME=$(jq -r --arg u "$DEVICE" '.result.devices[] | select(.hardwareProperties.udid == $u) | .deviceProperties.name' "$TMP/devices.json")
TRANSPORT=$(jq -r --arg u "$DEVICE" '.result.devices[] | select(.hardwareProperties.udid == $u) | .connectionProperties.transportType // ""' "$TMP/devices.json")
if [ "$TRANSPORT" = "wired" ]; then VIA="cable"; else VIA="Wi-Fi"; fi
echo "Device: $NAME ($DEVICE) over $VIA"

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

# Wi-Fi installs drop when the phone locks or sleeps (CoreDevice 3002, 4000): retry once.
if ! xcrun devicectl device install app --device "$DEVICE" "$APP"; then
    echo "Install failed; retrying once..."
    sleep 3
    xcrun devicectl device install app --device "$DEVICE" "$APP" || {
        echo "Install failed again. Plug the iPhone in with a cable, unlock it, and run:"
        echo "  ./scripts/install-ios.sh --no-build"
        exit 1
    }
fi
xcrun devicectl device process launch --device "$DEVICE" --terminate-existing "$BUNDLE_ID" \
    || echo "Installed, but launch failed (is the phone locked?). Open Krida by hand."

echo "Done."
