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
| `lobby` | host → each guest | `players`, `you` (receiver's seat; seats compact when someone leaves), `capacity?` (selected lobby size; absent = game maximum), `options?` (the host's rule options; see below) |
| `color` | guest → host | `color` (pawn colour wanted, before the game starts) |
| `intent` | guest → host | `seq`, `move` |
| `state` | host → all | `seq`, `turn`, `finished`, `state`, `players`, `last?` |
| `invalid` | host → guest | `seq`, `reason` |
| `matched` | relay (matchmaker) → player | `role` (`host` or `guest`), `code?` (6-char room code, only on host; optional, new in quick match) |
| `ping` / `pong` | both | `t` |
| `bye` | both | — |

`seq` in `intent` is the guest's own counter; `seq` in `state` is the host's state version.
A guest that sees a gap simply waits for the next full `state` — every `state` is a full
snapshot, so there is no diffing to get wrong.

Each entry in `players` is `{seat, name, platform, connected?, local?, bot?, color?}`. `bot: true`
marks a computer player; bots run only on the host, which moves for them like any other
seat, so guests just show them (e.g. as "Komputer") and never act for them. Older guests
ignore the field. The host may add bots to the lobby before the game starts and remove
them again; the seats after a removed one move up, as when a guest leaves.

`options` (optional) in `lobby` is the rule options the host chose, as game-specific JSON,
so guests can show the rules before the game starts: congklak `{ "tembakAfterStore": true }`,
ular tangga the `UlarTanggaOptions` fields (`bounceBack`, `sixToEnter`, `firstFinishEnds`,
`sixRollsAgain`). Fields at their default may be left out. The game's `state` still carries
the options the game is played with. Older hosts leave it out (guests then show no rules in
the lobby); older guests ignore it.

`color` (optional) is the seat's pawn colour in games where players pick one (ular tangga:
`0` red, `1` blue, `2` green, `3` yellow); it is absent in other games. Every new seat,
guest or bot, gets the first free colour. A guest asks for another with `color`; the host
grants it when no other seat holds it and the game has not started, and answers with a
`lobby` either way, so a refused guest sees the colour it still has. The colours go into the
game's state when it starts. Older guests ignore the field; older hosts drop the `color`
message, so the guest keeps its colour.

The host also sends a `state` with the same `seq` when only `players` changed during a
game: a guest dropped out (`connected: false`), rejoined, or was handed to the computer.
Guests take the new `players` from it and keep the game state as it is. When a guest has been
gone 15 s, the host may let a bot play its seat (`HostSession.replaceWithBot`), so the seat
turns `bot: true` and keeps its name. A guest that sends its `rejoinToken` in `hello` gets the
seat back at any time while the game runs (also after a `bye` or a restart), and `bot` goes
back to `false`. Older hosts only take a token back within the reconnect grace (60 s from the
drop) and answer later ones with `reject` `started`. Guests show the 15 s countdown from the snapshot that first shows the seat
`connected: false`. Older guests drop same-`seq` snapshots and catch up on the next move.

`last` (optional, `{seat, move}`) is the move that produced this snapshot, so every device
can animate it (e.g. congklak sowing). It is absent on the first snapshot. Animate it only
when the snapshot's `seq` is exactly one more than the previous one; after a gap or a rejoin,
just show the snapshot. `GuestSession.lastMove` already applies this rule.

Congklak's `state` carries `tembakAfterStore` (optional, default `false`): the host's choice
of the Indonesian tembak rule, under which a tembak only counts once the turn's sowing has
passed the mover's own store. Guests play and animate by the flag in the state. A guest from
before the field ignores it and may animate such a move the old way; the next full snapshot
puts its board right.

Ular tangga opens with a dice-off that sets the play order, thrown by the players one at a
time as ordinary moves. While `opening` (optional, default `false`) is true, `turn` is the
seat to throw and a move is that seat's throw, rolled by the host. `openingRolls` (optional,
default empty) holds the rounds, one array per round with a roll per seat: `0` for a seat
that does not throw that round, or has not thrown yet. Everyone throws in the first round;
seats whose throws so far are all equal throw another round between themselves, until every
seat's throws differ. When the dice-off ends, `order` (optional, default empty = seat order)
lists the seats in play order, higher throws first, `firstTurn` is its first seat, and turns
go round in that order. `lastRoll`/`lastSeat` stay absent during the dice-off; guests find
each throw as the newly filled entry of the last round. No ular tangga guest has shipped
before these fields, so they replace the earlier host-rolled dice-off without a version bump.

It also carries `colors` (optional, default empty): each seat's pawn colour, `0` red, `1`
blue, `2` green, `3` yellow, all different. Empty means seat order (seat 0 red, seat 1 blue,
...). Hosts fill it from the lobby colours.

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
reassembles per sender with `Reassembler`. LAN sends whole messages (length-prefixed) and
the online relay one message per WebSocket frame (see below); neither uses BLE framing.

## Online relay

