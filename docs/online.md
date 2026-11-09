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
- **Player drops (C).** The game pauses and everyone sees "Waiting for Ayu… 15" (15 s).
  After that the computer plays the seat (`HostSession.replaceWithBot`) and the game goes on.
  The player can still rejoin within the 60 s reconnect grace (`rejoinToken`) and take the
  seat back from the computer.
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
2. [ ] **Shared.** 6-char online codes and link parsing in `RoomCode`; 15 s pause and
   hand-to-computer in `HostSession`; tests in `shared/src/commonTest/`.
3. [ ] **Android.** `OnlineHostTransport.kt` / `OnlineGuestTransport.kt` (OkHttp); "Online"
   next to "Nearby", create/join by code, lobby, waiting countdown. Strings in ID + EN.
4. [ ] **iOS.** Same with `URLSessionWebSocketTask`; strings in the catalog. Device test
   iOS ↔ Android.
5. [ ] **Share links.** `/r/CODE` page in `web/`, `assetlinks.json` and
   `apple-app-site-association`, intent filter and associated domain; share sheet button.
6. [ ] **Rejoin.** Drop and return on a device (airplane mode, app to background) for guest
   and host.
7. [ ] **Privacy and store.** Privacy page (IP address and game traffic pass the relay, not
   stored), Play data safety, App Store privacy labels. Before any online release.
8. [ ] **Emotes.** Optional protocol message (`emote`), picker in the game screen.
9. [ ] **Quick match.** Matchmaker Durable Object; "Play online" with a 20 s search, then
   the computer.
10. [ ] **Accounts, friends, invites.**
