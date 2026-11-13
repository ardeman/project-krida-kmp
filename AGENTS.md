# AGENTS.md

Instructions for AI coding agents working in this repo. Read `README.md` for the overview, setup, commands and layout, and `docs/architecture.md` and `docs/protocol.md` before touching sessions, transports or the wire format. This file does not repeat them.

Also read:

- `docs/structure.md`: where code lives, and which Android file matches which iOS file.
- `docs/design.md` before any UI work: tokens, the components to reuse, screen patterns and a checklist. New screens are built from existing components, the same on both platforms.
- `docs/decisions.md`: product and rule decisions already made with the owner. Don't reverse one without asking; record new ones there.
- `docs/status.md`: open work outside the online plan (device checks, release setup, deferred ideas). Update it when an item is done.
- `docs/install.md`: put a debug build on the plugged-in Pixel or iPhone (`scripts/install-android.sh`, `scripts/install-ios.sh`).
- Store screenshots / marketing skills: canonical in `../project-pamerstudio-node` (skills `app-store-screenshots` + `product-launch-motion`; shared editor `store-screenshots/`, films under `krida/launch-motion/`). This repo only keeps symlinks under `.agents/skills/` — do not scaffold a second editor or launch film here.
- `.cursor/rules/working-style.mdc`: how the owner likes to work (usage, devices, replies). It applies to every agent, not only Cursor.

Docs go in `docs/` with lowercase names, never in the repo root (only `README.md`, `AGENTS.md` and `CLAUDE.md` live there).

## Definition of done

A change is done only when the checks for the parts you touched pass. If you can't run one, say so; don't claim it passed.

- `shared/`: `./gradlew :shared:jvmTest -PsharedOnly=true`. Add or update tests in `shared/src/commonTest/` for behavior you change.
- `androidApp/`: `./gradlew :androidApp:assembleDebug`.
- `ios/`: `cd ios && xcodegen && xcodebuild -scheme Krida -destination 'generic/platform=iOS Simulator' build`.
- `web/`: `cd web && npm run build`.
- iOS friend invites or notifications (`ios/Krida/Account/`): `node
  scripts/test-ios-invite-push.mjs`, an end-to-end UI test on a simulator against the live
  relay (makes and deletes two test accounts in its D1; needs `wrangler` signed in).
- Native game task lifetime changes: `./scripts/test-ios-game-lifetime.sh` for the iOS
  cancellation helper, plus the build for each changed platform.

Gradle needs JDK 17–21; Android Studio's bundled JDK 25 is too new (see `README.md`).

## Conventions

- Game rules live only in `shared/` as `GameRules<State, Move>`: pure functions, no I/O, no clocks, randomness only through the `Random` the host passes in. Android and iOS never re-implement a rule; they call the shared code, so an Android host and an iOS guest always agree.
- The host is authoritative. Guests send intents only; dice and validation happen on the host.
- Keep `shared/` callable from Swift: plain callback interfaces (no coroutines or `Flow` in public API), and a secondary constructor without default arguments wherever Kotlin defaults matter.
- Transports call their listener on one thread (the main thread), so sessions need no locks.
- Every game must stop board work on exit and screen removal. Use native `GameLifetime`
  (`rememberGameLifetime` on Android) for animations, bot delays, gesture timers and timeout
  work; SwiftUI view loading uses cancellable `.task`. Never ignore sleep cancellation.
  Cleanup is idempotent: detach callbacks (including renderer audio callbacks), clear queued
  updates, stop timers and game effects/voice, and leave the table/transport. Closed tables
  reject moves, bot steps and restarts. Menu music and tap feedback remain available.
- Protocol changes follow the compatibility rule in `docs/protocol.md`: never change an existing field's meaning, add optional fields only, bump `protocol` for a breaking change. Update `docs/protocol.md` in the same commit.
- Rendering and radios stay native: Filament (Android) and RealityKit (iOS), BLE and LAN per platform. No cross-platform engine or WebView.
- Install size budget is under 15 MB per store download. Ask before adding a dependency; prefer generating voxel meshes in code over shipping assets, and use `.glb` with vertex colors (no textures) when an asset is needed.
- User-facing text is Indonesian and English, English being the fallback for every other locale. On Android English goes in `res/values/strings.xml` and Indonesian in `res/values-in/strings.xml` (Android's resource folder for Indonesian; language tags elsewhere use `id`); on iOS both go in the string catalog. Add every string in both languages; don't add hardcoded strings. Code comments are in English.
- Deploy workflows (`.github/workflows/`) run on push to `main` only when files that end up in the deployed thing change: list its source and build files in `paths`, never a whole folder, and keep `workflow_dispatch` for deploying by hand. Docs, test scripts and other repo changes must not trigger a deploy. Checks that don't deploy (tests, commit messages) may run on every push.
- When a step in the README's multiplayer roadmap or the "Status" line changes, update `README.md` in the same commit.

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/): `<type>(<scope>): <description>`.

- Types: `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`.
- Scope is the part touched, lowercase: `shared`, `android`, `ios`, `web`, `protocol`, `congklak`, `brand`, `audio`, `ads`, `readme`. Leave it out when a change spans the whole repo.
- Description in English, imperative or a short noun phrase, no trailing period; the first line stays within 72 characters.
- A breaking change (for example a `protocol` bump) gets `!` after the scope and a `BREAKING CHANGE:` footer.
- The hook in `scripts/git-hooks/commit-msg` enforces the first line; enable it with `git config core.hooksPath scripts/git-hooks`. CI (`.github/workflows/commits.yml`) runs the same check on every push and pull request.

Examples: `feat(android): congklak over BLE`, `fix(ios): skip the x86_64 simulator slice`, `feat(protocol)!: rename seat field`.
