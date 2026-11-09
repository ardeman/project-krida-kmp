import Foundation
import Shared

/// Connects to the matchmaker WebSocket and waits for a match assignment.
/// The matchmaker will respond with a Message.Matched containing:
/// - role: "host" or "guest"
/// - code: (only for host) the 6-char room code to connect to
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
        onMatched: @escaping (_ role: String, _ code: String) -> Void,
        onError: @escaping (_ error: String) -> Void
    ) {
        isRunning = true
        
        // Connect to /v1/match/<GAME>/<PLAYER_COUNT>
        let urlString = "\(Krida.RELAY_URL)/v1/match/\(game)/\(playerCount)"
        guard let url = URL(string: urlString) else {
            onError("Invalid URL")
            return
        }
        
        let session = URLSession.shared
        let webSocket = session.webSocketTask(with: url)
        self.webSocket = webSocket
        
        webSocket.resume()
        
        // Listen for messages
        Task {
            await receiveMessages(webSocket: webSocket, onMatched: onMatched, onError: onError)
        }
    }
    
    func close() {
        isRunning = false
        webSocket?.cancel(with: .goingAway, reason: nil)
        webSocket = nil
    }
    
    private func receiveMessages(
        webSocket: URLSessionWebSocketTask,
        onMatched: @escaping (_ role: String, _ code: String) -> Void,
        onError: @escaping (_ error: String) -> Void
    ) async {
        while isRunning {
            do {
                let message = try await webSocket.receive()
                
                switch message {
                case .data(let data):
                    // Parse the message as JSON
                    do {
                        if let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                           let type = json["type"] as? String,
                           type == "matched",
                           let role = json["role"] as? String {
                            let code = (json["code"] as? String) ?? ""
                            // Call on main thread
                            await MainActor.run {
                                onMatched(role, code)
                            }
                            isRunning = false
                        }
                    } catch {
                        await MainActor.run {
                            onError("Failed to parse message: \(error.localizedDescription)")
                        }
                        isRunning = false
                    }
                    
                case .string(let text):
                    // Try to parse string as JSON
                    if let data = text.data(using: .utf8),
                       let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                       let type = json["type"] as? String,
                       type == "matched",
                       let role = json["role"] as? String {
                        let code = (json["code"] as? String) ?? ""
                        // Call on main thread
                        await MainActor.run {
                            onMatched(role, code)
                        }
                        isRunning = false
                    }
                    
                @unknown default:
                    break
                }
            } catch {
                if isRunning {
                    await MainActor.run {
                        onError("Connection error: \(error.localizedDescription)")
                    }
                }
                isRunning = false
            }
        }
    }
}
