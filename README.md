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
├── relay/        Online relay: Cloudflare Worker + Durable Object per room (TypeScript)
├── web/          Landing page for krida.ardeman.com (Astro, zero-JS static HTML, GitHub Pages)
├── docs/         architecture.md, protocol.md, brand.md (logo, icons, palette), store.md (listings, privacy answers)
├── scripts/      brand.swift: generates every icon and the wordmark
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
# once per clone: check commit messages (Conventional Commits, see AGENTS.md)
git config core.hooksPath scripts/git-hooks

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

# Online relay (Cloudflare Worker)
cd relay && npm install && npm run dev   # ws://localhost:8787
npm run smoke                            # host + guests test client against the dev relay
# Deploys from CI: .github/workflows/relay.yml on push to main (relay/ changes) or by hand
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

## Play modes

Every game supports three core modes:

- **Lawan komputer:** single player against bots. Bots live in `shared/` next to each game's
  rules and fill seats in a `HostSession`, so they play the same on Android and iOS.
- **Main bareng dekat:** player vs player nearby over BLE, iOS ↔ Android, no internet.
- **Main online:** player vs player over the internet.

Pass-and-play on one device comes for free with `LocalTransport`.

Seats mix freely: any seat can be a person (on this device, nearby or online) or a computer,
up to the game's player count. In ludo, for example: 1 player vs 3 computers, 2 players vs 2
computers, or 2 friends over BLE plus 2 computers. The host fills seats with bots before the
game starts; bots only ever run on the host, and guests see them as ordinary players.

Games with a team mode (e.g. ludo with partners) group seats into teams, and each seat in a
team is still a person or a computer: 1 player + 1 computer vs 2 computers, 2 friends vs 2
computers, and so on. Teams are part of the game setup the host picks; they will need a game
option passed to `GameRules.initialState` and an optional `team` field per player in `lobby`
and `state` (see the compatibility rule in `docs/protocol.md`).

## Multiplayer roadmap

1. Pass-and-play (one device) — `LocalTransport`
2. Single player vs computer — bot seats in `HostSession`, bots per game in `shared/`
3. **Offline BLE** — main offline mode, iOS ↔ Android, up to 4 players
4. Online rooms — Cloudflare Workers relay over WebSocket, plan in `docs/online.md`; relay
   live, Android rooms by code (iOS next)
5. Offline LAN / hotspot — Bonjour (`_krida._tcp`)
6. Wi‑Fi Aware — later, once device support is broad

All of them plug into the same `Transport` interface; game code never changes.

## Status

Skeleton + protocol v1 + congklak rules (`shared/.../games/CongklakGame.kt`, rule variant
documented in the file). Pass-and-play congklak on a voxel board on **Android** (Filament,
`androidApp/.../congklak/`) and **iOS** (RealityKit, `ios/Krida/Congklak/`). Board geometry,
seed layout and sowing animation steps are shared (`CongklakBoard.kt`), so both boards match.
Congklak over BLE on both platforms: host a room or join one by its code, iOS ↔ Android; the
board turns so your side is near, and remote moves animate like local ones. Both apps have the
redesigned nearby screens: a two-column lobby with the room's QR (encoded by
`shared/.../qr/QrCode.kt`), pawn colour picks and "+ Komputer" seats for ular tangga, a join
screen that scans the QR (Google code scanner on Android, VisionKit on iOS) or takes the code,
and one card for connecting and its failures.
Bot seats in the shared core: `HostSession.addBot` / `stepBot` and `CongklakBot` (easy,
normal, hard). Android and iOS play congklak vs computer (Mudah / Sedang / Sulit). Both apps
have the reworked UI (two-column home, turn pills, gold rings on playable holes, result card).
Sound on both: seed clicks while sowing, "Tembak!" ("Shoot!") on a capture, turn and result
voice lines in the app language (`vo_*_en.m4a` for English), and looping gamelan music, each switched off on its own from home or the board.
The `.m4a` files are made with ElevenLabs in `../project-pamerstudio-node/krida` (its README).
Ads on both (AdMob behind the UMP consent form; `androidApp/.../ads/`, `ios/Krida/Ads/`): a
banner on the home screen, an interstitial every 2nd finished game, and a rewarded ad
for a hint ("Petunjuk": the hard bot's move). The "Hapus iklan" purchase (non-consumable
`hapus_iklan`: Play Billing; StoreKit 2 on iOS, with "Pulihkan pembelian") turns ads off and
makes hints free. The real AdMob ids are in `res/values/ads.xml` and `ios/Krida/Ads/AdIds.swift`
(app id in `ios/project.yml`); debug builds use Google's test ad units (`src/debug/res/values/ads.xml`,
`#if DEBUG`). `hapus_iklan` exists in App Store Connect; the Play product still needs a build upload.
Ular tangga rules for 2–4 players are in the shared core (`UlarTanggaGame.kt`: fixed board, a
each player throws for the play order, exact 100 or stay put, a 6 rolls again, play on until one player is
left; bounce-back, six-to-enter, first-finish-ends and no-reroll are host options in
`UlarTanggaOptions`; `UlarTanggaBot` just rolls). Its voxel board geometry is shared
(`UlarTanggaBoard.kt`); Android and iOS (RealityKit) play it on this phone or against computers,
picked from the menu's left card. Android and iOS host ular tangga rooms over BLE for up to 4
phones (a guest gone past the reconnect grace is handed to the computer); a guest joins any room
by its code and learns the game from the host's `welcome` (`GuestJoin`). Next: an iPhone↔Android
check of a ular tangga room.

Gradle 8.14 needs JDK 17–21 (Android Studio's bundled JDK 25 is too new), e.g.
`export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`. Xcode's
"Build Shared framework" phase runs Gradle too, so export it before `xcodebuild` (or set it in
`~/.gradle/gradle.properties` as `org.gradle.java.home=…` for builds started from Xcode).
The iOS app accepts a `-openCongklak` launch argument to open the board directly.
