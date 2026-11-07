// TODO: LAN / hotspot transport (second offline mode).
//
// Host:  NWListener with service NWListener.Service(name: "Krida-<ROOM>", type: "_krida._tcp").
// Guest: NWBrowser(for: .bonjour(type: "_krida._tcp", domain: nil)), pick the result whose
//        name matches the room code, open an NWConnection.
// Wire:  4-byte big-endian length prefix + Codec bytes (no BLE framing needed).
//
// Implement the Shared `Transport` protocol like BleHostTransport/BleGuestTransport and keep
// every callback on the main queue. Android counterpart: androidApp/.../lan/LanTransport.kt.
// NSLocalNetworkUsageDescription and NSBonjourServices are already in project.yml.
