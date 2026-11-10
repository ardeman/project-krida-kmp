# Ludo UI mockup

Approved by the owner (2026-11-10). Open [the light and dark mockup](ludo.svg). Each panel
is 874 × 402, the landscape phone design size. English is shown for review; implementation
must include matching Indonesian copy.

Shared rules, bot and board path geometry are already in (`LudoGame`, `LudoBoard`). This
mockup covers the menu card, the game HUD and the result card. Nearby and online rooms
reuse the existing room screens once the game's table exists — no new lobby layout.

## Home

- Add Ludo to the left game switch (carousel on Android, switcher on iOS), between or after
  the current games. Card face: blue `#3D6EB5` with a four-colour yard hint; tagline
  "Race your four tokens home." / "Pacu empat bidakmu ke rumah."
- Play card matches ular tangga: **Play on** (This phone | Nearby | Online), seat tiles for
  2–4 (person / computer / empty), then **Start** / host / join. Default four seats with
  seat 0 a person.
- Rule chips (defaults on unless noted), with a hint line under them like ular tangga:
  - **6 to enter** (on)
  - **6 again** (on)
  - **Capture again** (on)
  - **First wins** (off — play until one player is left)
- Quick match / public rooms use the same 2–4 seat picker as ular tangga and
  `StandardRules.ludo`.

## Game screen

Reuse the ular tangga HUD pattern: transparent 3D board on the brand backdrop.

- Left: back, then player tiles (colour swatch + name; current seat highlighted).
- Centre: voxel Ludo board from `LudoBoard` path anchors (yards, ring, home stretches).
- Right: sound toggles, rules help, emote (nearby/online), and the 3D die.
- **Your turn · roll:** tap / hold the die as in ular tangga; gold rings only after a roll
  when a token must be chosen.
- **Pick a token:** after a roll with more than one legal token, gold rings on those tokens;
  tap one. A single legal token moves by itself (no extra tap). A burned third 6 shows a
  short status then passes the turn.
- Captures and home entries animate along the shared walk path; captured tokens return to
  their yard slots.

## Result

Same result card pattern as the other games: ranking (finish order, then the rest by
progress), **To menu** and **Play again** (host only in a room).

## Out of scope for v1 UI

- Partner teams (2v2).
- Partner colour locking beyond free-for-all (deferred with 2v2).
- New room or matchmaking layouts.

## Implementation

- Android (2026-11-10): menu card, play card, screen, table, preview, nearby/online wiring,
  relay accepts `ludo` with 2–4 seats. Device check open.
- iOS: next session.
