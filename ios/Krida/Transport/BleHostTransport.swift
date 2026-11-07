import CoreBluetooth
import Foundation
import Shared

/// BLE host: CBPeripheralManager GATT server. Android or iOS guests connect, subscribe to
/// `toGuest`, and write frames to `toHost`. Peer id = CBCentral.identifier.
///
/// iOS can only advertise a local name + service UUIDs, so the room code goes in the
/// local name ("Krida-ABCD"). Delegate queue is main, so everything runs on main.
final class BleHostTransport: NSObject, Transport, CBPeripheralManagerDelegate {
    private let roomCode: String
    private var manager: CBPeripheralManager?
    private weak var listener: TransportListener?
    private var toGuest: CBMutableCharacteristic?

    private var centrals: [String: CBCentral] = [:]
    private var outbox: [(central: CBCentral, frame: Data)] = []
    private let chunker = Chunker()
    private let reassembler = Reassembler()

    init(roomCode: String) {
        self.roomCode = roomCode
        super.init()
    }

    func start() {
        manager = CBPeripheralManager(delegate: self, queue: .main)
    }

    // MARK: Transport

    func setListener(listener: TransportListener?) {
        self.listener = listener
    }

    func send(peer: String?, data: KotlinByteArray) {
        let targets = peer.map { id in centrals[id].map { [$0] } ?? [] } ?? Array(centrals.values)
        for central in targets {
            let mtu = Int32(central.maximumUpdateValueLength + 3)
            for frame in chunker.split(message: data, mtu: mtu) {
                outbox.append((central, frame.toData()))
            }
        }
        flush()
    }

    func disconnect(peer: String) {
        // A peripheral can't force a central off; forget it. The guest closes itself on Reject.
        drop(peer)
    }

    func close() {
        manager?.stopAdvertising()
        manager?.removeAllServices()
        manager?.delegate = nil
        manager = nil
        centrals.removeAll()
        outbox.removeAll()
    }

    // MARK: CBPeripheralManagerDelegate

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard peripheral.state == .poweredOn else { return }
        let toHost = CBMutableCharacteristic(
            type: KridaBle.toHost,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        let notify = CBMutableCharacteristic(
            type: KridaBle.toGuest,
            properties: [.notify],
            value: nil,
            permissions: [.readable]
        )
        let service = CBMutableService(type: KridaBle.service, primary: true)
        service.characteristics = [toHost, notify]
        toGuest = notify
        peripheral.add(service)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        guard error == nil else { return }
        peripheral.startAdvertising([
            CBAdvertisementDataLocalNameKey: RoomCode.shared.advertisedName(code: roomCode),
            CBAdvertisementDataServiceUUIDsKey: [KridaBle.service],
        ])
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral,
                           didSubscribeTo characteristic: CBCharacteristic) {
        let id = central.identifier.uuidString
        guard centrals[id] == nil else { return }
        centrals[id] = central
        listener?.onPeerConnected(peer: id)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral,
                           didUnsubscribeFrom characteristic: CBCharacteristic) {
        drop(central.identifier.uuidString)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests where request.characteristic.uuid == KridaBle.toHost {
            guard let value = request.value else { continue }
            let id = request.central.identifier.uuidString
            if let message = reassembler.accept(sender: id, frame: value.toKotlin()) {
                listener?.onMessage(peer: id, data: message)
            }
        }
        if let first = requests.first {
            peripheral.respond(to: first, withResult: .success)
        }
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        flush()
    }

    // MARK: Private

    /// updateValue returns false when the transmit queue is full; retry in peripheralManagerIsReady.
    private func flush() {
        guard let manager, let toGuest else { return }
        while let next = outbox.first {
            guard manager.updateValue(next.frame, for: toGuest, onSubscribedCentrals: [next.central]) else { return }
            outbox.removeFirst()
        }
    }

    private func drop(_ id: String) {
        guard let central = centrals.removeValue(forKey: id) else { return }
        outbox.removeAll { $0.central.identifier == central.identifier }
        reassembler.clear(sender: id)
        listener?.onPeerDisconnected(peer: id)
    }
}
