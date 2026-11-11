# Store listings and privacy answers

The single source for what goes into Play Console and App Store Connect. Listing text is in
Indonesian (the default language on both stores) with an English translation below it. Describe only what the shipped build does:
congklak and ular tangga (pass-and-play, vs computer, nearby over Bluetooth or Wi-Fi, online
through the relay). Update this file when a game or a data flow is added.

Privacy policy: https://krida.ardeman.com/privasi/ (Indonesian, source `web/src/pages/privasi.astro`) and https://krida.ardeman.com/en/privacy/ (English, `web/src/pages/en/privacy.astro`).
Contact email: mail@ardeman.com.

## Listing text

**Name** (both stores, ≤ 30): Krida: Permainan Tradisional

**Play short description** (≤ 80):

> Congklak & ular tangga 3D: lawan komputer, teman dekat, atau main online.

**App Store subtitle** (≤ 30): Congklak 3D, main bareng

**App Store keywords** (≤ 100, comma-separated, no spaces):
`congklak,dakon,ular tangga,board game,permainan tradisional,bluetooth,online,main bareng,indonesia`

**App Store promotional text** (≤ 170):

> Congklak dan ular tangga dalam gaya voxel 3D. Lawan komputer, gantian di satu HP, main bareng lewat Bluetooth, atau online lewat internet — Android dan iPhone bisa satu meja.

**Full description** (Play ≤ 4000, App Store description):

> Krida menghadirkan permainan tradisional Nusantara dalam gaya voxel 3D yang hangat. Sekarang ada congklak dan ular tangga.
>
> Congklak: ambil biji dari lubangmu, tabur satu per satu, kumpulkan sebanyak mungkin di lumbung. Sudah lewat lumbungmu dan mendarat di lubang kosong milikmu? Tembak! Biji di seberangnya jadi milikmu.
>
> Ular tangga: lempar dadu, naik tangga, hindari ular, sampai 100.
>
> CARA MAIN
> • Lawan komputer: pilih tingkat kesulitan (congklak) atau isi kursi dengan komputer (ular tangga).
> • Gantian di satu HP atau tablet.
> • Main bareng teman di dekatmu lewat Bluetooth atau Wi-Fi yang sama, tanpa internet. Android dan iPhone bisa main bareng.
> • Main online lewat internet: buat atau gabung room, lobi publik, atau quick match.
>
> KENAPA KRIDA
> • Papan voxel 3D dengan animasi yang jelas.
> • Suara, sorakan, dan musik gamelan, masing-masing bisa dimatikan.
> • Petunjuk langkah dari komputer level Sulit saat kamu bingung (congklak).
> • Ringan dan hemat ruang.
>
> Permainan tradisional lain menyusul.
>
> Krida gratis dengan iklan. Beli "Hapus iklan" sekali untuk menghilangkan semua iklan dan memakai petunjuk tanpa menonton iklan.

### English (en-US)

Play Console: Grow → Store presence → Main store listing → Manage translations → Add your own
translation text → English (United States). App Store Connect: add the English (U.S.)
localization on App Information (name, subtitle, privacy policy URL) and on the version page
(promotional text, description, keywords). Use the English privacy policy URL there:
https://krida.ardeman.com/en/privacy/.

**Name** (≤ 30): Krida: Traditional Games

**Play short description** (≤ 80):

> 3D congklak & snakes and ladders: computer, nearby, or online.

**App Store subtitle** (≤ 30): 3D congklak with friends

**App Store keywords** (≤ 100, comma-separated, no spaces):
`congklak,mancala,snakes and ladders,board game,traditional,bluetooth,online,multiplayer,indonesia`

**App Store promotional text** (≤ 170):

> Classic congklak and snakes and ladders in 3D voxel style. Play the computer, take turns on one phone, play friends over Bluetooth, or online — Android and iPhone at the same table.

**Full description** (Play ≤ 4000, App Store description):

> Krida brings traditional games from across Indonesia to life in warm 3D voxel style. Now shipping: congklak (Indonesian mancala) and ular tangga (snakes and ladders).
>
> Congklak: pick up the seeds from one of your holes, sow them one by one, and gather as many as you can in your store. Passed your store and landed in an empty hole on your side? Shoot! The seeds in the hole opposite are yours.
>
> Ular tangga: roll the die, climb ladders, avoid snakes, reach 100.
>
> HOW TO PLAY
> • Play the computer: choose a difficulty (congklak) or fill seats with computers (ular tangga).
> • Take turns on one phone or tablet.
> • Play friends nearby over Bluetooth or the same Wi-Fi, no internet needed. Android and iPhone can play together.
> • Play online over the internet: create or join a room, browse public lobbies, or quick match.
>
> WHY KRIDA
> • A 3D voxel board with clear animation.
> • Seed sounds, calls and gamelan music, each of which can be turned off.
> • Move hints from the Hard computer when you're stuck (congklak).
> • Play in English or Indonesian.
> • Light and small to download.
>
> More traditional games are on the way.
>
> Krida is free with ads. Buy "Remove ads" once to remove all ads and get hints without watching an ad.

**In-app purchase** `hapus_iklan`, English (U.S.) localization on both stores: display name
"Remove ads", description "Removes all ads and unlocks hints without ads."

**Category:** Play: Games → Board. App Store: Games (primary), Board (sub-category); secondary
category Family is not used (the app is not in Families / Kids).

**Graphics still to make:** Play icon 512×512 PNG, feature graphic 1024×500, at least two phone
screenshots (and tablet ones for the tablet listing); App Store 6.9" iPhone and 13" iPad screenshots.

## Play Console: App content

