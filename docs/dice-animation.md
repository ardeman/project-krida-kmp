# Dice Animation Consistency

## Problem
When players threw dice in a multiplayer game, each player saw a different random trajectory and landing position. This caused visual inconsistency and confusion about the dice result across devices.

## Root Cause
The dice animation parameters (landing X/Z position and spin rotation) were generated locally on each client using platform-specific random number generators:
- Android: `Random.nextFloat()` and `Random.nextInt()`
- iOS: `.random(in:)` and `Int.random(in:)`

Since each device used its own random seed, the animation was different on each player's screen.

## Solution
Implemented deterministic pseudo-random animation by seeding the random number generator with deterministic values from the game state:

### Changes

#### Shared Code (`UlarTanggaGame.kt`)
- Added `UlarTanggaDiceAnimation` data class containing landing position (`landX`, `landZ`) and spin rotation (`spinYaw`)
- Added `UlarTanggaDiceAnimation.compute(seat, value, isLongToss)` function that:
  - Seeds the RNG with a combination of player seat and roll value
  - Generates consistent random parameters on all platforms
  - Returns the same animation parameters regardless of platform

**Key insight**: The seed is deterministic from game state alone (`(seat << 32) | value`), so all clients independently compute identical animation parameters without any protocol changes.

#### Android (`UlarTanggaRenderer.kt`)
- Updated `throwDie()` to accept optional `seat` parameter (default 0)
- Replaced local random calls with `UlarTanggaDiceAnimation.compute(seat, value, long)`
- Removed import of standalone `Random`

#### Android Screen (`UlarTanggaScreen.kt`)
- Updated both `throwDie()` call sites to pass the throwing player's seat number
  - Dice-off throw: passes `thrower` seat
  - Main game throw: passes `seat` (the current player)

#### iOS (`UlarTanggaScene.swift`)
- Updated `throwDie()` to accept optional `seat` parameter (default 0)
- Replaced local `.random(in:)` calls with `UlarTanggaDiceAnimation.compute()`
- Removed manual random calculations

#### iOS View (`UlarTanggaView.swift`)
- Updated both `scene.throwDie()` call sites to pass the throwing player's seat
  - Dice-off throw: passes `thrower` seat
  - Main game throw: passes `seat` (the current player)

## Benefits

1. **No Protocol Changes**: The solution works without modifying the network protocol or state format
2. **Consistent Animation**: All players see identical dice trajectories and spin
3. **Cross-Platform**: Works identically on Android and iOS
4. **Deterministic**: Given the same seat and roll value, animation is guaranteed to be identical
5. **Backward Compatible**: Seat parameter defaults to 0, old call sites still work

## Testing

The animation consistency can be tested by:
1. Starting a multiplayer game with 2+ players
2. Having each player throw dice in sequence
3. Verifying all players see the same:
   - Dice landing position on the board
   - Dice flight trajectory
   - Final face value display
   - Dice rotation/spin during flight

**Expected behavior**: All players see identical dice animations with the same landing position and final orientation.
