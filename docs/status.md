# Status and open work

What is still open outside the online plan (`docs/online.md` has its own steps). Update this
file in the same commit when an item is done or a new one comes up. Last updated 2026-11-10.

## Next

- Online step 8: privacy page (IP address and game traffic pass the relay, not stored), Play
  data safety, App Store privacy labels. Needed before any online release.

## Device checks the owner hasn't done yet

Real users will try online play first; don't push for these, ask whether feedback came in.

- Online: iOS ↔ Android room, rejoin (steps 4–5), quick match, tapping a share link on both
  phones.
- Nearby: iPhone ↔ Pixel room for ular tangga. Last try: the Pixel joining the iPhone's room
  showed only "Kode room" (no code, status or Back) and never connected. Not investigated.
- iPhone: seat tiles and rule hint lines, loading screen, play card, ular tangga board.
- Audio: SFX, voice-over and music not yet listened to by the owner.
- Bilingual: check both languages on the devices.

## Release setup

- Play: create the app, upload the AAB, the `hapus_iklan` product, Data safety. Play internal
  testing waits until several games are ready.
- Add the Play app signing key's SHA-256 to `web/public/.well-known/assetlinks.json` once Play
  signs the app.
- App Store: privacy labels; the IAP is ready to submit with the first build.
- Link the AdMob apps to the store listings after publishing; store graphics still open.
- Once online play ships, drop the push trigger from the relay deploy workflow (a deploy
  drops open games).

## Deferred game ideas

- Congklak: rounds with "hangus" (burned holes) and a simultaneous start, both from the
  traditional game; bring them up when planning congklak work.
- Ular tangga: a game list at launch, then the mode screen with a game switcher on the left
  card.
