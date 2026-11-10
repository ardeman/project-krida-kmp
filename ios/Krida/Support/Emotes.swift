import Shared
import SwiftUI

/// Localized label for a catalog id.
func emoteLabel(_ id: String) -> String {
    switch id {
    case "nice": return String(localized: "Nice!", bundle: .app)
    case "lets_go": return String(localized: "Let's go!", bundle: .app)
    case "awesome": return String(localized: "Awesome!", bundle: .app)
    case "oops": return String(localized: "Oops!", bundle: .app)
    case "hmm": return String(localized: "Hmm…", bundle: .app)
    case "ready": return String(localized: "Ready!", bundle: .app)
    default: return id
    }
}

/// HUD control for nearby/online games: smile button, picker, and a floating reaction bubble.
struct EmoteHud: View {
    let canEmote: Bool
    let emote: (seat: Int, id: String)?
    let playerName: (Int) -> String
    let onSend: (String) -> Void
    var s: CGFloat = 1

    @State private var open = false
    @State private var shown: (seat: Int, id: String)?

    var body: some View {
        if canEmote {
            VStack(alignment: .trailing, spacing: 8 * s) {
                if let shown {
                    EmoteBubble(name: playerName(shown.seat), id: shown.id, s: s)
                        .transition(.scale.combined(with: .opacity))
                }
                if open {
                    EmotePicker(s: s) { id in
                        open = false
                        onSend(id)
                    }
                    .transition(.opacity)
                }
                Button {
                    Sounds.shared.play(.tap)
                    open.toggle()
                } label: {
                    Text("😊")
                        .font(.system(size: 20 * s))
                        .frame(width: 44 * s, height: 44 * s)
                        .background(Theme.surface, in: Circle())
                        .overlay(Circle().stroke(Theme.line, lineWidth: 1.5))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "Send a reaction", bundle: .app))
            }
            .animation(.easeOut(duration: 0.2), value: open)
            .animation(.easeOut(duration: 0.2), value: shown?.id)
            .onChange(of: emote?.id) { _, _ in
                guard let emote else { return }
                shown = emote
                open = false
                Task {
                    try? await Task.sleep(nanoseconds: 2_500_000_000)
                    if shown?.id == emote.id, shown?.seat == emote.seat { shown = nil }
                }
            }
        }
    }
}

private struct EmoteBubble: View {
    let name: String
    let id: String
    var s: CGFloat = 1

    var body: some View {
        let glyph = Emotes.shared.glyph(id: id) ?? ""
        VStack(spacing: 2 * s) {
            Text(glyph).font(.system(size: 28 * s))
            Text("\(name) · \(emoteLabel(id))")
                .font(Theme.font(12 * s, .bold))
                .foregroundStyle(Theme.muted)
                .lineLimit(1)
        }
        .padding(.horizontal, 12 * s)
        .padding(.vertical, 8 * s)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14 * s, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14 * s, style: .continuous).stroke(Theme.line, lineWidth: 1.5))
    }
}

private struct EmotePicker: View {
    var s: CGFloat = 1
    let onPick: (String) -> Void

    private let columns = [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())]

    var body: some View {
        LazyVGrid(columns: columns, spacing: 6 * s) {
            ForEach(Array(Emotes.shared.ALL), id: \.id) { item in
                Button {
                    Sounds.shared.play(.tap)
                    onPick(item.id)
                } label: {
                    VStack(spacing: 2 * s) {
                        Text(item.glyph).font(.system(size: 22 * s))
                        Text(emoteLabel(item.id))
                            .font(Theme.font(11 * s, .bold))
                            .foregroundStyle(Theme.ink)
                            .lineLimit(1)
                    }
                    .padding(.horizontal, 10 * s)
                    .padding(.vertical, 8 * s)
                    .frame(maxWidth: .infinity)
                    .background(Theme.chip, in: RoundedRectangle(cornerRadius: 12 * s, style: .continuous))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(10 * s)
        .frame(width: 220 * s)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 16 * s, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16 * s, style: .continuous).stroke(Theme.line, lineWidth: 1.5))
    }
}
