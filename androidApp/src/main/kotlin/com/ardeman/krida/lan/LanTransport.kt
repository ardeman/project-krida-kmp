package com.ardeman.krida.lan

/*
 * TODO: LAN / hotspot transport (second offline mode).
 *
 * Host:  ServerSocket on a free port + NsdManager.registerService(type = Krida.BONJOUR_SERVICE_TYPE,
 *        name = RoomCode.advertisedName(roomCode)).
 * Guest: NsdManager.discoverServices(Krida.BONJOUR_SERVICE_TYPE), resolve the one whose name
 *        matches the room code, open a Socket.
 * Wire:  4-byte big-endian length prefix + Codec bytes (no BLE framing needed).
 *
 * Implement com.ardeman.krida.core.transport.Transport exactly like the BLE classes, posting
 * every callback to the main thread. iOS counterpart: ios/Krida/Transport/LanTransport.swift.
 */
