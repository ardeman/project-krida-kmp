import Foundation

/// Run with GameLifetime.swift, without an iOS runtime or third-party test dependency.
@main
struct GameLifetimeTests {
    @MainActor
    static func waitUntil(_ condition: () -> Bool) async throws {
        let deadline = ContinuousClock.now + .seconds(2)
        while !condition() {
            precondition(ContinuousClock.now < deadline, "Lifecycle operation did not complete")
            try await Task.sleep(for: .milliseconds(1))
        }
    }

    @MainActor
    static func main() async throws {
        // Stopping cancels a turn waiting for loading or a bot delay.
        let stopped = GameLifetime()
        var moved = false
        var cancelled = false
        var started = false
        stopped.launch("bot") {
            started = true
            do {
                try await Task.sleep(for: .seconds(60))
                moved = true
            } catch {
                cancelled = true
                throw error
            }
        }
        try await waitUntil { started }
        stopped.stop()
        stopped.stop()
        stopped.launch("late") { moved = true }
        try await waitUntil { cancelled }
        precondition(cancelled && !moved && !stopped.active)

        // Cancellation during a sow/hop stops every later step and announcement.
        let animation = GameLifetime()
        var steps = 0
        var announced = false
        animation.launch("animation") {
            steps += 1
            try await Task.sleep(for: .seconds(60))
            steps += 1
            announced = true
        }
        try await waitUntil { steps == 1 }
        animation.stop()
        try await Task.sleep(for: .milliseconds(20))
        precondition(steps == 1 && !announced)

        // A finishing old task cannot unregister the task that replaced it.
        let replacement = GameLifetime()
        var oldTurn = false
        var newTurn = false
        var oldStarted = false
        var newStarted = false
        var newCancelled = false
        replacement.launch("bot") {
            oldStarted = true
            try await Task.sleep(for: .seconds(60))
            oldTurn = true
        }
        try await waitUntil { oldStarted }
        replacement.launch("bot") {
            newStarted = true
            do {
                try await Task.sleep(for: .seconds(60))
                newTurn = true
            } catch {
                newCancelled = true
                throw error
            }
        }
        try await waitUntil { newStarted }
        replacement.stop()
        try await waitUntil { newCancelled }
        precondition(!oldTurn && !newTurn)

        // Completing one move can schedule the next under the same name.
        let chained = GameLifetime()
        var nextMove = false
        chained.launch("animation") {
            chained.launch("animation") {
                try await Task.sleep(for: .milliseconds(10))
                nextMove = true
            }
        }
        try await waitUntil { nextMove }
        precondition(nextMove)
        chained.stop()
        print("PASS: stop, interrupted animation, task replacement and chained moves")
    }
}
