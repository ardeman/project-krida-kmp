# Decisions

Product and design decisions already made with the owner, so they are not reopened or undone
by accident. Newest notes at the bottom of each section. When a decision changes, edit it here
in the same commit as the code.

## How we work

- **Mockup first for UI.** New screens or a redesign start as a mockup the owner approves, then
  Android in one session, iOS in the next. Approved mockups: UI rework (home, game, end of
  game) and nearby play (host lobby, guest waiting, join, connecting/errors).
- **Both platforms the same.** Every screen and feature ships on Android and iOS with the same
  layout and copy; see [design.md](design.md).
- **Test on the physical devices** (an iPhone and a Pixel XL), not simulators or emulators: the
  build Mac has little memory. Install with `./scripts/install-ios.sh` /
  `./scripts/install-android.sh` ([install.md](install.md)). If no device is connected, build
  only and say what still needs a look on a device.
- **iOS builds need JDK 21:** set `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`,
  or the shared-framework build phase fails with "Unable to locate a Java Runtime".
- Time Android startup with a release build (`assembleRelease`); debug Compose is ~5× slower.

## Look and feel

- **Always landscape**, including iPad and foldables (`LandscapeStage` turns the layout on a
  portrait window).
- **Backdrop:** the terracotta radial glow on every screen and the website; apps follow the
  system light/dark setting. 3D boards render on a transparent view so the glow shows behind
  them. Rejected: plain cream/sand, sage green, an outline on the die.
- **Voxel 3D**, generated in code; no textures.
- **Loading screen** (wordmark + spinning die) at start until the menu's board preview is ready
  (0.7–3 s) and before each game until the board's first frame. Every other loading indicator
  reuses that same animated cube/K logo at a compact size, on both platforms.
- **Menu:** left card switches games (arrows and swipe); right is one **play card** with a
  "Play on: This phone | Nearby friends" picker, seat tiles, rule options and one Start button.
  "Join with code" only shows for nearby play.
- **Seat tiles:** tapping cycles person → computer → empty; at least 2 seats filled and at least
  1 person. Congklak: seat 1 is always a person.
- **Rule chips explain themselves:** a hint line under the options says what the chosen or last
  tapped option does.
- **Ads:** banner on menus only (not in game, not in room lobbies), interstitial after a game
  (frequency-capped), rewarded ad for a hint from the hard bot. A one-time "Remove ads"
  purchase turns them off. Audience General / 13+, not Families.

## Language and audio

- Indonesian and English. Indonesian devices get Indonesian; every other locale gets English.
  The app also has an ID | EN picker beside the sound toggles. The website is Indonesian at `/`
  and English at `/en/`.
- Voice lines exist in both languages (same voice) and switch live with the in-app language.

## Congklak

- Traditional rules: sowing runs clockwise, each player's store is on their left.
- **Tembak (capture) rule** is a pre-game option, default the Indonesian rule: a capture only
  counts once this turn's sowing has passed your own store ("Past store"); the other option is
  "Anytime". A "once per game" reading was rejected.
- **Who starts:** Me / Opponent / Random, default Random. Rock-paper-scissors start was rejected.
- Game screen: gold rings only on playable pits, never on the stores; store labels instead;
  "seeds in hand" count while sowing.
- Deferred, each its own task: multiple rounds with burnt (hangus) holes (probably a protocol
  bump) and the simultaneous first move.

## Ular tangga

- Fixed board, defined in code. Default rules: land exactly on 100 (an overshoot stays put),
  a 6 rolls again, play goes on until one player is left. Options: bounce back, a 6 to enter,
  first to finish ends the game, no extra roll on 6.
- **First player by dice-off:** every player throws, ties rethrow among themselves, the throws
  set the whole play order. Played by hand (bots throw by themselves).
- 3D die: tap for a short toss, hold to spin and release for a long throw. Both players see the
  same throw ([dice-animation.md](dice-animation.md)).
- **Pawn colours** are picked in room lobbies (a colour another person holds is disabled). A
  computer's colour can be taken: you switch with that computer. On this phone, a lone person
  vs computers gets the same picker; pass-and-play with only people keeps seat colours.

## Nearby and online rooms

