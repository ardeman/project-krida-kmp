import Foundation

/// Friend invites ("Invites" in docs/protocol.md). Receiving: the inbox socket, open while
/// the app is signed in and shows a menu; `current` is the invite to show. Sending: the host
/// of an online room invites a friend, and `sent` remembers it for the lobby's picker. Same
/// behavior as Android (`account/Invites.kt`).
@MainActor
final class Invites: ObservableObject {
    static let shared = Invites()

    struct Invite: Equatable, Identifiable {
        let id: String
        let from: String
        let fromAvatar: String?
        let code: String
        let game: String
        let seats: Int
        let players: Int
        let until: Date
    }

    /// An invite this phone sent from room `code`; `id` is what the friend's join carries.
    struct Sent: Equatable {
        let id: String
        let code: String
        let until: Date
    }

    private struct Message: Decodable {
        struct From: Decodable {
            let name: String
            let avatar: String?
        }
        let type: String
        let id: String
        let from: From?
        let code: String?
        let game: String?
        let seats: Int?
        let players: Int?
        let ttlMs: Double?
    }

    private struct Answer: Decodable {
        let id: String?
        let ttlMs: Double?
        let error: String?
    }

    private static let retrySeconds = 5
    private static let pingSeconds = 20
    /// The relay's invite lifetime, for an answer that does not say.
    private static let inviteSeconds: TimeInterval = 120

    @Published private var received: [Invite] = []
    /// Invites sent from this phone, by friend id.
    @Published private(set) var sent: [String: Sent] = [:]
    /// The last invite could not be sent; cleared by the next one.
    @Published var sendFailed = false

    /// The invite to show over the menus: the oldest one still open.
    var current: Invite? { received.first }

    private var socket: URLSessionWebSocketTask?
    private var inbox: Task<Void, Never>?

    private init() {}

    /// Opens the inbox and keeps it open (retrying) until `stop`.
    func listen() {
        guard inbox == nil else { return }
        inbox = Task { [weak self] in await self?.run() }
    }

    /// Closes the inbox. Invites still open on the relay come again with the next `listen`.
    func stop() {
        inbox?.cancel()
        inbox = nil
        socket?.cancel(with: .normalClosure, reason: nil)
        socket = nil
        received = []
    }

    /// Not now, or Join: the invite goes away on every device of this account.
    func dismiss(_ id: String) {
        expire(id)
        guard let data = try? JSONEncoder().encode(["type": "dismiss", "id": id]), let text = String(data: data, encoding: .utf8) else { return }
        socket?.send(.string(text)) { _ in }
    }

    /// The invite's two minutes are over.
    func expire(_ id: String) {
        received.removeAll { $0.id == id }
    }

    /// Invites `friendId` to the online room `code` this phone hosts with `hostKey`.
    func send(friendId: String, code: String, hostKey: String) {
        guard let token = Account.shared.sessionToken else { return }
        sendFailed = false
        Task {
            let reply = await Account.shared.send("POST", "/v1/invites", token: token, body: ["friendId": friendId, "code": code, "key": hostKey])
            let answer = reply.flatMap { try? JSONDecoder().decode(Answer.self, from: $0.1) }
            if reply?.0 == 200, let id = answer?.id {
                let seconds = answer?.ttlMs.map { $0 / 1000 } ?? Self.inviteSeconds
                sent[friendId] = Sent(id: id, code: code, until: Date().addingTimeInterval(seconds))
            } else if answer?.error == "already_invited" {
                // Sent before (say, before this screen was rebuilt): show it as waiting.
                sent[friendId] = Sent(id: "", code: code, until: Date().addingTimeInterval(Self.inviteSeconds))
            } else {
                sendFailed = true
            }
        }
    }

    /// One socket after another until cancelled; a drop waits `retrySeconds` first.
    private func run() async {
        while !Task.isCancelled {
            guard let token = Account.shared.sessionToken,
                  let url = URL(string: Account.api.replacingOccurrences(of: "https://", with: "wss://") + "/v1/inbox") else { return }
            var request = URLRequest(url: url)
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            let task = URLSession.shared.webSocketTask(with: request)
            socket = task
            task.resume()
            let pinger = Task {
                while !Task.isCancelled {
                    do { try await Task.sleep(for: .seconds(Self.pingSeconds)) } catch { return }
                    // A dead socket fails the ping; cancelling ends `receive` below.
                    task.sendPing { error in if error != nil { task.cancel() } }
                }
            }
            do {
                while true {
                    let message = try await task.receive()
                    if Task.isCancelled { break }
                    if case .string(let text) = message { read(text) }
                }
            } catch {}
            pinger.cancel()
            if Task.isCancelled { return }
            socket = nil
            received = []
            // 401: the session ended; Account signs out on its own check.
            if (task.response as? HTTPURLResponse)?.statusCode == 401 { inbox = nil; return }
            do { try await Task.sleep(for: .seconds(Self.retrySeconds)) } catch { return }
        }
    }

    private func read(_ text: String) {
        // A message this build can't read is skipped.
        guard let message = try? JSONDecoder().decode(Message.self, from: Data(text.utf8)) else { return }
        switch message.type {
        case "invite":
            guard let from = message.from, let code = message.code, let game = message.game,
                  let seats = message.seats, let players = message.players, let ttl = message.ttlMs else { return }
            let invite = Invite(
                id: message.id, from: from.name, fromAvatar: from.avatar,
                code: code, game: game, seats: seats, players: players,
                until: Date().addingTimeInterval(ttl / 1000)
            )
            received = received.filter { $0.id != invite.id } + [invite]
        case "invite_end":
            expire(message.id)
        default:
            break
        }
    }
}
