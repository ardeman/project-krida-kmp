# Name and avatar proposal (online step 10d)

Approved by the owner (2026-11-12). Preview
[Account, the edit state for Google and Apple accounts, and where the avatar shows](profile.svg).
Each panel is 874 × 402. Reuse the room frame, cards, text field and buttons from the
Account screen. English appears in the preview; implementation needs matching Indonesian
and English copy.

## Flow

- The account card gets an **Edit** pill in its top corner, and the grey circle becomes the
  player's avatar.
- Edit turns the account card into "Edit profile": the name field (at most 24 characters,
  with a counter), Cancel / Save, and the Friends card becomes the avatar picker. Save
  sends name and avatar at once; a failed save keeps the card open with the error under
  the field. An empty name is not saved.
- Avatars are a **shape + colour mix**, drawn in code on both platforms (no image assets,
  no uploads). The player picks independently:
  - **Shape** (12): seed, die, kite, star, moon, sun, flower, leaf, heart, crown, drop,
    hill.
  - **Background** and **Symbol** colour (14 each): the twelve shape colours plus dark
    brown and white. Background and symbol cannot be the same colour (that tile is
    dimmed).
- The picker has three tabs (Shape / Background / Symbol). The live preview at the top of
  the edit card updates as each choice changes.
- A Google account gets a row under the grid: **Use my Google picture**. Apple accounts
  see only the shape and colour tabs.
- An account that never picked gets a mix chosen from its account id (shape and both
  colours), so nobody has a blank circle and it stays the same on every phone.

## Proposed answers to the open questions

- **Google picture: an option, never the default.** A photo of the player's face goes to
  other people only after the player picks it.
- **Everyone in the room sees the name and avatar,** strangers from quick match included.
  One rule is simpler than two, and the player chose both. A player who wants to stay
  private keeps a drawn avatar and a nickname.
- **Signed out:** the seat keeps the device name and gets a neutral grey figure. Bots get
  the same figure.
- **Where it shows:** friend rows, the invite card, the room's player list, and the seat
  name on the board. iPhone notifications keep text only (the name).

## Relay and protocol

- D1: an `avatar` value on the user: `{shape}-{bg}-{fg}` with zero-based indices (shape
  `0`–`11`, colour `0`–`13`), `g` for the Google picture, empty for "not picked". The
  relay stores the Google `picture` URL at sign-in and refreshes it at each sign-in;
  deleting the account deletes both. Same background and symbol indices are refused.
- New request with the bearer token: `PATCH /v1/me` `{name?, avatar?}` → `{user}`. The
  name is trimmed and cut to 24 characters; `g` is refused for Apple accounts; a bad
  mix string is refused.
- `user`, friend entries and an invite's `from` get an optional `avatar`: the mix string,
  or the picture URL when the player chose `g`.
- Rooms: `hello` and each entry in `players` get an optional `avatar` with the same
  values; a signed-in client sends its account name as `name`. Optional fields only, so
  `protocol` stays 1 and older clients show the neutral figure.
- A client loads a picture only from `https://lh3.googleusercontent.com/`; anything else
  shows as the neutral figure, so a guest cannot make other phones call another server.
  No image library: the platform's own loader, with a small memory cache.
- `docs/protocol.md`, the privacy page and `docs/store.md` get a line: the relay keeps the
  chosen avatar and, for Google accounts, the picture's URL; other players' phones load
  that picture from Google.

## Implementation and validation

Relay, shared, Account/friends/invites and room seat avatars are coded on Android and iOS.
Deploy the relay and privacy pages, then check the screens on the Pixel and iPhone in both
themes and languages, with one Google and one Apple account in the same room.
