#!/bin/bash
# Install Krida app to connected iPhone using devicectl

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
APP_PATH="$SCRIPT_DIR/ios/build/device/Build/Products/Debug-iphoneos/Krida.app"

echo "🍎 Installing Krida to iPhone via devicectl..."
echo ""

# Check if app exists
if [ ! -d "$APP_PATH" ]; then
    echo "❌ App bundle not found at: $APP_PATH"
    echo ""
    echo "Please build first:"
    echo "  cd ios && export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && xcodebuild -scheme Krida -destination 'generic/platform=iOS' -configuration Debug build"
    exit 1
fi

# List connected devices
echo "📱 Connected devices:"
devicectl list devices --json-output compact 2>/dev/null | jq -r '.[] | "\(.properties.name) (\(.properties.osVersion))"' || devicectl list devices

echo ""
echo "Installing $APP_PATH..."
echo ""

# Install to first connected device
DEVICE_ID=$(devicectl list devices --json-output compact 2>/dev/null | jq -r '.[0].properties.udid' 2>/dev/null)

if [ -z "$DEVICE_ID" ]; then
    echo "❌ No connected device found"
    echo "Please connect your iPhone via USB"
    exit 1
fi

echo "Installing to device: $DEVICE_ID"
devicectl device install app "$APP_PATH" --device "$DEVICE_ID"

echo ""
echo "✅ Installation complete!"
echo ""
echo "Next steps:"
echo "1. Trust the app: Settings > General > VPN & Device Management"
echo "2. Open Krida from your home screen"
echo "3. Go to a game and toggle Online"
echo "4. Tap 'Pencarian Cepat' for Quick Match"
