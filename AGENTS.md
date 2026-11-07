# AGENTS.md

Instructions for AI coding agents working in this repo. Read `README.md` for the overview, setup, commands and layout, and `docs/architecture.md` and `docs/protocol.md` before touching sessions, transports or the wire format. This file does not repeat them.

## Definition of done

A change is done only when the checks for the parts you touched pass. If you can't run one, say so; don't claim it passed.

- `shared/`: `./gradlew :shared:jvmTest -PsharedOnly=true`. Add or update tests in `shared/src/commonTest/` for behavior you change.
- `androidApp/`: `./gradlew :androidApp:assembleDebug`.
- `ios/`: `cd ios && xcodegen && xcodebuild -scheme Krida -destination 'generic/platform=iOS Simulator' build`.
- `web/`: `cd web && npm run build`.

Gradle needs JDK 17–21; Android Studio's bundled JDK 25 is too new (see `README.md`).

## Conventions

- Game rules live only in `shared/` as `GameRules<State, Move>`: pure functions, no I/O, no clocks, randomness only through the `Random` the host passes in. Android and iOS never re-implement a rule; they call the shared code, so an Android host and an iOS guest always agree.
- The host is authoritative. Guests send intents only; dice and validation happen on the host.
- Keep `shared/` callable from Swift: plain callback interfaces (no coroutines or `Flow` in public API), and a secondary constructor without default arguments wherever Kotlin defaults matter.
- Transports call their listener on one thread (the main thread), so sessions need no locks.
- Protocol changes follow the compatibility rule in `docs/protocol.md`: never change an existing field's meaning, add optional fields only, bump `protocol` for a breaking change. Update `docs/protocol.md` in the same commit.
- Rendering and radios stay native: Filament (Android) and RealityKit (iOS), BLE and LAN per platform. No cross-platform engine or WebView.
- Install size budget is under 15 MB per store download. Ask before adding a dependency; prefer generating voxel meshes in code over shipping assets, and use `.glb` with vertex colors (no textures) when an asset is needed.
- User-facing text is Indonesian. On Android it belongs in `res/values/strings.xml`, on iOS in a string catalog; don't add more hardcoded strings. Code comments are in English.
- When a step in the README's multiplayer roadmap or the "Status" line changes, update `README.md` in the same commit.
