#!/bin/bash
# Install Krida app to connected Android device using adb

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
APK_PATH="$SCRIPT_DIR/builds/Krida-android-debug.apk"

echo "🤖 Installing Krida to Android device via adb..."
echo ""

# Check if adb is available
if ! command -v adb &> /dev/null; then
    echo "❌ adb not found"
    echo ""
    echo "Please install Android SDK Platform Tools:"
    echo "  • macOS: brew install android-platform-tools"
    echo "  • Or download from: https://developer.android.com/tools/releases/platform-tools"
    exit 1
fi

# Check if APK exists
if [ ! -f "$APK_PATH" ]; then
    echo "❌ APK not found at: $APK_PATH"
    echo ""
    echo "Please build first:"
    echo "  export JAVA_HOME=/opt/homebrew/opt/openjdk@21"
    echo "  ./gradlew :androidApp:assembleDebug"
    exit 1
fi

# List connected devices
echo "📱 Connected devices:"
DEVICES=$(adb devices | grep -v "List of" | grep -v "^$" | awk '{print $1}' | grep -v "device")

if [ -z "$DEVICES" ]; then
    echo "❌ No connected device found"
    echo ""
    echo "Please:"
    echo "  1. Connect your Android phone via USB"
    echo "  2. Enable USB debugging (Settings > Developer Options > USB Debugging)"
    echo "  3. Run this script again"
    exit 1
fi

adb devices -l | grep -v "List of" | grep -v "^$"

echo ""
echo "📦 Installing Krida..."
echo "   From: $APK_PATH"
echo ""

# Install to connected device
adb install -r "$APK_PATH"

echo ""
echo "✅ Installation complete!"
echo ""
echo "Next steps:"
echo "1. Open Krida from your app drawer"
echo "2. Go to a game (Congklak or Ular Tangga)"
echo "3. Toggle 'Online' (top-right toggle)"
echo "4. Tap 'Quick Match' button"
echo ""
echo "Tip: To test with another device, use:"
echo "  adb -s <DEVICE_ID> install -r builds/Krida-android-debug.apk"
