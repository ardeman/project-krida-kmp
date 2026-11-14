# Status and open work

What is still open outside the online plan (`docs/online.md` has its own steps). Update this
file in the same commit when an item is done or a new one comes up. Last updated 2026-11-12.

## Next

- **Perang kapal** (fourth game): shared, Android and iOS done 2026-11-12
  ([mockups/perang-kapal.md](mockups/perang-kapal.md)); both build, nothing played on a
  device yet. Reworked the same day on the owner's notes (voxel ships, tap/drag placement,
  tap to turn, large radar with a small ocean, two people on one phone). Next: phone checks
  (this phone vs computer first: drag and tap ships from the tray, turn one, Ready, fire,
  hit pegs on ships, the ring on the last shot, result card; then two people on one phone
  with the pass cover; then nearby and online, iPhone ↔ Pixel, both languages and themes).
- **Gaple** (fifth game): rules picked by the owner 2026-11-12 (2–4 seats with partners
  across, balak enam opens, pass when no tile fits, pips to 100). Mockup approved
  ([mockups/gaple.md](mockups/gaple.md)). Shared rules, board layout, bots, turn-timeout
  move and tests done; relay allows `gaple/2`–`/4` (deploys with the next relay push).
  Android on this phone is done: you against 1–3 computers, partners with 4 seats,
  shared table meshes (`GapleScene.kt`). Android nearby (Bluetooth, Wi‑Fi) and online rooms
  are wired (host, join, computers in empty seats, online create with 2–4 seats and partners,
  quick match without partners); builds, not played on a device yet. iOS matches it: this phone
  (same screen, table and menu card) plus nearby and online rooms (host, join, computers in
  empty seats, 2–4 seat online rooms, partners, quick match); builds, not played on a device
  yet. Next: phone checks (vs computer, then nearby/online iPhone ↔ Pixel). The "Pas!" voice line plays when a seat passes (recorded 2026-11-12).
  End rings light on a dual-fit pick; no camera zoom (2026-11-14).
