# Matchmaking alignment proposal

Approved by the owner, including the partial-group computer-seat choice. Android and relay
implementation complete; relay deployment and iOS parity remain pending.

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
- After 20 seconds, offer Back and Play the computer. Connection failure offers Back and
  Try again with a translated, friendly message.
- All actions fit without scrolling. Back in the header and the system back action agree
  with Cancel/Back in the card.

## Implementation accompanying the visual change

Retry must start a fresh search. Play the computer must start the selected game's local bot
mode. Search state must survive recomposition; timers and matchmaking clients must stop on
exit, failure or a successful match. Raw server errors must not appear in the UI.

Follow the agreed platform order: Android after mockup approval, iOS in the following session.
Build each changed platform and inspect light/dark on a physical device when available.

## Follow-up handoff

- iOS: expose and reuse its existing `NearbyFrame`/card styling, adopt the group JSON events
  in `QuickMatchClient.swift`, use a cancellable countdown, and add EN/ID catalog entries.
- Wire native host readiness before releasing guests, direct guest joining, unanimous
  computer-seat agreement and local solo fallback, matching Android.
- On iOS, match Android's quick-match lobby restrictions: wait for all expected humans,
  add only agreed bots and disable Start until the selected total is present.
- The Android offer card adds a second button row for Keep searching, with compact spacing
  to fit the landscape height. It shows real counts and the requirement for everyone's
  agreement; the original visual above predates that approved flow addition.
- Group search is opt-in (`?flow=group`); deployment must include the `v3` Durable Object
  migration in `relay/wrangler.toml`. Existing clients stay on the old matchmaker.
- Run `cd relay && npm run test:match && npm run check`, then the Android build in AGENTS.md.
- Verify a four-seat search with two humans, consent from each, one cancellation, continuing
  the search, and a successful online game with two computers on physical phones after
  deploying the relay. Until deployed, the new app's search will show the friendly error.

## Validation

Android `assembleDebug`, relay TypeScript checks, and 11 targeted group-search tests pass.
The actual local Workers runtime smoke test covers full 2/3/4-player groups, host readiness,
the 20-second alarm, partial-group agreement and keeping the group together while searching.
The online menu entry now keeps its description above the action buttons so Quick Match
is reachable; it previously pushed the buttons outside the row.

Physical Pixel XL: inspected the online entry, four-player setup and friendly error screen;
they fit without scrolling to the actions. Dark-mode device inspection remains pending:
this phone reports `mNightModeLocked=true` and refuses a night-mode override. The theme was
left at its original light setting. Partial-group offer and in-game cross-phone checks remain
pending relay deployment; local runtime and targeted tests cover the queue behavior.
