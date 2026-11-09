import AVFoundation
import Shared
import SwiftUI
import VisionKit

// The "Teman dekat" screens from the nearby-play mockup: the room lobby (host and guest), the
// join screen (scan the host's QR or type the code) and the connecting / failure card.
// Same as Android's `NearbyScreens.kt`.

private let okGreen = Color(light: 0x3F7D2E, dark: 0x8BC77A)

private extension View {
    func nearbyCard(_ s: CGFloat) -> some View {
        background(Theme.surface, in: RoundedRectangle(cornerRadius: 20 * s))
            .overlay(RoundedRectangle(cornerRadius: 20 * s).stroke(Theme.line, lineWidth: 1.5))
    }
}

/// Back button, title and the "Teman dekat" or "Online" line, over the screen's cards.
private struct NearbyFrame<Content: View>: View {
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
            .padding(.leading, hole?.leading ?? 0)
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

/// The line under a room screen's title: Bluetooth nearby, or the internet.
private func placeLine(_ online: Bool) -> Text {
    online ? Text("Online · lewat internet") : Text("Teman dekat · Bluetooth, tanpa internet")
}

private func gameTitle(_ game: String?) -> Text {
    switch game {
    case UlarTanggaGame.shared.id: Text("Ular tangga")
    case CongklakGame.shared.id: Text("Congklak")
    default: Text("Gabung room")
    }
}

/// Pawn colour names, in the order of `UlarTanggaBoard` colours.
private var colorNames: [String] {
    [
        String(localized: "Merah", bundle: .app), String(localized: "Biru", bundle: .app),
        String(localized: "Hijau", bundle: .app), String(localized: "Kuning", bundle: .app),
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

    var body: some View {
        let hostName = room.players.first { $0.seat == 0 }?.name ?? ""
        NearbyFrame(
            title: gameTitle(room.game),
            subtitle: room.isHost ? placeLine(room.online) : Text("Room \(room.code) · host \(hostName)"),
            online: room.online,
            backLabel: room.isHost ? "Kembali" : "Keluar room",
            onBack: onLeave
        ) {
            GeometryReader { geo in
                HStack(spacing: 14 * s) {
                    // Shares the width with the seats, so a narrow phone still fits "+ Komputer".
                    VStack(spacing: 10 * s) {
                        if room.isHost { hostCode } else { joinedNote }
                    }
                    .padding(.horizontal, 16 * s)
                    .padding(.vertical, 14 * s)
                    .frame(width: min((geo.size.width - 14 * s) * 0.37, 290 * s))
                    .frame(maxHeight: .infinity)
                    .nearbyCard(s)
                    VStack(alignment: .leading, spacing: 10 * s) {
                        playersHeader
                        if room.colorCount > 0 { colorRow }
                        seats
                        Spacer(minLength: 0)
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
            Text("Kode room").textCase(.uppercase).font(Theme.font(13 * s, .heavy)).foregroundStyle(Theme.muted)
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
            Text(room.online ? "Temanmu buka Online › Gabung,\nlalu pindai QR ini atau ketik kodenya" : "Temanmu buka Teman dekat › Gabung,\nlalu pindai QR ini atau ketik kodenya")
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
            Text("Sudah masuk").font(Theme.font(22 * s, .black))
            VStack(spacing: 2 * s) {
                Text("Menunggu host memulai…")
                if let seat, let color {
                    Text("Kamu pemain \(seat + 1), pion \(colorName(color).lowercased()).")
                } else if let seat {
                    Text("Kamu pemain \(seat + 1).")
                }
            }
            .font(Theme.font(14 * s, .bold))
            .foregroundStyle(Theme.muted)
            .multilineTextAlignment(.center)
        }
    }

    private var playersHeader: some View {
        let count = room.players.count
        let hint: LocalizedStringKey = if !room.isHost {
            "Hanya host yang bisa memulai"
        } else if room.canStart && count < room.maxPlayers {
            "Kursi kosong tidak ikut main"
        } else if room.canStart {
            "Room penuh, siap main"
        } else if room.canAddBots {
            "Tunggu teman, atau isi kursi dengan komputer"
        } else {
            "Menunggu teman masuk"
        }
        return HStack(spacing: 12 * s) {
            VStack(alignment: .leading, spacing: 2 * s) {
                Text("Pemain · \(count) dari \(room.maxPlayers)").font(Theme.font(17 * s, .black)).lineLimit(1)
                Text(hint).font(Theme.font(12 * s, .bold)).foregroundStyle(Theme.muted).lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if room.isHost {
                Button(action: room.start) { Text("Mulai").frame(minWidth: 94 * s) }
                    .buttonStyle(PrimaryButtonStyle())
                    .opacity(room.canStart ? 1 : 0.5)
                    .disabled(!room.canStart)
            } else {
                Button(action: onLeave) { Text("Keluar room").frame(minWidth: 94 * s) }
                    .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
            }
        }
        .frame(minHeight: 44 * s)
    }

    /// "Warna pionmu": one swatch per colour; another player's colour shows their seat number.
    private var colorRow: some View {
        let mine = room.players.first { Int($0.seat) == room.mySeat }?.color?.intValue
        return HStack(spacing: 12 * s) {
            Text("Warna pionmu").font(Theme.font(13 * s, .heavy)).foregroundStyle(Theme.muted)
                .frame(maxWidth: .infinity, alignment: .leading)
            ForEach(0..<room.colorCount, id: \.self) { c in
                let holder = room.players.first { $0.color?.intValue == c && Int($0.seat) != room.mySeat }
                swatch(c, mine: c == mine, holder: holder.map { Int($0.seat) })
            }
        }
        .padding(.leading, 12 * s)
        .padding(.trailing, 10 * s)
        .padding(.vertical, 4 * s)
        .background(Theme.surfaceSoft, in: RoundedRectangle(cornerRadius: 14 * s))
        .overlay(RoundedRectangle(cornerRadius: 14 * s).stroke(Theme.line, lineWidth: 1))
    }

    private func swatch(_ c: Int, mine: Bool, holder: Int?) -> some View {
        let color = pawnColor(c)
        let name = colorName(c)
        let label: Text = if mine { Text("\(name), dipilih") } else if let holder { Text("\(name), dipakai pemain \(holder + 1)") } else { Text(name) }
        return Button {
            Sounds.shared.play(.tap)
            room.pickColor(c)
        } label: {
            ZStack {
                Circle().fill(color).padding(mine ? 4 * s : 0)
                if mine {
                    Circle().stroke(Theme.ink, lineWidth: 2)
                    Image(systemName: "checkmark").font(.system(size: 18 * s, weight: .black)).foregroundStyle(inkOn(color))
                } else if let holder {
                    Text("\(holder + 1)").font(Theme.font(15 * s, .black)).foregroundStyle(inkOn(color))
                }
            }
            .frame(width: 44 * s, height: 44 * s)
            .opacity(holder != nil ? 0.4 : 1)
        }
        .buttonStyle(.plain)
        .disabled(mine || holder != nil)
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
            Text("Host · HP ini")
        } else if seat == 0, let pawn {
            Text("Host · pion \(pawn)")
        } else if seat == 0 {
            Text("Host")
        } else if me {
            Text("HP ini")
        } else if let pawn {
            Text("Pion \(pawn)")
        } else {
            Text("Sudah masuk")
        }
        let name = p.bot ? String(localized: "Komputer", bundle: .app) : p.name
        return HStack(spacing: 12 * s) {
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
            VStack(alignment: .leading, spacing: 1 * s) {
                (me ? Text("\(name) (kamu)") : Text(verbatim: name)).font(Theme.font(15 * s, .heavy))
                sub.font(Theme.font(12 * s, .bold)).foregroundStyle(Theme.muted)
            }
            .lineLimit(1)
            .frame(maxWidth: .infinity, alignment: .leading)
            if p.bot && room.isHost {
                Button {
                    Sounds.shared.play(.tap)
                    room.removeBot(seat: seat)
                } label: {
                    Image(systemName: "xmark").font(.system(size: 15 * s, weight: .bold)).foregroundStyle(Theme.muted)
                        .frame(width: 44 * s, height: 44 * s)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Hapus komputer dari kursi \(seat + 1)"))
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
                    let computers = (1..<room.maxPlayers).map { String(localized: "Komputer \($0)", bundle: .app) }
                    room.addBot(name: computers.first { n in !room.players.contains { $0.name == n } } ?? computers[0])
                } label: {
                    HStack(spacing: 4 * s) {
                        Image(systemName: "plus").font(.system(size: 14 * s, weight: .black))
                        Text("Komputer").font(Theme.font(14 * s, .black)).lineLimit(1).fixedSize()
                    }
                    .foregroundStyle(Theme.ink)
                    .padding(.horizontal, 12 * s)
                    .frame(height: 44 * s)
                    .background(Theme.chip, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Tambah komputer di kursi \(seat + 1)"))
            } else {
                Text("Menunggu teman…").font(Theme.font(14 * s, .bold)).foregroundStyle(Theme.muted).lineLimit(1)
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
        .accessibilityLabel(Text("QR kode room"))
    }
}

// MARK: - Join

/// Join a room: scan the host's QR (left) or type its code (right), 4 letters nearby or 6
/// `online`. `error` shows under the code (scanner trouble), or nil.
struct JoinScreen: View {
    @Binding var code: String
    let online: Bool
    let error: LocalizedStringKey?
    let onScan: () -> Void
    let onJoin: () -> Void
    let onBack: () -> Void
    @Environment(\.uiScale) private var s
    @FocusState private var typing: Bool

    var body: some View {
        NearbyFrame(title: Text("Gabung room"), subtitle: placeLine(online), online: online, backLabel: "Kembali", onBack: onBack) {
            VStack(spacing: 12 * s) {
                Image(systemName: "qrcode.viewfinder")
                    .font(.system(size: 44 * s, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                    .frame(width: 88 * s, height: 88 * s)
                    .background(Theme.chip, in: RoundedRectangle(cornerRadius: 24 * s))
                Text("Pindai QR").font(Theme.font(22 * s, .black))
                Text("Arahkan kamera ke QR di layar HP host. Langsung masuk, tanpa mengetik.")
                    .font(Theme.font(14 * s, .bold))
                    .foregroundStyle(Theme.muted)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 260 * s)
                    .fixedSize(horizontal: false, vertical: true)
                Button(action: onScan) {
                    Label("Buka kamera", systemImage: "camera.fill").frame(minWidth: 164 * s)
                }
                .buttonStyle(PrimaryButtonStyle(height: 48))
            }
            .padding(18 * s)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .nearbyCard(s)
            VStack(spacing: 8 * s) {
                Rectangle().fill(Theme.ink.opacity(0.18)).frame(width: 2)
                Text("atau").font(Theme.font(12 * s, .black)).fixedSize()
                Rectangle().fill(Theme.ink.opacity(0.18)).frame(width: 2)
            }
            .frame(width: 28 * s)
            VStack(spacing: 12 * s) {
                Text("Ketik kode").font(Theme.font(22 * s, .black))
                Text("Kode room dari temanmu").font(Theme.font(14 * s, .bold)).foregroundStyle(Theme.muted)
                codeBoxes
                Button(action: onJoin) { Text("Masuk").frame(minWidth: 164 * s) }
                    .buttonStyle(PrimaryButtonStyle(height: 48))
                    .opacity(valid ? 1 : 0.5)
                    .disabled(!valid)
                // Keeps its line when empty, so the boxes don't jump once the code is complete.
                Group {
                    if let error {
                        Text(error).foregroundStyle(Theme.error)
                    } else {
                        Text(valid ? " " : online ? "Masuk aktif setelah 6 huruf" : "Masuk aktif setelah 4 huruf").foregroundStyle(Theme.muted)
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

    /// One text field drawn as `length` letter boxes; the next box to fill is outlined while typing.
    private var codeBoxes: some View {
        let chars = Array(code)
        let wide = length <= Int(Krida.shared.ROOM_CODE_LENGTH)
        return ZStack {
            TextField("", text: $code)
                .focused($typing)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .keyboardType(.asciiCapable)
                .submitLabel(.go)
                .onSubmit { if valid { onJoin() } }
                .foregroundStyle(.clear)
                .tint(.clear)
                .accessibilityLabel(Text("Kode room dari temanmu"))
                .onChange(of: code) { _, new in
                    let clean = String(new.uppercased().filter { $0.isASCII && ($0.isLetter || $0.isNumber) }.prefix(length))
                    if clean != new { code = clean }
                }
            HStack(spacing: (wide ? 10 : 6) * s) {
                ForEach(0..<length, id: \.self) { i in
                    let active = typing && i == min(chars.count, length - 1)
                    Text(i < chars.count ? String(chars[i]) : "")
                        .font(.system(size: 32 * s, weight: .black, design: .monospaced))
                        .frame(width: (wide ? 54 : 42) * s, height: 64 * s)
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
                CircleIconButton(systemImage: "xmark", label: "Batal", action: onClose).padding(20)
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
        case .searching: room.online
            ? ("Menyambung ke room…", "Lewat internet. Kedua HP perlu koneksi.")
            : ("Mencari room…", "Dekatkan HP ke HP host. Bluetooth harus menyala di kedua HP.")
        case .connecting, .lobby: ("Menghubungkan…", "Room ketemu. Sebentar lagi masuk.")
        case .full: ("Room sudah penuh", "Semua kursi sudah terisi. Minta host membuat room baru.")
        case .started: ("Permainan sudah dimulai", "Room ini sudah main. Tunggu ronde berikutnya atau buat room sendiri.")
        case .version: ("Versi aplikasi berbeda", "Perbarui Krida di kedua HP, lalu coba lagi.")
        case .notFound: room.online
            ? ("Room tidak ketemu", "Cek kodenya, pastikan host masih membuka room, lalu coba lagi.")
            : ("Room tidak ketemu", "Cek kodenya, pastikan host masih di layar room, lalu coba lagi.")
        case .lost: room.online
            ? ("Terputus dari host", "Sambungan internet ke host hilang.")
            : ("Terputus dari host", "Sambungan Bluetooth ke host hilang.")
        case .unknownGame: ("Tidak bisa masuk room", "Room ini memainkan permainan yang belum ada di aplikasi ini. Perbarui aplikasi.")
        case .refused: ("Tidak bisa masuk room", "Coba lagi, atau minta host membuat room baru.")
        case .offline: ("Krida online tidak terjangkau", "Cek koneksi internet HP ini, lalu coba lagi.")
        }
    }

    private var retry: LocalizedStringKey? {
        switch room.stage {
        case .notFound, .refused, .offline: "Coba lagi"
        case .lost: "Sambung lagi"
        default: nil
        }
    }

    var body: some View {
        let failed = room.failed
        let cancel: LocalizedStringKey = failed ? "Kembali" : "Batal"
        let (title, detail) = texts
        NearbyFrame(title: gameTitle(room.game), subtitle: placeLine(room.online), online: room.online, backLabel: cancel, onBack: onCancel) {
            VStack(spacing: 12 * s) {
                Group {
                    if failed {
                        Image(systemName: "exclamationmark.circle.fill")
                            .font(.system(size: 30 * s, weight: .bold))
                            .foregroundStyle(Theme.error)
                    } else {
                        ProgressView().controlSize(.large).tint(Theme.accent)
                    }
                }
                .frame(width: 64 * s, height: 64 * s)
                .background(failed ? Theme.error.opacity(0.14) : Theme.chip, in: Circle())
                Text(title).font(Theme.font(22 * s, .black)).multilineTextAlignment(.center)
                Text(detail).font(Theme.font(14 * s, .bold)).foregroundStyle(Theme.muted)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 8 * s) {
                    Text("Kode room").font(Theme.font(13 * s, .heavy)).foregroundStyle(Theme.muted)
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
