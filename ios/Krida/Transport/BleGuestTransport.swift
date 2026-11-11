import CoreBluetooth
import Foundation
import Shared

/// BLE guest: scans for a Krida host with the given room code (Android hosts put it in
/// service data, iOS hosts in the local name — or the readable room characteristic when
/// the name was dropped from a full advertising packet), connects, subscribes, writes frames.
final class BleGuestTransport: NSObject, Transport, CBCentralManagerDelegate, CBPeripheralDelegate {
    private let roomCode: String
    private var central: CBCentralManager?
    private var host: CBPeripheral?
    private var toHost: CBCharacteristic?
    private var pendingNotify: CBCharacteristic?
    private weak var listener: TransportListener?

    private var ready = false
    private var codeVerified = false
    private var mtu: Int32 = Chunker.companion.MIN_MTU
    private var outbox: [Data] = []
    private var writing = false
    private let chunker = Chunker()
    private let reassembler = Reassembler()
    /// Peripherals we connected to and rejected (wrong room).
    private var rejected: Set<UUID> = []

    init(roomCode: String) {
        self.roomCode = roomCode
        super.init()
    }

    func start() {
        rejected.removeAll()
        central = CBCentralManager(delegate: self, queue: .main)
    }

    // MARK: Transport

    func setListener(listener: TransportListener?) {
        self.listener = listener
    }

    func send(peer: String?, data: KotlinByteArray) {
        guard ready else { return }
        outbox += chunker.split(message: data, mtu: mtu).map { $0.toData() }
        pump()
    }

    func disconnect(peer: String) {}

    func close() {
        central?.stopScan()
        if let host { central?.cancelPeripheralConnection(host) }
        ready = false
        codeVerified = false
        pendingNotify = nil
        outbox.removeAll()
        rejected.removeAll()
        host = nil
        central = nil
    }

    // MARK: CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard central.state == .poweredOn, host == nil else { return }
        startScan()
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard host == nil, !rejected.contains(peripheral.identifier) else { return }
        switch roomOf(advertisementData) {
        case roomCode:
            connect(peripheral, verified: true)
        case nil:
            // Service UUID matched the scan filter but the name was dropped — verify via GATT.
            connect(peripheral, verified: false)
        default:
            break // another Krida room
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([KridaBle.service])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        reject(peripheral, rescan: true)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        let wasReady = ready
        let needsRescan = !codeVerified && host?.identifier == peripheral.identifier
        ready = false
        writing = false
        pendingNotify = nil
        outbox.removeAll()
        reassembler.clear(sender: KridaBle.hostPeer)
        host = nil
        if wasReady {
            listener?.onPeerDisconnected(peer: KridaBle.hostPeer)
        } else if needsRescan {
            startScan()
        }
    }

    // MARK: CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == KridaBle.service }) else {
            reject(peripheral, rescan: true)
            return
        }
        var uuids = [KridaBle.toHost, KridaBle.toGuest]
        if !codeVerified { uuids.append(KridaBle.room) }
        peripheral.discoverCharacteristics(uuids, for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        let characteristics = service.characteristics ?? []
        toHost = characteristics.first { $0.uuid == KridaBle.toHost }
        guard let notify = characteristics.first(where: { $0.uuid == KridaBle.toGuest }) else {
            reject(peripheral, rescan: true)
            return
        }
        if !codeVerified {
            if let room = characteristics.first(where: { $0.uuid == KridaBle.room }) {
                pendingNotify = notify
                peripheral.readValue(for: room)
                return
            }
            // Older host without the room characteristic: try the peripheral name.
            if RoomCode.shared.parse(text: peripheral.name ?? "") != roomCode {
                reject(peripheral, rescan: true)
                return
            }
            codeVerified = true
        }
        peripheral.setNotifyValue(true, for: notify)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if characteristic.uuid == KridaBle.room {
            if error != nil {
                reject(peripheral, rescan: true)
                return
            }
            let text = characteristic.value.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            guard RoomCode.shared.parse(text: text) == roomCode else {
                reject(peripheral, rescan: true)
                return
            }
            codeVerified = true
            guard let notify = pendingNotify else {
                reject(peripheral, rescan: true)
                return
            }
            pendingNotify = nil
            peripheral.setNotifyValue(true, for: notify)
            return
        }
        guard characteristic.uuid == KridaBle.toGuest, let value = characteristic.value else { return }
        if let message = reassembler.accept(sender: KridaBle.hostPeer, frame: value.toKotlin()) {
            listener?.onMessage(peer: KridaBle.hostPeer, data: message)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard characteristic.uuid == KridaBle.toGuest, characteristic.isNotifying, !ready else { return }
        // Frame size must fit one ATT write: the Android host doesn't handle long (prepared) writes.
        mtu = Int32(peripheral.maximumWriteValueLength(for: .withoutResponse) + 3)
        ready = true
        listener?.onPeerConnected(peer: KridaBle.hostPeer)
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        writing = false
        pump()
    }

    // MARK: Private

    private func startScan() {
        guard let central, central.state == .poweredOn, host == nil else { return }
        central.scanForPeripherals(withServices: [KridaBle.service])
    }

    private func connect(_ peripheral: CBPeripheral, verified: Bool) {
        central?.stopScan()
        codeVerified = verified
        host = peripheral
        peripheral.delegate = self
        central?.connect(peripheral)
    }

    private func reject(_ peripheral: CBPeripheral, rescan: Bool) {
        rejected.insert(peripheral.identifier)
        codeVerified = false
        pendingNotify = nil
        if host?.identifier == peripheral.identifier { host = nil }
        central?.cancelPeripheralConnection(peripheral)
        if rescan { startScan() }
    }

    private func pump() {
        guard !writing, !outbox.isEmpty, let host, let toHost else { return }
        writing = true
        host.writeValue(outbox.removeFirst(), for: toHost, type: .withResponse)
    }

    private func roomOf(_ advertisement: [String: Any]) -> String? {
        if let serviceData = advertisement[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data],
           let data = serviceData[KridaBle.service],
           let text = String(data: data, encoding: .utf8) {
            return RoomCode.shared.parse(text: text)
        }
        if let name = advertisement[CBAdvertisementDataLocalNameKey] as? String {
            return RoomCode.shared.parse(text: name)
        }
        return nil
    }
}
