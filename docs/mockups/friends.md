# Friends list proposal (online step 10b)

Approved by the owner (2026-11-11). Preview [Account with friends and with none, in light and dark](friends.svg).
Each panel is 874 × 402. Reuse the room frame, cards, text field and buttons from the
account and join screens. English appears in the preview; implementation needs matching
Indonesian and English copy.

## Flow

- Friends need an account. Signed out, the Account screen stays as it is today (one card).
- Signed in, the Account screen gets two cards. Left: the account (name, provider, the
  player's friend code with a Share pill, Delete account, Sign out). Right: Friends (a code
  field with Add, then the list, which scrolls).
- Every account gets one friend code: 8 characters from the room-code alphabet, shown as
  two groups of four. It does not change.
- Share opens the share sheet with `https://krida.ardeman.com/f/CODE`. Opening the link
  works like room links: it opens the app on the Account screen with the code filled in,
  and only from the menus, never over a game or lobby. Signed out, the player signs in
  first and the code is kept.
- Adding is instant and mutual. Typing a code or opening a link and tapping Add
  puts each player in the other's list; there is no request to accept. Either player can
  remove the other with ×, after a confirmation, and that removes both sides.
- A friend row shows only the display name ("Player" when empty). No online status and no
  invite button yet; invites are step 10c.
- Errors appear under the field: code not found, your own code, already friends, list
  full (100 friends).

## Relay

- D1: a `friend_code` column on `users` (unique, created on first use) and a `friends`
  table with one row per pair. Deleting the account deletes its rows.
- New requests, all with the bearer token: `GET /v1/friends` (own code and the list),
  `POST /v1/friends` `{code}`, `DELETE /v1/friends/{id}`. `docs/protocol.md` gets them
  under "Accounts". Rooms and matchmaking still do not use accounts.
- Adding by code is rate limited per account, so codes cannot be guessed in bulk.

## Other changes

- Web: `/f/CODE` page like `/r/CODE`, and `/f/*` in `apple-app-site-association`; Android
  gets an `autoVerify` filter for `/f/`.
- Privacy page and `docs/store.md`: the relay now also keeps the friend code and who is
  friends with whom, deleted with the account.

## Implementation and validation after approval

Relay and Android first, iOS in the next session. Build each changed platform and check
the screen on the Pixel and iPhone in both themes and languages.

## Implementation status

Relay, web and Android are built (2026-11-11). The relay keeps the code in its own
`friend_codes` table instead of a column on `users`, so the tables can be created on first
use without a migration. On Android the row's × turns the row into "Remove NAME?" with
Cancel and Remove. A friend link opens the Account screen with the code filled in; it stays
in the field through sign-in while the app keeps running. Checked: relay typecheck and
`npm run smoke:accounts` (add, list, remove, the errors, deletion, the try limit), shared
JVM tests, Android `assembleDebug`, web build. Not checked on a device.

iOS is built (2026-11-11) the same way as Android: the two cards, the row that turns into
"Remove NAME?", and `/f/` and `krida://friend` links that open the Account screen from the
menus only. `apple-app-site-association` lists `/f/*`. Checked: the simulator build and the
web build. Not checked on a device.
