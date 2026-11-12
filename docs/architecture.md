# Architecture

## Goals

- Smooth 3D voxel rendering on mid-range tablets (e.g. Redmi Pad class) and older iPhones.
- Small install: target **< 15 MB** download on both stores for the first release.
- iOS ↔ Android multiplayer online and offline, 1–4 players.

## Layers

```
┌──────────────────────────────┐   ┌──────────────────────────────┐
│ Android (Kotlin)             │   │ iOS (Swift)                  │
│  UI: Jetpack Compose         │   │  UI: SwiftUI                 │
│  3D: Filament                │   │  3D: RealityKit (built in)   │
│  BLE: BluetoothGattServer /  │   │  BLE: CBPeripheralManager /  │
│       BluetoothLeScanner     │   │       CBCentralManager       │
│  LAN: NsdManager + sockets   │   │  LAN: Network.framework      │
└──────────────┬───────────────┘   └──────────────┬───────────────┘
               │  implements Transport             │
┌──────────────┴───────────────────────────────────┴──────────────┐
│ shared/ (Kotlin Multiplatform)                                  │
│  protocol: Message, Codec, Chunker (BLE framing), constants     │
│  session:  HostSession (authoritative), GuestSession            │
│  games:    GameRules<State, Move> per game (congklak, ludo …)   │
└─────────────────────────────────────────────────────────────────┘
```

## Why native instead of a cross-platform engine

| Option | Typical size cost | Notes |
|---|---|---|
| **Native (chosen)** | Android: Filament ≈ 2–4 MB per ABI (AAB delivers one ABI). iOS: RealityKit 0 MB, Kotlin/Native shared framework ≈ 1–3 MB | Best performance, smallest size, full radio access |
| Godot 4 | ≈ 25–40 MB runtime | Easy 3D, but big and radios need plugins anyway |
| Unity | ≈ 20–40 MB+ | Heaviest |
| WebView (Capacitor + Three.js) | small app, slower WebGL on mid tablets | JS ↔ native bridge for every BLE packet |

Voxel art is low-poly and flat-shaded, so it does not need a heavy engine — a thin
renderer per platform is enough.

## Size rules

- Android: ship an **App Bundle** (per-ABI split), R8 minify + resource shrink on release.
- Voxel models as **.glb** (meshes merged per piece, vertex colors, no textures).
- Audio as short **.ogg** (Android) / **.m4a** (iOS); music streamed or downloaded later.
- Games beyond the launch set can be added as on-demand asset packs.

## Authority model

One device is the **host**. It owns the true game state, validates every move with
`GameRules`, and broadcasts the result. Guests only send **intents**. Dice rolls happen
on the host. The host may be an Android or an iOS device — both run the same Kotlin rules.

## Transports

`Transport` is a plain callback interface (no coroutines) so Swift can implement it
directly. Each platform provides:

| Transport | Android | iOS |
|---|---|---|
| Local (pass-and-play) | shared `LocalTransport` | shared `LocalTransport` |
| BLE host | `BleHostTransport.kt` | `BleHostTransport.swift` |
| BLE guest | `BleGuestTransport.kt` | `BleGuestTransport.swift` |
| LAN | `LanHostTransport.kt` / `LanGuestTransport.kt` | `LanHostTransport.swift` / `LanGuestTransport.swift` |
| Online | `OnlineHostTransport` / `OnlineGuestTransport` | same |