Online rooms go through a Cloudflare Worker (`relay/`) with one Durable Object per room. The
relay only forwards bytes; the host phone stays authoritative and the messages above are
unchanged. Online room codes are 6 chars from the same alphabet as `RoomCode`
(`RoomCode.generateOnline`), shared as `https://krida.ardeman.com/r/<CODE>`; the QR shows the
same link, and `RoomCode.parseOnline` also takes `krida://join?room=<CODE>` or a typed code.

Connect with a WebSocket to `wss://krida-relay.ardeman.com/v1/rooms/<CODE>`:

- Host: `?role=host&key=<KEY>`. `KEY` is 16–64 chars `[A-Za-z0-9_-]`, random, made by the
  host phone and kept for the room's life. The first host to connect claims the code with its
  key; only the same key can connect as host again (rejoin). A new host socket with the right
  key replaces an old one.
- Guest: `?role=guest`. Allowed once a host has claimed the room, up to 4 guest sockets.

Every WebSocket message is binary, one whole protocol message per frame:

```
byte 0     kind: 1 data, 2 connected, 3 disconnected, 4 kick (host → relay only)
byte 1     peer
byte 2..   payload (data only): the UTF-8 JSON message
```

On the host's socket `peer` is a guest id (1–255, given by the relay; the transport's peer
id is its decimal string). The relay tags each guest's data with the guest's id, sends
`connected`/`disconnected` as guests come and go, and on a host (re)connect sends
`connected` for every guest already there. The host sends data with `peer` = a guest id, or
`0` for every guest, and `kick` with a guest id to close that guest.

On a guest's socket `peer` is always `0`, the host. The guest gets `connected` when a host is
there (right after joining, or when the host comes back) and `disconnected` when the host
drops. Guests send data with `peer` = `0`.

If the host stays away for the reconnect grace (60 s), the relay closes the guests with
`4010` and forgets the room. A host that closes its socket normally (`1000`, leaving the room
on purpose) ends the room at once the same way; a dropped connection gets the grace. Close codes:

| Code | Meaning |
|---|---|
| `4000` | Bad request (code, role, key, or a text frame) |
| `4001` | Room taken: a host with another key holds this code; pick a new code |
| `4004` | No such room (guest joined before any host) |
| `4008` | Kicked by the host |
| `4009` | Room full |
| `4010` | Host left (closed normally) or gone past the grace |
| `4011` | Replaced by a newer host socket |
| `4013` | Message over 64 KB |
| `4029` | Rate limited (bursts of 50, 20 messages/s sustained) |

## Quick match (matchmaker)

Quick match pairs two or more players online without exchanging codes. The relay includes
a matchmaker Durable Object per game and player count.

Connect to `wss://krida-relay.ardeman.com/v1/match/<GAME>/<PLAYER_COUNT>` with a WebSocket:

- The first player to connect gets a 6-char room code and role `host` in a `matched` message.
  They then connect as host to the room endpoint.
- Each subsequent player for up to 20 seconds receives role `guest` in a `matched` message
  with the same 6-char code. They then connect as guest to the room.
- If no guest joins within 20 seconds, the host's connection expires from the queue.
  The host falls back to playing against the computer on their phone, and a game running
  against the computer never pulls in a human later (no rejoin from quick match into a
  computer-only game).

Players that cancel during the wait (close the WebSocket) are removed from the queue.

### Opt-in group search

Android and iOS clients add `?flow=group` to the same match URL. It uses a separate
`GroupMatchmaker` Durable Object namespace so legacy clients keep their existing flow.
Valid selections are `congklak/2` and `ular_tangga/2`, `/3`, `/4`, using the shared game IDs. All group events are JSON text WebSocket frames, independent of game-session
messages and binary room framing. Clients close the socket to cancel.

Relay events:

| type | Fields / meaning |
|---|---|
| `queue` | `humans` (including you), `target`, `bots` (empty seats), `remaining` (seconds), `offer` (20 s elapsed), `accepted` (your agreement), `agreed` (number who agreed), `revision` (composition/offer version) |
| `prepare_host` | `code`, `humans`, `bots`, `target`; this player opens the host room first |
| `preparing` | Same counts/code; other players wait for host readiness |
| `matched` | `role` (`host`, `guest`, or group-only `solo`), `code` for online roles; `humans`, `bots`, `target` for online roles, `target` for solo |
| `match_error` | Room preparation failed or a participant left during preparation; retry with a fresh search |

Optional group-flow fields: `queue` adds `acceptedHumans` (your humans-only vote) and
`agreedHumans` (number voting for that choice), both defaulting to false/zero. Preparation
and match events may include `seats`, the agreed total; absent means `target`. `target`
remains the original search selection. `humans` and `bots` always describe the actual room.

