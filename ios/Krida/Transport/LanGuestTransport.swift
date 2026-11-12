import Foundation
import Network
import Shared

/// LAN guest: Bonjour browse for `Krida-<ROOM>`, connect with `NWConnection`. Host peer id is
/// `KridaBle.hostPeer`. Callbacks on the main queue.
final class LanGuestTransport: Transport {
    private let roomCode: String
    /// Emulator / sim e2e: skip Bonjour and connect here (adb forward → 127.0.0.1).
    private let directHost: String?
    private let directPort: UInt16?
    private weak var listener: TransportListener?
    private var browser: NWBrowser?
    private var connection: NWConnection?
    private var buffer = Data()
    private var closed = false
    private var connecting = false

    init(roomCode: String, host: String? = nil, port: UInt16? = nil) {
        self.roomCode = roomCode
        self.directHost = host
        self.directPort = port
    }

    func start() {
        guard !closed, browser == nil, !connecting else { return }
        if let host = directHost, let port = directPort, let nwPort = NWEndpoint.Port(rawValue: port) {
            connecting = true
            connect(.hostPort(host: host, port: nwPort))
            return
        }
        let wanted = RoomCode.shared.advertisedName(code: roomCode)
        let browser = NWBrowser(for: .bonjour(type: Krida.shared.BONJOUR_SERVICE_TYPE, domain: nil), using: .tcp)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            DispatchQueue.main.async {
                guard let self, !self.closed, !self.connecting else { return }
                guard let match = results.first(where: { result in
                    if case let .service(name: name, type: _, domain: _, interface: _) = result.endpoint {
                        return name == wanted
                    }
                    return false
                }) else { return }
                self.connecting = true
                self.browser?.cancel()
                self.browser = nil
                self.connect(match.endpoint)
            }
        }
        browser.stateUpdateHandler = { _ in }
        browser.start(queue: .main)
        self.browser = browser
    }

    private func connect(_ endpoint: NWEndpoint) {
        let connection = NWConnection(to: endpoint, using: .tcp)
        connection.stateUpdateHandler = { [weak self] state in
            DispatchQueue.main.async {
                guard let self else { return }
                switch state {
                case .ready:
                    self.listener?.onPeerConnected(peer: KridaBle.hostPeer)
                    self.receive()
                case .failed, .cancelled:
                    self.lost()
                default:
                    break
                }
            }
        }
        connection.start(queue: .main)
        self.connection = connection
    }

    private func receive() {
        connection?.receive(minimumIncompleteLength: 1, maximumLength: LanFraming.maxMessage + 4) { [weak self] data, _, isComplete, error in
            DispatchQueue.main.async {
                guard let self, !self.closed else { return }
                if let data, !data.isEmpty {
                    self.buffer.append(data)
                    do {
                        for message in try LanFraming.takeMessages(from: &self.buffer) {
                            self.listener?.onMessage(peer: KridaBle.hostPeer, data: message.toKotlin())
                        }
                    } catch {
                        self.lost()
                        return
                    }
                }
                if isComplete || error != nil {
                    self.lost()
                    return
                }
                self.receive()
            }
        }
    }

    func setListener(listener: TransportListener?) {
        self.listener = listener
    }

    func send(peer: String?, data: KotlinByteArray) {
        guard let connection else { return }
        let frame: Data
        do {
            frame = try LanFraming.encode(data.toData())
        } catch {
            return
        }
        connection.send(content: frame, completion: .contentProcessed { [weak self] error in
            if error != nil {
                DispatchQueue.main.async { self?.lost() }
            }
        })
    }

    func disconnect(peer: String) {}

    func close() {
        guard !closed else { return }
        closed = true
        browser?.cancel()
        browser = nil
        connection?.cancel()
        connection = nil
    }

    private func lost() {
        let had = connection != nil
        connection?.cancel()
        connection = nil
        if had {
            listener?.onPeerDisconnected(peer: KridaBle.hostPeer)
        }
    }
}
