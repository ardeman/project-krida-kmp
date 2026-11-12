import Foundation
import Network
import Shared

/// LAN host: `NWListener` with Bonjour `_krida._tcp`, name `Krida-<ROOM>`. Peer id = a short
/// counter. Listener callbacks stay on the main queue.
final class LanHostTransport: Transport {
    private let roomCode: String
    private weak var listener: TransportListener?
    private var server: NWListener?
    private var closed = false
    private var nextPeer = 1
    private var guests: [String: Guest] = [:]

    init(roomCode: String) {
        self.roomCode = roomCode
    }

    func start() {
        guard !closed, server == nil else { return }
        do {
            let params = NWParameters.tcp
            let service = NWListener.Service(name: RoomCode.shared.advertisedName(code: roomCode), type: Krida.shared.BONJOUR_SERVICE_TYPE)
            let listener = try NWListener(using: params)
            listener.service = service
            listener.newConnectionHandler = { [weak self] connection in
                DispatchQueue.main.async { self?.attach(connection) }
            }
            listener.stateUpdateHandler = { [weak self] state in
                if case .failed = state {
                    DispatchQueue.main.async { self?.close() }
                }
            }
            listener.start(queue: .main)
            server = listener
        } catch {
            // Start failed; the room stays empty until the host leaves.
        }
    }

    private func attach(_ connection: NWConnection) {
        guard !closed else {
            connection.cancel()
            return
        }
        let id = "\(nextPeer)"
        nextPeer += 1
        let guest = Guest(id: id, connection: connection)
        guests[id] = guest
        listener?.onPeerConnected(peer: id)
        receive(guest)
        connection.stateUpdateHandler = { [weak self] state in
            DispatchQueue.main.async {
                if case .failed = state { self?.drop(id) }
                if case .cancelled = state { self?.drop(id) }
            }
        }
        connection.start(queue: .main)
    }

    private func receive(_ guest: Guest) {
        guest.connection.receive(minimumIncompleteLength: 1, maximumLength: LanFraming.maxMessage + 4) { [weak self] data, _, isComplete, error in
            DispatchQueue.main.async {
                guard let self, !self.closed, self.guests[guest.id] != nil else { return }
                if let data, !data.isEmpty {
                    guest.buffer.append(data)
                    do {
                        for message in try LanFraming.takeMessages(from: &guest.buffer) {
                            self.listener?.onMessage(peer: guest.id, data: message.toKotlin())
                        }
                    } catch {
                        self.drop(guest.id)
                        return
                    }
                }
                if isComplete || error != nil {
                    self.drop(guest.id)
                    return
                }
                self.receive(guest)
            }
        }
    }

    func setListener(listener: TransportListener?) {
        self.listener = listener
    }

    func send(peer: String?, data: KotlinByteArray) {
        let payload = data.toData()
        let frame: Data
        do {
            frame = try LanFraming.encode(payload)
        } catch {
            return
        }
        let targets: [Guest]
        if let peer {
            targets = guests[peer].map { [$0] } ?? []
        } else {
            targets = Array(guests.values)
        }
        for guest in targets {
            guest.connection.send(content: frame, completion: .contentProcessed { [weak self] error in
                if error != nil {
                    DispatchQueue.main.async { self?.drop(guest.id) }
                }
            })
        }
    }

    func disconnect(peer: String) {
        drop(peer)
    }

    func close() {
        guard !closed else { return }
        closed = true
        guests.keys.forEach(drop)
        server?.cancel()
        server = nil
    }

    private func drop(_ id: String) {
        guard let guest = guests.removeValue(forKey: id) else { return }
        guest.connection.cancel()
        listener?.onPeerDisconnected(peer: id)
    }

    private final class Guest {
        let id: String
        let connection: NWConnection
        var buffer = Data()
        init(id: String, connection: NWConnection) {
            self.id = id
            self.connection = connection
        }
    }
}
