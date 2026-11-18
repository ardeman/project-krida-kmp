import SwiftUI

/// Once-per-marketing-version “What’s New” on the home menu. Compares the short version
/// string to the last dismissed value in `UserDefaults` — build-number-only bumps do not
/// re-prompt. Same idea as Android `WhatsNew`.
enum WhatsNew {
    static let seenKey = "whats_new.seen_version"

    static var marketingVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    }

    static var shouldShow: Bool {
        let seen = UserDefaults.standard.string(forKey: seenKey)
        if seen == nil || seen?.isEmpty == true { return true }
        return compareVersions(seen!, marketingVersion) < 0
    }

    static func dismiss() {
        UserDefaults.standard.set(marketingVersion, forKey: seenKey)
    }

    /// Segment-wise numeric compare: `"1.0.3" < "1.0.4"`.
    static func compareVersions(_ a: String, _ b: String) -> Int {
        let left = a.split(separator: ".").map { Int($0) ?? 0 }
        let right = b.split(separator: ".").map { Int($0) ?? 0 }
        let n = max(left.count, right.count)
        for i in 0..<n {
            let d = (i < left.count ? left[i] : 0) - (i < right.count ? right[i] : 0)
            if d != 0 { return d > 0 ? 1 : -1 }
        }
        return 0
    }

    /// Bullets for the current shipping notes (keep in sync with `docs/changelog.md`).
    /// English source strings are also the catalog keys.
    static let bulletKeys: [String] = [
        "Gaple: a tile that fits both open ends now plays at once, no end tap needed.",
        "Gaple: your hand stays fully on screen on wide phones, and turn labels are clearer.",
    ]
}

/// Home-menu status card for the current marketing version’s bullets.
struct WhatsNewCard: View {
    let onDismiss: () -> Void
    @Environment(\.uiScale) private var s

    var body: some View {
        ZStack {
            Color.black.opacity(0.45).ignoresSafeArea().contentShape(Rectangle()).onTapGesture {}
            VStack(spacing: 12 * s) {
                Text("What's New")
                    .font(Theme.font(22 * s, .black))
                    .multilineTextAlignment(.center)
                VStack(alignment: .leading, spacing: 8 * s) {
                    ForEach(WhatsNew.bulletKeys, id: \.self) { key in
                        HStack(alignment: .top, spacing: 6 * s) {
                            Text("•")
                            Text(LocalizedStringKey(key))
                        }
                        .font(Theme.font(14 * s, .bold))
                        .foregroundStyle(Theme.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                Button(action: {
                    WhatsNew.dismiss()
                    onDismiss()
                }) {
                    Text("Got it").frame(maxWidth: .infinity)
                }
                .buttonStyle(PrimaryButtonStyle())
                .padding(.top, 6 * s)
            }
            .padding(.horizontal, 24 * s)
            .padding(.vertical, 22 * s)
            .frame(width: 420 * s)
            .nearbyCard(s)
            .foregroundStyle(Theme.ink)
            .padding(24 * s)
        }
    }
}
