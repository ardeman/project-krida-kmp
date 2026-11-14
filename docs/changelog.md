# Changelog

Public release notes. Keep these three places in sync for each upload:

1. This file (agent/source of truth).
2. The web pages [`/catatan/`](https://krida.ardeman.com/catatan/) and
   [`/en/changelog/`](https://krida.ardeman.com/en/changelog/) under `web/src/pages/`.
3. Store “What’s New” / release notes — paste the current version’s id / en-US blocks from
   [store.md](store.md) into Play Console and App Store Connect.

Bump Android `versionCode` and iOS `CURRENT_PROJECT_VERSION` for every upload; keep
marketing versions aligned on both stores.

## 1.0.3 (build 4) — 2026-11-14

### id

- Iklan penuh layar setiap selesai main (bukan selang-seling).
- Tombol Hapus iklan sebelum iklan interstitial / rewarded.
- Hapus iklan tetap tersedia untuk penguji closed testing.

### en-US

- Full-screen ad after every finished game (not every other).
- Remove ads offered before interstitial and rewarded ads.
- Remove ads stays available for closed testers.

## 1.0.2 (build 3) — 2026-11-14

### id

- Suara pengumuman lebih ceria (gaya arcade).
- Zoom kamera dalam permainan dihapus (ludo, ular tangga, gaple).
- Perang kapal: jeda lebih jelas antara miss / kena / giliran, terutama lawan komputer.
- Iklan Android siap rilis Play (UMP + unit produksi).

### en-US

- Cheerful arcade-style announcement voices.
- Removed in-game camera zoom (ludo, ular tangga, gaple).
- Battleship: clearer gaps between miss / hit / turn, especially vs computer.
- Android ads ready for Play release (UMP + production units).

## 1.0.1 (build 2) — 2026-11-14

### id

- Perbaikan bahasa Indonesia di Android (pemilih bahasa tidak lagi tertahan di Inggris).
- Indikator pemain perang kapal disamakan dengan ular tangga / ludo.
- Gaple: tombol Menu di kartu antar-ronde.
- Congklak: kamera tetap pada seluruh papan saat menabur.
- Nama Inggris perang kapal: Battleship.

### en-US

- Fixed Indonesian language on Android (the in-app picker no longer stuck on English).
- Perang kapal player tiles match ular tangga / ludo.
- Gaple: Menu on the between-rounds card.
- Congklak: camera stays on the whole board while sowing.
- English name for perang kapal: Battleship.

## 1.0.0 (build 1) — 2026-11

### id

- Rilis pertama di App Store dan Google Play (uji tertutup).
- Lima permainan: congklak, ular tangga, ludo, perang kapal, dan gaple.
- Main gantian, lawan komputer, nearby (Bluetooth / Wi‑Fi), dan online.

### en-US

- First release on the App Store and Google Play (closed testing).
- Five games: congklak, ular tangga, ludo, perang kapal, and gaple.
- Pass-and-play, computer, nearby (Bluetooth / Wi‑Fi), and online.