- **LAN lives under Nearby** (2026-11-12): no fourth Play-on tile. Nearby means same place —
  Bluetooth (BLE) or Wi‑Fi/hotspot (Bonjour `_krida._tcp`). When Nearby is selected, a
  Bluetooth | Wi‑Fi control chooses the radio (default Bluetooth, so today's nearby stays
  the same). Same 4-letter codes, lobby and join screens as BLE. Offline LAN is README
  multiplayer roadmap step 6; built 2026-11-12 (phone checks still open).
- Join by QR code or by typing the 4-character code. The QR holds the room link
  `krida://join?room=ABCD`. No new dependencies for QR: shared Kotlin encoder, VisionKit on iOS,
  Google code scanner on Android (no camera permission).
- The host can add computers to empty seats in an ular tangga room; host + one computer can
  start. Not offered for congklak, which has its own vs-computer mode.
- Online play goes through our Cloudflare Workers relay (plan and steps in
  [online.md](online.md)). A dropped player gets a 15 s countdown, then a computer plays the
  seat; they can rejoin and take it back any time during the game ("Rejoin / Forget" on the
  menu).
- **Quick match:** search for the selected total (2 for congklak; 2–4 for ular tangga).
  Ular tangga defaults to four seats. Full human groups proceed immediately. After 20 s,
  two or three humans vote to start with just those humans or fill the remaining seats
  with computers; they can also keep searching together or cancel. Every player sees the
  live tally (a dot per player) as a poll. The vote lasts 15 s (decided 2026-11-10): it ends
  early once everyone has voted and one choice leads; at the deadline the choice with more
  votes wins, and a tie or no votes searches again for 20 s with a note saying why.
  Membership changes clear the votes and restart the timer.
- **Quick match uses standard rules (2026-11-10):** a matched group always plays the default
  rules (`StandardRules` in shared: ular tangga defaults; congklak tembak after the store and a
  random first player), whatever the matched host saved. Custom rules stay in rooms someone
  creates; a public room plays its host's rules. The Join room search card says so.
- **Countdowns look the same everywhere (2026-11-10):** a draining ring with the seconds in
  the middle, red for the last 5 s: quick-match search, the vote, and a dropped player's wait.
  A lone player can choose 2–4 total seats (including themselves) against 1–3 computers
  locally; congklak stays at two total seats. A partial human group stays online.
- **Matchmaking UI alignment approved:** reuse the room header and compact status cards;
  see [mockup](mockups/matchmaking.svg). Implement Android first, then iOS next session.
- **Public rooms and combined joining (2026-11-10):** online home offers Create room and
  Join room. Creation puts Room setup on the left and Private / Public plus Create room
  on the right; default Private. Public rooms are discoverable by matchmaking and still
  accept code/QR joins. Private rooms accept code/QR only. Join room combines searching
  with manual code/QR. Search prefers an available public lobby for the same game and
  selected total seats, then retains group matchmaking and its existing fallback offers.
  Waiting groups stay together. Existing public-room hosts keep their rule choices and
  manual Start; full, started and disconnected rooms cannot receive matches. Approved
  [mockup](mockups/public-rooms.md). Android and relay first, then iOS (done 2026-11-10).
- **Accounts are optional** (2026-11-11): playing never needs one. Sign in with Apple (iOS)
  and Google (both) from an account button on the menu; the relay keeps only the provider's
  user id and a display name in D1, no email. Google runs as a browser flow through the
  relay, so no sign-in SDK is added. Deleting the account is in the app.
- **Friends** (2026-11-11): every account has one fixed friend code. Adding by code or
  `/f/CODE` link is instant and mutual, with no request to accept; either player can remove
  the other. A friend row shows only the display name. Approved
  [mockup](mockups/friends.md).
- **No Firebase** (2026-11-11): Android gets no FCM push. Invites on Android show while the
  app is open (over the relay) or come as a share link; iOS may use APNs.
- **Name and avatar** (2026-11-12): a signed-in player can set and change their name, and
  it replaces the device name on their seat in rooms. Avatars are a shape + colour mix
  drawn in code (any of 12 shapes with independent background and symbol colours) or the
  Google profile picture; no photo uploads. Plan: step 10d in [online.md](online.md).
  Approved [mockup](mockups/profile.md).
- **Notification permission** (iPhone, 2026-11-12): asked right after sign-in, never at
  launch. The app shows its own card first (what the notifications are for, Not now /
  Allow); the iOS question comes only after Allow, so a Not now does not use it up.
