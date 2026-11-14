import AVFoundation
import Shared
import SwiftUI
import VisionKit

// The "Nearby" screens from the nearby-play mockup: the room lobby (host and guest), the
// join screen (scan the host's QR or type the code) and the connecting / failure card.
// Same as Android's `NearbyScreens.kt`.

private let okGreen = Color(light: 0x3F7D2E, dark: 0x8BC77A)

extension View {
    func nearbyCard(_ s: CGFloat) -> some View {
        background(Theme.surface, in: RoundedRectangle(cornerRadius: 20 * s))
            .overlay(RoundedRectangle(cornerRadius: 20 * s).stroke(Theme.line, lineWidth: 1.5))
    }
}

/// Back button, title and the "Nearby" or "Online" line, over the screen's cards.
struct NearbyFrame<Content: View>: View {
    let title: Text
    let subtitle: Text
    var online = false
    let backLabel: LocalizedStringKey
    let onBack: () -> Void
    @ViewBuilder let content: Content
    @Environment(\.uiScale) private var s
    @Environment(\.punchHole) private var hole

    var body: some View {
        VStack(alignment: .leading, spacing: 12 * s) {
            HStack(spacing: 12 * s) {
                CircleIconButton(systemImage: "chevron.left", label: backLabel, action: onBack)
                VStack(alignment: .leading, spacing: 2 * s) {
                    title.font(Theme.font(20 * s, .black)).lineLimit(1)
                    HStack(spacing: 4 * s) {
                        Image(systemName: online ? "globe" : "antenna.radiowaves.left.and.right").font(.system(size: 12 * s, weight: .bold))
                        subtitle.font(Theme.font(13 * s, .bold)).lineLimit(1)
                    }
                    .foregroundStyle(Theme.muted)
                }
            }
            // Top-leading camera only — the header row. Cards keep the full trailing strip;
            // a bottom-trailing hole sits over empty card corner, not the whole side.
            .clearPunchHole(hole, .topLeading, already: 24 * s)
            HStack(spacing: 14 * s) { content }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .padding(.horizontal, 24 * s)
        .padding(.top, 16 * s)
        .padding(.bottom, 18 * s)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Theme.backdropGradient)
        .foregroundStyle(Theme.ink)
    }
}

/// The line under a room screen's title: Bluetooth, Wi‑Fi nearby, or the internet.
func placeLine(_ online: Bool, lan: Bool = false) -> Text {
    if online { return Text("Online · over the internet") }
    if lan { return Text("Nearby · Wi‑Fi, no internet") }
    return Text("Nearby · Bluetooth, no internet")
}

func gameTitle(_ game: String?) -> Text {
    switch game {
    case UlarTanggaGame.shared.id: Text("Snakes & ladders")
    case LudoGame.shared.id: Text("Ludo")
    case PerangKapalGame.shared.id: Text("Perang kapal")
    case GapleGame.shared.id: Text("Gaple")
    case CongklakGame.shared.id: Text("Congklak")
    default: Text("Join a room")
    }
}

/// Pawn colour names, in the order of `UlarTanggaBoard` colours.
private var colorNames: [String] {
    [
        String(localized: "Red", bundle: .app), String(localized: "Blue", bundle: .app),
        String(localized: "Green", bundle: .app), String(localized: "Yellow", bundle: .app),
    ]
}

private func colorName(_ c: Int) -> String { c < colorNames.count ? colorNames[c] : "\(c + 1)" }

/// Text on a pawn colour: dark on yellow, white on the rest.
private func inkOn(_ color: Color) -> Color {
    let c = UIColor(color)
    var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
    c.getRed(&r, green: &g, blue: &b, alpha: &a)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.5 ? Theme.inkOnLight : .white
}

// MARK: - Lobby

