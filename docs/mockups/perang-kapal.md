# Perang kapal UI mockup

Approved by the owner (2026-11-12). Open [the light and dark mockup](perang-kapal.svg).
The owner changed placement, the ships, the battle layout and one-phone play the same day
(see "Changes after the mockup" below); the SVG still shows the first version.
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

- **This phone:** person vs computer, or two people passing the phone (the second seat tile
  switches between computer and person).
- **Nearby / Online:** two seats (person / computer). Same room screens; no new lobby layout.

## Home

- Add **Perang kapal** to the left game switch after Ludo. Card face: deep sea blue
  `#1B4F72` with a five-ship silhouette; tagline "Sink the fleet." / "Tenggelamkan armadanya."
- Play card: **Play on** (This phone | Nearby | Online). This phone locks You + Bot; Nearby /
  Online show two seats. Hint line: classic one-shot rule. No rule chips.

## Game screen

- **Placement:** your ocean grid and a tray of the five ships (drawn top-down, no labels).
  Tap a ship in the tray then a cell, or drag it from the tray; drag a placed ship to move
  it; tap a placed ship to turn it. **Ready** when all five are legal. In a room both
  players ready before fire (computer places at once).
- **Battle:** the **Radar** (fog, then miss/hit/sunk) is the large grid; **Your ocean**
  (ships + incoming shots) is a small view-only copy to its left. Tap an open radar cell
  on your turn; a gold ring marks the last shot. Status: Miss / Hit! /
  ship sunk (bilingual). Left: back + two player tiles. Right: sound, rules help, emote
  (nearby/online). No die.

## Result

Same result card: winner / loser, **To menu** and **Play again** (host only in a room).

## Changes after the mockup (owner, 2026-11-12)

- Two people can play on one phone. A full-screen "Pass the phone to …" cover with
  **Continue** goes up between the two placements and after each shot (the shot's result
  shows for 1.5 s first), so neither sees the other's ocean.
- Ships are voxel models (carrier with flight deck, battleship, cruiser, submarine,
  destroyer), built in `PerangKapalBoard.shipParts`; the tray draws the same boxes top-down.
- No Rotate button: a tap on a placed ship turns it around its middle.
- In battle the opponent's grid is large (it fills the space between the HUD columns) and
  your own small (about a third of the size, bottom left, under the player tiles).
- Battle status in the right column: **Enemy fleet** and **Your fleet**, each with the
  ships left (e.g. 3/5) and the five ships drawn small, sunk ones faded and struck out.

## Out of scope for v1

- Salvo / continue-on-hit / free-move variants and host rule chips.
- Custom fleets, smaller grids, or "ships must not touch".
- Team or >2 players.
- New room or matchmaking layouts.

## Implementation order

1. [x] Rules + SVG mockup approved.
2. [x] Shared: `PerangKapalGame` / board / bot + tests; `StandardRules.perangKapal`; protocol
   + relay allow `perang_kapal` / 2; fog-of-war views in `HostSession`.
3. [x] Android screen + menu + table + nearby/online wiring (`assembleDebug` 2026-11-12).
4. [x] iOS parity: `ios/Krida/PerangKapal/` (table, scene, view, preview), menu and
   nearby/online wiring (simulator build 2026-11-12).
4b. [x] Changes after the mockup, shared + Android + iOS (builds 2026-11-12).
5. Phone checks (this phone vs computer, then nearby/online).