- **Invites** (2026-11-12): only the host of an online room invites, and only friends. An
  invite is delivered over a per-account inbox WebSocket while the friend's app shows a
  menu, and lasts 2 minutes. No switch to turn invites off yet. Approved
  [mockup](mockups/invites.md).
- Relay deploys on push to `main` touching `relay/`; drop the push trigger once online play
  ships, because a deploy drops open games.

## Ludo

- Free-for-all only for v1 (2–4 seats, four tokens each). Partner teams (2v2) are deferred.
- Default rules match common Indonesian/app Ludo: a 6 leaves the yard onto the colour’s
  start square; a 6 rolls again; three 6s in a row burn that third roll and end the turn;
  landing exactly on the home stretch finish is required; capturing a lone opponent token
  sends it to the yard and grants another roll. Colour start squares, the four starred
  safe squares (eight steps past each start) and the home stretch are safe; two or more of
  the same colour on one square form a block (safe, and others cannot land there). The
  board mesh shows those stars, home-entrance arrows (into each finish lane, not on the
  start squares), coloured yard pads and a four-triangle centre, matching a classic
  Ludo board. Non-play yard colour and inner cream are continuous slabs (not gapped
  tiles); only the four yard slot pads keep spacing, with cream padding between them.
- Host options (same idea as ular tangga): no-6-to-enter, no extra roll on 6, no extra roll
  on capture, first to finish ends the game. Quick match will use the defaults
  (`StandardRules.ludo`).
- Pawn colours are the same four as ular tangga (red, blue, green, yellow), picked in room
  lobbies and when a lone person plays computers on this phone (switch with a computer).
- **Roll timer (2026-11-10; ular tangga 2026-11-11):** when it is a local player's turn to
  throw, a 15 s countdown (`Krida.ROLL_TURN_MS`) sits above the die (`CountdownRing`). Holding
  the die pauses it; at 0 the phone rolls a short toss. Same on ludo and ular tangga (tap /
  hold / long toss and the countdown).
- **Board facing (2026-11-10):** each phone orbits the camera so that player's pawn colour
  sits bottom-left (`LudoBoard.cameraEyeFor`; corners are red→green→yellow→blue clockwise,
  not colour-index order). Pass-and-play keeps seat 0's colour there; guests and the host
  each see their own side.
- **Board layout (2026-11-10):** classic corners — red bottom-left, blue top-left, yellow
  top-right, green bottom-right — with matching clockwise starts, home stretches, stars
  (start+8) and centre triangles.
- Shared rules and board path geometry first; UI mockup approved
  ([mockups/ludo.md](mockups/ludo.md)); Android done, then iOS.

## Perang kapal

- Fourth game (2026-11-12). Classic Battleship; game id `perang_kapal`. Mockup approved
  ([mockups/perang-kapal.md](mockups/perang-kapal.md)).
- **2 seats only.** This phone is person vs computer, or two people passing the phone behind
  a "Pass the phone to …" cover (owner, 2026-11-12; first decided as vs computer only).
  Nearby / Online allow person / computer seats like the other 2-player games.
- **10 × 10 grid**, fleet 5 / 4 / 3 / 3 / 2 (Kapal induk, perang, penjelajah, selam, perusak).
  Ships orthogonal, no overlap or overhang; **may touch**. Alternate **one shot** per turn
  (hit does not grant another). No host rule chips in v1; quick match uses
  `StandardRules.perangKapal`.
- Shared rules / board / bot first, then Android (both done 2026-11-12), then iOS. Voxel
  grids, no textures. Fog of war: `PrivateStateRules` + per-seat `state` payloads from
  `HostSession`; simultaneous placement via `GameRules.actingSeats`.
- Placement and board (owner, 2026-11-12, both platforms): ships are voxel models, not
  boxes; the tray shows the ships themselves, to tap then place or to drag and drop; a tap
  on a placed ship turns it (no Rotate button); in battle the opponent's grid is large and
  your own a small view-only copy.
- Added by the agent, not asked for (tell the owner if they should go): placing a ship
  selects the next ship not yet on the ocean; a gold ring marks the last shot; a peg on a
  ship stands on its deck so hits on your own ships show.

## Game lineup

- Dam-daman and macanan are dropped from the web page and the plan (owner, 2026-11-12).
  Add either one only if users ask for it. The web page lists only games that exist.

## Release

- Play internal testing waits until several games exist, not just congklak.
- Store listing text and privacy answers live in [store.md](store.md).