| Task | Answer |
|---|---|
| Privacy policy | https://krida.ardeman.com/privasi/ |
| Sign-in details (app access) | All functionality is available without special access |
| Ads | Yes, the app contains ads |
| Content rating (IARC) | Category Game. Violence, sexuality, language, controlled substances, gambling: No. Users interact or exchange content: No (nearby and online only send game moves and seat names, no free-text chat). Shares location with other users: No. Digital purchases: Yes |
| Target audience | 13–15, 16–17, 18+ (no age group under 13, so the app stays out of the Families program) |
| Government apps / Financial features / Health | No / none / none |
| Advertising ID | Yes, used for Advertising or marketing (the AdMob SDK adds the `AD_ID` permission) |

`ACCESS_FINE_LOCATION` is declared only up to API 30, where Android needs it for BLE scanning.
Play's precise-location declaration is required from 2027-01-27; if the Console asks, the use is
"finding nearby devices for local multiplayer", and the app never reads location.

### Data safety

General questions:

- Does the app collect or share any of the required user data types? **Yes** (AdMob SDK, and
  the online relay while a room is open).
- Is all user data encrypted in transit? **Yes** (HTTPS / WSS to the relay; AdMob over TLS;
  nearby play stays on the local link).
- Do you provide a way for users to request that their data is deleted? **Yes**, once
  accounts (online step 10a) ship: "Delete account" on the in-app account screen removes the
  account, name and sessions at once. Account deletion URL for the Play form:
  `https://krida.ardeman.com/en/privacy#account`. Without an account, uninstalling removes
  local data (including any rejoin token), ad data is handled by Google, and relay room
  state is deleted when the room ends.

Data types collected by the **Google Mobile Ads SDK** (unchanged; the app itself sends nothing
to us for ads):

| Data type | Collected | Shared | Optional? | Purposes |
|---|---|---|---|---|
| Location → Approximate location (from IP) | Yes | Yes | Required | Advertising or marketing; Analytics; Fraud prevention, security, and compliance |
| App activity → App interactions | Yes | Yes | Required | Advertising or marketing; Analytics |
| App info and performance → Crash logs, Diagnostics | Yes | Yes | Required | Analytics; Fraud prevention, security, and compliance |
| Device or other IDs | Yes | Yes | Required | Advertising or marketing; Analytics; Fraud prevention, security, and compliance |

Data types processed by the **online relay** (Cloudflare Workers at `krida-relay.ardeman.com`).
The app transmits these off-device only while an online room or matchmaking search is open.
Mark them as collected for **App functionality**. Cloudflare runs the relay on our behalf; do
**not** mark them Shared (they are not sold or used for advertising). Room state is deleted
when the room ends or after a short idle period — not a lasting player profile.

| Data type | Collected | Shared | Optional? | Purposes | Notes |
|---|---|---|---|---|---|
| Location → Approximate location (from IP) | Yes | No | Required | App functionality | The relay sees the phone's IP while the WebSocket is open |
| App activity → Other user-generated content | Yes | No | Required | App functionality | Seat names chosen in the lobby; no free-text chat |
| App activity → Other actions | Yes | No | Required | App functionality | Room codes, matchmaking, game moves and lobby status forwarded by the relay |

With an **optional account** (step 10a) add, as collected, not shared, **Optional**, for App
functionality and Account management: Personal info → Name (the display name from Apple or
Google) and Personal info → User IDs (the provider's account id). Stored in Cloudflare D1
until the player deletes the account. The friends list (step 10b: a friend code and which
accounts are friends) is kept with the account and deleted with it; it uses the same name and
user id, so it adds no new data type (agent's reading of the forms, not checked with a store).

Not collected by us: personal info other than the optional account's name and id (no email, phone), financial info (Play Billing handles
payment; the app keeps only an on-device "ads removed" flag), messages/chat, contacts, photos,
files, health, web history, precise location, device IDs for the relay. Game data sent over
Bluetooth or Wi-Fi for nearby play goes device to device and never leaves the players'
devices, so nearby play is not "collected".

Before submitting, check the AdMob rows against Google's current
[Google Mobile Ads SDK data disclosure](https://developers.google.com/admob/android/privacy/play-data-disclosure);
those rows are marked conservatively (everything "shared") and Google's page is the authority.

## App Store Connect: App Privacy

Data used to track you: **none** (the app shows no App Tracking Transparency prompt, so AdMob
gets no IDFA). Privacy Nutrition Labels:

**Data Not Linked to You** (no accounts):

| Data | Purposes | Source |
|---|---|---|
| Location → Coarse Location | Third-Party Advertising, Analytics | AdMob (IP) |
| Location → Coarse Location | App Functionality | Online relay (IP while connected) |
| Identifiers → Device ID | Third-Party Advertising, Analytics | AdMob |
| Usage Data → Product Interaction | Third-Party Advertising, Analytics | AdMob |
| Usage Data → Product Interaction | App Functionality | Online play (moves, lobby, matchmaking) |
| Usage Data → Other User Content | App Functionality | Seat names in online lobbies |
| Diagnostics → Crash Data, Performance Data | Analytics | AdMob |
| Usage Data → Advertising Data | Third-Party Advertising, Analytics | AdMob |

Once accounts ship, add under **Data Linked to You** (only for players who sign in):
Contact Info → Name and Identifiers → User ID, both for App Functionality. The app offers
in-app account deletion (App Review guideline 5.1.1(v)).

Purchases are handled by the App Store and not collected by the app. Check against Google's
[iOS data disclosure](https://developers.google.com/admob/ios/privacy/data-disclosure) before
submitting.

Age rating questionnaire: all content categories None; Unrestricted web access No; Gambling No;
Contests No. Result: 4+ (the 13+ target is a Play audience setting, not an Apple rating).
