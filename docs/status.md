# Status and open work

What is still open outside the online plan (`docs/online.md` has its own steps). Update this
file in the same commit when an item is done or a new one comes up. Last updated 2026-11-11.

## Next

- Online step 10: accounts, friends, invites (largest step; ask before Firebase/FCM).

## Device checks the owner hasn't done yet

Real users will try online play first; don't push for these, ask whether feedback came in.

- Online: iOS ↔ Android room, rejoin (steps 4–5), public Create/Join and quick match,
  tapping a share link on both phones, emotes in a running game.
- Nearby: iPhone ↔ Pixel room for ular tangga. Last try: the Pixel joining the iPhone's room
  showed only "Kode room" (no code, status or Back) and never connected. Not investigated.
- Ludo: iPhone and Pixel (menu, this phone, nearby, online). Android and iOS are wired; not checked on a device.
- iPhone: seat tiles and rule hint lines, loading screen, play card, ular tangga board.
- Audio: SFX, voice-over and music not yet listened to by the owner.
- Bilingual: check both languages on the devices.

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
