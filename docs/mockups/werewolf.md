# Werewolf UI mockup

Approved with the plan (2026-11-16). Open [the light and dark mockup](werewolf.svg).
Each panel is 874 × 402, the landscape phone design size. English is shown on the SVG;
implementation must include matching Indonesian copy (full rules below in both languages).

Social deduction. Game id `werewolf`. Host stays authoritative; each seat sees only its own
role (wolves also see fellow wolves) via `PrivateStateRules`.

## Modes

- **This phone:** you against computers (5–8 seats, bot difficulty). Practice / quick-match
  “Play with computers”.
- **Nearby:** Wi‑Fi / LAN only (Bluetooth control hidden). 5–8 seats, person / computer.
- **Online:** same. Quick match and public rooms for 5–8.
- **No BLE.**

## Home

- Add **Werewolf** to the left game switch after Gaple. Card face: deep night `#1A2332` with a
  wolf silhouette and a small moon; tagline EN/ID below.
- Play card: **Play on** (This phone | Nearby | Online). Seat chips 5–8. Hint line about
  emotes then vote. Nearby locks to Wi‑Fi.

## Game screen

- **Left:** back, title, seat tiles (two a row, scroll if needed for 8) with alive/dead,
  Kepala desa badge, Account avatar when signed in.
- **Centre:** phase panel (role reveal, night actions, dawn deaths, election, discuss, vote,
  result). Soft village / night atmosphere (code-drawn, no large assets).
- **Right:** sound, rules help, emote (nearby/online), phase countdown / autopilot.

## Timers (v1)

| Phase | Duration |
|-------|----------|
| Night | 45 s |
| Hunter shot / Sheriff successor | 20 s |
| Sheriff election | 20 s |
| Discussion | 60 s |
| Lynch vote | 20 s |

At 0 the host auto-resolves (skip unused night actions, abstain, random successor / election
tie-break as in rules).

## Rules (English)

### Setup

- **Players:** 5–8 seats (people and/or computers).
- **Bots:** “+ Computer” easy / normal / hard. Mid-game disconnect → bot after the usual wait;
  Rejoin can reclaim.
- **Mulai** when every seat is filled, total 5–8. Host may lower target (min 5). Quick match:
  after ~20 s fill empties with computers or start with humans found if count is 5–8.

### Role counts

| Seats | Werewolf | Seer | Doctor | Hunter | Witch | Villager |
|------:|---------:|-----:|-------:|-------:|------:|---------:|
| 5 | 1 | 1 | 1 | 0 | 0 | 2 |
| 6 | 1 | 1 | 1 | 1 | 0 | 2 |
| 7 | 2 | 1 | 1 | 1 | 0 | 2 |
| 8 | 2 | 1 | 1 | 1 | 1 | 2 |

### Secret roles

| Role | Side | Ability |
|------|------|---------|
| **Werewolf** | Wolves | Night: living wolves pick one victim (majority; tie → random). Wolves see each other. |
| **Villager** | Town | No night power. |
| **Seer** | Town | Night: peek one living seat; learn **exact role**. |
| **Doctor** | Town | Night: protect one living seat (incl. self) from the wolf kill. |
| **Hunter** | Town | On death: shoot one living seat. |
| **Witch** | Town | Once each/game: heal that night’s wolf target, and/or poison one living seat. Sees wolf target before heal. |

### Sheriff (Kepala desa)

Public badge, not a dealt card. Election after first dawn and when vacant. Lynch vote ×2.
On death names a living successor (timeout → random).

### Phase loop

Deal → Night → Dawn (deaths; Hunter shot if needed) → Day (Sheriff election if vacant →
discuss → lynch vote) → repeat until Town (all wolves dead) or Wolves (wolves ≥ others).

### UI copy (English)

- Tagline: Find the wolves before they outnumber the village.
- Hint: No chat — use emotes, then vote.
- Lobby: Add people or computers. / Waiting for N more…
- Actions: Your role · Pick a victim · Peek at someone · Protect someone · Heal the victim ·
  Poison someone · Skip · Shoot someone · Vote to lynch · Abstain · Vote for Sheriff ·
  Name a successor · Waiting for others…
- Outcomes: Night · Dawn · Day · Discussion · Vote · Election · You died · Eliminated ·
  Lynched · Town wins · Wolves win · Peaceful night · Protected · Healed · Poisoned

---

## Aturan (Bahasa Indonesia)

### Persiapan

- **Pemain:** 5–8 kursi (orang dan/atau komputer).
- **Bot:** “+ Komputer” mudah / sedang / sulit. Putus di tengah → bot setelah masa tunggu;
  Gabung lagi bisa merebut kursi.
- **Mulai** jika semua kursi terisi, total 5–8. Target boleh diturunkan (min 5). Cari lawan:
  setelah ~20 d isi dengan komputer atau mulai jika manusia 5–8.

### Jumlah peran

| Kursi | Serigala | Peramal | Dokter | Pemburu | Penyihir | Penduduk |
|------:|---------:|--------:|-------:|--------:|---------:|---------:|
| 5 | 1 | 1 | 1 | 0 | 0 | 2 |
| 6 | 1 | 1 | 1 | 1 | 0 | 2 |
| 7 | 2 | 1 | 1 | 1 | 0 | 2 |
| 8 | 2 | 1 | 1 | 1 | 1 | 2 |

### Peran rahasia

| Peran | Sisi | Kemampuan |
|------|------|-----------|
| **Serigala** | Serigala | Malam: pilih satu korban (mayoritas; seri → acak). Saling melihat. |
| **Penduduk** | Desa | Tidak ada aksi malam. |
| **Peramal** | Desa | Malam: intip satu kursi; tahu **peran pasti**. |
| **Dokter** | Desa | Malam: lindungi satu kursi (termasuk diri) dari serigala. |
| **Pemburu** | Desa | Saat mati: tembak satu kursi hidup. |
| **Penyihir** | Desa | Masing-masing sekali/permainan: sembuhkan korban serigala malam itu, dan/atau racuni satu kursi. Melihat korban sebelum sembuh. |

### Kepala desa

Lencana publik. Pemilihan setelah fajar pertama dan saat kosong. Suara hukuman ×2.
Saat mati menunjuk pengganti (waktu habis → acak).

### Alur

Bagian → Malam → Fajar (gugur; tembakan Pemburu jika perlu) → Siang (pemilihan Kepala desa
jika kosong → diskusi → voting hukuman) → ulang sampai Desa atau Serigala menang.

### Teks antarmuka (Indonesia)

- Tagline: Temukan serigala sebelum mereka menguasai desa.
- Petunjuk: Tanpa obrolan — pakai reaksi, lalu voting.
- Lobi: Tambah orang atau komputer. / Menunggu N lagi…
- Aksi: Peranmu · Pilih korban · Intip seseorang · Lindungi seseorang · Sembuhkan korban ·
  Racuni seseorang · Lewati · Tembak seseorang · Pilih untuk dihukum · Golput · Pilih Kepala
  desa · Tunjuk pengganti · Menunggu yang lain…
- Hasil: Malam · Fajar · Siang · Diskusi · Voting · Pemilihan · Kamu gugur · Gugur · Dihukum ·
  Desa menang · Serigala menang · Malam tenang · Dilindungi · Disembuhkan · Diracun
