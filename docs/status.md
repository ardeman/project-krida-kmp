# Status and open work

What is still open outside the online plan (`docs/online.md` has its own steps). Update this
file in the same commit when an item is done or a new one comes up. Last updated 2026-11-22.

## Next

- **Werewolf** (sixth game): shared, Android, iOS and relay capacity (MAX_PLAYERS 8 /
  7 guests) done 2026-11-16 ([mockups/werewolf.md](mockups/werewolf.md)). LAN (Wi‑Fi) +
  Online + **This phone** vs computers (and quick-match “Play with computers”) wired
  2026-11-16; 5–8 seats, bots, Kepala desa. Builds. iOS Simulator smoke
  (`WerewolfSmokeTests`, host + 4 computers via `-openWerewolf`) passed 2026-11-16.
  Next: real phone checks (This phone / Play with computers, then multi-phone). **Claim
  chips** for discussion wired 2026-11-16 (shared `claim` message + Android/iOS UI).
  Phase VO + SFX (Night / Dawn / Day / Vote / Election / win / you died) wired 2026-11-16.
  Host leave confirm (lobby + mid-game, all linked games, Android + iOS) wired 2026-11-16.
  Device checks 2026-11-16: Pixel XL lobby + mid-game Leave room? dialog OK; iOS Simulator
  vs computers OK (Computer N + BotFace, claim chips/feed with seat numbers, Play again,
  Night→Dawn→Election→Discussion). Physical iPhone install + launch OK; host-leave there
  not driven end-to-end (Device Hub kept sticking on the simulator). Pushing relay to
  `main` drops open games — deploy deliberately.
- **What’s New after update:** once-per-marketing-version home card done 2026-11-16
  (Android + iOS; dismiss stores seen marketing version).
- **Seat link dots (all linked games):** 4-dot quality cue under each seat tile (decisions,
  2026-11-16). Not built yet.
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
- **Gaple pass-and-play** (owner, 2026-11-19; asked for by a TesBareng tester): built
  2026-11-22 on Android and iOS. On this phone seats 2–4 now cycle person → computer → empty
  (any mix, at least two seats), people are "Player 1…4", and a "Pass the phone to …" cover
  (shared `PassCover`, also used by perang kapal) goes up whenever another person on the
  phone has to play; the turn timer waits behind it. Computers' turns keep the last
  holder's hand on screen. Both build. iOS Simulator check 2026-11-22: two people (cover
  each way, the timer waits behind it) and two people + a computer (timeout autoplay,
  computer turn, cover back) OK. Pixel_XL emulator 2026-11-22, in Indonesian: same two
  tables OK, plus seat cycling and the play-card text, and a cover at the deal when
  Player 2 opens. Not yet on a real phone, nor a full round to the result card.
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
  Play → closed testing first, then production later. **App Store 1.0.4 is public**
  (2026-11-16, `https://apps.apple.com/app/id6832093929`). Landing page hero links there;
  Hero store CTAs are code buttons (black/white with theme), App Store → listing, Play
  button and status line → TesBareng (closed-testing sign-up). Swap Play href to public URL when live.
- Play: create the app, upload the AAB to closed testing, the `hapus_iklan` product. Enter
  Data safety from `docs/store.md` (AdMob + online relay rows) when uploading.
- Play app signing SHA-256 added to `web/public/.well-known/assetlinks.json` (2026-11-14;
  keep upload + debug fingerprints too). Live after the next web deploy.
- App Store version **1.0** (build **1**): rejected 2026-11-14 for guideline **2.1**
  (demo account / Sign-in required checked with empty credentials). Fixed same day:
  unchecked Sign-in required, Notes + Reply that sign-in is optional (Apple/Google for
  friends only; no username/password), resubmitted → **Waiting for Review**. Listing
  (id+en, no Android per 2.3.10), age 4+, Games/Board, free, worldwide, App Privacy from
  `docs/store.md`. Review-notes template in `docs/store.md`. IAP `hapus_iklan` with the
  version.
- Store listing copy in `docs/store.md` updated for all five games (2026-11-13). Graphics:
  editor + decks in `../project-pamerstudio-node/store-screenshots/` (phone, 7″/10″,
  iPhone/iPad, FG, App Store header/search creatives); older refs under `docs/brand/store/`.
