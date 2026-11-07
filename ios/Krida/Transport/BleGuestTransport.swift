import CoreBluetooth
import Foundation
import Shared

/// BLE guest: scans for a Krida host with the given room code (Android hosts put it in
/// service data, iOS hosts in the local name), connects, subscribes, writes frames.
final class BleGuestTransport: NSObject, Transport, CBCentralManagerDelegate, CBPeripheralDelegate {
    private let roomCode: String
    private var central: CBCentralManager?
    private var host: CBPeripheral?
    private var toHost: CBCharacteristic?
    private weak var listener: TransportListener?

    private var ready = false
    private var mtu: Int32 = Chunker.companion.MIN_MTU
    private var outbox: [Data] = []
    private var writing = false
    private let chunker = Chunker()
    private let reassembler = Reassembler()

    init(roomCode: String) {
        self.roomCode = roomCode
        super.init()
    }

    func start() {
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
        outbox.removeAll()
        host = nil
        central = nil
    }

    // MARK: CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard central.state == .poweredOn, host == nil else { return }
        central.scanForPeripherals(withServices: [KridaBle.service])
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard host == nil, roomOf(advertisementData) == roomCode else { return }
        central.stopScan()
        host = peripheral
        peripheral.delegate = self
        central.connect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([KridaBle.service])
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        let wasReady = ready
        ready = false
        writing = false
        outbox.removeAll()
        reassembler.clear(sender: KridaBle.hostPeer)
        host = nil
        if wasReady { listener?.onPeerDisconnected(peer: KridaBle.hostPeer) }
    }

    // MARK: CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == KridaBle.service }) else { return }
        peripheral.discoverCharacteristics([KridaBle.toHost, KridaBle.toGuest], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        let characteristics = service.characteristics ?? []
        toHost = characteristics.first { $0.uuid == KridaBle.toHost }
        if let notify = characteristics.first(where: { $0.uuid == KridaBle.toGuest }) {
            peripheral.setNotifyValue(true, for: notify)
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

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == KridaBle.toGuest, let value = characteristic.value else { return }
        if let message = reassembler.accept(sender: KridaBle.hostPeer, frame: value.toKotlin()) {
            listener?.onMessage(peer: KridaBle.hostPeer, data: message)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        writing = false
        pump()
    }

    // MARK: Private

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
