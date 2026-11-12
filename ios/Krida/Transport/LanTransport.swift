// Offline LAN / hotspot (README multiplayer roadmap 6). Lives under Nearby with a
// Bluetooth | Wi‑Fi control (docs/decisions.md).
//
// Host:  LanHostTransport — NWListener + Bonjour `_krida._tcp`
// Guest: LanGuestTransport — NWBrowser + NWConnection
// Wire:  4-byte big-endian length + Codec bytes (LanFraming); no BLE framing.
// Android: androidApp/.../lan/LanHostTransport.kt / LanGuestTransport.kt
