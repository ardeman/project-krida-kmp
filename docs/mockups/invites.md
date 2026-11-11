# Invites proposal (online step 10c)

Approved by the owner (2026-11-12). Preview [host lobby, friend picker, invite card and iPhone notification](invites.svg).
Each panel is 874 × 402. Reuse the room frame, lobby cards, friend rows, the status card
and buttons. English appears in the preview; implementation needs matching Indonesian and
English copy.

## Flow

- Invites need an account on both sides, and only friends can be invited. Signed out, or
  in a nearby (BLE) room, the lobby stays as it is today.
- The host of an online room, signed in, gets an **Invite friends** button in the Players
  header, shown while a seat is open. It swaps the Players card for the friends list; ‹
  returns to the seats. Share (the room link) stays for everyone else.
- Each friend row has Invite. After a tap it reads "Invite sent"; when that friend takes a
  seat it reads "Joined". Back in the seats, a muted line under them lists invites still
  waiting ("Budi · invite sent"). No online status is shown, as in the friends list.
- The friend sees a card over any menu screen: "NAME invites you", the game, the room code
  and the player count, with Not now and Join. Join goes through the same path as a room
  link. Like room links, the card never appears over a game or a lobby; an invite that
  arrives then is shown on return to the menus if it is still valid.
- An invite ends when the room starts, fills or closes, when the friend taps Not now, or
  after 2 minutes. The host can invite the same friend again after that.
- iPhone, app closed or in the background: a notification "NAME invites you to play GAME"
  that opens the room. Android gets no push (decision "No Firebase"); the host uses Share.

## Relay

- A per-account inbox: the app keeps one WebSocket open (`/v1/inbox`, bearer token) while
  it is signed in and showing a menu, and the relay sends invites down it. Hibernating
  Durable Object, so an idle inbox costs nothing.
- `POST /v1/invites` `{friendId, code, key}` with the bearer token (`key` is the room's
  host key, so only the host can invite): the relay checks the two are friends and the
  room is open, stores the invite in the inbox (2 minutes) and, for an
  iPhone with a push token, sends the APNs notification. Rate limited per account and one
  live invite per friend and room. `docs/protocol.md` gets both under "Accounts".
- The host learns "Joined" from the lobby itself; rooms still do not use accounts, so the
  invited friend's join sends the invite id along as an optional field.
- APNs: `POST /v1/push-token` stores the iPhone's token with the session; sign out and
  account deletion remove it.

## Two parts

1. **10c-1 In-app invites**: relay inbox, Android and iOS lobby button, picker and card.
   No owner setup.
2. **10c-2 iPhone notifications**: needs owner setup first: an APNs key (`.p8`) set as
   relay secrets, and the Push Notifications capability on the App ID. The app asks for
   notification permission the first time the player opens Invite friends or adds a
   friend, never at launch. Privacy page and `docs/store.md` get the push token row.

## Decided (2026-11-12)

The owner left these to the agent's judgement:

- Delivery is the inbox WebSocket, not polling.
- No switch to turn invites off yet; removing the friend stops their invites.
- Only the host can invite.
