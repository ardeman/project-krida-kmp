# Krida protocol v1

Same bytes on every transport (BLE, LAN, online). Source of truth:
`shared/src/commonMain/kotlin/com/ardeman/krida/core/protocol/`.

## Constants

| Name | Value |
|---|---|
| Protocol version | `1` |
| Max players | `4` (host + 3 guests) |
| BLE service UUID | `6b72a1d0-4b1e-4c8a-9b8e-6b7269646101` |
| BLE `toHost` characteristic (guest → host, write) | `6b72a1d0-4b1e-4c8a-9b8e-6b7269646102` |
| BLE `toGuest` characteristic (host → guest, notify) | `6b72a1d0-4b1e-4c8a-9b8e-6b7269646103` |
| Bonjour / NSD service type | `_krida._tcp` |
| Reconnect grace | 60 s |

Example: `{"type":"hello","protocol":1,"name":"Ayu","platform":"ios"}`

The room code (4 chars, e.g. `KRD4`) is put in the BLE advertisement's local name as
`Krida-KRD4`, and in the QR code as `krida://join?room=KRD4`.

## Messages

JSON, UTF‑8, with a `type` discriminator. Game-specific data (`move`, `state`) is an
opaque JSON value owned by each game's `GameRules`.

| type | Direction | Fields |
|---|---|---|
| `hello` | guest → host | `protocol`, `name`, `platform`, `rejoinToken?` |
| `welcome` | host → guest | `seat`, `roomCode`, `game`, `rejoinToken` |
| `reject` | host → guest | `reason` (`version`, `full`, `started`) |
| `lobby` | host → each guest | `players`, `you` (receiver's seat; seats compact when someone leaves) |
| `intent` | guest → host | `seq`, `move` |
| `state` | host → all | `seq`, `turn`, `finished`, `state`, `players`, `last?` |
| `invalid` | host → guest | `seq`, `reason` |
| `ping` / `pong` | both | `t` |
| `bye` | both | — |

`seq` in `intent` is the guest's own counter; `seq` in `state` is the host's state version.
A guest that sees a gap simply waits for the next full `state` — every `state` is a full
snapshot, so there is no diffing to get wrong.

Each entry in `players` is `{seat, name, platform, connected?, local?, bot?}`. `bot: true`
marks a computer player; bots run only on the host, which moves for them like any other
seat, so guests just show them (e.g. as "Komputer") and never act for them. Older guests
ignore the field.

`last` (optional, `{seat, move}`) is the move that produced this snapshot, so every device
can animate it (e.g. congklak sowing). It is absent on the first snapshot. Animate it only
when the snapshot's `seq` is exactly one more than the previous one; after a gap or a rejoin,
just show the snapshot. `GuestSession.lastMove` already applies this rule.

Congklak's `state` carries `tembakAfterStore` (optional, default `false`): the host's choice
of the Indonesian tembak rule, under which a tembak only counts once the turn's sowing has
passed the mover's own store. Guests play and animate by the flag in the state. A guest from
before the field ignores it and may animate such a move the old way; the next full snapshot
puts its board right.

Ular tangga's `state` carries `openingRolls` (optional, default empty): the host's opening
dice-off, one array per round with a roll per seat (`0` for a seat already out). Every seat
throws in the first round, the seats tied on the best roll throw again, and `firstTurn` is
the seat left with the best roll. Guests animate the rounds from it; a guest that ignores it
just starts at `turn`.

It also carries `colors` (optional, default empty): each seat's pawn colour, `0` red, `1`
blue, `2` green, `3` yellow, all different, chosen by the players before the game. Empty
means seat order (seat 0 red, seat 1 blue, ...), which is also what a guest from before the
field shows.

## BLE framing

BLE writes/notifications are limited by MTU (iOS usually ~185 bytes, Android up to 512).
Every encoded message is split into frames:

```
byte 0     message id (0–255, wraps)
byte 1     frame index
byte 2     frame count
byte 3..   payload slice
```

Max payload per frame = `mtu - 3 (ATT header) - 3 (frame header)`. The receiver
reassembles per sender with `Reassembler`. LAN and online transports send whole
messages (length-prefixed) and skip framing.

## Compatibility rule

Never change the meaning of an existing field. Add optional fields only. Bump
`protocol` when a breaking change is unavoidable; the host rejects mismatches with
`reject{reason:"version"}` so players get a clear "update the app" message.
