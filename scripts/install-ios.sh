#!/bin/bash
# Build the debug app and install + launch it on a connected iPhone (cable or Wi-Fi).
# Usage: ./scripts/install-ios.sh [--no-build]
set -euo pipefail

cd "$(dirname "$0")/.."
BUNDLE_ID="com.ardeman.krida"
DERIVED="ios/build/device"
APP="$DERIVED/Build/Products/Debug-iphoneos/Krida.app"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

command -v jq >/dev/null || { echo "jq not found (brew install jq)"; exit 1; }

# Refresh the CoreDevice device list into $TMP/devices.json.
list_devices() {
    xcrun devicectl list devices --timeout 60 --json-output "$TMP/devices.json" >/dev/null
}

# Field from the selected device's connectionProperties (empty when missing).
device_conn() {
    jq -r --arg u "$DEVICE" \
        ".result.devices[] | select(.hardwareProperties.udid == \$u) | .connectionProperties.$1 // empty" \
        "$TMP/devices.json"
}

# Pick the first paired physical iPhone. Prefer cable, then a Wi-Fi phone whose
# developer tunnel is already up, then any paired phone (we wake the tunnel next).
pick_device() {
    list_devices
    DEVICE=$(jq -r '
      [.result.devices[]
        | select((.hardwareProperties.reality // "physical") != "simulated"
                 and .hardwareProperties.platform == "iOS"
                 and .connectionProperties.pairingState == "paired")]
      | sort_by(
          (if .connectionProperties.transportType == "wired" then 0 else 1 end),
          (if (.connectionProperties.tunnelState // "") == "connected" then 0 else 1 end),
          (.deviceProperties.name // "")
        )
      | .[0].hardwareProperties.udid // empty
    ' "$TMP/devices.json")
    if [ -z "$DEVICE" ]; then
        echo "No paired iPhone found. Plug it in (or put it on the same Wi-Fi) and trust this Mac."
        exit 1
    fi
    NAME=$(jq -r --arg u "$DEVICE" \
        '.result.devices[] | select(.hardwareProperties.udid == $u) | .deviceProperties.name' \
        "$TMP/devices.json")
}

# Human label for how we reach the phone right now.
via_label() {
    local transport tunnel
    transport=$(device_conn transportType)
    tunnel=$(device_conn tunnelState)
    case "$transport" in
        wired) echo "cable" ;;
        localNetwork)
            if [ "$tunnel" = "connected" ]; then echo "Wi-Fi"; else echo "Wi-Fi (tunnel down)"; fi
            ;;
        *) echo "${transport:-unknown}" ;;
    esac
}

# Listing apps over the developer channel wakes a sleeping localNetwork tunnel.
wake_tunnel() {
    xcrun devicectl device info apps --device "$DEVICE" --timeout 60 >/dev/null 2>&1 || true
    list_devices
}

# Cable is ready immediately. Wi-Fi needs tunnelState=connected; poke and wait.
wait_ready() {
    local tries=${1:-15}
    local i transport tunnel
    for i in $(seq 1 "$tries"); do
        list_devices
        transport=$(device_conn transportType)
        tunnel=$(device_conn tunnelState)
        if [ "$transport" = "wired" ] || [ "$tunnel" = "connected" ]; then
            return 0
        fi
        echo "Waiting for Wi-Fi tunnel to $NAME ($i/$tries)…"
        wake_tunnel
        sleep 2
    done
    return 1
}

pick_device
echo "Device: $NAME ($DEVICE) over $(via_label)"

if ! wait_ready 15; then
    echo "Could not reach $NAME over Wi-Fi. Unlock the phone, keep it awake, and stay on the same network — or plug in a cable."
    exit 1
fi
echo "Ready over $(via_label)"

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

# A long build often drops the Wi-Fi tunnel; wake it again before installing.
if ! wait_ready 15; then
    echo "Lost the link to $NAME after the build. Unlock the phone (or plug in a cable) and run:"
    echo "  ./scripts/install-ios.sh --no-build"
    exit 1
fi

# Wi-Fi installs flake with CoreDevice 3002 / 4000 when the tunnel drops mid-copy:
# longer timeout, a few retries, and a tunnel wake between attempts.
INSTALL_TIMEOUT=180
INSTALL_TRIES=4
attempt=1
while true; do
    if xcrun devicectl device install app --device "$DEVICE" --timeout "$INSTALL_TIMEOUT" "$APP"; then
        break
    fi
    if [ "$attempt" -ge "$INSTALL_TRIES" ]; then
        echo "Install failed after $INSTALL_TRIES tries. Plug the iPhone in with a cable, unlock it, and run:"
        echo "  ./scripts/install-ios.sh --no-build"
        exit 1
    fi
    echo "Install failed (attempt $attempt/$INSTALL_TRIES); waking the tunnel and retrying…"
    wake_tunnel
    sleep $((attempt * 2))
    attempt=$((attempt + 1))
    wait_ready 10 || true
done

xcrun devicectl device process launch --device "$DEVICE" --timeout 60 --terminate-existing "$BUNDLE_ID" \
    || echo "Installed, but launch failed (is the phone locked?). Open Krida by hand."

echo "Done."