- **Turn timer in every game** (owner, 2026-11-12): every turn gets a countdown, and at 0 the
  phone moves for the player. Ludo and ular tangga have it (roll timer; ludo also a 15 s
  pick timer, not checked on a device yet), gaple and congklak
  (15 s; congklak plays the easy computer's pick) too. Perang kapal has a 60 s placement
  timer and a 15 s firing timer with auto-play (2026-11-12, both platforms build, not checked
  on a device). Congklak's timer is checked vs computer on
  the iOS simulator and a Pixel XL (plus pass-and-play on iOS), not yet in nearby/online rooms.
- Offline LAN (README roadmap 6): coded under Nearby (Bluetooth | Wi‑Fi control, default
  Bluetooth; decision in `docs/decisions.md`). Android and iOS host/guest transports,
  play-card Link control, join/lobby copy. iOS dual-simulator e2e passes
  (`node scripts/test-ios-lan.mjs`, 2026-11-12). Android dual-emulator and
  Android↔iOS scripts are in (`scripts/test-android-lan.mjs`,
  `scripts/test-cross-lan.mjs`; TCP via `adb forward` because emulator NSD is unreliable).
  Local AVDs (2026-11-12): **Pixel_XL** = API 29 google_apis (stable); **Pixel_9a** =
  Android 16 / API 36 Play Store + `pixel_9a` skin (recreated after a broken `target` that
  showed as API 0 in Device Manager). Dual-AVD / long CLI cold boots overheat this Mac —
  prefer Device Manager and one AVD. Pixel_9a bootlooped on 2026-11-12 because of gesture
  navigation (SurfaceFlinger `RegionSampling` abort on emulator 37.2.13, any GPU mode); it
  is fixed by keeping it on 3-button navigation, not by wiping or GPU flags (commands in
  `.cursor/rules/android-emulators.mdc`). Open: phone LAN checks (same Wi‑Fi and hotspot,
  Android ↔ iOS, leave/rejoin);
  dual-emu / cross only if the Mac can hold two AVDs without thrashing.
- Online step 10b, friends list: relay, web, Android and iOS are built and live
  (`docs/mockups/friends.md`); phone checks open (add/remove by code and `/f/`, both
  themes and languages).
- Online step 10c-1, in-app invites (`docs/mockups/invites.md`): the relay, Android and
  iOS are built and the relay is deployed. Next: check on two signed-in phones (invite from
  each side, Not now, Join, the 2-minute expiry).
- Online step 10c-2, iPhone invite notifications: code done (relay `src/push.ts`, iOS
  `Account/Push.swift`), deployed, and passing end to end on a simulator with real sandbox
  APNs (`node scripts/test-ios-invite-push.mjs`, 2026-11-12). Still open: the same on the
  real iPhone (allow notifications, get invited with the app closed and in the background,
  tap to join; sign out and confirm no notification comes), and production APNs with a
  TestFlight build. Android's in-app invite card has not been checked on a phone; iOS had
  a bug there (the inbox closed after the Account screen was opened), fixed 2026-11-12.
- Online step 10d, name and avatar: coded and deployed (relay + privacy, 2026-11-12).
  Open: phone checks (edit profile, friends/invites, seats; Google + Apple; both themes
  and languages).
- In-game avatars (2026-11-12): multi-device players with an account show their avatar on
  the player tiles in every game (ringed in the seat colour; Ular Tangga in the tile's
  corner, Congklak in the pills) and on the Ludo and Ular Tangga result lists. Builds on
  both platforms; not yet seen in a real two-phone match.
- Step 10a (sign-in) is live on the relay and checked on the iPhone. Still open: the Pixel
  check.

## Device checks the owner hasn't done yet

Real users will try online play first; don't push for these, ask whether feedback came in.

- Online: iOS ↔ Android room, rejoin (steps 4–5), public Create/Join and quick match,
  tapping a share link on both phones, emotes in a running game.
- Nearby: iPhone ↔ Pixel ular tangga room checked 2026-11-11 (Pixel joined iPhone host
  lobby over BLE after the `room` characteristic fix).
- Ludo: iPhone and Pixel (menu, this phone, nearby, online). Android and iOS are wired; not checked on a device.
- iPhone: seat tiles and rule hint lines, loading screen, play card, ular tangga board.
- Audio: SFX, voice-over and music not yet listened to by the owner. New lines 2026-11-12:
  ular tangga dice-off ("Throw the die to set the playing order") and perang kapal placement
  ("Drag ships onto your ocean").   Die faces 1–6 (Satu!…Enam! / One!…Six!) spoken after each
  throw on ular tangga and ludo (2026-11-14). Perang kapal: miss / hit / sunk SFX + VO,
  then your/their turn; win/lose at the end (2026-11-14).
- Gameplay camera zoom removed on ular tangga, ludo, gaple and congklak (2026-11-14);
  boards stay on the whole-board / whole-table view.
- Bilingual: check both languages on the devices.
- Account: iPhone checked 2026-11-11 (Google sign-in and sign out, Apple sign-in and delete
  account). Pixel: sign in with Google, sign out, delete account still open.
- Friends (Android and iPhone): add by code and by link, remove, the errors, both themes
  and languages. Web `/f/` and AASA are live.

## Release setup

- **Path (owner, 2026-11-13):** App Store → production submit (not TestFlight as the goal).
  Play → closed testing first, then production later.
- Play: create the app, upload the AAB to closed testing, the `hapus_iklan` product. Enter
  Data safety from `docs/store.md` (AdMob + online relay rows) when uploading.
- Play app signing SHA-256 added to `web/public/.well-known/assetlinks.json` (2026-11-14;
  keep upload + debug fingerprints too). Live after the next web deploy.
- App Store version **1.0** (`WAITING_FOR_REVIEW`, resubmitted 2026-11-13 via Zen after a
  Developer Reject to add creatives): listing (id+en, no Android per 2.3.10), age 4+,
  Games/Board, free pricing, worldwide, build **1**, review contact, App Privacy published
  from `docs/store.md`. Screenshots from Pamer framed exports (iPhone 6.7″+6.5″, iPad 12.9″,
  iPhone Duo outer — en+id). Header + search creatives uploaded for Indonesian and English
  (U.S.) from `.tmp/pamer-exports/creatives/`. IAP `hapus_iklan` went with the version.
- Store listing copy in `docs/store.md` updated for all five games (2026-11-13). Graphics:
  editor + decks in `../project-pamerstudio-node/store-screenshots/` (phone, 7″/10″,
  iPhone/iPad, FG, App Store header/search creatives); older refs under `docs/brand/store/`.
- Link the AdMob apps to the store listings after publishing (needs a **public** Play page).
- **Android release ads (2026-11-14):** packaging OK (`scripts/verify-android-release-ads.sh`;
  real ids, `AD_ID`, R8). AdMob UMP **Krida EU consent** published (both Krida apps,
  privacy URL `https://krida.ardeman.com/privasi/`). Closed testing – Alpha **1.0.2 /
  versionCode 3** published. Debug build: Google test banner OK on Pixel XL. Release /
  closed install: production banner can fill on Pixel (TikTok Shop seen 2026-11-14) after
  an initial `Ad failed to load : 3` (NO_FILL). AdMob **Apps to confirm** shows
  `com.ardeman.krida` (**Setup incomplete**, 136 requests / 7d). **Finish setup** → add to
  existing Krida Android (`~5902194713`); do **not** create a second AdMob app.
  **Link to Google Play** still returns “Couldn't find any matching Google Play apps”
  (closed tracks aren’t publicly searchable; store URL 404). **Continue** stays disabled
  until Play links. Next: promote **1.0.2** to Play **production**, then retry Finish
  setup / Link to Play. Closed **1.0.3 / versionCode 4** AAB for Alpha (interstitial every finish + Remove-ads gate). AAB: `~/Desktop/krida-1.0.3-release.aab`.
  Interstitial after **every** finished game when ready (was every other; Android + iOS,
  2026-11-14).   Pre-ad gate with **Remove ads** before interstitial and rewarded (can't
  overlay AdMob creatives) — see `docs/decisions.md`. Remove-ads button and
  gate no longer wait for a loaded price string (closed testers). Play product
  `hapus_iklan` is active (1 purchase option, 2026-11-14). For **free test**
  purchases add the closed Google Group to account **License testing**; real
  money works for opted-in closed testers on a Play install without that.
- Once online play ships, drop the push trigger from the relay deploy workflow (a deploy
  drops open games).

## Deferred game ideas

- Congklak: rounds with "hangus" (burned holes) and a simultaneous start, both from the
  traditional game; bring them up when planning congklak work.
- Ular tangga: a game list at launch, then the mode screen with a game switcher on the left
  card.
