# Installing debug builds on phones

Two scripts build Krida and put it on phones plugged into this Mac. Both use JDK 21 from `/opt/homebrew/opt/openjdk@21` when it is there (Gradle needs JDK 17–21, see `README.md`).

## Android

```bash
./install-android.sh            # build, install and open on every connected device
./install-android.sh --no-build # reinstall the last build
```

Needs `adb` (`brew install android-platform-tools`) and USB debugging turned on, with the phone's "Allow USB debugging" prompt accepted. Devices that show as `unauthorized` or `offline` in `adb devices` are skipped.

The APK it installs is `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

## iOS

```bash
./install-ios.sh            # build, install and open on the paired iPhone
./install-ios.sh --no-build # reinstall the last build
```

Needs Xcode, `xcodegen` and `jq`, and an iPhone that has trusted this Mac (plugged in, or on the same Wi-Fi after pairing). The script picks the first paired physical iPhone that `xcrun devicectl list devices` reports and ignores simulators.

It regenerates the project with `xcodegen`, builds for that phone with automatic signing (team `B36A8H9S8E`, `-allowProvisioningUpdates`) into `ios/build/device`, installs with `xcrun devicectl device install app`, and opens the app. If the phone is locked the install still succeeds; open Krida by hand.

The first install from a new signing certificate may need trusting on the phone: Settings › General › VPN & Device Management.

## Testing online play between two phones

1. Install on both phones with the scripts above.
2. Both phones need an internet connection; they meet through the relay, not the local network.
3. Open the same game on both, choose Online, then Quick Match (or host a room on one phone and join with its code or QR on the other).

## Troubleshooting

- **`No Android device ready`**: check the cable, USB debugging, and the authorization prompt on the phone; `adb devices` should list it as `device`.
- **`No paired iPhone found`**: open Xcode's Device Hub with the phone plugged in and trust the Mac on the phone.
- **iOS signing errors**: open `ios/Krida.xcodeproj` once in Xcode and check Signing & Capabilities is signed in to the team.
- **Gradle fails with a Java version error**: install JDK 21 (`brew install openjdk@21`).
