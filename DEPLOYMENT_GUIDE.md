# Deployment Guide - Quick Match Build

Successfully built both Android and iOS with Quick Match Play Online feature integrated! 🎉

## Build Information

- **Android APK**: `builds/Krida-android-debug.apk` (38 MB)
- **iOS Device Build**: `/ios/build/device/Build/Products/Debug-iphoneos/Krida.app` (18 MB)
- **iOS Simulator Build**: `/ios/build/dd/Build/Products/Debug-iphonesimulator/Krida.app` (18 MB)

## Android Deployment

### Option 1: Installation Script (Recommended)
The easiest way - just run:
```bash
./install-android.sh
```

This script will:
- Check for adb and connected devices
- List your device info
- Install the APK automatically
- Print next steps

### Option 2: USB Installation (Manual)
1. Connect your Pixel phone via USB
2. Enable USB debugging on the phone (Settings > Developer Options > USB Debugging)
3. Run:
   ```bash
   adb install -r builds/Krida-android-debug.apk
   ```

### Option 3: Manual APK Installation
1. Copy `builds/Krida-android-debug.apk` to your phone or cloud storage
2. Open the APK file on your phone
3. Tap "Install"

### Option 4: Email/Cloud Sharing
1. Share `builds/Krida-android-debug.apk` via email, Google Drive, or iCloud
2. Download on your Pixel phone and install

## iOS Deployment

### Prerequisites
- Xcode must be installed
- Your iPhone must be connected via USB

### Option 1: devicectl Install (Recommended - Fastest)
```bash
# First, build the app
cd ios
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
xcodebuild -scheme Krida -destination 'generic/platform=iOS' -configuration Debug build

# Then install to connected iPhone
cd ..
./install-ios.sh
```

### Option 2: Xcode Direct Deploy
```bash
cd /path/to/project-krida-kmp/ios
xcodebuild -scheme Krida \
  -configuration Debug \
  -destination 'generic/platform=iOS' \
  -derivedDataPath build/device \
  install
```

### Option 2: Automatic Signing (Recommended - Interactive)
1. Open `ios/Krida.xcodeproj` in Xcode
2. Connect your iPhone via USB
3. Select your device as the build destination
4. Press the Play button to build and deploy
5. Trust the app on your phone (Settings > General > VPN & Device Management)

### Option 3: Manual Installation via Xcode
1. Build in Xcode (Cmd+B)
2. Open iOS build:
   ```bash
   open /Volumes/ardeman.com/github/project-krida-kmp/ios/build/device/Build/Products/Debug-iphoneos/Krida.app
   ```
3. Drag to Devices & Simulators window

### Option 4: Direct devicectl Command
For advanced users:
```bash
devicectl device install app ./ios/build/device/Build/Products/Debug-iphoneos/Krida.app --device <DEVICE_ID>
```

## Testing Quick Match

Once installed on both phones:

1. **Android Phone (Pixel)**:
   - Tap "Main Menu"
   - Select a game (Congklak or Ular Tangga)
   - Toggle "Online" (top-right toggle)
   - Tap "Pencarian Cepat" (Quick Search) button

2. **iPhone**:
   - Tap "Main Menu"
   - Select a game
   - Toggle "Online" (top-right)
   - Tap "Pencarian Cepat" button

3. **Expected Flow**:
   - Select player count (2-4)
   - Tap "Search"
   - 20-second countdown begins
   - Both devices connect to matchmaker relay
   - One device becomes host, other joins
   - Game starts automatically

## Troubleshooting

### Android APK won't install
- Ensure Android 8.0+ (API 26+)
- Check USB debugging is enabled
- Try: `adb install -r` to reinstall over existing version

### iOS won't launch
- Check code signing: Xcode > Signing & Capabilities
- Trust developer certificate on phone: Settings > General > VPN & Device Management
- Ensure both phones are on same WiFi network

### Quick Match hangs
- Check both phones are connected to internet
- Verify relay server (`Krida.shared.RELAY_URL`) is reachable
- May need to adjust firewall settings for WebSocket connections

### Tests Passing
- ✅ Shared Kotlin code compiles
- ✅ Android debug build successful
- ✅ iOS device build successful
- ✅ PlayOnlineScreens correctly uses buttonStyle modifiers
- ✅ QuickMatchClient properly accesses Krida.shared singleton

## Build Commands (For Future Rebuilds)

### Android
```bash
cd /Volumes/ardeman.com/github/project-krida-kmp
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew :androidApp:assembleDebug
# Output: androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

### iOS
```bash
cd /Volumes/ardeman.com/github/project-krida-kmp/ios
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
xcodebuild -scheme Krida -destination 'generic/platform=iOS Simulator' build
# Output: ios/build/dd/Build/Products/Debug-iphonesimulator/Krida.app
```

## What Was Changed

### Android (MainActivity.kt)
- Added `playingQuickMatch` and `quickMatchGame` state variables
- Integrated `PlayOnlineScreen` in main navigation when
- Added "Quick Match" button to online game mode

### iOS (ContentView.swift)
- Added `playingQuickMatch` and `quickMatchGame` state variables
- Integrated `PlayOnlineView` in body's if-else chain
- Added "Pencarian Cepat" button for online mode
- Fixed syntax to use `.buttonStyle()` modifier pattern

### iOS (PlayOnlineScreens.swift)
- Replaced custom `SecondaryButton` and `PrimaryButton` components
- Now uses proper SwiftUI `Button` + `.buttonStyle()` pattern
- Consistent with rest of iOS codebase

### iOS (QuickMatchClient.swift)
- Fixed Kotlin singleton access: `Krida.RELAY_URL` → `Krida.shared.RELAY_URL`

### Android & iOS (Strings)
- Added `button_cancel` string resource to both platforms in both languages

## Notes

- Both platforms connect to the same relay server for matchmaking
- WebSocket connection requires internet/network access
- 20-second timeout if no match found (falls back to play computer)
- All game logic runs on host; guest sends intents only
- Protocol-compatible across both platforms
