import Foundation
import Shared
import UIKit

/// Compares this build to the App Store listing (iTunes lookup). Runs once at launch
/// (`silent: true`) and from the menu button. Until Krida is public, lookup is empty.
@MainActor
final class AppUpdate: ObservableObject {
    static let shared = AppUpdate()

    enum Status {
        case idle, checking, upToDate, available, notListed, failed
    }

    @Published private(set) var status: Status = .idle

    private var task: Task<Void, Never>?
    private var launchedCheck = false

    /// Once per process when the menu appears. Skipped for UI-test / screenshot launch args.
    func checkOnLaunch() {
        guard !launchedCheck else { return }
        launchedCheck = true
        let args = ProcessInfo.processInfo.arguments
        if args.contains(where: { $0.hasPrefix("-open") || $0 == "-skipAds" }) { return }
        check(silent: true)
    }

    /**
     - Parameter silent: launch-time check — no button feedback unless an update opens the
       store; failures stay quiet. Menu taps use `silent: false`.
     */
    func check(silent: Bool = false) {
        guard status != .checking else { return }
        if !silent { status = .checking }
        task?.cancel()
        task = Task { [weak self] in
            guard let self else { return }
            do {
                let store = try await Self.fetchStoreVersion()
                guard !Task.isCancelled else { return }
                guard let store else {
                    if silent {
                        self.status = .idle
                    } else {
                        self.status = .notListed
                        self.clearLater()
                    }
                    return
                }
                let local = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
                if Self.isNewer(store, than: local) {
                    self.status = .available
                    self.openStore()
                } else if silent {
                    self.status = .idle
                } else {
                    self.status = .upToDate
                    self.clearLater()
                }
            } catch {
                guard !Task.isCancelled else { return }
                if silent {
                    self.status = .idle
                } else {
                    self.status = .failed
                    self.clearLater()
                }
            }
        }
    }

    private func clearLater() {
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            guard let self else { return }
            if self.status == .upToDate || self.status == .failed || self.status == .notListed {
                self.status = .idle
            }
        }
    }

    func openStore() {
        guard let url = URL(string: Krida.shared.APP_STORE_URL) else { return }
        UIApplication.shared.open(url)
    }

    private static func fetchStoreVersion() async throws -> String? {
        let bundleId = Bundle.main.bundleIdentifier ?? "com.ardeman.krida"
        guard let url = URL(string: "https://itunes.apple.com/lookup?bundleId=\(bundleId)") else {
            return nil
        }
        let (data, _) = try await URLSession.shared.data(from: url)
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let results = json["results"] as? [[String: Any]],
              let first = results.first,
              let version = first["version"] as? String else {
            return nil
        }
        return version
    }

    /// Dot-separated marketing versions: 1.0.3 > 1.0.2 > 1.0.
    static func isNewer(_ candidate: String, than current: String) -> Bool {
        let a = candidate.split(separator: ".").compactMap { Int($0) }
        let b = current.split(separator: ".").compactMap { Int($0) }
        let n = max(a.count, b.count)
        for i in 0..<n {
            let x = i < a.count ? a[i] : 0
            let y = i < b.count ? b[i] : 0
            if x != y { return x > y }
        }
        return false
    }
}
