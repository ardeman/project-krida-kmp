# Public rooms and combined joining proposal

Approved by the owner, with Room setup on the left. Preview [Create room and Join room in light and dark](public-rooms.svg).
Each panel is 874 × 402. Reuse native room frames, cards, segmented controls and buttons.
English appears in the preview; implementation needs matching Indonesian and English copy.

## Flow

- Online home offers Create room and Join room. Move the standalone Quick match action
  into Join room, alongside code entry and QR scanning.
- Create room places Room setup on the left and the visibility choice plus Create room
  action on the right. It offers Private / Public before opening the lobby. Proposed default: Private,
  preserving existing rooms' invitation-only behavior. The preview shows Public selected.
- Public rooms accept both matchmaking players and friends using a code or QR. Private
  rooms accept code/QR joins and never appear in matchmaking. This controls discovery;
  it does not add a password or change code-based access.
- Keep the current game setup controls. Ular tangga rooms select 2–4 total seats, default
  four; congklak has two. This total also determines matchmaking compatibility.
- Join room has two side-by-side cards: Find a room (player count and Find a match), and
  Have a room code? (code entry, QR and Join room). Manual joins still learn the game
  from the host. Searching uses the game currently selected on home.
- Matchmaking first looks for an available public lobby for that game and total seat count.
  If none is available, retain group matchmaking, the 20-second offers, unanimous partial
  group decisions and local computer fallback. Searches must also discover rooms made
  public while those players are waiting.
- Matched players enter the existing lobby. A manually created public room keeps its host,
  rule choices and manual Start. Guests enter the existing lobby.
- Show Public / Private status in the host lobby. Visibility changes after creation are
  outside this proposal; leave and recreate to choose differently.

## Relay and lifecycle requirements for implementation

- Only the authenticated room host can publish its room. Existing clients and rooms default
  to private; introduce optional discovery metadata without changing game protocol fields.
- Reserve open seats atomically across code joins and matchmaking, and release failed or
  cancelled reservations. Preserve waiting groups rather than silently splitting them.
- Stop matching rooms that are full, started, closed or whose host is disconnected. Remove
  stale listings; restore eligible listings only when the authenticated host returns.
- Base availability on actual lobby seats, including computers and pending admissions,
  rather than raw socket counts. Recheck eligibility when handing off a match.
- Keep the host authoritative and retain existing rejoin behavior for running games.

## Implementation and validation after approval

Follow the project's approved order: Android and relay first, iOS in the next session.
Update protocol docs, product decisions and the README roadmap with the implementation.
Use targeted relay tests for discovery, privacy, compatibility, competing joins, cancellation,
stale listings and room lifetime. Build each changed native platform and run shared tests if
shared behavior changes. Inspect the changed screens on physical devices in light and dark
when available. This proposal changes documentation only; no runtime checks have been run.

## Implementation status

Android, iOS and relay implementation complete. Both apps use these two cards for online
creation and joining, with the actual saved rule controls in Room setup. The private/public
choice defaults to Private. Room setup can scroll on a short screen while the Create room
action remains visible in the other card. Both languages use the same layout. The existing
nearby/Bluetooth join screen stays available.

The relay stores discovery entries alongside group-search state. New searches opt in with
`public=1`; older clients keep their existing queue. Reservations last 15 seconds, are
consumed on admission, and prevent manual joins taking held seats. Cancelling during
reservation releases them; abandoning an already delivered match expires its reservation.
A host adding computers or starting before a reserved guest arrives can invalidate that
admission; the guest gets the existing room-full failure with retry/code joining available.
The shared host session enforces the chosen capacity for humans, local seats and computers.
Public-room hosts retain their settings, computers, and manual Start behavior.

Validation: Android assembleDebug, shared JVM tests, relay TypeScript and targeted relay
checks pass. The local Workers runtime smoke test covers a waiting two-person group,
private-room exclusion, joining a newly public room with reservations, and started-room
exclusion. Live relay smoke (room, group, public) against `krida-relay.ardeman.com` passed
2026-11-11; cross-phone end-to-end checks remain open.

Physical Pixel XL validation: the online home actions, Create room for congklak, and merged
Join room for both games were inspected in Indonesian/light mode. Setup is on the left;
all primary actions and the four-seat selector are visible without scrolling. Dark-mode
inspection remains unavailable because this Pixel reports `mNightModeLocked=true`.
Screenshots were captured only for the changed screens. Shared coverage also checks that
guests learn the selected capacity through optional lobby metadata. There are 29 passing
relay tests, plus passing local room-transport and group timeout smoke checks.
