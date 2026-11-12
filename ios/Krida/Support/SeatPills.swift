import SwiftUI

/// The player indicator of two-player games (congklak, perang kapal): the opponent in a pill
/// above the board, this device's player in a larger pill below it.
struct PillSeat {
    var name: String
    var avatar: String?
    var bot: Bool
}

/// The far seat, above the board: who you play against and what they're doing.
struct OpponentPill: View {
    var seat: PillSeat
    var on: Bool
    var detail: String
    var s: CGFloat

    var body: some View {
        HStack(spacing: 10 * s) {
            PillAvatar(seat: seat, on: on, size: 30 * s, s: s)
            Text(seat.name).font(Theme.font(16 * s))
            Text(detail).font(Theme.font(13 * s, .bold)).foregroundStyle(on ? Color(hex: 0xFBE7D3) : Theme.muted)
        }
        .lineLimit(1)
        .padding(.leading, 6 * s)
        .padding(.trailing, 16 * s)
        .frame(height: 40 * s)
        .foregroundStyle(on ? .white : Theme.ink)
        .background(on ? Theme.accent : Theme.surface, in: Capsule())
        .overlay(Capsule().stroke(on ? .clear : Theme.line, lineWidth: 1.5))
        .shadow(color: on ? Theme.gold.opacity(0.6) : .clear, radius: 6 * s)
        .accessibilityElement(children: .combine)
    }
}

/// The near seat, below the board; glows on its turn.
struct PlayerPill: View {
    var seat: PillSeat
    var on: Bool
    var status: String
    var s: CGFloat

    var body: some View {
        HStack(spacing: 10 * s) {
            PillAvatar(seat: seat, on: on, size: 34 * s, s: s)
            Text(seat.name).font(Theme.font(17 * s, .black))
            Text(status).font(Theme.font(15 * s, .bold)).foregroundStyle(on ? Color(hex: 0xFBE7D3) : Theme.muted)
        }
        .lineLimit(1)
        .padding(.leading, 6 * s)
        .padding(.trailing, 20 * s)
        .frame(height: 46 * s)
        .foregroundStyle(on ? .white : Theme.ink)
        .background(on ? Theme.accent : Theme.surface, in: Capsule())
        .overlay(Capsule().stroke(on ? Theme.gold.opacity(0.45) : Theme.line, lineWidth: on ? 4 : 1.5).padding(on ? -2 : 0))
        .animation(.easeOut(duration: 0.2), value: on)
        .accessibilityElement(children: .combine)
    }
}

/// The account avatar in multi-device games; otherwise the name's initial, or the bot icon.
private struct PillAvatar: View {
    var seat: PillSeat
    var on: Bool
    var size: CGFloat
    var s: CGFloat

    var body: some View {
        Group {
            if !seat.bot, let avatar = seat.avatar {
                // `size` is already scaled; AvatarBadge scales its own.
                AvatarBadge(avatar: avatar, size: size / s)
            } else if seat.bot {
                Image(systemName: "desktopcomputer").font(.system(size: size * 0.5, weight: .semibold))
            } else {
                Text(String(seat.name.prefix(1)).uppercased()).font(Theme.font(size * 0.45, .black))
            }
        }
        .foregroundStyle(on ? Theme.accent : Theme.muted)
        .frame(width: size, height: size)
        .background(on ? Theme.raised : Theme.track, in: Circle())
        .accessibilityHidden(true)
    }
}

/// Back button, opponent pill, buttons. The pill (the middle of three subviews) sits
/// in the middle of the bar when it fits, slides aside to clear the others when it doesn't,
/// and is offered only the space left between them (so it truncates instead of overlapping).
struct PillTopBar: Layout {
    var gap: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let height = subviews.map { $0.sizeThatFits(.unspecified).height }.max() ?? 0
        return CGSize(width: proposal.width ?? subviews.map { $0.sizeThatFits(.unspecified).width }.reduce(0, +), height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard subviews.count == 3 else { return }
        let lead = subviews[0].sizeThatFits(.unspecified)
        let trail = subviews[2].sizeThatFits(.unspecified)
        let room = max(bounds.width - lead.width - trail.width - 2 * gap, 0)
        let center = subviews[1].sizeThatFits(ProposedViewSize(width: room, height: nil))
        let width = min(center.width, room)
        let minX = bounds.minX + lead.width + gap
        let x = min(max(bounds.midX - width / 2, minX), minX + room - width)
        subviews[0].place(at: CGPoint(x: bounds.minX, y: bounds.midY), anchor: .leading, proposal: ProposedViewSize(lead))
        subviews[1].place(at: CGPoint(x: x, y: bounds.midY), anchor: .leading, proposal: ProposedViewSize(width: width, height: center.height))
        subviews[2].place(at: CGPoint(x: bounds.maxX, y: bounds.midY), anchor: .trailing, proposal: ProposedViewSize(trail))
    }
}
