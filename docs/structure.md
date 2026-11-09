# Code structure

Where things live, and which Android file matches which iOS file. The top-level layout is in
`README.md`; the layers and transports in [architecture.md](architecture.md).

## Shared (`shared/src/commonMain/kotlin/`)

Everything both apps must agree on: game rules (`GameRules<State, Move>` per game), bots, board
geometry and meshes (`CongklakBoard`, `UlarTanggaBoard`), the protocol (`Message`, codec,
chunker), sessions (`HostSession`, `GuestSession`, `GuestJoin`), room codes and the QR encoder
(`QrCode`), and seat helpers (`LocalSeats`). Tests in `shared/src/commonTest/`.

If both apps would need the same logic, it goes here, not twice in the apps.

## App files, side by side

Android paths are under `androidApp/src/main/kotlin/com/ardeman/krida/`, iOS under `ios/Krida/`.
A change to one side usually needs the same change in the file on the same row.

| Area | Android | iOS |
|---|---|---|
| App entry, navigation, menu, play card | `MainActivity.kt` | `KridaApp.swift`, `ContentView.swift` |
| Theme, buttons, landscape stage | `ui/Theme.kt` | `Support/Theme.swift` |
| Loading screen | `ui/Loading.kt` | `Support/Loading.swift` |
| Sounds | `ui/Sounds.kt` | `Support/Sounds.swift` |
| Language picker | `ui/Language.kt` | `Support/Language.swift` |
| 3D view host | `ui/VoxelStage.kt` | (RealityKit `ARView` in each scene) |
| Congklak screen | `congklak/CongklakScreen.kt` | `Congklak/CongklakView.swift` |
| Congklak 3D | `congklak/BoardRenderer.kt`, `Glb.kt` | `Congklak/BoardScene.swift` |
| Congklak menu preview | `congklak/BoardPreview.kt` | `Congklak/BoardPreview.swift` |
| Congklak table (host/guest glue) | `congklak/CongklakTable.kt` | `Congklak/CongklakTable.swift` |
| Congklak settings | `congklak/CongklakSettings.kt` | (inside `CongklakTable.swift` / `ContentView.swift`) |
| Ular tangga screen | `ulartangga/UlarTanggaScreen.kt` | `UlarTangga/UlarTanggaView.swift` |
| Ular tangga 3D | `ulartangga/UlarTanggaRenderer.kt` | `UlarTangga/UlarTanggaScene.swift`, `UlarTanggaPreview.swift` |
| Ular tangga table + settings | `ulartangga/UlarTanggaTable.kt`, `UlarTanggaSettings.kt` | `UlarTangga/UlarTanggaTable.swift` |
| Room state (nearby and online) | `nearby/NearbyRoom.kt` | `Nearby/NearbyRoom.swift` |
| Lobby, join, QR, connecting screens | `nearby/NearbyScreens.kt` | `Nearby/NearbyScreens.swift` |
| Saved room for rejoin | `nearby/LastRoom.kt` | `Nearby/LastRoom.swift` |
| Dropped-player countdown | `online/Waiting.kt` | `Nearby/Waiting.swift` |
| Quick match | `playonline/PlayOnlineScreens.kt`, `QuickMatchClient.kt` | `PlayOnline/PlayOnlineScreens.swift`, `QuickMatchClient.swift` |
| BLE | `ble/BleHostTransport.kt`, `BleGuestTransport.kt` | `Transport/BleHostTransport.swift`, `BleGuestTransport.swift` |
| LAN | `lan/LanTransport.kt` | `Transport/LanTransport.swift` |
| Online (WebSocket to the relay) | `online/OnlineTransport.kt` | `Transport/OnlineTransport.swift` |
| Ads, remove-ads purchase | `ads/Ads.kt`, `ads/Billing.kt` | `Ads/Ads.swift`, `Store.swift`, `AdIds.swift` |
| Strings | `res/values/strings.xml` (EN), `res/values-in/strings.xml` (ID) | `Localizable.xcstrings`, `InfoPlist.xcstrings` |
| Audio | `res/raw/` | `Audio/` |

## Other parts

| Part | Where |
|---|---|
| Online relay (Cloudflare Worker, Durable Object per room, matchmaker) | `relay/src/` |
| Website and privacy page | `web/src/` |
| Brand generator | `scripts/brand.swift` → outputs listed in [brand.md](brand.md) |
| Commit-message hook | `scripts/git-hooks/commit-msg` |
| CI and deploys | `.github/workflows/` |

## Adding a game

1. Rules, bot, board geometry and tests in `shared/`.
2. One package per platform (`<game>/` on Android, `<Game>/` on iOS) with screen, 3D, table and
   settings, following congklak and ular tangga.
3. A card in the menu's game switch and a play-card variant in `MainActivity.kt` /
   `ContentView.swift`.
4. Rooms work through the game-agnostic `NearbyRoom` once the game's table has host and guest
   variants.
