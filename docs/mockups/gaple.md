# Gaple UI mockup

Approved by the owner (2026-11-12). Open [the light and dark mockup](gaple.svg).
Each panel is 874 × 402, the landscape phone design size. English is shown for review;
implementation must include matching Indonesian copy.

Indonesian block dominoes with a double-six set, as a voxel table game. Game id `gaple`. The
host stays authoritative and deals; guests see only their own hand.

## Rules (v1)

Picked by the owner, 2026-11-12.

- **Seats:** 2–4 (person / computer). With 4 seats the host may turn on **partners**: seats
  across from each other (0 + 2, 1 + 3) play as a team and share one score.
- **Tiles:** 28 (double-six). Each seat gets 7. With 2 or 3 players the rest are set aside
  face down and stay out of play.
- **Opening:** in the first round the holder of **balak enam [6|6]** plays it. If nobody holds
  it (2–3 players), the holder of the highest double plays that double. Later rounds: the
  last round's winner leads with any tile (on a team win, the seat that went out or had the
  lowest pips).
- **Play:** turns go clockwise. A tile must match one open end of the line; if it fits both
  ends the player picks the end. A player with no fitting tile **passes** (no drawing).
  Playing a fitting tile is compulsory.
- **Round end:** a player empties their hand ("domino"), or nobody can play ("gaple"). In a
  gaple the lowest pip total in hand wins the round; ties go to the earlier seat after the
  last player to place a tile.
- **Scoring:** the round winner (or team) scores the pips left in every other hand (with
  partners, the partner's pips are not counted). First to **100** wins the match; if both
  pass 100 in one round, the higher total wins.
- **Bots:** easy plays a random fitting tile; normal plays the heaviest fitting tile; hard
  also keeps ends it can follow and blocks ends the next seat just passed on. Covered in
  shared tests.
- **Quick match / public rooms:** the 2–4 seat picker, solo, these rules
  (`StandardRules.gaple`). No partners in matchmaking for v1.

## Modes

- **This phone:** you against 1–3 computers. Two people on one phone is out of scope
  (hidden hands would need a pass cover every turn).
- **Nearby / Online:** 2–4 seats (person / computer / empty), same room screens. The host
  sets partners in the lobby when there are 4 seats.

## Home

- Add **Gaple** to the left game switch after Perang kapal. Card face: maroon `#7B2D3B` with
  an upright [6|6] and two tiles laid against it; tagline "Match the ends, empty your hand."
  / "Sambung ujungnya, habiskan kartumu."
- Play card: **Play on** (This phone | Nearby | Online), seat tiles for 2–4, then a
  **Partners** chip pair (Off | Across 2 v 2) shown only with 4 seats. Hint line:
  "No tile fits? Pass. First to 100 points wins."

## Table screen

- **Left:** back, title with the round number and the target, then one tile per seat:
  colour, name, tiles left, score (team letter A / B with partners; partners show the shared
  team score). The seat whose turn it is is outlined in its colour.
- **Centre:** a felt table seen from above at a slight tilt. The line of play starts in the
  middle and turns at the table's edges; doubles lie across the line. The two open ends get
  dashed gold rings. Opponents' hands are face-down voxel tiles at their table sides.
- **Bottom:** your hand, face up and upright. Tiles that fit are raised with a gold ring.
  Tap a tile to play it; if it fits both ends, both end rings pulse and you tap one.
- **Status pills** on the felt: "Your turn" and the last event ("Bot 3: Pass", "Bot 1
  plays 6|4"). A pass also gets a short "Pas!" voice line.
- **Right:** sound, rules help, emote (nearby / online). No die.

## Round end

A card over the table: the headline ("Domino! You went out" / "Gaple! Nobody can play"), then
one row per seat (or per team with partners) with pips left, points this round and total.
The round winner's row is gold. **Next round** (host only in a room; guests see "Waiting for
the host").

## Result

The same result card as the other games: winner headline, final totals ranked, **To menu**
and **Play again** (host only in a room).

## Out of scope for v1

- Drawing from the boneyard, "ngocok" bonuses, extra points for ending on a double or for
  closing both ends, and other house scoring.
- Two people on one phone.
- Partners in quick match / public rooms.
- A target other than 100.

## Implementation order

1. [x] Rules + SVG mockup approved.
2. [ ] Shared: `GapleGame` / board layout (`GapleBoard`, line turns at the edges) / bot +
   tests; `StandardRules.gaple`; protocol + relay allow `gaple` / 2–4; hidden-hand views in
   `HostSession` (like perang kapal's fog of war); partners as a game option.
3. [ ] Android screen + menu + table + nearby/online wiring.
4. [ ] iOS parity: `ios/Krida/Gaple/`.
5. [ ] Phone checks (vs computer, then nearby/online).