Vote timer (optional fields): once the offer opens for two or more humans, `queue` adds
`voteRemaining`, the seconds left in a 15 s vote; absent means no limit (a lone player).
Any join or leave clears the votes and restarts the timer. When every human has voted and
one choice has more votes, it starts at once. When the timer runs out, the choice with more
votes starts for everyone (undecided players go along); a tie or no votes at all sends the
group back to searching for another 20 s, and that `queue` carries `restart`: `"no_votes"`
or `"tie"`. Unanimous agreement still starts immediately. Clients that ignore these fields
still work: they see the group start or the offer close.

Client actions are `{ "type": "accept_bots", "revision": <queue revision> }`, `{ "type": "keep_searching" }`, and
`{ "type": "host_ready" }`. The first two only apply after the offer. Computer seats
require every human's explicit agreement. A new arrival or departure clears all prior
agreement and changes `revision`; stale agreement actions are ignored. Keep searching resets
the group's 20 s window and clears agreement while keeping its players together. A full human group proceeds immediately. A lone player who accepts
gets `matched` with `role: "solo"` and starts a local game with `target - 1` computers.

After the offer, `{ "type": "accept_humans", "revision": <queue revision> }` votes to
start with only the humans found (at least two). A unanimous choice starts at once;
otherwise the vote timer above decides. Changing a vote replaces that player's previous choice. Membership changes and continuing
the search clear both kinds of votes. Unanimous humans-only agreement produces zero bots
and `seats` equal to the human count. A lone player may instead send
`{ "type": "play_solo", "revision": <queue revision>, "seats": <total> }` to choose
2–4 total seats for ular tangga or two for congklak, including themselves. The relay checks
the revision, game limit and lone membership before returning `matched` with `role: "solo"`
and the chosen `seats`; the client starts locally with `seats - 1` computers. Earlier group
clients retain their existing computer-fill choice and cannot cast humans-only votes.
Both apps' solo buttons instead close the match socket and start the selected local game
directly, so this path also works before the updated relay is deployed. Leaving removes
the player from the queue as with cancellation; any concurrent arrival stays in the queue.

For online groups the selected host opens the room socket, then sends `host_ready` only
after that socket opens. The relay then sends `matched` with the same code to every player
and closes the match sockets normally. It fails the whole group if preparation takes over
15 s. The host waits for the expected humans before adding the agreed computers; Start
stays disabled until the agreed seats are filled. Quick-match lobbies do not offer manual
computer-seat changes. Computers still run only on the authoritative host.

Queue membership, deadlines and agreement live in socket attachments, and alarms implement
the deadlines, so a Durable Object waking from hibernation preserves the search. A completed
group never admits new queued players.

## Public online rooms (optional discovery extension)

A new host may add `game=<GAME>&seats=<TOTAL>&public=1` to its existing authenticated
room WebSocket query. Valid combinations are congklak/2 and ular_tangga/2–4. Omitting
`public=1` keeps the room private; omitting all metadata retains legacy room behavior.
Metadata is fixed on the first authenticated host connection; another key cannot publish
or change the room. Public/private controls discovery, not access by code or QR.

Binary relay kind `5` is optional host → relay lobby status, with peer `0` and UTF-8 JSON
payload `{ "bots": <count>, "started": <boolean> }`. It is never forwarded to guests.
Guests cannot send status. Hosts resend their latest status after reconnecting; `started`
is monotonic for the room lifetime. The relay also recognizes outgoing host `lobby`/`state`
envelopes for seat count and start lifecycle; game-specific rules/state remain on the host.
Existing kinds retain their meanings and the game-session protocol remains v1.

New group-search clients add `&public=1` to `?flow=group`. Their tickets stay separate from
older clients' groups. Before preparing a new group room, the matchmaker checks public
lobbies for the same game and selected total seats. It also checks while waiting, so new
public rooms can receive queued groups. A group is transferred together only when it fits.
When none fits, existing full-group preparation, offers and local fallback remain unchanged.

A public-room match sends `{ "type": "matched", "role": "guest", "code": <CODE>,
"reservation": <TOKEN>, "target": <TOTAL>, "seats": <TOTAL> }`. `reservation` is a new
optional field. That guest connects using `?role=guest&reservation=<TOKEN>`. Tokens hold
one seat each for 15 seconds, are consumed once, and are omitted on socket reconnect.
Reservations, live sockets and computer seats count toward lobby capacity; code joins
cannot take held seats. Cancelled in-flight transfers release reservations. After a
match is delivered, abandoned tokens expire automatically. If a host fills seats with
computers or starts before admission, the relay can reject a reservation with `4009`;
clients retain the existing failure/retry flow. Started games still admit ordinary rejoin
connections for the host to validate using their session rejoin token.

Room publication, reservation and release use internal DO fetches unreachable through the
public Worker router. Rooms without a connected host, closed/started rooms, and full
lobbies cannot provide new reservations. The authoritative room rechecks eligibility and
serializes reservations with manual admissions. Public hosts keep their settings and
manual Start; matching into an existing room does not elect another host.

## Compatibility rule

Never change the meaning of an existing field. Add optional fields only. Bump
`protocol` when a breaking change is unavoidable; the host rejects mismatches with
`reject{reason:"version"}` so players get a clear "update the app" message.
