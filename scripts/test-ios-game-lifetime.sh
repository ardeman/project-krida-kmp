#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
lifetime_tmp=$(mktemp -d)
trap 'rm -rf "$lifetime_tmp"' EXIT
swiftc -parse-as-library -module-cache-path "$lifetime_tmp/cache" \
    ios/Krida/Support/GameLifetime.swift scripts/test-ios-game-lifetime.swift \
    -o "$lifetime_tmp/tests"
"$lifetime_tmp/tests"