/// The room before the game starts. Host: the code and its QR on the left; guest: "Sudah
/// masuk". On the right the seats, the pawn colours (ular tangga) and Mulai or Keluar room.
struct RoomLobby: View {
    @ObservedObject var room: NearbyRoom
    let onLeave: () -> Void
    @Environment(\.uiScale) private var s
    @State private var showRules = false
    @State private var inviting = false
    @ObservedObject private var account = Account.shared

    /// Invites need an account and an open seat.
    private var canInvite: Bool { room.canInvite && account.user != nil && room.players.count < room.maxPlayers }

    var body: some View {
        // Text("… \(x)") can print Optional(Optional("…")); resolve to a plain string first.
        let hostName = room.players.first { Int($0.seat) == 0 }?.name ?? ""
        let guestSubtitle = String(localized: "Room \(room.code) · host \(hostName)", bundle: .app)
        NearbyFrame(
            title: gameTitle(room.game),
            subtitle: room.isHost ? placeLine(room.online, lan: room.lan) : Text(verbatim: guestSubtitle),
            online: room.online,
            backLabel: room.isHost ? "Back" : "Leave room",
            onBack: onLeave
        ) {
            GeometryReader { geo in
                HStack(spacing: 14 * s) {
                    // Shares the width with the seats, so a narrow phone still fits "+ Komputer".
                    VStack(spacing: 10 * s) {
                        if room.isHost {
                            if room.online {
                                Text(room.publicRoom ? "Public" : "Private")
                                    .font(Theme.font(13 * s, .heavy)).foregroundStyle(Theme.muted)
                            }
                            hostCode
                        } else { joinedNote }
                    }
                    .padding(.horizontal, 16 * s)
                    .padding(.vertical, 14 * s)
                    .frame(width: min((geo.size.width - 14 * s) * 0.37, 290 * s))
                    .frame(maxHeight: .infinity)
                    .nearbyCard(s)
                    VStack(alignment: .leading, spacing: 10 * s) {
                        if inviting && canInvite {
                            InvitePicker(room: room, onBack: { inviting = false })
                        } else {
                            playersHeader
                            if room.colorCount > 0 { colorRow }
                            seats
                            if room.canInvite { WaitingInvites(room: room) }
                            Spacer(minLength: 0)
                        }
                    }
                    // The picker closes by itself once the last seat is taken.
                    .onChange(of: canInvite, initial: true) { _, can in
                        if can { account.loadFriends() } else { inviting = false }
                    }
                    .padding(.horizontal, 16 * s)
                    .padding(.top, 12 * s)
                    .padding(.bottom, 14 * s)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .nearbyCard(s)
                }
            }
        }
    }

