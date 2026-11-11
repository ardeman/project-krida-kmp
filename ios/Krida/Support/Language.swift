import SwiftUI

/// The app language: Indonesian or English. It follows the device by default (an Indonesian
/// device gets Indonesian, every other language English, the development language) and can be
/// changed in iOS Settings (per-app language) or with the in-app picker.
///
/// Both write `AppleLanguages` in the app's defaults, so the next launch starts in the chosen
/// language. Within this launch the switch is immediate: SwiftUI text follows the root
/// `\.locale` environment and `String(localized:bundle: .app)` reads from [Bundle.app].
final class Language: ObservableObject {
    static let shared = Language()

    enum Code: String, CaseIterable { case id, en }

    @Published private(set) var code: Code

    private init() {
        code = Code(rawValue: Bundle.main.preferredLocalizations.first ?? "") ?? .en
        Bundle.app = Self.bundle(for: code)
    }

    var locale: Locale { Locale(identifier: code.rawValue) }

    func set(_ new: Code) {
        guard new != code else { return }
        UserDefaults.standard.set([new.rawValue], forKey: "AppleLanguages")
        Bundle.app = Self.bundle(for: new)
        code = new
        // Invite notifications are written in this language (Account/Push.swift).
        Task { @MainActor in Push.shared.upload() }
    }

    private static func bundle(for code: Code) -> Bundle {
        Bundle.main.path(forResource: code.rawValue, ofType: "lproj").flatMap(Bundle.init(path:)) ?? .main
    }
}

extension Bundle {
    /// Strings in the app language, for `String(localized:bundle:)` outside SwiftUI text.
    static var app: Bundle = .main
}

/// ID | EN segmented switch, as on Android.
struct LanguagePicker: View {
    @ObservedObject private var language = Language.shared
    @Environment(\.uiScale) private var s

    var body: some View {
        HStack(spacing: 2 * s) {
            ForEach(Language.Code.allCases, id: \.self) { code in
                let on = code == language.code
                Button {
                    Sounds.shared.play(.tap)
                    language.set(code)
                } label: {
                    Text(verbatim: code.rawValue.uppercased())
                        .font(Theme.font(14 * s))
                        .foregroundStyle(on ? Theme.ink : Theme.muted)
                        .padding(.horizontal, 12 * s)
                        .frame(height: 38 * s)
                        .background {
                            if on {
                                RoundedRectangle(cornerRadius: 10 * s).fill(Theme.raised).shadow(color: .black.opacity(0.12), radius: 1.5)
                            }
                        }
                }
                .buttonStyle(.plain)
                // Each name in its own language, as language pickers do.
                .accessibilityLabel(Text(verbatim: code == .id ? "Bahasa Indonesia" : "English"))
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(3 * s)
        .background(Theme.track, in: RoundedRectangle(cornerRadius: 12 * s))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text("Language"))
    }
}
