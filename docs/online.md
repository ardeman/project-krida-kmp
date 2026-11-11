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
7. [ ] **Share links.** Code done 2026-11-10: `web/public/.well-known/` has
   `assetlinks.json` (upload and debug keys) and `apple-app-site-association` (`/r/*`); GitHub
   Pages serves `/r/CODE` through `404.astro`, which shows the code, a `krida://join` button
   and a link to the site. Android has an `autoVerify` filter for `https://krida.ardeman.com/r/`,
   `singleTask` and `onNewIntent` (a link only applies from the menus, never over a game or
   lobby); iOS has `applinks:krida.ardeman.com` in `Krida.entitlements` and the existing
   `onOpenURL`. A "Share" pill beside the room code in the online host lobby opens the share
   sheet. `/.well-known/assetlinks.json` and `apple-app-site-association` are live on
   `krida.ardeman.com` (upload and debug keys only). Open: add the Play app signing key's
   SHA-256 once Play signs the app (Play Console › App integrity); tap a link on both phones.
8. [x] **Privacy and store.** Privacy page covers the relay (IP and game traffic pass through,
   not kept as a history; short-lived room state only) in Indonesian and English, effective
   10 November 2026. Play Data safety and App Store privacy labels for AdMob + the online
   relay are in `docs/store.md` (enter them in the consoles when submitting). Listing copy
   there now mentions online play. Entering the answers in Play Console / App Store Connect
   still waits on the first online release upload.
9. [x] **Emotes.** Optional `emote` message and fixed bilingual catalog in `Emotes`; host
   fans out with a 2 s cooldown. Picker + bubble on congklak and ular tangga (Android and
   iOS), nearby and online only. Shared tests cover fan-out and cooldown; device check open.
10. [ ] **Accounts, friends, invites.** Split in three:
    - [x] **10a Sign-in.** Relay: `relay/src/accounts.ts` with D1 (users, sessions), Sign in
      with Apple (identity token check), Google through a browser flow via the relay (no
      SDK), sign out, account deletion ("Accounts" in `docs/protocol.md`);
      `npm run smoke:accounts` passes locally against mocked Apple/Google. Android: account
      button on the menu, `account/Account.kt` + `AccountScreen.kt` (Google). iOS:
      `Account/Account.swift` + `AccountScreen.swift` (Apple + Google). Built 2026-11-11
      without a mockup at the owner's request. Relay deployed by hand (D1 `krida` created,
      Google secrets set) and checked on the iPhone the same day: Google sign-in, sign out,
      Apple sign-in, delete account. Open: the Pixel check. Apple sign-in on Android is not
      offered.
    - [ ] **10b Friends list.** Approved [mockup](mockups/friends.md): a friend code per
      account, instant mutual adding by code or `/f/CODE` link, removal by either player.
      Relay (`GET`/`POST /v1/friends`, `DELETE /v1/friends/{id}`, D1 tables made on first
      use), the web `/f/CODE` page and Android (two-card Account screen, `/f/` and
      `krida://friend` links) built 2026-11-11; `npm run smoke:accounts` covers friends.
      iOS built the same day (same two-card screen and links, `/f/*` in
      `apple-app-site-association`). Open: deploying the relay and web, and the Pixel and
      iPhone checks.
    - [ ] **10c Invites.** iOS via APNs. Android without Firebase (owner, 2026-11-11):
      invites show while the app is open, over the relay, or arrive as a share link.
      Approved [mockup](mockups/invites.md) (2026-11-12). 10c-1, in-app invites: the
      relay (inbox, `POST /v1/invites`, deployed), Android and iOS are built (2026-11-12);
      the phone checks are open. 10c-2, iPhone notifications: code done 2026-11-12. The
      relay keeps APNs device tokens (`POST /v1/push-token`, `relay/src/push.ts`) and sends
      each invite through APNs; iOS (`Account/Push.swift`) asks for notifications when a
      signed-in player opens Invite friends or adds a friend, and a tapped notification
      joins the room from the menus. `npm run smoke:accounts` covers it against a mocked
      APNs, and `node scripts/test-ios-invite-push.mjs` runs it end to end on a simulator
      against the live relay with real (sandbox) APNs: the app's card and the iOS question,
      an in-app invite, then a notification with the app in the background and closed,
      each tapped into its room (passed 2026-11-12). Open: a check on the real iPhone, and
      production APNs, which only a TestFlight or App Store build uses.

    Owner setup for 10a (1 and 2 done; the optional key in 3 still open): (1) Google Cloud: an OAuth client of type "Web application" with
    redirect URI `https://krida-relay.ardeman.com/v1/auth/google/callback`, then
    `wrangler secret put GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` in `relay/`. (2) The
    relay's Cloudflare API token (GitHub secret) needs D1 edit rights (added 2026-11-12); the first deploy
    creates the `krida` database. (3) Apple Developer: enable Sign in with Apple on the
    `com.ardeman.krida` App ID; optionally create a Sign in with Apple key and set
    `APPLE_TEAM_ID`, `APPLE_KEY_ID`, `APPLE_PRIVATE_KEY` so deletion revokes access at
    Apple. (4) Update the store privacy answers from `docs/store.md`.

    Owner setup for 10c-2: (1) and (2) done 2026-11-12. (1) Apple Developer › Keys: the
    team's APNs key (Sandbox & Production, team scoped, shared by all the owner's apps; the
    `.p8` is kept on the owner's Mac). (2) Relay secrets `APNS_KEY_ID`, `APNS_PRIVATE_KEY`
    and `APPLE_TEAM_ID` are set. (3) Push Notifications on the `com.ardeman.krida` App ID;
    Xcode's automatic signing adds it on the next device build.

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
as does the local Workers runtime public-room smoke test. iOS (2026-11-10) builds with the
same relay query, lobby status frames and reservations. Live relay smoke (room, group,
public) against `wss://krida-relay.ardeman.com` passed 2026-11-11; Android/iOS multi-phone
checks remain open. The approved design and handoff are in
[mockups/public-rooms.md](mockups/public-rooms.md).
