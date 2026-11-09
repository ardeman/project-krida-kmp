# Online play plan

Roadmap step 4 in `README.md`. Players on different networks play through a relay. The
host phone stays authoritative exactly as over BLE: the relay only forwards bytes and knows
no game rules. Same `Transport` interface, same protocol messages (`docs/protocol.md`).

## Decisions (2026-11-09)

- Relay: **Cloudflare Workers** with one **Durable Object per room** (WebSockets, no server
  to run). Source in `relay/` (TypeScript, `wrangler`).
- Android WebSocket client: **OkHttp** (approved, ~0.5 MB). iOS: `URLSessionWebSocketTask`.
- Scope: room code + share link/QR, rejoin with a short wait, quick match falling back to
  the computer, preset emotes, later accounts/friends/invites.

## Design

- **Relay framing.** A host socket and up to 3 guest sockets per room. The relay tags guest
  messages with a peer id for the host, routes host messages to one guest or all, and tells
  the host when a guest connects or drops. Documented as "Online relay" in `docs/protocol.md`.
  Limits: 4 sockets per room, message size cap, rate limit per socket, room deleted after
  10 min idle.
- **Room codes.** Online codes are 6 chars (same alphabet as `RoomCode`) so they can't be
  guessed; BLE keeps 4. Share link `https://krida.ardeman.com/r/CODE` (App Links / Universal
  Links via `web/`, falling back to the store page), QR encodes the same link.
- **Player drops (C).** Everyone sees "Waiting for Ayu… 15" (15 s), and the game waits when
  it is that seat's turn.
  After that the computer plays the seat (`HostSession.replaceWithBot`) and the game goes on.
  The player can rejoin with its `rejoinToken` at any time during the game and take the seat
  back from the computer, also after leaving the room on purpose or closing the app: the app
  keeps the seat (`SavedRoom`) and the menu offers "Rejoin".
  The phone itself reopens a dropped socket for the 60 s reconnect grace; after that, or
  after leaving, the player rejoins from the menu.
- **Host drops.** Guests see the same 15 s wait. The game state lives on the host phone, so
  if the host doesn't return the game ends ("Host left"). Host migration is out of scope.
- **Quick match (E).** A matchmaker Durable Object per game and player count. The first in
  the queue hosts. If nobody matches within 20 s, the game starts against the computer on
  the phone; a running bot game never pulls in a human later. The phone hosting a stranger's
  game could cheat; accepted for now.
- **Emotes (F).** A fixed set of bilingual emotes ("👍 Bagus!"), no free text, so no
  moderation is needed. Free-text chat would need filtering, report and block, and is not
  planned.
- **Accounts (D).** Sign in with Apple + Google, friends list in Cloudflare D1, invites via
  push (APNs; FCM on Android needs Firebase, ask first). App Store requires in-app account
  deletion. Largest step, last.

## Steps

Each step ends with the checks from `AGENTS.md` and, where it says so, a test on the iPhone
and the Pixel on different networks (one on mobile data).

1. [x] **Relay.** `relay/` Worker + room Durable Object, deployed to `workers.dev`; framing
   in `docs/protocol.md`; tiny test client script. Live at
   `wss://krida-relay.are-theman.workers.dev`; CI (`.github/workflows/relay.yml`) runs the
   smoke test locally, deploys, and runs it again against the live relay.
   `wss://krida-relay.ardeman.com` is deployed and passes the live smoke test. Mobile-data
   verification is pending. On the iPhone's mobile-data connection, the
   `workers.dev` address failed HTTPS and WebSocket TLS (`NSURLErrorDomain/-1200`), while
   Wi-Fi joined successfully. This does not establish a rejoin-token failure. An online
   connection timeout now reports the relay as unreachable; only the relay's `4004`
   response means the room is missing.
2. [x] **Shared.** 6-char online codes and link parsing in `RoomCode`; 15 s pause and
   hand-to-computer in `HostSession`; tests in `shared/src/commonTest/`. The session doesn't
   block moves while a player is away (congklak's host screens don't hand seats to the
   computer yet, so a hard pause could freeze a game); the game waits on that seat's turn and
   the apps show the countdown (`HostSession.handOffInMs`).
3. [x] **Android.** `online/OnlineTransport.kt` (OkHttp; host and guest reopen a dropped
   socket for up to the 60 s grace); "Online" next to "Nearby" reuses `NearbyRoom` and its
   screens with 6-letter codes and the share link in the QR; "Waiting for Ayu… 15" in both
   games (`online/Waiting.kt`), after which congklak and ular tangga hand the seat to the
   computer, and a guest whose host stays away sees "Disconnected from the host". When the
   host's own socket drops, its guests count as away too and get their seats back on rejoin.
   Not yet tried on a device.
4. [ ] **iOS.** Same with `URLSessionWebSocketTask` (`Transport/OnlineTransport.swift`,
   `Nearby/Waiting.swift`); strings in the catalog. Code done and builds; device test
   iOS ↔ Android open.
5. [ ] **Rejoin.** Moved up from step 7 (2026-11-09). Code done: a guest keeps its seat
   (`SavedRoom`, stored as `rooms.last` on both platforms) when it leaves a running game or
   the app closes, the menu shows "<game> is still on · Rejoin / Forget", and joining that
   code again (also "Reconnect") sends the token. The host takes a token back at any time
   during the game. Forgotten after leaving a lobby or a finished game, when the host
   refuses, or when the room is gone. Device test open: drop and return (airplane mode, app
   to background, leave and rejoin) for guest and host, nearby and online.
6. [x] **Quick match.** Matchmaker Durable Object; "Play online" with a 20 s search, then
   the computer. Moved up from step 9 (2026-11-09): playing strangers comes before share links.
   Code done on Android and iOS: PlayOnlineScreens with player count selection, QuickMatchClient
   connecting to /v1/match/{game}/{playerCount}, timeout fallback to computer. Device tests pending.
7. [ ] **Share links.** `/r/CODE` page in `web/`, `assetlinks.json` and
   `apple-app-site-association`, intent filter and associated domain; share sheet button.
8. [ ] **Privacy and store.** Privacy page (IP address and game traffic pass the relay, not
   stored), Play data safety, App Store privacy labels. Before any online release.
9. [ ] **Emotes.** Optional protocol message (`emote`), picker in the game screen.
10. [ ] **Accounts, friends, invites.**

## Public rooms and combined joining (2026-11-10)

Android and iOS offer Create room (Room setup left; Private/Public right) and Join room
(matchmaking left; code/QR right). Private is the default. New searches opt in to public
lobby discovery before group matchmaking. Public rooms retain their host, options,
computer controls and manual Start. Search matches the same game and selected total seats,
and does not split a waiting group across rooms. Host disconnects, starts and room closure
remove eligibility; an authenticated reconnect can restore a still-waiting public room.

Room admission reserves seats for 15 seconds and accounts for computer seats, live guest
sockets (including pending welcomes), and outstanding reservations. A host starting or
filling seats with computers before admission can make a delivered reservation unusable;
the guest receives the existing room-full failure. Running-game rejoin still uses the
session token. Optional relay metadata and the reservation field are in [protocol.md](protocol.md).
No new Durable Object migration or dependencies are required.

Android build, shared JVM tests, relay type checks and targeted discovery/group tests pass,
as does the actual local Workers runtime public-room smoke test. iOS (2026-11-10) builds;
it uses the same relay query, lobby status frames and reservations. Deployment and Android/iOS multi-phone checks
remain pending. The approved design and handoff are in [mockups/public-rooms.md](mockups/public-rooms.md).
