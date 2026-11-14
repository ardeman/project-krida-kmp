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

/// At most this many reactions show at once; older ones drop first.
private let maxShownEmotes = 4

/// One reaction from `seat`. `n` numbers every reaction on this device, so two equal
/// reactions in a row (or two in the same frame) are still told apart.
struct EmoteEvent: Equatable {
    let seat: Int
    let id: String
    let n: Int

    @MainActor private static var next = 1

    @MainActor init(seat: Int, id: String) {
        self.seat = seat
        self.id = id
        n = EmoteEvent.next
        EmoteEvent.next += 1
    }
}

extension Array where Element == EmoteEvent {
    /// Appends a reaction to a table's recent list, keeping only the last few.
    @MainActor func plusEmote(seat: Int, id: String) -> [EmoteEvent] {
        Array((self + [EmoteEvent(seat: seat, id: id)]).suffix(maxShownEmotes))
    }
}

/// HUD control for nearby/online games: smile button, picker, and floating reaction bubbles.
/// `emotes` is the table's recent reactions; each new one floats beside the button for 2.5 s,
/// stacked when several arrive together. Only the button takes layout space; the bubbles and
/// picker float to its left so they never move the button. They open downward from the
/// button's top, or upward from its bottom when `opensUp` (a button near the bottom of the
/// screen).
struct EmoteHud: View {
    let canEmote: Bool
    let emotes: [EmoteEvent]
    let playerName: (Int) -> String
    let onSend: (String) -> Void
    var s: CGFloat = 1
    var opensUp = false

    @State private var open = ProcessInfo.processInfo.arguments.contains("-openEmotes")
    @State private var shown: [EmoteEvent] = []
    /// Reactions already on show (or there before this HUD appeared); nil until it appears.
    @State private var seen: Int?

    var body: some View {
        if canEmote {
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
            .overlay(alignment: opensUp ? .bottomLeading : .topLeading) {
                VStack(alignment: .trailing, spacing: 8 * s) {
                    ForEach(shown, id: \.n) { e in
                        EmoteBubble(name: playerName(e.seat), id: e.id, s: s)
                            .transition(.scale.combined(with: .opacity))
                    }
                    if open {
                        EmotePicker(s: s) { id in
                            open = false
                            onSend(id)
                        }
                        .transition(.opacity)
                    }
                }
                .fixedSize()
                // Right edge 8 pt left of the button.
                .alignmentGuide(.leading) { d in d[.trailing] + 8 * s }
            }
            .zIndex(1)
            .animation(.easeOut(duration: 0.2), value: open)
            .animation(.easeOut(duration: 0.2), value: shown.map(\.n))
            .onAppear {
                if seen == nil { seen = emotes.last?.n ?? 0 }
                // Seed a floating bubble for store screenshots alongside the open picker.
                if ProcessInfo.processInfo.arguments.contains("-openEmotes"), shown.isEmpty {
                    shown = [EmoteEvent(seat: 1, id: "awesome")]
                }
            }
            // Use the onChange *new* value — capturing `emotes` from the View is stale.
            .onChange(of: emotes) { _, new in
                let fresh = new.filter { $0.n > (seen ?? 0) }
                guard let last = fresh.last else { return }
                seen = last.n
                open = false
                for e in fresh {
                    shown.append(e)
                    if shown.count > maxShownEmotes { shown.removeFirst() }
                    Task {
                        try? await Task.sleep(nanoseconds: 2_500_000_000)
                        shown.removeAll { $0.n == e.n }
                    }
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
        // Fixed width so a trailing HUD column end-aligns it left onto the board.
        .frame(width: 220 * s, alignment: .trailing)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 16 * s, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16 * s, style: .continuous).stroke(Theme.line, lineWidth: 1.5))
    }
}
