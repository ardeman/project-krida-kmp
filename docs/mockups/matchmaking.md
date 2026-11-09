# Matchmaking alignment proposal

Approved by the owner, including the partial-group computer-seat choice. Android, iOS and relay
implementation complete; relay rollout verification and cross-phone checks remain pending.

Open [the light and dark mockup](matchmaking.svg). Each panel is 874 × 402, the landscape
phone design size. The preview uses the current theme colours and a system rounded font
fallback; the implementation will use the native Theme fonts and existing components.
English is shown for review; implementation must include matching Indonesian copy.

## Layout

- Reuse `NearbyFrame`: circular back button, game title and the online place line.
- Use a centred 480-wide card, corner 20, border 1.5, 24 horizontal padding and existing buttons.
- Setup uses the existing player selector styling: 2–4 for ular tangga. Congklak has two
  players, so show that as information rather than offering unsupported counts.
- Searching uses the existing 64-circle status treatment, a 22 Black title, muted detail,
  a small countdown and Cancel. The spinner animates in the native implementation.
- After 20 seconds, partial groups can unanimously choose Play with 2/3 players or Play
  with computers. Lone players can pick 2–4 total seats for ular tangga (congklak stays two)
  and play locally against computers. Both offers retain Cancel and Keep searching.
  Connection failure offers Back and Try again with a translated, friendly message.
- All actions fit without scrolling. Back in the header and the system back action agree
  with Cancel/Back in the card.

## Implementation accompanying the visual change

Retry must start a fresh search. Play the computer must start the selected game's local bot
mode. Search state must survive recomposition; timers and matchmaking clients must stop on
exit, failure or a successful match. Raw server errors must not appear in the UI.

Follow the agreed platform order: Android after mockup approval, iOS in the following session.
Build each changed platform and inspect light/dark on a physical device when available.

## Platform implementation

- Both platforms reuse the room frame, compact cards and segmented picker, with matching
  EN/ID copy. Congklak has two seats; ular tangga defaults to four and permits 2–4.
- iOS now uses the shared `ular_tangga` game ID, creates the host room during `prepare_host`,
  and sends `host_ready` only after its room socket opens. Guests join directly.
- Both hosts wait for all expected humans before adding agreed bots, disable Start until
  the agreed seats are present, and hide manual bot changes in quick-match lobbies.
- Both clients support unanimous agreement for computers or humans only, preserving revision
  resets. Solo fallback closes the search socket and immediately starts the chosen local
  game, without relay approval. Retry creates a fresh search; exit/failure closes any
  untransferred host room, socket and countdown.
- Group search is opt-in (`?flow=group`); deployment includes the `v3` Durable Object
  migration in `relay/wrangler.toml`. Existing clients stay on the old matchmaker.
- Still verify a four-seat search on Android/iOS phones: two humans, agreement from each,
  one cancellation, continuing the search, and an online game with two computers.
- iOS debug builds accept `-openQuickMatch ular_tangga` (or `congklak`) for screenshots;
  add `-searchQuickMatch` to exercise the real relay search and 20-second offer.

## Validation

Android `assembleDebug`, the iOS simulator build, relay TypeScript checks, and 20 targeted
group-search tests pass. The iOS quick-match catalog matches Android in EN and ID for
all 22 strings used by this flow; it also respects the in-app language picker.
iOS visual/timeout verification remains pending: simulator installation/launch stalled
during this check, so no light/dark or native end-to-end result is claimed.
The added tests cover unanimous starts with two/three humans, conflicting votes, choice
changes, membership/revision resets, solo 2/3/4-seat choices and congklak's two-seat limit.
The updated setup was inspected on the Pixel XL in light mode: four seats selected by
default, translated fallback guidance and both actions fit without scrolling. The solo
timeout flow was verified on the Pixel: after the 20-second offer, selecting three seats
and pressing Play with computers opens a local game with You and two Computer seats.
Physical-device checks of partial-group choices still require relay deployment;
dark-mode inspection remains pending.
The actual local Workers runtime smoke test covers full 2/3/4-player groups, host readiness,
the 20-second alarm, partial-group agreement and keeping the group together while searching.
The online menu entry now keeps its description above the action buttons so Quick Match
is reachable; it previously pushed the buttons outside the row.

Physical Pixel XL: inspected the online entry, four-player setup and friendly error screen;
they fit without scrolling to the actions. Dark-mode device inspection remains pending:
this phone reports `mNightModeLocked=true` and refuses a night-mode override. The theme was
left at its original light setting. Partial-group offer and in-game cross-phone checks remain
pending relay deployment; local runtime and targeted tests cover the queue behavior.
