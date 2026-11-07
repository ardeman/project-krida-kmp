# Brand

Every brand asset is generated from one geometry by `scripts/brand.swift`. Don't edit the
outputs by hand: change the script and run it again from the repo root:

```sh
swift scripts/brand.swift path/to/Baloo2[wght].ttf
```

The font is Baloo 2 (OFL, `github.com/google/fonts/tree/main/ofl/baloo2`). It is only used to
outline the "rida" letters into paths, so no font ships in the apps or on the web.

## Marks

- **App icon (`docs/brand/icon.svg`):** a cream isometric die on the accent colour. The top
  face has four pips in the four player colours (1–4 players). The left face shows the voxel
  K from the wordmark. The right face has two wood pips, as on a real die.
- **Wordmark (`docs/brand/wordmark.svg`, `wordmark-dark.svg`):** a K stacked from five rows of
  wooden blocks, followed by "rida" in Baloo 2 ExtraBold. The block K is exactly the cap height
  of the letters. Only the "rida" letters change colour between light and dark mode.
- Use the die alone where space is square or tiny (icons, favicon, avatars). Use the wordmark
  in headers. Don't recolour the die, and don't set "Krida" in plain text where the wordmark fits.

## Palette

| Token | Light | Dark | Use |
|---|---|---|---|
| Aksen | `#C9783A` | `#E39A5C` | Icon background, links, primary actions |
| Krem | `#FFF7EC` | — | Light background, die top |
| Malam | `#1B1612` | — | Dark background |
| Tinta | `#2B211A` | `#F6EADC` | Text, wordmark letters |
| Kayu terang / Kayu / Kayu gelap | `#E3A66B` / `#B8743F` / `#8F552A` | same | Wood: boards, voxel K |

Player colours, seat 1–4: Merah `#D6453D`, Biru `#2F6FD1`, Hijau `#3E9A4F`, Kuning `#F0B429`.

## Where the outputs live

| Platform | Files |
|---|---|
| iOS | `ios/Krida/Assets.xcassets`: `AppIcon` (default, dark and tinted 1024 px), `AccentColor`, `Wordmark` (vector SVG, light and dark) |
| Android | `res/mipmap-anydpi-v26/ic_launcher*.xml` (adaptive and themed icon), `res/drawable/ic_launcher_*.xml`, `res/drawable/krida_wordmark.xml`, `res/values*/brand_colors.xml` |
| Web | `web/public/favicon.svg`, `web/public/apple-touch-icon.png`, `web/src/components/Wordmark.astro` |
