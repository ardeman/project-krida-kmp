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
    /// Host → relay lobby status for public rooms; never forwarded to guests.
    static let lobbyStatus: UInt8 = 5

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

    /// Called on main after the room socket opens, before matched guests are released.
    var onReady: (() -> Void)?

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

    private func trace(_ event: String) {
        #if DEBUG
        print("[KridaOnline] \(type(of: self)) \(code): \(event)")
        #endif
    }

    // Overridden by the host and guest.
    func query() -> String { "" }
    func onFrame(kind: UInt8, peer: Int, payload: Data) {}
    /// The socket opened, before `onReady`.
    func onSocketOpen() {}
    /// The socket dropped (it may come back).
    func onSocketLost() {}

    func start() {
        trace("start")
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
        trace("close requested")
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
        trace("send kind=\(kind) peer=\(peer) bytes=\(frame.count)")
        task.send(.data(frame)) { [weak self] error in
            if let error = error as NSError? {
                DispatchQueue.main.async { self?.trace("send failed \(error.domain)/\(error.code)") }
            }
        }
    }

    // MARK: Socket

    private func connect() {
        guard !closed, let session,
              let url = URL(string: "\(Krida.shared.RELAY_URL)/v1/rooms/\(code)?\(query())") else { return }
        let task = session.webSocketTask(with: url)
        self.task = task
        trace("connect task=\(task.taskIdentifier)")
        task.resume()
        receive(task)
    }

    private func receive(_ task: URLSessionWebSocketTask) {
        task.receive { [weak self] result in
            DispatchQueue.main.async {
                guard let self, task === self.task, !self.closed else { return }
                guard case .success(let message) = result else {
                    if case .failure(let error) = result {
                        let error = error as NSError
                        self.trace("receive failed \(error.domain)/\(error.code), close=\(task.closeCode.rawValue)")
                    }
                    return  // the delegate reports the drop
                }
                if case .data(let data) = message, data.count >= 2 {
                    self.trace("receive kind=\(data[data.startIndex]) peer=\(data[data.startIndex + 1]) bytes=\(data.count)")
                    self.onFrame(kind: data[data.startIndex], peer: Int(data[data.startIndex + 1]), payload: data.dropFirst(2))
                }
                self.receive(task)
            }
        }
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        guard webSocketTask === task else { return }
        trace("opened task=\(webSocketTask.taskIdentifier)")
        onSocketOpen()
        onReady?()
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
        trace("closed code=\(closeCode.rawValue)")
        dropped(closeCode: closeCode.rawValue)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard task === self.task else { return }
        if let error = error as NSError? { trace("completed \(error.domain)/\(error.code)") }
        // A close the server started may only show up here, with the code on the task.
        let code = (task as? URLSessionWebSocketTask)?.closeCode.rawValue ?? 0
        dropped(closeCode: code > 1000 ? code : nil)
    }

    /// `closeCode` is nil for a network failure.
    private func dropped(closeCode: Int?) {
        guard !closed else { return }
        trace("dropped code=\(closeCode.map(String.init) ?? "network")")
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
    /// Room metadata for the relay's public-room discovery; nil keeps legacy behaviour.
    private let game: String?
    private let seats: Int
    private let publicRoom: Bool
    private var lobbyBots = 0
    private var started = false

    init(code: String, game: String? = nil, seats: Int = 4, publicRoom: Bool = false) {
        self.game = game
        self.seats = seats
        self.publicRoom = publicRoom
        super.init(code: code)
    }

    override func query() -> String {
        var q = "role=host&key=\(key)"
        if let game { q += "&game=\(game)&seats=\(seats)&public=\(publicRoom ? 1 : 0)" }
        return q
    }

    /// Tells the relay how many seats computers hold and whether the game began.
    func updateLobby(bots: Int, started: Bool? = nil) {
        let started = started ?? self.started
        guard lobbyBots != bots || self.started != started else { return }
        lobbyBots = bots
        self.started = started
        sendLobbyStatus()
    }

    private func sendLobbyStatus() {
        guard game != nil else { return }
        sendFrame(kind: Self.lobbyStatus, peer: 0, payload: Data("{\"bots\":\(lobbyBots),\"started\":\(started)}".utf8))
    }

    override func onSocketOpen() { sendLobbyStatus() }

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
    /// A matchmaker seat hold, used on the first connect only.
    private var reservation: String?

    init(code: String, reservation: String? = nil) {
        self.reservation = reservation
        super.init(code: code)
    }

    override func query() -> String { "role=guest" + (reservation.map { "&reservation=\($0)" } ?? "") }

    override func onSocketOpen() { reservation = nil }

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
