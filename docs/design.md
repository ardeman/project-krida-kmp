# UI design guide

How Krida's screens look and how to build a new one so it matches the rest. Read this before
touching any screen on Android (`androidApp/`) or iOS (`ios/`). Brand marks and the palette
source are in [brand.md](brand.md); this file covers the app UI built on top of them.

The rule behind everything here: **a new screen is assembled from the existing pieces, not
drawn from scratch.** Before writing a card, button, header or status view, find the one that
already exists (tables below) and reuse it. If two screens need the same thing, move it into
`ui/Theme.kt` / `Support/Theme.swift` instead of copying it.

## Platform parity

Android and iOS show the same screen with the same layout, spacing, copy and states. When you
build or change a screen on one platform, do the other in the matching file (see
[structure.md](structure.md)) or note in the commit and roadmap that it is still open. Names
match across platforms: `RoomLobby`, `JoinScreen`, `ConnectingScreen` exist on both.

## Layout

- **Always landscape.** The whole app is inside `LandscapeStage`, which turns the layout on
  portrait or foldable screens. Design for a landscape phone first: **874 × 402 pt** on iOS
  (the phone design size); Android caps menus and room screens at **874 × 460 dp**.
- **Scale on iOS.** Every size in SwiftUI is multiplied by `s` (`@Environment(\.uiScale)`):
  `24 * s`, `Theme.font(16 * s)`. It is 1 on phones and up to 1.5 on iPad. Android scales
  through `LandscapeStage`'s density, so use plain `dp`/`sp`.
- **Insets.** Android: `.windowInsetsPadding(gameInsets)`. iOS: leave room for
  `@Environment(\.punchHole)` (a camera hole in one corner) in headers.
- **Two columns, not one long column.** A landscape phone has little height. Put cards side by
  side (`Row`/`HStack` with 14 spacing) rather than stacking them; a single centred card is
  fine for status screens.
- **No scrolling to reach the main action.** The primary button must be visible without
  scrolling on an 874 × 402 screen.

## Tokens

Use only `Theme.*`. Never hardcode a hex colour, `Color.White` background or a system font in a
screen. The exceptions already in the code (player colours, `okGreen`) live at the top of the
file that owns them.

| Token | Use |
|---|---|
| `backdropBrush` (Android) / `backdropGradient` (iOS) | Full-screen background of every non-game screen |
| `surface` | Cards |
| `surfaceSoft`, `highlight`, `raised` | Inner panels, selected rows, raised tiles |
| `ink` / `muted` | Main text / secondary text and icons |
| `accent` | Primary button fill, selected state, links |
| `line` | Card border (1.5) |
| `border` | Secondary button border when not accent-tinted, dashed empty seats |
| `chip` | Chips, code pills, the circle behind a status icon |
| `error` | Failure icon and its circle (`error` at 14 % alpha) |
| White on `accent` | Text on primary buttons and selected chips |

Text: `Theme.font(size, weight)` (Android adds the colour as third argument). Sizes in use:

| Role | Size / weight |
|---|---|
| Screen title (in the frame header) | 20 Black |
| Status card title | 22 Black |
| Card title | 18–20 Black |
| Body / detail | 14 Bold, `muted` |
| Small label above a control ("Pemain", "Tembak") | 13 ExtraBold, `muted` |
| Button | 16 (inside the button styles) |

## Shapes and spacing

| Thing | Value |
|---|---|
| Card | corner 20, `surface`, 1.5 `line` border, padding 24 horizontal × 22 vertical (status cards) or 20 |
| Button | height 44, corner 14 |
| Chip / segmented option / small tile | corner 12, height 44 |
| Code pill | corner 8, `chip`, monospace |
| Gap between cards | 14 |
| Gap inside a card | 12 |
| Gap between buttons in a row | 10 |
| Frame padding | 24 sides, 16 top, 18 bottom |
| Minimum touch target | 44 |

Android cards: `Modifier.card()` (in `nearby/NearbyScreens.kt`). iOS: `.background(Theme.surface,
in: RoundedRectangle(cornerRadius: 20 * s))` + `.overlay(... .stroke(Theme.line, lineWidth: 1.5))`.

## Components to reuse