    private var hostCode: some View {
        Group {
            HStack(spacing: 10 * s) {
                Text("Room code").textCase(.uppercase).font(Theme.font(13 * s, .heavy)).foregroundStyle(Theme.muted)
                if room.online, let url = URL(string: RoomCode.shared.shareUrl(code: room.code)) {
                    // The share link through the system share sheet; Krida opens it as a universal link.
                    ShareLink(item: url, message: Text("Join my Krida game!")) {
                        HStack(spacing: 6 * s) {
                            Image(systemName: "square.and.arrow.up").font(.system(size: 13 * s, weight: .bold))
                            Text("Share").font(Theme.font(13 * s, .heavy))
                        }
                        .foregroundStyle(Theme.accent)
                        .padding(.horizontal, 12 * s)
                        .frame(height: 30 * s)
                        .background(Theme.chip, in: Capsule())
                    }
                    .simultaneousGesture(TapGesture().onEnded { Sounds.shared.play(.tap) })
                }
            }
            // Six online letters get narrower boxes, so they fit the same card.
            HStack(spacing: (room.online ? 6 : 8) * s) {
                ForEach(Array(room.code.enumerated()), id: \.offset) { _, ch in
                    Text(String(ch))
                        .font(.system(size: (room.online ? 26 : 30) * s, weight: .black, design: .monospaced))
                        .frame(width: (room.online ? 36 : 46) * s, height: 52 * s)
                        .background(Theme.chip, in: RoundedRectangle(cornerRadius: 12 * s))
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(room.code.map(String.init).joined(separator: " ")))
            RoomQr(link: room.online ? RoomCode.shared.shareUrl(code: room.code) : RoomCode.shared.joinUrl(code: room.code))
            Text(room.online
                ? String(localized: "Your friend opens Online › Join,\nthen scans this QR or types the code", bundle: .app)
                : String(localized: "Your friend opens Nearby › Join,\nthen scans this QR or types the code", bundle: .app))
                .font(Theme.font(12 * s, .bold))
                .foregroundStyle(Theme.muted)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var joinedNote: some View {
        let seat = room.mySeat
        let color = room.players.first { Int($0.seat) == seat }?.color?.intValue
        return Group {
            Image(systemName: "checkmark")
                .font(.system(size: 32 * s, weight: .black))
                .foregroundStyle(okGreen)
                .frame(width: 72 * s, height: 72 * s)
                .background(Theme.chip, in: Circle())
            Text("You're in").font(Theme.font(22 * s, .black))
            VStack(spacing: 2 * s) {
                Text("Waiting for the host to start…")
                if let seat, let color {
                    Text("Kamu pemain \(seat + 1), pion \(colorName(color).lowercased()).")
                } else if let seat {
                    Text("Kamu pemain \(seat + 1).")
                }
            }
            .font(Theme.font(14 * s, .bold))
            .foregroundStyle(Theme.muted)
            .multilineTextAlignment(.center)
            // "How to play" with the host's options, so a guest knows the rules before the start.
            // Older hosts send none; the game's own Rules button has them then.
            if room.congklakOptions != nil || room.ularOptions != nil || room.ludoOptions != nil {
                Button { showRules = true } label: { Text("How to play") }
                    .buttonStyle(SecondaryButtonStyle())
            }
        }
        .sheet(isPresented: $showRules) {
            if let options = room.congklakOptions {
                CongklakRulesSheet(tembakAfterStore: options.tembakAfterStore)
            } else if let options = room.ularOptions {
                UlarTanggaRulesSheet(options: options)
            } else if let options = room.ludoOptions {
                LudoRulesSheet(options: options)
            }
        }
    }

    private var playersHeader: some View {
        let count = room.players.count
        let hint: LocalizedStringKey = if !room.isHost {
            "Only the host can start"
        } else if room.canStart && count < room.maxPlayers {
            "Empty seats don't play"
        } else if room.canStart {
            "Room full, ready to play"
        } else if room.canAddBots {
            "Wait for friends, or fill a seat with the computer"
        } else {
            "Waiting for a friend to join"
        }
        return HStack(spacing: 12 * s) {
            VStack(alignment: .leading, spacing: 2 * s) {
                Text("Players · \(count) of \(room.maxPlayers)").font(Theme.font(17 * s, .black)).lineLimit(1)
                Text(hint).font(Theme.font(12 * s, .bold)).foregroundStyle(Theme.muted).lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if room.isHost {
                if canInvite {
                    Button { inviting = true } label: { Text("Invite friends") }
                        .buttonStyle(SecondaryButtonStyle())
                }
                Button(action: room.start) { Text("Start").frame(minWidth: 94 * s) }
                    .buttonStyle(PrimaryButtonStyle())
                    .opacity(room.canStart ? 1 : 0.5)
                    .disabled(!room.canStart)
            } else {
                Button(action: onLeave) { Text("Leave room").frame(minWidth: 94 * s) }
                    .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
            }
        }
        .frame(minHeight: 44 * s)
    }

    /// "Your pawn color": one swatch per colour; a person's colour shows their seat; a computer's is tappable to switch.
    private var colorRow: some View {
        let mine = room.players.first { Int($0.seat) == room.mySeat }?.color?.intValue
        return HStack(spacing: 12 * s) {
            Text("Your pawn color").font(Theme.font(13 * s, .heavy)).foregroundStyle(Theme.muted)
                .frame(maxWidth: .infinity, alignment: .leading)
            ForEach(0..<room.colorCount, id: \.self) { c in
                let holder = room.players.first { $0.color?.intValue == c && Int($0.seat) != room.mySeat }
                swatch(c, mine: c == mine, holderSeat: holder.map { Int($0.seat) }, computer: holder?.bot == true)
            }
        }
        .padding(.leading, 12 * s)
        .padding(.trailing, 10 * s)
        .padding(.vertical, 4 * s)
        .background(Theme.surfaceSoft, in: RoundedRectangle(cornerRadius: 14 * s))
        .overlay(RoundedRectangle(cornerRadius: 14 * s).stroke(Theme.line, lineWidth: 1))
    }

    private func swatch(_ c: Int, mine: Bool, holderSeat: Int?, computer: Bool) -> some View {
        let color = pawnColor(c)
        let name = colorName(c)
        let personHeld = holderSeat != nil && !computer
        let label: String = if mine {
            String(localized: "\(name), chosen", bundle: .app)
        } else if computer {
            String(localized: "\(name), switch with the computer", bundle: .app)
        } else if let holderSeat {
            String(localized: "\(name), taken by player \(holderSeat + 1)", bundle: .app)
        } else {
            name
        }
        return Button {
            Sounds.shared.play(.tap)
            room.pickColor(c)
        } label: {
            ZStack {
                Circle().fill(color).padding(mine ? 4 * s : 0)
                if mine {
                    Circle().stroke(Theme.ink, lineWidth: 2)
                    Image(systemName: "checkmark").font(.system(size: 18 * s, weight: .black)).foregroundStyle(inkOn(color))
                } else if computer {
                    Image(systemName: "desktopcomputer").font(.system(size: 16 * s, weight: .bold)).foregroundStyle(inkOn(color))
                } else if let holderSeat {
                    Text("\(holderSeat + 1)").font(Theme.font(15 * s, .black)).foregroundStyle(inkOn(color))
                }
            }
            .frame(width: 44 * s, height: 44 * s)
            .opacity(personHeld ? 0.4 : 1)
        }
        .buttonStyle(.plain)
        .disabled(mine || personHeld)
        .accessibilityLabel(label)
        .accessibilityAddTraits(mine ? .isSelected : [])
    }

    /// Two columns of seats: filled, a computer (host can remove it) or empty (dashed).
    private var seats: some View {
        let rows = stride(from: 0, to: room.maxPlayers, by: 2).map { Array($0..<min($0 + 2, room.maxPlayers)) }
        return VStack(spacing: 10 * s) {
            ForEach(rows, id: \.self) { pair in
                HStack(spacing: 10 * s) {
                    ForEach(pair, id: \.self) { seat in
                        Group {
                            if let p = room.players.first(where: { Int($0.seat) == seat }) {
                                filledSeat(p)
                            } else {
                                emptySeat(seat)
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 64 * s)
                    }
                    if pair.count == 1 { Color.clear.frame(maxWidth: .infinity) }
                }
            }
        }
    }

    private func filledSeat(_ p: PlayerInfo) -> some View {
        let seat = Int(p.seat)
        let me = seat == room.mySeat
        let ular = room.colorCount > 0
        let circle: Color = ular ? pawnColor(p.color?.intValue ?? seat) : seat == 0 ? Theme.wood : Theme.seed
        let pawn = p.color.map { colorName($0.intValue).lowercased() }
        let sub: Text = if p.bot && room.isHost, let pawn {
            // The host knows its computers run on its own phone; guests are told.
            Text("Pion \(pawn)")
        } else if p.bot {
            Text("Pion \(pawn ?? "") · di HP host")
        } else if me && room.isHost {
            Text("Host · this phone")
        } else if seat == 0, let pawn {
            Text("Host · pion \(pawn)")
        } else if seat == 0 {
            Text("Host")
        } else if me {
            Text("This phone")
        } else if let pawn {
            Text("Pion \(pawn)")
        } else {
            Text("You're in")
        }
        let name = p.bot ? String(localized: "Computer", bundle: .app) : p.name
        return HStack(spacing: 12 * s) {
            if !p.bot, let avatar = p.avatar {
                AvatarBadge(avatar: avatar, size: 40)
            } else {
                ZStack {
                    Circle().fill(circle)
                    if p.bot {
                        Image(systemName: "desktopcomputer").font(.system(size: 18 * s, weight: .bold))
                    } else {
                        Text("\(seat + 1)").font(Theme.font(18 * s, .black))
                    }
                }
                .foregroundStyle(inkOn(circle))
                .frame(width: 40 * s, height: 40 * s)
            }
            VStack(alignment: .leading, spacing: 1 * s) {
                (me ? Text("\(name) (kamu)") : Text(verbatim: name)).font(Theme.font(15 * s, .heavy))
                sub.font(Theme.font(12 * s, .bold)).foregroundStyle(Theme.muted)
            }
            .lineLimit(1)
            .frame(maxWidth: .infinity, alignment: .leading)
            if p.bot && room.isHost && room.canAddBots {
                Button {
                    Sounds.shared.play(.tap)
                    room.removeBot(seat: seat)
                } label: {
                    Image(systemName: "xmark").font(.system(size: 15 * s, weight: .bold)).foregroundStyle(Theme.muted)
                        .frame(width: 44 * s, height: 44 * s)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Remove the computer from seat \(seat + 1)"))
            }
        }
        .padding(.leading, 12 * s)
        .padding(.trailing, p.bot && room.isHost ? 4 * s : 12 * s)
        .frame(maxHeight: .infinity)
        .background(me ? Theme.highlight : Theme.surfaceSoft, in: RoundedRectangle(cornerRadius: 14 * s))
        .overlay(RoundedRectangle(cornerRadius: 14 * s).stroke(me ? Theme.accent : Theme.line, lineWidth: me ? 2 : 1))
    }

    private func emptySeat(_ seat: Int) -> some View {
        let dash = StrokeStyle(lineWidth: 2, dash: [6 * s, 4 * s])
        return HStack(spacing: 10 * s) {
            Text("\(seat + 1)").font(Theme.font(16 * s, .black)).foregroundStyle(Theme.muted)
                .frame(width: 36 * s, height: 36 * s)
                .overlay(Circle().stroke(Theme.border, style: dash))
            if room.isHost && room.canAddBots {
                Spacer(minLength: 0)
                Button {
                    Sounds.shared.play(.tap)
                    let computers = (1..<room.maxPlayers).map { String(localized: "Computer \($0)", bundle: .app) }
                    room.addBot(name: computers.first { n in !room.players.contains { $0.name == n } } ?? computers[0])
                } label: {
                    HStack(spacing: 4 * s) {
                        Image(systemName: "plus").font(.system(size: 14 * s, weight: .black))
                        Text("Computer").font(Theme.font(14 * s, .black)).lineLimit(1).fixedSize()
                    }
                    .foregroundStyle(Theme.ink)
                    .padding(.horizontal, 12 * s)
                    .frame(height: 44 * s)
                    .background(Theme.chip, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Add a computer in seat \(seat + 1)"))
            } else {
                Text("Waiting for a friend…").font(Theme.font(14 * s, .bold)).foregroundStyle(Theme.muted).lineLimit(1)
                Spacer(minLength: 0)
            }
        }
        .padding(.leading, 12 * s)
        .padding(.trailing, 10 * s)
        .frame(maxHeight: .infinity)
        .overlay(RoundedRectangle(cornerRadius: 14 * s).stroke(Theme.line, style: dash))
    }
}

/// The room link as a QR code: dark modules on white in both themes, so any camera reads it.
private struct RoomQr: View {
    let link: String
    @Environment(\.uiScale) private var s

    var body: some View {
        let qr = QrCode.companion.encode(text: link)
        let n = Int(qr.size)
        Canvas { ctx, size in
            let cell = min(size.width, size.height) / CGFloat(n)
            var path = Path()
            for r in 0..<n {
                for c in 0..<n where qr.isDark(row: Int32(r), col: Int32(c)) {
                    // A hair wider than a cell, so neighbouring modules leave no seams.
                    path.addRect(CGRect(x: CGFloat(c) * cell, y: CGFloat(r) * cell, width: cell + 0.3, height: cell + 0.3))
                }
            }
            ctx.fill(path, with: .color(Theme.inkOnLight))
        }
        .padding(10 * s)
        .frame(width: 132 * s, height: 132 * s)
        .background(.white, in: RoundedRectangle(cornerRadius: 12 * s))
        .overlay(RoundedRectangle(cornerRadius: 12 * s).stroke(Theme.line, lineWidth: 1))
        .accessibilityElement()
        .accessibilityLabel(Text("Room QR code"))
    }
}

// MARK: - Join

/// Join a room: scan the host's QR (left) or type its code (right), 4 letters nearby or 6
/// `online`. `error` shows under the code (scanner trouble), or nil.
struct JoinScreen: View {
    @Binding var code: String
    let online: Bool
    var lan: Bool = false
    let error: LocalizedStringKey?
    let onScan: () -> Void
    let onJoin: () -> Void
    let onBack: () -> Void
    @Environment(\.uiScale) private var s

    var body: some View {
        NearbyFrame(title: Text("Join a room"), subtitle: placeLine(online, lan: lan), online: online, backLabel: "Back", onBack: onBack) {
            VStack(spacing: 12 * s) {
                Image(systemName: "qrcode.viewfinder")
                    .font(.system(size: 44 * s, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                    .frame(width: 88 * s, height: 88 * s)
                    .background(Theme.chip, in: RoundedRectangle(cornerRadius: 24 * s))
                Text("Scan the QR").font(Theme.font(22 * s, .black))
                Text("Point the camera at the QR on the host's phone. You're in, no typing.")
                    .font(Theme.font(14 * s, .bold))
                    .foregroundStyle(Theme.muted)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 260 * s)
                    .fixedSize(horizontal: false, vertical: true)
                Button(action: onScan) {
                    Label("Open camera", systemImage: "camera.fill").frame(minWidth: 164 * s)
                }
                .buttonStyle(PrimaryButtonStyle(height: 48))
            }
            .padding(18 * s)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .nearbyCard(s)
            VStack(spacing: 8 * s) {
                Rectangle().fill(Theme.ink.opacity(0.18)).frame(width: 2)
                Text("or").font(Theme.font(12 * s, .black)).fixedSize()
                Rectangle().fill(Theme.ink.opacity(0.18)).frame(width: 2)
            }
            .frame(width: 28 * s)
            VStack(spacing: 12 * s) {
                Text("Type the code").font(Theme.font(22 * s, .black))
                Text("Room code from your friend").font(Theme.font(14 * s, .bold)).foregroundStyle(Theme.muted)
                CodeBoxes(code: $code, length: length, valid: valid, onSubmit: onJoin)
                Button(action: onJoin) { Text("Join").frame(minWidth: 164 * s) }
                    .buttonStyle(PrimaryButtonStyle(height: 48))
                    .opacity(valid ? 1 : 0.5)
                    .disabled(!valid)
                // Keeps its line when empty, so the boxes don't jump once the code is complete.
                Group {
                    if let error {
                        Text(error).foregroundStyle(Theme.error)
                    } else {
                        Text(valid ? " " : online ? "Join turns on after 6 letters" : "Join turns on after 4 letters").foregroundStyle(Theme.muted)
                    }
                }
                .font(Theme.font(12 * s, .bold))
                .multilineTextAlignment(.center)
            }
            .padding(18 * s)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .nearbyCard(s)
        }
    }

    private var length: Int {
        Int(online ? Krida.shared.ONLINE_ROOM_CODE_LENGTH : Krida.shared.ROOM_CODE_LENGTH)
    }

    private var valid: Bool {
        online ? RoomCode.shared.isValidOnline(code: code) : RoomCode.shared.isValid(code: code)
    }

}

/// One text field drawn as `length` letter boxes; the next box to fill is outlined while typing.
struct CodeBoxes: View {
    @Binding var code: String
    let length: Int
    let valid: Bool
    let onSubmit: () -> Void
    var compact = false
    @Environment(\.uiScale) private var s
    @FocusState private var typing: Bool

    var body: some View {
        let chars = Array(code)
        let wide = length <= Int(Krida.shared.ROOM_CODE_LENGTH)
        return ZStack {
            TextField("", text: $code)
                .focused($typing)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .keyboardType(.asciiCapable)
                .submitLabel(.go)
                .onSubmit { if valid { onSubmit() } }
                .foregroundStyle(.clear)
                .tint(.clear)
                .accessibilityLabel(Text("Room code from your friend"))
                .onChange(of: code) { _, new in
                    let clean = String(new.uppercased().filter { $0.isASCII && ($0.isLetter || $0.isNumber) }.prefix(length))
                    if clean != new { code = clean }
                }
            HStack(spacing: (wide ? 10 : 6) * s) {
                ForEach(0..<length, id: \.self) { i in
                    let active = typing && i == min(chars.count, length - 1)
                    Text(i < chars.count ? String(chars[i]) : "")
                        .font(.system(size: 32 * s, weight: .black, design: .monospaced))
                        .frame(width: (wide ? 54 : 42) * s, height: (compact ? 48 : 64) * s)
                        .background(active ? Theme.surface : Theme.chip, in: RoundedRectangle(cornerRadius: 14 * s))
                        .overlay(RoundedRectangle(cornerRadius: 14 * s).stroke(active ? Theme.accent : Theme.line, lineWidth: 2))
                }
            }
            .contentShape(Rectangle())
            .onTapGesture { typing = true }
            .accessibilityHidden(true)
        }
        .fixedSize()
    }
}

/// VisionKit's live scanner for QR codes, full screen with a close button. Hands the first
/// QR it reads to `onCode` (raw text).
struct QrScannerView: View {
    let onCode: (String) -> Void
    let onClose: () -> Void

    var body: some View {
        QrScanner(onCode: onCode)
            .ignoresSafeArea()
            .overlay(alignment: .topLeading) {
                CircleIconButton(systemImage: "xmark", label: "Cancel", action: onClose).padding(20)
            }
    }

    /// Whether this phone can scan (A12 or newer, camera allowed); asks for the camera first.
    @MainActor static func available() async -> Bool {
        guard DataScannerViewController.isSupported else { return false }
        if AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined {
            _ = await AVCaptureDevice.requestAccess(for: .video)
        }
        return DataScannerViewController.isAvailable
    }
}

private struct QrScanner: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let vc = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .fast,
            isHighlightingEnabled: true
        )
        vc.delegate = context.coordinator
        try? vc.startScanning()
        return vc
    }

    func updateUIViewController(_ vc: DataScannerViewController, context: Context) {}

    static func dismantleUIViewController(_ vc: DataScannerViewController, coordinator: Coordinator) {
        vc.stopScanning()
    }

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onCode: (String) -> Void
        private var done = false
        init(onCode: @escaping (String) -> Void) { self.onCode = onCode }

        func dataScanner(_ scanner: DataScannerViewController, didAdd items: [RecognizedItem], allItems: [RecognizedItem]) {
            for item in items {
                guard !done, case .barcode(let code) = item, let text = code.payloadStringValue else { continue }
                done = true
                onCode(text)
            }
        }
    }
}

// MARK: - Connecting

/// A guest on its way into a room, or stopped: one card with what is happening, the room code
/// and Batal / Kembali, plus Coba lagi or Sambung lagi where trying again can help.
struct ConnectingScreen: View {
    @ObservedObject var room: NearbyRoom
    let onCancel: () -> Void
    let onRetry: () -> Void
    @Environment(\.uiScale) private var s

    private var texts: (LocalizedStringKey, LocalizedStringKey) {
        switch room.stage {
        case .searching:
            if room.online {
                ("Connecting to the room…", "Over the internet. Both phones need a connection.")
            } else if room.lan {
                ("Looking for the room…", "Same Wi‑Fi or hotspot as the host. No internet needed.")
            } else {
                ("Looking for the room…", "Hold your phone near the host's. Bluetooth must be on on both phones.")
            }
        case .connecting, .lobby: ("Connecting…", "Found the room. Almost in.")
        case .full: ("The room is full", "Every seat is taken. Ask the host to make a new room.")
        case .started: ("The game has already started", "This room is already playing. Wait for the next round or make your own room.")
        case .version: ("Different app versions", "Update Krida on both phones, then try again.")
        case .notFound: room.online
            ? ("Room not found", "Check the code, make sure the host still has the room open, then try again.")
            : ("Room not found", "Check the code, make sure the host is still on the room screen, then try again.")
        case .lost:
            if room.online {
                ("Disconnected from the host", "The internet connection to the host was lost.")
            } else if room.lan {
                ("Disconnected from the host", "The Wi‑Fi connection to the host was lost.")
            } else {
                ("Disconnected from the host", "The Bluetooth connection to the host was lost.")
            }
        case .unknownGame: ("Couldn't join the room", "This room plays a game this app doesn't have. Update the app.")
        case .refused: ("Couldn't join the room", "Try again, or ask the host to make a new room.")
        case .offline: ("Can't reach Krida online", "Check this phone's internet connection, then try again.")
        }
    }

    private var retry: LocalizedStringKey? {
        switch room.stage {
        case .notFound, .refused, .offline: "Try again"
        case .lost: "Reconnect"
        default: nil
        }
    }

    var body: some View {
        let failed = room.failed
        let cancel: LocalizedStringKey = failed ? "Back" : "Cancel"
        let (title, detail) = texts
        NearbyFrame(title: gameTitle(room.game), subtitle: placeLine(room.online, lan: room.lan), online: room.online, backLabel: cancel, onBack: onCancel) {
            VStack(spacing: 12 * s) {
                Group {
                    if failed {
                        Image(systemName: "exclamationmark.circle.fill")
                            .font(.system(size: 30 * s, weight: .bold))
                            .foregroundStyle(Theme.error)
                    } else {
                        LoadingIndicator()
                    }
                }
                .frame(width: 64 * s, height: 64 * s)
                .background(failed ? Theme.error.opacity(0.14) : .clear, in: Circle())
                Text(title).font(Theme.font(22 * s, .black)).multilineTextAlignment(.center)
                Text(detail).font(Theme.font(14 * s, .bold)).foregroundStyle(Theme.muted)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 8 * s) {
                    Text("Room code").font(Theme.font(13 * s, .heavy)).foregroundStyle(Theme.muted)
                    Text(verbatim: room.code)
                        .font(.system(size: 16 * s, weight: .black, design: .monospaced))
                        .padding(.horizontal, 10 * s)
                        .padding(.vertical, 4 * s)
                        .background(Theme.chip, in: RoundedRectangle(cornerRadius: 8 * s))
                }
                HStack(spacing: 10 * s) {
                    Button(action: onCancel) { Text(cancel).frame(minWidth: 94 * s) }
                        .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                    if let retry {
                        Button(action: onRetry) { Text(retry).frame(minWidth: 94 * s) }
                            .buttonStyle(PrimaryButtonStyle())
                    }
                }
                .padding(.top, 4 * s)
            }
            .padding(.horizontal, 24 * s)
            .padding(.vertical, 22 * s)
            .frame(width: 480 * s)
            .nearbyCard(s)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}
