import Foundation

/// Owns asynchronous board work. Stop before navigating away; stopped owners never restart.
@MainActor
final class GameLifetime {
    private(set) var active = true
    private var tasks: [String: (UUID, Task<Void, Never>)] = [:]

    /// Replacing a named task cancels the previous one (for example a pending bot turn).
    func launch(_ name: String, operation: @escaping @MainActor () async throws -> Void) {
        guard active else { return }
        cancel(name)
        let id = UUID()
        let task = Task { [weak self] in
            do {
                try Task.checkCancellation()
                try await operation()
            } catch is CancellationError {
                // Cancellation is the expected exit path, including an interrupted sleep.
            } catch {
                assertionFailure("Unexpected board task error: \(error)")
            }
            if self?.tasks[name]?.0 == id { self?.tasks[name] = nil }
        }
        tasks[name] = (id, task)
    }

    func cancel(_ name: String) {
        tasks.removeValue(forKey: name)?.1.cancel()
    }

    func stop() {
        guard active else { return }
        active = false
        let pending = tasks.values.map { $0.1 }
        tasks.removeAll()
        pending.forEach { $0.cancel() }
    }
}
