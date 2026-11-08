# Store listings and privacy answers

The single source for what goes into Play Console and App Store Connect. Listing text is in
Indonesian (the default language on both stores) with an English translation below it. Describe only what the shipped build does:
right now that is congklak (pass-and-play, vs computer, nearby play over Bluetooth or Wi-Fi).
Update this file when a game or a data flow is added.

Privacy policy: https://krida.ardeman.com/privasi/ (Indonesian, source `web/src/pages/privasi.astro`) and https://krida.ardeman.com/en/privacy/ (English, `web/src/pages/en/privacy.astro`).
Contact email: mail@ardeman.com.

## Listing text

**Name** (both stores, ≤ 30): Krida: Permainan Tradisional

**Play short description** (≤ 80):

> Congklak 3D: lawan komputer atau main bareng teman di dekatmu, tanpa internet.

**App Store subtitle** (≤ 30): Congklak 3D, main bareng

**App Store keywords** (≤ 100, comma-separated, no spaces):
`congklak,dakon,dakonan,board game,permainan tradisional,offline,bluetooth,main bareng,indonesia`

**App Store promotional text** (≤ 170):

> Congklak klasik dalam gaya voxel 3D. Lawan komputer, gantian di satu HP, atau main bareng teman lewat Bluetooth, Android dan iPhone bisa satu meja.

**Full description** (Play ≤ 4000, App Store description):

> Krida menghadirkan permainan tradisional Nusantara dalam gaya voxel 3D yang hangat. Yang pertama: congklak.
>
> Ambil biji dari lubangmu, tabur satu per satu, kumpulkan sebanyak mungkin di lumbung. Sudah lewat lumbungmu dan mendarat di lubang kosong milikmu? Tembak! Biji di seberangnya jadi milikmu.
>
> CARA MAIN
> • Lawan komputer: pilih Mudah, Sedang, atau Sulit.
> • Gantian di satu HP atau tablet, cocok untuk main berdua.
> • Main bareng teman di dekatmu lewat Bluetooth atau Wi-Fi yang sama, tanpa internet. Android dan iPhone bisa main bareng.
>
> KENAPA KRIDA
> • Papan voxel 3D dengan animasi biji yang ditabur satu per satu.
> • Suara biji, sorakan "Tembak!", dan musik gamelan, masing-masing bisa dimatikan.
> • Petunjuk langkah dari komputer level Sulit saat kamu bingung.
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

> 3D congklak: play the computer or friends nearby, no internet needed.

**App Store subtitle** (≤ 30): 3D congklak with friends

**App Store keywords** (≤ 100, comma-separated, no spaces):
`congklak,mancala,dakon,board game,traditional,offline,bluetooth,local multiplayer,indonesia`

**App Store promotional text** (≤ 170):

> Classic congklak in 3D voxel style. Play the computer, take turns on one phone, or play friends over Bluetooth, with Android and iPhone at the same table.

**Full description** (Play ≤ 4000, App Store description):

> Krida brings traditional games from across Indonesia to life in warm 3D voxel style. The first one: congklak, the Indonesian mancala.
>
> Pick up the seeds from one of your holes, sow them one by one, and gather as many as you can in your store. Passed your store and landed in an empty hole on your side? Shoot! The seeds in the hole opposite are yours.
>
> HOW TO PLAY
> • Play the computer: choose Easy, Medium or Hard.
> • Take turns on one phone or tablet, perfect for two players.
> • Play friends nearby over Bluetooth or the same Wi-Fi, no internet needed. Android and iPhone can play together.
>
> WHY KRIDA
> • A 3D voxel board with seeds sown one at a time.
> • Seed sounds, a "Shoot!" call and gamelan music, each of which can be turned off.
> • Move hints from the Hard computer when you're stuck.
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
| Content rating (IARC) | Category Game. Violence, sexuality, language, controlled substances, gambling: No. Users interact or exchange content: No (nearby play only sends game moves, no chat). Shares location with other users: No. Digital purchases: Yes |
| Target audience | 13–15, 16–17, 18+ (no age group under 13, so the app stays out of the Families program) |
| Government apps / Financial features / Health | No / none / none |
| Advertising ID | Yes, used for Advertising or marketing (the AdMob SDK adds the `AD_ID` permission) |

`ACCESS_FINE_LOCATION` is declared only up to API 30, where Android needs it for BLE scanning.
Play's precise-location declaration is required from 2027-01-27; if the Console asks, the use is
"finding nearby devices for local multiplayer", and the app never reads location.

### Data safety

General questions:

- Does the app collect or share any of the required user data types? **Yes** (through the AdMob SDK).
- Is all user data encrypted in transit? **Yes**.
- Do you provide a way for users to request that their data is deleted? **No**. There are no
  accounts; uninstalling removes local data, and ad data is handled by Google.

Data types (all collected by the Google Mobile Ads SDK; the app itself sends nothing to us):

| Data type | Collected | Shared | Optional? | Purposes |
|---|---|---|---|---|
| Location → Approximate location (from IP) | Yes | Yes | Required | Advertising or marketing; Analytics; Fraud prevention, security, and compliance |
| App activity → App interactions | Yes | Yes | Required | Advertising or marketing; Analytics |
| App info and performance → Crash logs, Diagnostics | Yes | Yes | Required | Analytics; Fraud prevention, security, and compliance |
| Device or other IDs | Yes | Yes | Required | Advertising or marketing; Analytics; Fraud prevention, security, and compliance |

Not collected: personal info, financial info (Play Billing handles payment; the app keeps only
an on-device "ads removed" flag), messages, contacts, photos, files, health, web history,
precise location. Game data sent over Bluetooth or Wi-Fi goes device to device and never leaves
the players' devices, so it is not "collected".

Before submitting, check the table against Google's current
[Google Mobile Ads SDK data disclosure](https://developers.google.com/admob/android/privacy/play-data-disclosure);
it is marked conservatively (everything "shared") and Google's page is the authority.

## App Store Connect: App Privacy

Data used to track you: **none** (the app shows no App Tracking Transparency prompt, so AdMob
gets no IDFA). Data not linked to you, collected through AdMob:

| Data | Purposes |
|---|---|
| Location → Coarse Location | Third-Party Advertising, Analytics |
| Identifiers → Device ID | Third-Party Advertising, Analytics |
| Usage Data → Product Interaction, Advertising Data | Third-Party Advertising, Analytics |
| Diagnostics → Crash Data, Performance Data | Analytics |

Purchases are handled by the App Store and not collected by the app. Check against Google's
[iOS data disclosure](https://developers.google.com/admob/ios/privacy/data-disclosure) before
submitting.

Age rating questionnaire: all content categories None; Unrestricted web access No; Gambling No;
Contests No. Result: 4+ (the 13+ target is a Play audience setting, not an Apple rating).
