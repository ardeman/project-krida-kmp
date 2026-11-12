# Perang kapal UI mockup

Approved by the owner (2026-11-12). Open [the light and dark mockup](perang-kapal.svg).
Each panel is 874 × 402, the landscape phone design size. English is shown for review;
implementation must include matching Indonesian copy.

Classic Battleship as a voxel board game. Game id `perang_kapal`. Host stays authoritative.

## Rules (v1)

- **Seats:** 2 only (person vs person, or person vs computer).
- **Grid:** 10 × 10, columns A–J, rows 1–10.
- **Fleet** (five ships): Kapal induk 5, Kapal perang 4, Kapal penjelajah 3, Kapal selam 3,
  Kapal perusak 2.
- **Placement:** orthogonal, on-board, no overlap. Ships may touch (edge or corner).
- **Fire:** alternate **one shot** per turn (a hit does not grant another shot). Miss / hit /
  sunk are announced; a sunk ship reveals its full length on the firer's radar.
- **Win:** sink the whole enemy fleet.
- **Bots:** random valid placement; hunt/target firing. Easy / normal / hard vary how often
  the bot aims at a known ship cell vs pure hunt (covered in shared tests when coded).
- **Quick match / public rooms:** 2 seats, these rules (`StandardRules.perangKapal`). No host
  rule chips in v1.

## Modes

- **This phone:** person vs computer only (seat 0 person, seat 1 computer).
- **Nearby / Online:** two seats (person / computer). Same room screens; no new lobby layout.
- **Pass-and-play (two people, one phone):** out of scope for v1.

## Home

- Add **Perang kapal** to the left game switch after Ludo. Card face: deep sea blue
  `#1B4F72` with a five-ship silhouette; tagline "Sink the fleet." / "Tenggelamkan armadanya."
- Play card: **Play on** (This phone | Nearby | Online). This phone locks You + Bot; Nearby /
  Online show two seats. Hint line: classic one-shot rule. No rule chips.

## Game screen

- **Placement:** your ocean grid; tap a ship then a cell; **Rotate**; **Ready** when all five
  are legal. In a room both players ready before fire (computer places at once).
- **Battle:** two grids — **Your ocean** (ships + incoming shots) and **Radar** (fog, then
  miss/hit/sunk). Your turn: gold highlight / tap an open radar cell. Status: Miss / Hit! /
  ship sunk (bilingual). Left: back + two player tiles. Right: sound, rules help, emote
  (nearby/online). No die.

## Result

Same result card: winner / loser, **To menu** and **Play again** (host only in a room).

## Out of scope for v1

- Pass-and-play on one phone.
- Salvo / continue-on-hit / free-move variants and host rule chips.
- Custom fleets, smaller grids, or "ships must not touch".
- Team or >2 players.
- New room or matchmaking layouts.

## Implementation order

1. [x] Rules + SVG mockup approved.
2. [x] Shared: `PerangKapalGame` / board / bot + tests; `StandardRules.perangKapal`; protocol
   + relay allow `perang_kapal` / 2; fog-of-war views in `HostSession`.
3. [x] Android screen + menu + table + nearby/online wiring (`assembleDebug` 2026-11-12).
4. iOS parity.
5. Phone checks (this phone vs computer, then nearby/online).
