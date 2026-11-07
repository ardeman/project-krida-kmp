# Brand

Every brand asset is generated from one geometry by `scripts/brand.swift`. Don't edit the
outputs by hand: change the script and run it again from the repo root:

```sh
swift scripts/brand.swift path/to/Baloo2[wght].ttf
```

The font is Baloo 2 (OFL, `github.com/google/fonts/tree/main/ofl/baloo2`). It is only used to
outline the "Krida" letters into paths, so no font ships in the apps or on the web.

## Marks

- **App icon (`docs/brand/icon.svg`):** a cream isometric die on the accent colour. The top
  face has four pips in the four player colours: play together, nearby over BLE or online, up
  to 4 players. The right face has one big wood pip: play alone against the computer. The left
  face shows a voxel K, for Krida.
- **Wordmark (`docs/brand/wordmark.svg`, `wordmark-dark.svg`):** the same die as the app icon,
  followed by "Krida" in Baloo 2 ExtraBold. The die is a little taller than the capitals and
  centred on them. Only the letters change colour between light and dark mode, so the die is
  identical everywhere: icon, favicon and header.
- Use the die alone where space is square or tiny (icons, favicon, avatars). Use the wordmark
  in headers. Don't recolour the die, and don't set "Krida" in plain text where the wordmark fits.

## Palette

| Token | Light | Dark | Use |
|---|---|---|---|
| Aksen | `#C9783A` | `#E39A5C` | Icon background, links, primary actions |
| Krem | `#FFF7EC` | — | Light background, die top |
| Malam | `#1B1612` | — | Dark background |
| Tinta | `#2B211A` | `#F6EADC` | Text, wordmark letters |
| Kayu terang / Kayu / Kayu gelap | `#E3A66B` / `#B8743F` / `#8F552A` | same | Wood: boards, the K and the single pip on the die use Kayu gelap, so they stand out from the accent background |

Player colours, seat 1–4: Merah `#D6453D`, Biru `#2F6FD1`, Hijau `#3E9A4F`, Kuning `#F0B429`.

## Where the outputs live

| Platform | Files |
|---|---|
| iOS | `ios/Krida/AppIcon.icon` (Icon Composer document: die and marks as two SVG layers on the accent fill; the system renders Liquid Glass, dark, tinted and the fallback for older iOS). Preview it with Icon Composer or `ictool`. `ios/Krida/Assets.xcassets`: `AccentColor`, `Wordmark` (vector SVG, light and dark) |
| Android | `res/mipmap-anydpi-v26/ic_launcher*.xml` (adaptive and themed icon), `res/drawable/ic_launcher_*.xml`, `res/drawable/krida_wordmark.xml`, `res/values*/brand_colors.xml` |
| Web | `web/public/favicon.svg`, `web/public/apple-touch-icon.png`, `web/src/components/Wordmark.astro` |