| Need | Android (`ui/Theme.kt` unless noted) | iOS (`Support/Theme.swift` unless noted) |
|---|---|---|
| Main action | `PrimaryButton` | `.buttonStyle(PrimaryButtonStyle())` |
| Cancel / back / secondary | `SecondaryButton(tint = Theme.muted)` | `SecondaryButtonStyle(tint: Theme.muted)` |
| Round icon button (back, sound, close) | `CircleIconButton` | `CircleIconButton` |
| Screen header with back, title, place line | `NearbyFrame` (`nearby/NearbyScreens.kt`) | `NearbyFrame` (`Nearby/NearbyScreens.swift`) |
| Waiting / failure status | `ConnectingScreen` layout | `ConnectingScreen` layout |
| Game name | `gameTitle(game)` | `gameTitle` in `NearbyScreens.swift` |
| "Online · lewat internet" / "Teman dekat · Bluetooth" | `subtitle(online)` | `placeLine(online)` |
| Seat tiles, colour swatches, dashed empty seat | `NearbyScreens.kt` | `NearbyScreens.swift` |
| Loading | `LoadingIndicator` / `LoadingScreen` (`ui/Loading.kt`) | `LoadingIndicator` / `LoadingScreen` (`Support/Loading.swift`) |
| Tap sound | built into the buttons | `.tapSound(...)` (built into the button styles) |

`NearbyFrame` is internal on Android; iOS should expose its existing frame when adapting
quick match. Room-related screens reuse it rather than build their own header.

Every loading indicator uses the startup cube/K renderer and its 2.4-second turn, including
room connection and matchmaking. Use the compact `LoadingIndicator` in cards and the full
`LoadingScreen` with the wordmark for startup and board loading. Do not introduce system
spinners.

## Screen patterns

**Room / online screen** (lobby, join, quick match):

```
[‹]  Congklak                          ← NearbyFrame: CircleIconButton + gameTitle (20 Black)
     🌐 Online · lewat internet         ← place line (13 Bold, muted, globe or bluetooth icon)
┌───────────────┐  ┌───────────────┐
│ left card     │  │ right card    │   ← one or two cards, 14 apart, filling the height
└───────────────┘  └───────────────┘
```

**Status screen** (searching, connecting, not found, timed out, error): one centred card,
width 480, inside `NearbyFrame`, in this order:

1. The shared 64 cube/K `LoadingIndicator` while waiting, using the startup animation.
   After a failure, a 64 circle with `error` at 14 % and the error icon.
2. Title, 22 Black.
3. Detail, 14 Bold muted, from a string resource, never a raw error or exception text.
4. Optional info row (room code pill, seconds left).
5. Buttons: `SecondaryButton` (Cancel while waiting, Back after a failure) then
   `PrimaryButton` for the way forward (Try again, Reconnect, Play the computer), each at
   least 130 wide. Every button does something; no placeholder handlers.

The back button in the header and the system back gesture do the same as the Cancel/Back
button.

## Copy

- Every string in both languages (see `AGENTS.md`): Android `values/strings.xml` (English) and
  `values-in/strings.xml` (Indonesian); iOS `Localizable.xcstrings`, whose **keys are the
  Indonesian text** with explicit `en` and `id` values. A `Text("Play Online")` with an English
  key that isn't in the catalog shows English to Indonesian players.
- Same wording on both platforms; the Android English string and the iOS `en` value match.
- Reuse existing strings before adding new ones (`nearby_cancel`, `nearby_try_again`,
  `congklak_back` …).
- Tone: short, friendly, informal Indonesian ("kamu", not "Anda").

## Checklist before calling a screen done

- [ ] Built from the components above; no new card, button or header style.
- [ ] Same layout and states on Android and iOS (or the missing one is noted).
- [ ] Fits an 874 × 402 landscape phone without scrolling to the main action; checked in light
      and dark.
- [ ] iOS sizes use `* s`; colours and fonts only from `Theme`.
- [ ] All text in EN + ID; no raw error messages shown.
- [ ] Back button, system back and Cancel agree; every button works.
- [ ] Compose state is in `remember { }` (a bare `mutableStateOf` in a composable resets on
      every recomposition); timers and clients are stopped when the screen goes away.

## Known gaps

- **Quick match iOS (`PlayOnline/PlayOnlineScreens.swift`)** still needs the approved room
  header/status-card redesign and group-search flow. Android now reuses `NearbyFrame`, card,
  buttons and the menu's shared segmented picker, with translated messages, retry, cleanup,
  real player counts and unanimous computer-seat agreement. See
  [the implementation handoff](mockups/matchmaking.md).
- Android's game card is a carousel (`HorizontalPager`); iOS switches games without one.
