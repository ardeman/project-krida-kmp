#!/usr/bin/env bash
# Boot the Android 10 Pixel XL AVD with the GPU flags that stay up on this Mac.
# Prefer a real Pixel XL when one is plugged in (see AGENTS.md).
set -euo pipefail
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
export PATH="$SDK/emulator:$SDK/platform-tools:$PATH"

if adb devices | awk 'NR>1 && $2=="device" && $1 !~ /emulator/{found=1} END{exit !found}'; then
  echo "A physical device is already connected; use that instead of the emulator."
  adb devices -l
  exit 0
fi

pkill -f 'qemu-system.*Pixel_XL|emulator.*Pixel_XL' 2>/dev/null || true
sleep 1
# Drop stale locks from previous crashes
rm -f "$HOME/.android/avd/Pixel_XL.avd"/*.lock \
      "$HOME/.android/avd/Pixel_XL.avd/multiinstance.lock" 2>/dev/null || true

exec emulator -avd Pixel_XL \
  -no-snapshot \
  -no-boot-anim \
  -gpu swiftshader_indirect \
  -feature -Vulkan \
  -crash-report-mode disabled \
  -no-metrics \
  -netdelay none \
  -netspeed full \
  "$@"