- Link the AdMob apps to the store listings after publishing (needs a **public** Play page).
  iOS (AdMob app `~9588102946`): store link found App Store 1.0.4, but verify failed
  2026-11-16 — AdMob crawls `sellerUrl` `https://krida.ardeman.com/app-ads.txt`, which
  404'd. File added at `web/public/app-ads.txt` (same line as `ardeman.com/app-ads.txt`:
  `google.com, pub-4887874546732768, DIRECT, f08c47fec0942fa0`). After deploy to main:
  open that URL (plain text, not the 404 page), then AdMob **Check for updates**.
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
  setup / Link to Play.   Closed **1.0.3 / versionCode 4** AAB for Alpha (interstitial every finish + Remove-ads gate). AAB: `~/Desktop/krida-1.0.3-release.aab`.
  Interstitial when leaving a game (HUD back or result Menu) and on Play again /
  next Gaple round against computers, when ready (Android + iOS, 2026-11-14).
  Interstitial plays immediately (no confirm gate); rewarded still uses a
  **Remove ads** gate (can't overlay AdMob creatives) — see `docs/decisions.md`.
  Remove-ads button and gate no longer wait for a loaded price string (closed
  testers). Play product
  `hapus_iklan` is active (1 purchase option, 2026-11-14). Account **License
  testing** set to Google Groups `google-tester-indonesia@googlegroups.com`
  with `RESPOND_NORMALLY` (2026-11-14; replaced Email lists selection). Real
  money still works for opted-in closed testers on a Play install without that.
  **1.0.4 / versionCode 5** (2026-11-14): quit interstitial (no gate), in-app
  update check, larger gaple board with voxel hand on the rim. AAB:
  `~/Desktop/krida-1.0.4-release.aab` — Play closed testing **sent for review**
  (5 / 1.0.4). App Store **1.0.4** rejected 2026-11-15 (**5.1.2** ATT + **2.1(b)**
  IAP). Fixed and resubmitted 2026-11-15: ATT after UMP (`47c12f9`), IPA **build 6**,
  App Privacy Device ID tracking published, Review Notes + `usesIdfa`, encryption
  exempt. Cancelled the version-only queue, then one submission with **1.0.4** +
  **`hapus_iklan`**. **Live on the App Store** 2026-11-16 (track id `6832093929`).
  **1.0.5 / versionCode 6** (2026-11-17): Werewolf + claim chips (and related polish).
  AAB: `~/Desktop/krida-1.0.5-release.aab` — Play closed testing **sent for review**
  (6 / 1.0.5). Details in `docs/store.md`.
  **1.0.6 / versionCode 7 / iOS build 8** (2026-11-19): gaple fixes (both-ends tile plays at
  once, hand stays on screen on wide phones, clearer turn labels). iOS build 8 uploaded to
  App Store Connect (needs a 1.0.6 version + submit). AAB: `~/Desktop/krida-1.0.6-release.aab`
  uploaded to Play closed testing (alpha, `7 (1.0.6)`, completed) through the Play
  Developer API with gcloud ADC, quota project `pamer-studio`. Export used the Xcode account: the ASC API key
  can't cloud-sign (`Cloud signing permission error`).
  App Store **1.0.6 (build 8)** submitted 2026-11-19 through the ASC API → **Waiting for
  Review** (release after approval). iOS skipped 1.0.5, so its What's New lists Werewolf + claim
  chips and the gaple fixes; description updated from `docs/store.md` (adds werewolf). The
  in-app What's New card on iOS still shows only the gaple lines. 1.0.6 is live on the App
  Store (Ready for Sale, seen 2026-11-22).
  **1.0.7 / versionCode 8 / iOS build 9** (2026-11-22): gaple pass-and-play (2–4 people on one
  phone) and the battleship/gaple tap-aim fix. AAB `~/Desktop/krida-1.0.7-release.aab`
  uploaded to Play closed testing (alpha, `8 (1.0.7)`, completed) through the API. iOS build 9
  uploaded with altool (IPA exported with the Xcode account); App Store **1.0.7** submitted
  2026-11-22 through the ASC API → **Waiting for Review** (release after approval).
- Once online play ships, drop the push trigger from the relay deploy workflow (a deploy
  drops open games).

## Deferred game ideas

- Congklak: rounds with "hangus" (burned holes) and a simultaneous start, both from the
  traditional game; bring them up when planning congklak work.
- Ular tangga: a game list at launch, then the mode screen with a game switcher on the left
  card.
