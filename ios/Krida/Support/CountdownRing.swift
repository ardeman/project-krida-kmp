import SwiftUI

/// Every countdown in the app (search, vote, a dropped player's wait): a ring that drains
/// smoothly from full to empty with the seconds in the middle, turning red for the last 5 s.
/// Same as Android's `CountdownRing`.
struct CountdownRing: View {
    let seconds: Int
    let total: Int
    var size: CGFloat = 56
    @Environment(\.uiScale) private var s

    var body: some View {
        // Aim one second ahead and animate linearly over that second, so the ring never jumps.
        let progress = min(1, max(0, Double(max(seconds - 1, 0)) / Double(max(total, 1))))
        let urgent = (1...5).contains(seconds)
        let width = size * s * 0.1
        ZStack {
            Circle().stroke(Theme.line, lineWidth: width)
            Circle()
                .trim(from: 0, to: progress)
                .stroke(urgent ? Theme.error : Theme.accent, style: StrokeStyle(lineWidth: width, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .animation(.linear(duration: seconds > 0 ? 1 : 0.2), value: progress)
                .animation(.easeInOut, value: urgent)
            Text("\(seconds)")
                .font(Theme.font(size * s * 0.36, .black))
                .foregroundStyle(urgent ? Theme.error : Theme.ink)
                .monospacedDigit()
        }
        .padding(width / 2)
        .frame(width: size * s, height: size * s)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(format: Bundle.app.localizedString(forKey: seconds == 1 ? "%d second left" : "%d seconds left", value: nil, table: nil), seconds))
    }
}

/// Same footprint as `CountdownRing`: tap to leave autoplay and get a normal turn countdown
/// again. Shown in the timer slot while the phone is playing for a local seat.
struct AutopilotButton: View {
    var size: CGFloat = 40
    var action: () -> Void
    @Environment(\.uiScale) private var s

    var body: some View {
        Button {
            Sounds.shared.play(.tap)
            action()
        } label: {
            Image("Autopilot")
                .resizable()
                .scaledToFit()
                .foregroundStyle(Theme.accent)
                .frame(width: size * s * 0.55, height: size * s * 0.55)
                .frame(width: size * s, height: size * s)
                .background(Theme.surface, in: Circle())
                .overlay(Circle().stroke(Theme.line, lineWidth: 1.5))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("autopilotButton")
        .accessibilityLabel(Text("Autopilot"))
    }
}

/// Timer slot: countdown while counting, or the autopilot button while the phone is playing
/// for this seat. Always keeps `size` so the pill / die beside it does not jump when the
/// countdown starts or autopilot takes over.
struct TurnTimer: View {
    var seconds: Int
    var total: Int
    var auto: Bool
    var size: CGFloat = 40
    var onResume: () -> Void
    @Environment(\.uiScale) private var s

    var body: some View {
        ZStack {
            if auto {
                AutopilotButton(size: size, action: onResume)
            } else if seconds > 0 {
                CountdownRing(seconds: seconds, total: total, size: size)
            }
        }
        .frame(width: size * s, height: size * s)
    }
}

/// A short line with its countdown ring in front, e.g. "Waiting for Ayu…" while a seat waits.
struct CountdownNotice: View {
    let text: String
    let seconds: Int
    let total: Int
    var fontSize: CGFloat = 14
    @Environment(\.uiScale) private var s

    var body: some View {
        HStack(spacing: 8 * s) {
            CountdownRing(seconds: seconds, total: total, size: fontSize * 2.2)
            Text(text)
                .font(Theme.font(fontSize * s, .bold))
                .foregroundStyle(Theme.error)
                .multilineTextAlignment(.center)
                .lineLimit(2)
        }
    }
}
