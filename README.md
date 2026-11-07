# Krida

3D voxel board games from Nusantara — ludo, ular tangga, congklak, perang kapal and more —
for **Android and iOS**, playable **online** or **offline over Bluetooth / local Wi‑Fi**, with
iOS ↔ Android in every mode. 1–4 players.

- Store title: **Krida: Board Games**
- Web: **krida.ardeman.com**
- Package / bundle ID: **com.ardeman.krida**

## Repo layout

```
krida/
├── shared/       Kotlin Multiplatform core: game rules, protocol v1, sessions (Android + iOS)
├── androidApp/   Native Android app (Kotlin, Compose UI, Filament 3D) + BLE/LAN transports
├── ios/          Native iOS app (Swift, SwiftUI, RealityKit 3D) + BLE/LAN transports
├── web/          Landing page for krida.ardeman.com (Astro, zero-JS static HTML, GitHub Pages)
├── docs/         architecture.md, protocol.md
└── bitbucket-pipelines.yml
```

Why this split: game rules and the wire protocol are written **once** in Kotlin, so an
Android host and an iOS guest always agree on what a legal move is. Rendering and radios
(Bluetooth, Wi‑Fi) stay fully native on each platform for the best performance and the
smallest install size. See [docs/architecture.md](docs/architecture.md).

## Requirements

| Part | Needs |
|---|---|
| shared + Android | JDK 17+, Android Studio (Ladybug or newer), Android SDK 36 |
| iOS | macOS, Xcode 16+, [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`) |
| web | Node 20+ |

## Getting started

```bash
# shared core tests (JVM)
./gradlew :shared:jvmTest                     # add -PsharedOnly=true if no Android SDK

# Android: open the repo root in Android Studio, run "androidApp"
./gradlew :androidApp:assembleDebug

# iOS: generate the Xcode project, then open it
cd ios && xcodegen && open Krida.xcodeproj
# The "Build Shared framework" phase runs Gradle and links shared/ automatically.

# Web landing
cd web && npm install && npm run dev     # http://localhost:4321
npm run build                            # static site in web/dist
```

## Landing page hosting (GitHub Pages)

`.github/workflows/pages.yml` builds `web/` and deploys it on every push to `main`.

1. Push this repo to GitHub (or mirror it from Bitbucket).
2. Repo **Settings → Pages → Source: GitHub Actions**.
3. DNS at your ardeman.com provider: `CNAME  krida  →  <your-github-user>.github.io`.
4. In Settings → Pages, confirm the custom domain and tick **Enforce HTTPS**.

`web/public/CNAME` already contains `krida.ardeman.com`. Without a custom domain, set
`BASE_PATH: /<repo-name>` in the workflow and delete `web/public/CNAME`.

Why it loads fast: zero JavaScript, CSS inlined into the HTML, system fonts, all art is
inline SVG. The whole page is one HTML request plus a tiny favicon, with no third-party requests.

## Multiplayer roadmap

1. Pass-and-play (one device) — `LocalTransport`
2. Online rooms — WebSocket backend (TBD)
3. **Offline BLE** — main offline mode, iOS ↔ Android, up to 4 players
4. Offline LAN / hotspot — Bonjour (`_krida._tcp`)
5. Wi‑Fi Aware — later, once device support is broad

All of them plug into the same `Transport` interface; game code never changes.

## Status

Skeleton + protocol v1 + congklak rules (`shared/.../games/CongklakGame.kt`, rule variant
documented in the file). Pass-and-play congklak on a voxel board on **Android** (Filament,
`androidApp/.../congklak/`) and **iOS** (RealityKit, `ios/Krida/Congklak/`). Board geometry,
seed layout and sowing animation steps are shared (`CongklakBoard.kt`), so both boards match.
Next: congklak over BLE (Android ↔ iOS).

Gradle 8.14 needs JDK 17–21 (Android Studio's bundled JDK 25 is too new), e.g.
`export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`. Xcode's
"Build Shared framework" phase runs Gradle too, so export it before `xcodebuild` (or set it in
`~/.gradle/gradle.properties` as `org.gradle.java.home=…` for builds started from Xcode).
The iOS app accepts a `-openCongklak` launch argument to open the board directly.
