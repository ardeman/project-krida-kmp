package com.ardeman.krida.lan

/*
 * Offline LAN / hotspot (README multiplayer roadmap 6). Lives under Nearby with a
 * Bluetooth | Wi‑Fi control (docs/decisions.md).
 *
 * Host:  [LanHostTransport] — ServerSocket + NsdManager.registerService
 * Guest: [LanGuestTransport] — NsdManager.discoverServices + Socket
 * Wire:  4-byte big-endian length + Codec bytes ([LanFraming]); no BLE framing.
 * iOS:   ios/Krida/Transport/LanTransport.swift
 */
