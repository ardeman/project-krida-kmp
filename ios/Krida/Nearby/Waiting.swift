import Foundation
import Shared

/// "Menunggu Ayu…" beside a ring of 15: a dropped player and the seconds left before the computer (or, for
/// the host, the end). Same as Android's `online/Waiting.kt`.
struct Waiting {
    let name: String
    let seconds: Int
    var host = false

    var text: String { String(localized: "Menunggu \(name)…", bundle: .app) }
}

/// Runs `tick` on the main thread once a second until `stop`.
@MainActor
final class SecondTicker {
    private var timer: Timer?
    private let tick: @MainActor () -> Void

    init(_ tick: @escaping @MainActor () -> Void) { self.tick = tick }

    func start() {
        timer?.invalidate()
        tick()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }
}

private func seconds(_ ms: Int64) -> Int { Int((ms + 999) / 1000) }

/// The host's countdown: the dropped player whose seat goes to the computer soonest.
func hostWaiting(players: [PlayerInfo], handOffInMs: (Int32) -> Int64) -> Waiting? {
    players.compactMap { p -> Waiting? in
        let ms = handOffInMs(p.seat)
        return ms <= 0 ? nil : Waiting(name: p.name, seconds: seconds(ms))
    }.min { $0.seconds < $1.seconds }
}

/// A guest's countdown, kept from the player lists and the host link, as guests don't see
/// the host's timers: seconds since each drop was first seen, 15 s each.
struct GuestWaiting {
    private var awaySince: [Int32: Date] = [:]
    private var hostAwaySince: Date?
    private let waitMs = Double(Krida.shared.HANDOFF_WAIT_MS)

    mutating func players(_ list: [PlayerInfo]) {
        hostAwaySince = nil
        let away = Set(list.filter { !$0.connected && !$0.bot }.map(\.seat))
        awaySince = awaySince.filter { away.contains($0.key) }
        let now = Date()
        for seat in away where awaySince[seat] == nil { awaySince[seat] = now }
    }

    mutating func hostLost() {
        if hostAwaySince == nil { hostAwaySince = Date() }
    }

    /// The countdown to show; a host past its wait shows 0 ("host left").
    func current(_ list: [PlayerInfo]) -> Waiting? {
        let now = Date()
        if let hostAwaySince {
            let name: String = list.first(where: { $0.seat == 0 })?.name ?? ""
            let left = Int64(waitMs - now.timeIntervalSince(hostAwaySince) * 1000)
            return Waiting(name: name, seconds: left <= 0 ? 0 : seconds(left), host: true)
        }
        return awaySince.compactMap { seat, since -> Waiting? in
            let left = Int64(waitMs - now.timeIntervalSince(since) * 1000)
            guard left > 0, let name = list.first(where: { $0.seat == seat })?.name else { return nil }
            return Waiting(name: name, seconds: seconds(left))
        }.min { $0.seconds < $1.seconds }
    }
}
