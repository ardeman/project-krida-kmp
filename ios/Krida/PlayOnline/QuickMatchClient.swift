import Foundation
import Shared

/// Group-search events are separate from the game-session protocol.
/// Socket state and UI callbacks stay on the main actor, like native transports.
@MainActor
final class QuickMatchClient {
    private let game: String
    private let playerCount: Int
    private var webSocket: URLSessionWebSocketTask?
    private var receiveTask: Task<Void, Never>?
    private var closed = false

    init(game: String, playerCount: Int) {
        self.game = game
        self.playerCount = playerCount
    }

    func start(onEvent: @escaping ([String: Any]) -> Void, onError: @escaping () -> Void) {
        guard !closed, webSocket == nil else { return }
        guard let url = URL(string: "\(Krida.shared.RELAY_URL)/v1/match/\(game)/\(playerCount)?flow=group") else {
            close()
            onError()
            return
        }
        let socket = URLSession.shared.webSocketTask(with: url)
        webSocket = socket
        socket.resume()
        receiveTask = Task { [weak self] in
            while !Task.isCancelled {
                do {
                    let message = try await socket.receive()
                    guard let self, !self.closed else { return }
                    let data: Data
                    switch message {
                    case .data(let bytes): data = bytes
                    case .string(let text): data = Data(text.utf8)
                    @unknown default: throw CocoaError(.coderReadCorrupt)
                    }
                    guard let event = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                        throw CocoaError(.coderReadCorrupt)
                    }
                    onEvent(event)
                } catch {
                    guard let self, !self.closed else { return }
                    self.close()
                    onError()
                    return
                }
            }
        }
    }

    func acceptBots(revision: Int) { send(["type": "accept_bots", "revision": revision]) }
    func acceptHumans(revision: Int) { send(["type": "accept_humans", "revision": revision]) }
    func keepSearching() { send(["type": "keep_searching"]) }
    func hostReady() { send(["type": "host_ready"]) }

    private func send(_ event: [String: Any]) {
        guard !closed, let data = try? JSONSerialization.data(withJSONObject: event),
              let text = String(data: data, encoding: .utf8) else { return }
        webSocket?.send(.string(text)) { _ in }
    }

    func close() {
        guard !closed else { return }
        closed = true
        receiveTask?.cancel()
        receiveTask = nil
        webSocket?.cancel(with: .normalClosure, reason: nil)
        webSocket = nil
    }
}
