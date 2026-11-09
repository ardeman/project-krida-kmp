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
  build Mac has little memory. If no device is connected, build only and say what still needs
  a look on a device.
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
- **Pawn colours** are picked in room lobbies only (a colour another player holds is disabled).
  No colour picker for this-phone or vs-computer games.

## Nearby and online rooms

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
- Relay deploys on push to `main` touching `relay/`; drop the push trigger once online play
  ships, because a deploy drops open games.

## Release

- Play internal testing waits until several games exist, not just congklak.
- Store listing text and privacy answers live in [store.md](store.md).
