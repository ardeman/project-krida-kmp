import Foundation
import Shared

/// Connects to the matchmaker WebSocket and waits for a match assignment.
/// Group-search events are separate from the game-session protocol.
actor QuickMatchClient {
    private let game: String
    private let playerCount: Int
    private var webSocket: URLSessionWebSocketTask?
    private var isRunning = false
    
    nonisolated init(game: String, playerCount: Int) {
        self.game = game
        self.playerCount = playerCount
    }
    
    func start(
        onEvent: @escaping (_ event: [String: Any]) -> Void,
        onError: @escaping () -> Void
    ) {
        isRunning = true
        
        // Connect to /v1/match/<GAME>/<PLAYER_COUNT>?flow=group
        let urlString = "\(Krida.shared.RELAY_URL)/v1/match/\(game)/\(playerCount)?flow=group"
        guard let url = URL(string: urlString) else {
            onError()
            return
        }
        
        let session = URLSession.shared
        let webSocket = session.webSocketTask(with: url)
        self.webSocket = webSocket
        
        webSocket.resume()
        
        // Listen for messages
        Task {
            await receiveMessages(webSocket: webSocket, onEvent: onEvent, onError: onError)
        }
    }
    
    func acceptBots(revision: Int) {
        let json: [String: Any] = ["type": "accept_bots", "revision": revision]
        if let data = try? JSONSerialization.data(withJSONObject: json),
           let text = String(data: data, encoding: .utf8) {
            webSocket?.send(.string(text)) { _ in }
        }
    }
    
    func acceptHumans(revision: Int) {
        let json: [String: Any] = ["type": "accept_humans", "revision": revision]
        if let data = try? JSONSerialization.data(withJSONObject: json),
           let text = String(data: data, encoding: .utf8) {
            webSocket?.send(.string(text)) { _ in }
        }
    }
    
    func keepSearching() {
        let json: [String: Any] = ["type": "keep_searching"]
        if let data = try? JSONSerialization.data(withJSONObject: json),
           let text = String(data: data, encoding: .utf8) {
            webSocket?.send(.string(text)) { _ in }
        }
    }
    
    func hostReady() {
        let json: [String: Any] = ["type": "host_ready"]
        if let data = try? JSONSerialization.data(withJSONObject: json),
           let text = String(data: data, encoding: .utf8) {
            webSocket?.send(.string(text)) { _ in }
        }
    }
    
    func playSolo(revision: Int, seats: Int) {
        let json: [String: Any] = ["type": "play_solo", "revision": revision, "seats": seats]
        if let data = try? JSONSerialization.data(withJSONObject: json),
           let text = String(data: data, encoding: .utf8) {
            webSocket?.send(.string(text)) { _ in }
        }
    }
    
    func close() {
        isRunning = false
        webSocket?.cancel(with: .goingAway, reason: nil)
        webSocket = nil
    }
    
    private func receiveMessages(
        webSocket: URLSessionWebSocketTask,
        onEvent: @escaping (_ event: [String: Any]) -> Void,
        onError: @escaping () -> Void
    ) async {
        while isRunning {
            do {
                let message = try await webSocket.receive()
                
                switch message {
                case .data(let data):
                    do {
                        if let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
                            await MainActor.run {
                                if isRunning {
                                    onEvent(json)
                                }
                            }
                        }
                    } catch {
                        await MainActor.run {
                            if isRunning {
                                close()
                                onError()
                            }
                        }
                    }
                    
                case .string(let text):
                    if let data = text.data(using: .utf8),
                       let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                        await MainActor.run {
                            if isRunning {
                                onEvent(json)
                            }
                        }
                    }
                    
                @unknown default:
                    break
                }
            } catch {
                if isRunning {
                    await MainActor.run {
                        if isRunning {
                            close()
                            onError()
                        }
                    }
                }
            }
        }
    }
}
