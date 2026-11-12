# Status and open work

What is still open outside the online plan (`docs/online.md` has its own steps). Update this
file in the same commit when an item is done or a new one comes up. Last updated 2026-11-12.

## Next

- Offline LAN (README roadmap 6): coded under Nearby (Bluetooth | Wi‑Fi control, default
  Bluetooth; decision in `docs/decisions.md`). Android and iOS host/guest transports,
  play-card Link control, join/lobby copy. iOS dual-simulator e2e passes
  (`node scripts/test-ios-lan.mjs`, 2026-11-12). Android dual-emulator and
  Android↔iOS scripts are in (`scripts/test-android-lan.mjs`,
  `scripts/test-cross-lan.mjs`; TCP via `adb forward` because emulator NSD is unreliable).
  Local AVDs (2026-11-12): **Pixel_XL** = API 29 google_apis (stable); **Pixel_9a** =
  Android 16 / API 36 Play Store + `pixel_9a` skin (recreated after a broken `target` that
  showed as API 0 in Device Manager). Dual-AVD / long CLI cold boots overheat this Mac and
  can leave Play images bootlooping if killed mid-first-boot — prefer Device Manager, one
  AVD, wipe userdata if bootloop; do not force `-gpu swiftshader_indirect` (Studio uses
  `auto`). Open: phone LAN checks (same Wi‑Fi and hotspot, Android ↔ iOS, leave/rejoin);
  dual-emu / cross only if the Mac can hold two AVDs without thrashing.
- Online step 10b, friends list: relay, web, Android and iOS are built
  (`docs/mockups/friends.md`); deploying the relay and web and the phone checks are open.
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
- Audio: SFX, voice-over and music not yet listened to by the owner.
- Bilingual: check both languages on the devices.
- Account: iPhone checked 2026-11-11 (Google sign-in and sign out, Apple sign-in and delete
  account). Pixel: sign in with Google, sign out, delete account still open.
- Friends (Android and iPhone): add by code and by link, remove, the errors, both themes
  and languages. On the iPhone `/f/` links open the app only after the web deploy.

## Release setup

- Play: create the app, upload the AAB, the `hapus_iklan` product. Enter Data safety from
  `docs/store.md` (AdMob + online relay rows) when uploading. Play internal testing waits
  until several games are ready.
- Add the Play app signing key's SHA-256 to `web/public/.well-known/assetlinks.json` once Play
  signs the app.
- App Store: enter App Privacy from `docs/store.md` when submitting; the IAP is ready with
  the first build.
- Link the AdMob apps to the store listings after publishing; store graphics still open.
- Once online play ships, drop the push trigger from the relay deploy workflow (a deploy
  drops open games).

## Deferred game ideas

- Congklak: rounds with "hangus" (burned holes) and a simultaneous start, both from the
  traditional game; bring them up when planning congklak work.
- Ular tangga: a game list at launch, then the mode screen with a game switcher on the left
  card.
