import Foundation
import Shared

/// One WebSocket to the online relay (`docs/protocol.md`, "Online relay"), reopened after a
/// drop for up to `Krida.RECONNECT_GRACE_MS`. Relay frames are `[kind, peer, payload…]`.
/// URLSession calls back on the main queue; anything else is hopped there before it touches
/// state or the listener. Same as Android's `OnlineTransport`.
class OnlineTransport: NSObject, Transport, URLSessionWebSocketDelegate {
    static let data: UInt8 = 1
    static let connected: UInt8 = 2
    static let disconnected: UInt8 = 3
    static let kick: UInt8 = 4

    static let roomTaken = 4001
    static let noRoom = 4004
    static let kicked = 4008
    static let full = 4009
    static let hostGone = 4010
    static let replaced = 4011
    /// Not a relay code: the socket stayed down past the reconnect grace.
    static let gaveUp = -1
    private static let fatal: Set<Int> = [4000, roomTaken, noRoom, kicked, full, hostGone, replaced, 4013]

    let code: String
    weak var sink: TransportListener?

    /// The relay closed this room for good with one of its close codes (4001, 4004, …).
    var onClosed: ((Int) -> Void)?

    private var session: URLSession?
    private var task: URLSessionWebSocketTask?
    private var closed = false
    private var downSince: Date?
    private var attempt = 0
    private var pinger: Timer?

    init(code: String) {
        self.code = code
        super.init()
    }

    // Overridden by the host and guest.
    func query() -> String { "" }
    func onFrame(kind: UInt8, peer: Int, payload: Data) {}
    /// The socket dropped (it may come back).
    func onSocketLost() {}

    func start() {
        // The session keeps its delegate until invalidated, which `close` does.
        session = URLSession(configuration: .default, delegate: self, delegateQueue: .main)
        connect()
    }

    // MARK: Transport

    func setListener(listener: TransportListener?) { sink = listener }

    func send(peer: String?, data: KotlinByteArray) {}

    func disconnect(peer: String) {}

    func close() {
        guard !closed else { return }
        closed = true
        pinger?.invalidate()
        task?.cancel(with: .normalClosure, reason: nil)
        task = nil
        session?.invalidateAndCancel()
        session = nil
    }

    func sendFrame(kind: UInt8, peer: Int, payload: Data = Data()) {
        // While the socket is down messages are dropped; the host resends the state on rejoin.
        guard let task else { return }
        var frame = Data([kind, UInt8(truncatingIfNeeded: peer)])
        frame.append(payload)
        task.send(.data(frame)) { _ in }
    }

    // MARK: Socket

    private func connect() {
        guard !closed, let session,
              let url = URL(string: "\(Krida.shared.RELAY_URL)/v1/rooms/\(code)?\(query())") else { return }
        let task = session.webSocketTask(with: url)
        self.task = task
        task.resume()
        receive(task)
    }

    private func receive(_ task: URLSessionWebSocketTask) {
        task.receive { [weak self] result in
            DispatchQueue.main.async {
                guard let self, task === self.task, !self.closed else { return }
                guard case .success(let message) = result else { return }  // the delegate reports the drop
                if case .data(let data) = message, data.count >= 2 {
                    self.onFrame(kind: data[data.startIndex], peer: Int(data[data.startIndex + 1]), payload: data.dropFirst(2))
                }
                self.receive(task)
            }
        }
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        guard webSocketTask === task else { return }
        attempt = 0
        downSince = nil
        // Keeps NATs and mobile networks from dropping an idle game.
        pinger?.invalidate()
        pinger = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in
            self?.task?.sendPing { _ in }
        }
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        guard webSocketTask === task else { return }
        dropped(closeCode: closeCode.rawValue)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard task === self.task else { return }
        // A close the server started may only show up here, with the code on the task.
        let code = (task as? URLSessionWebSocketTask)?.closeCode.rawValue ?? 0
        dropped(closeCode: code > 1000 ? code : nil)
    }

    /// `closeCode` is nil for a network failure.
    private func dropped(closeCode: Int?) {
        guard !closed else { return }
        task?.cancel()
        task = nil
        pinger?.invalidate()
        onSocketLost()
        if let closeCode, Self.fatal.contains(closeCode) {
            close()
            onClosed?(closeCode)
            return
        }
        let now = Date()
        let since = downSince ?? now
        downSince = since
        if now.timeIntervalSince(since) * 1000 > Double(Krida.shared.RECONNECT_GRACE_MS) {
            close()
            onClosed?(Self.gaveUp)
            return
        }
        // 0.5 s, 1 s, 2 s, then every 4 s.
        let wait = min(0.5 * Double(1 << min(attempt, 3)), 4)
        attempt += 1
        DispatchQueue.main.asyncAfter(deadline: .now() + wait) { [weak self] in
            guard let self, self.task == nil else { return }
            self.connect()
        }
    }

    private static let keyAlphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-")

    /// The host's secret for its room: 24 random chars, as the relay asks for 16–64.
    static func newHostKey() -> String {
        var rng = SystemRandomNumberGenerator()
        return String((0..<24).map { _ in keyAlphabet.randomElement(using: &rng)! })
    }
}

/// Hosting an online room. The relay gives each guest a number; the session's peer id is its
/// decimal string. When this phone's own socket drops, every guest is reported gone, and the
/// relay reports them back once the socket is up again.
final class OnlineHostTransport: OnlineTransport {
    private let key = OnlineTransport.newHostKey()
    private var guests: [Int] = []

    override func query() -> String { "role=host&key=\(key)" }

    override func send(peer: String?, data: KotlinByteArray) {
        let id: Int
        if let peer {
            guard let n = Int(peer), guests.contains(n) else { return }
            id = n
        } else {
            id = 0
        }
        sendFrame(kind: Self.data, peer: id, payload: data.toData())
    }

    override func disconnect(peer: String) {
        guard let id = Int(peer) else { return }
        sendFrame(kind: Self.kick, peer: id)
    }

    override func onFrame(kind: UInt8, peer: Int, payload: Data) {
        switch kind {
        case Self.data:
            if guests.contains(peer) { sink?.onMessage(peer: String(peer), data: payload.toKotlin()) }
        case Self.connected:
            if !guests.contains(peer) {
                guests.append(peer)
                sink?.onPeerConnected(peer: String(peer))
            }
        case Self.disconnected:
            if let i = guests.firstIndex(of: peer) {
                guests.remove(at: i)
                sink?.onPeerDisconnected(peer: String(peer))
            }
        default: break
        }
    }

    override func onSocketLost() {
        let gone = guests
        guests.removeAll()
        for g in gone { sink?.onPeerDisconnected(peer: String(g)) }
    }
}

/// Joining an online room by code. The host is peer `KridaBle.hostPeer`, as over BLE.
final class OnlineGuestTransport: OnlineTransport {
    private var hostHere = false

    override func query() -> String { "role=guest" }

    override func send(peer: String?, data: KotlinByteArray) {
        sendFrame(kind: Self.data, peer: 0, payload: data.toData())
    }

    override func onFrame(kind: UInt8, peer: Int, payload: Data) {
        switch kind {
        case Self.data:
            sink?.onMessage(peer: KridaBle.hostPeer, data: payload.toKotlin())
        case Self.connected:
            if !hostHere {
                hostHere = true
                sink?.onPeerConnected(peer: KridaBle.hostPeer)
            }
        case Self.disconnected:
            onSocketLost()
        default: break
        }
    }

    override func onSocketLost() {
        guard hostHere else { return }
        hostHere = false
        sink?.onPeerDisconnected(peer: KridaBle.hostPeer)
    }
}
