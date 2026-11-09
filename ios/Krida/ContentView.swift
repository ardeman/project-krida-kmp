import SwiftUI
import Shared

/// Menu: congklak pass-and-play or vs computer on this device, or a nearby room over BLE (host or join),
/// iOS and Android alike. Then the lobby, then the board.
struct ContentView: View {
    @Binding var roomCode: String
    @State private var passAndPlay: CongklakTable?
    @State private var ularTable: UlarTanggaTable?
    /// The game on the left card; the play card follows it.
    @State private var game: MenuGame = .congklak
    /// Ular tangga's seat tiles for a game on this phone, as `LocalSeats.encode` text.
    @AppStorage(UlarTanggaSettings.seatsKey) private var ularSeatsText = ""
    /// Congklak's seats: the first is always a person, the second a person or the computer.
    @State private var congklakSeats: [LocalSeat] = [.person, .person]
    @State private var room: NearbyRoom?
    @State private var level: CongklakBot.Level = .normal
    /// The join screen (scan the host's QR or type the code) is up.
    @State private var joining = false
    /// The join screen takes a 6-letter online code instead of a 4-letter nearby one.
    @State private var joinOnline = false
    /// Quick match for online play
    @State private var playingQuickMatch = false
    @State private var quickMatchGame: String = ""
    /// Under the join screen's code: the scanner can't open or read another QR.
    @State private var joinError: LocalizedStringKey?
    @State private var scanning = false
    @State private var place: Place = .phone
    /// The tembak rule for every mode started here (and rooms this device hosts).
    @AppStorage(CongklakSettings.tembakAfterStoreKey) private var tembakAfterStore = true
    /// Who moves first in every mode started here: "me", "opponent" or "random".
    @AppStorage(CongklakSettings.starterKey) private var starter = "random"
    /// The loading screen at app start, over the menu while its board snapshot renders.
    @State private var starting = true
    @State private var menuReady = false
    /// Menu column height and where its content ends, for the "more below" hint.
    @State private var menuViewport: CGFloat = 0
    @State private var menuEnd: CGFloat = 0
    @State private var startMinDone = false
    @Environment(\.uiScale) private var s
    @Environment(\.punchHole) private var hole
    @ObservedObject private var ads = Ads.shared
    @ObservedObject private var lastRoom = LastRoom.shared

    var body: some View {
        Group {
            if let table = passAndPlay {
                CongklakView(table: table) { passAndPlay = nil }
                    .id(ObjectIdentifier(table))
            } else if let table = ularTable {
                UlarTanggaView(table: table) { ularTable = nil }
                    .id(ObjectIdentifier(table))
            } else if let room {
                RoomView(
                    room: room,
                    onExit: { self.room = nil; joining = false },
                    onLeave: { leaveRoom(room) },
                    onRetry: { self.room = room.retry() }
                )
                .id(ObjectIdentifier(room))
            } else if playingQuickMatch {
                PlayOnlineView(
                    game: quickMatchGame,
                    onCancel: { playingQuickMatch = false; quickMatchGame = "" },
                    onHost: { code, game in
                        room = NearbyRoom.host(game: game == "ular-tangga" ? .ularTangga : .congklak, online: true, code: code)
                        playingQuickMatch = false
                    },
                    onJoin: { code, game in
                        roomCode = code
                        joinOnline = true
                        joining = true
                        playingQuickMatch = false
                    }
                )
            } else if joining {
                JoinScreen(code: $roomCode, online: joinOnline, error: joinError, onScan: scanRoomQr, onJoin: joinRoom, onBack: { joining = false; joinError = nil })
            } else {
                menu
            }
        }
        // `-openCongklak` launch argument: straight to the board (simulator screenshots, UI tests).
        .onAppear { if ProcessInfo.processInfo.arguments.contains("-openCongklak") { startPassAndPlay() } }
        .onAppear { if ProcessInfo.processInfo.arguments.contains("-openUlarTangga") { game = .ularTangga; startUlarTangga() } }
        // A room link opens the app ready to join: the code field on "Teman dekat" or "Online".
        .onAppear { if !roomCode.isEmpty { openJoin() } }
        .onChange(of: roomCode) { _, code in if !code.isEmpty && !joining { openJoin() } }
        .onChange(of: roomCode) { _, _ in joinError = nil }
        .fullScreenCover(isPresented: $scanning) {
            QrScannerView(
                onCode: { text in
                    scanning = false
                    // Either kind of room, whichever join screen is open.
                    let nearbyCode = RoomCode.shared.parse(text: text)
                    if let code = nearbyCode ?? RoomCode.shared.parseOnline(text: text) {
                        joinOnline = nearbyCode == nil
                        roomCode = code
                        joinRoom()
                    } else {
                        joinError = "QR itu bukan room Krida"
                    }
                },
                onClose: { scanning = false }
            )
        }
        .overlay { if starting { LoadingScreen().transition(.opacity) } }
        // Up for at least 0.7 s so it doesn't flash, at most 3 s in case the snapshot never
        // comes (a join link straight into a room, a slow GPU). Same as Android.
        .task {
            try? await Task.sleep(for: .milliseconds(700))
            startMinDone = true
            if menuReady { endStartup() }
            try? await Task.sleep(for: .milliseconds(2300))
            endStartup()
        }
        .onChange(of: menuReady) { _, ready in if ready && startMinDone { endStartup() } }
    }

    private func openJoin() {
        joinOnline = RoomCode.shared.isValidOnline(code: roomCode)
        place = joinOnline ? .online : .nearby
        joining = true
    }

    /// Joins the room `roomCode` names, online or nearby.
    private func joinRoom() {
        let valid = joinOnline ? RoomCode.shared.isValidOnline(code: roomCode) : RoomCode.shared.isValid(code: roomCode)
        guard valid else { return }
        joinError = nil
        room = NearbyRoom.join(code: roomCode, online: joinOnline)
    }

    /// VisionKit's scanner, after asking for the camera if needed.
    private func scanRoomQr() {
        joinError = nil
        Task {
            if await QrScannerView.available() { scanning = true } else { joinError = "Pemindai QR tidak bisa dibuka" }
        }
    }

    /// Leaving a room the guest got into, or the host's own, goes back to the menu; backing
    /// out of a search or a failure goes back to the join screen. Same as Android.
    private func leaveRoom(_ r: NearbyRoom) {
        let inRoom = r.isHost || r.stage == .lobby
        r.leave()
        room = nil
        if inRoom { joining = false }
    }

    private func endStartup() {
        withAnimation(.easeOut(duration: 0.3)) { starting = false }
    }

    /// Two columns at any size: on a big screen the menu grows with `uiScale` and keeps the
    /// phone's proportions, centered, instead of stretching the cards across the screen.
    /// On a narrow screen (a foldable's cover display) the left column gives up some width.
    /// A corner punch hole sits beside the wordmark (top-leading) or the banner (bottom-trailing).
    private var menu: some View {
        HStack(alignment: .top, spacing: 24 * s) {
            VStack(alignment: .leading, spacing: 14 * s) {
                Image("Wordmark").resizable().scaledToFit().frame(height: 40 * s).accessibilityLabel("Krida")
                    .padding(.leading, max((hole?.leading ?? 0) - 24 * s, 0))
                GameCard(game: $game) { menuReady = true }
                HStack { SoundToggles(); Spacer(minLength: 8 * s); LanguagePicker() }
            }
            .containerRelativeFrame(.horizontal) { width, _ in min(236 * s, width * 0.3) }
            VStack(spacing: 10 * s) {
                ScrollViewReader { proxy in
                    ScrollView {
                        VStack(spacing: 10 * s) {
                            if let saved = lastRoom.current { rejoinCard(saved) }
                            playCard
                            AdButtons()
                        }
                        .frame(maxWidth: .infinity)
                        .onGeometryChange(for: CGFloat.self) { $0.frame(in: .named("menu")).maxY } action: { menuEnd = $0 }
                        .id("menuContent")
                    }
                    .scrollBounceBehavior(.basedOnSize)
                    .coordinateSpace(name: "menu")
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { menuViewport = $0 }
                    .overlay(alignment: .bottom) { moreBelowHint(proxy) }
                }
                // Only while the banner shows: with ads removed the cards take the full height.
                if !ads.removed && ads.started {
                    HStack(spacing: 8 * s) {
                        AdBanner()
                        RemoveAdsButton()
                    }
                    .padding(.trailing, max((hole?.trailing ?? 0) - 24 * s, 0))
                }
            }
        }
        .padding(.horizontal, 24 * s)
        .padding(.top, 20 * s)
        .padding(.bottom, 14 * s)
        .frame(maxWidth: LandscapeStage<EmptyView>.designSize.width * s, maxHeight: 460 * s)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        // Full height like the board: down over the home indicator strip.
        .ignoresSafeArea(.container, edges: .bottom)
        .background(Theme.backdropGradient)
        .foregroundStyle(Theme.ink)
    }

    /// The cards can end exactly at the column's bottom edge, so nothing tells a first-time
    /// player there is more below. While the column can still scroll: a backdrop fade over the
    /// bottom edge and a chevron that scrolls to the end.
    @ViewBuilder
    private func moreBelowHint(_ proxy: ScrollViewProxy) -> some View {
        let moreBelow = menuViewport > 0 && menuEnd > menuViewport + 1
        ZStack(alignment: .bottom) {
            LinearGradient(colors: [Theme.backdrop.opacity(0), Theme.backdrop], startPoint: .top, endPoint: .bottom)
                .frame(height: 44 * s)
                .allowsHitTesting(false)
            Button {
                withAnimation(.easeInOut(duration: 0.35)) { proxy.scrollTo("menuContent", anchor: .bottom) }
            } label: {
                Image(systemName: "chevron.down")
                    .font(.system(size: 15 * s, weight: .bold))
                    .foregroundStyle(Theme.accent)
                    .frame(width: 32 * s, height: 32 * s)
                    .background(Theme.surface, in: Circle())
                    .shadow(color: .black.opacity(0.15), radius: 2, y: 1)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("Pilihan lain di bawah"))
        }
        .opacity(moreBelow ? 1 : 0)
        .allowsHitTesting(moreBelow)
        .animation(.easeOut(duration: 0.2), value: moreBelow)
    }

    /// One card for every way to play: on this phone (seat tiles for people and computers) or
    /// with friends nearby over Bluetooth or online; right below it what that means (or the computer's
    /// level) with Mulai, and Gabung for "Teman dekat"; then the game's rules.
    /// Same as Android.
    /// A running game this device left as a guest: go back to the same seat, or forget it.
    private func rejoinCard(_ saved: SavedRoom) -> some View {
        let name = saved.game == UlarTanggaGame.shared.id ? String(localized: "Ular tangga") : "Congklak"
        return HStack(spacing: 8 * s) {
            VStack(alignment: .leading, spacing: 2 * s) {
                Text("\(name) masih berjalan").font(Theme.font(15 * s)).lineLimit(1)
                detail("Room \(saved.code) · kursimu masih ada")
            }
            Spacer(minLength: 0)
            Button("Lupakan") { lastRoom.clear() }
                .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
            Button("Gabung lagi") {
                // Backing out of a failed rejoin lands on the join screen, as after a typed code.
                joining = true
                joinOnline = saved.online
                roomCode = saved.code
                joinRoom()
            }
                .buttonStyle(PrimaryButtonStyle())
        }
        .lineLimit(1)
        .padding(.leading, 18 * s)
        .padding(.trailing, 14 * s)
        .padding(.vertical, 12 * s)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 20 * s))
        .overlay(RoundedRectangle(cornerRadius: 20 * s).stroke(Theme.accent, lineWidth: 1.5))
    }

    private var playCard: some View {
        let ular = game == .ularTangga
        // A room with other phones, over Bluetooth or the internet.
        let nearby = place != .phone
        let online = place == .online
        let seats = ular ? ularSeats : congklakSeats
        let computers = seats.filter { $0 == .computer }.count
        let people = seats.filter { $0 == .person }.count
        let places: [(Place, LocalizedStringKey, String?)] = [
            (.phone, "HP ini", "person.2"), (.nearby, "Teman dekat", "antenna.radiowaves.left.and.right"),
            (.online, "Online", "globe"),
        ]
        return VStack(alignment: .leading, spacing: 10 * s) {
            SettingRow(label: "Main di") {
                Segmented(options: places, selection: $place, label: "Main di")
            }
            // In a nearby room everyone who joins plays, up to 4.
            if !nearby {
                SettingRow(label: "Pemain") {
                    SeatTiles(
                        seats: seats,
                        colors: ular ? seats.indices.map { pawnColor($0) } : [Theme.wood, Theme.seed],
                        tappable: ular ? Set(seats.indices) : [1]
                    ) { i in
                        if ular {
                            ularSeatsText = LocalSeats.shared.encode(seats: LocalSeats.shared.next(seats: seats, index: Int32(i), allowEmpty: true))
                        } else {
                            congklakSeats = LocalSeats.shared.next(seats: congklakSeats, index: Int32(i), allowEmpty: false)
                        }
                    }
                }
            }
            // The action row: what this choice means (or the computer's level), then the
            // buttons, right under the choice that changes them. "Gabung" (only for "Teman dekat")
            // opens the join screen.
            SettingRow(label: !ular && !nearby && computers > 0 ? "Tingkat" : nil) {
                HStack(spacing: 8 * s) {
                    let level = !ular && !nearby && computers > 0
                    if online {
                        detail(ular ? "Sampai 4 HP lewat internet, teman di mana saja" : "Lewat internet, teman di mana saja")
                    } else if nearby {
                        detail(ular ? "Sampai 4 HP lewat Bluetooth, tanpa internet" : "Bluetooth, tanpa internet")
                    } else if level {
                        Segmented(options: [(.easy, "Mudah", nil), (.normal, "Sedang", nil), (.hard, "Sulit", nil)], selection: $level, label: "Tingkat kesulitan")
                    } else if !ular {
                        detail("Berdua, gantian di satu HP")
                    } else if computers == 0 {
                        detail("HP digilir; semua melempar dadu saat gilirannya.")
                    } else if people == 1 {
                        detail("Kamu melawan komputer.")
                    } else {
                        detail("HP digilir; komputer melempar dadunya sendiri.")
                    }
                    if !level { Spacer(minLength: 0) }
                    if online {
                        Button("Pencarian Cepat") {
                            playingQuickMatch = true
                            quickMatchGame = ular ? "ular-tangga" : "congklak"
                        }
                        .buttonStyle(PrimaryButtonStyle())
                    } else if nearby {
                        Button("Gabung") {
                            if joinOnline != online { roomCode = "" }
                            joinOnline = online
                            joining = true
                        }
                            .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                        Button(nearby ? "Buat room" : "Mulai", action: start)
                            .buttonStyle(PrimaryButtonStyle())
                    } else {
                        Button(nearby ? "Buat room" : "Mulai", action: start)
                            .buttonStyle(PrimaryButtonStyle())
                    }
                }
                .lineLimit(1)
            }
            if ular {
                SettingRow(label: "Aturan") { UlarTanggaOptionChips() }
            } else {
                SettingRow(label: "Tembak") {
                    VStack(alignment: .leading, spacing: 6 * s) {
                        Segmented(options: [(true, "Lewat lumbung", nil), (false, "Bebas", nil)], selection: $tembakAfterStore, label: "Aturan tembak")
                        RuleHint(text: tembakAfterStore
                            ? "Tembak (ambil biji di lubang lawan) hanya berlaku setelah bijimu melewati lumbungmu di giliran itu."
                            : "Tembak kapan saja: biji terakhir di lubang kosongmu mengambil biji di lubang lawan di depannya.")
                    }
                }
                SettingRow(label: "Jalan duluan") {
                    Segmented(options: [("me", "Saya", nil), ("opponent", "Lawan", nil), ("random", "Acak", nil)], selection: $starter, label: "Siapa jalan duluan")
                }
            }
        }
        .padding(.leading, 18 * s)
        .padding(.trailing, 14 * s)
        .padding(.vertical, 14 * s)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 20 * s))
        .overlay(RoundedRectangle(cornerRadius: 20 * s).stroke(Theme.line, lineWidth: 1.5))
    }

    private func detail(_ text: LocalizedStringKey) -> some View {
        Text(text).font(Theme.font(14 * s, .semibold)).foregroundStyle(Theme.muted).lineLimit(2)
            .fixedSize(horizontal: false, vertical: true)
    }

    /// Ular tangga's seat tiles; before the tiles the card stored a player count, which becomes
    /// that many people.
    private var ularSeats: [LocalSeat] {
        let people = UlarTanggaSettings.players
        let fallback = (0..<4).map { $0 < people ? LocalSeat.person : LocalSeat.empty }
        return LocalSeats.shared.decode(text: ularSeatsText.isEmpty ? nil : ularSeatsText, size: 4, fallback: fallback)
    }

    private func start() {
        if place != .phone {
            room = NearbyRoom.host(game: game == .ularTangga ? .ularTangga : .congklak, online: place == .online)
            return
        }
        if game == .ularTangga { return startUlarTangga() }
        if congklakSeats.contains(.computer) { startVsComputer(level) } else { startPassAndPlay() }
    }

    private func startVsComputer(_ level: CongklakBot.Level) {
        let name = switch level {
        case .easy: String(localized: "Mudah", bundle: .app)
        case .hard: String(localized: "Sulit", bundle: .app)
        default: String(localized: "Sedang", bundle: .app)
        }
        passAndPlay = HostTable.vsComputer(you: String(localized: "Kamu", bundle: .app), computer: String(localized: "Komputer", bundle: .app), level: level, levelName: name)
    }

    private func startUlarTangga() {
        let seats = ularSeats
        let computers = seats.filter { $0 == .computer }.count
        let people = seats.filter { $0 == .person }.count
        let colors = [String(localized: "Merah", bundle: .app), String(localized: "Biru", bundle: .app), String(localized: "Hijau", bundle: .app), String(localized: "Kuning", bundle: .app)]
        var nextComputer = 0
        var players: [LocalPlayer] = []
        for (i, kind) in seats.enumerated() {
            switch kind {
            case .computer:
                nextComputer += 1
                let name = computers == 1 ? String(localized: "Komputer", bundle: .app) : String(localized: "Komputer \(nextComputer)", bundle: .app)
                players.append(LocalPlayer(name: name, computer: true, color: i))
            case .person:
                // A lone person is "Kamu"; people passing the phone go by their pawn colour.
                players.append(LocalPlayer(name: people == 1 ? String(localized: "Kamu", bundle: .app) : colors[i], computer: false, color: i))
            default: break
            }
        }
        ularTable = UlarTanggaHostTable.local(players: players)
    }

    private func startPassAndPlay() {
        passAndPlay = HostTable.passAndPlay(names: [String(localized: "Pemain 1", bundle: .app), String(localized: "Pemain 2", bundle: .app)])
    }
}

/// "Pulihkan pembelian" while ads are on; "Privasi iklan" where required. "Hapus iklan" sits beside the banner.
private struct AdButtons: View {
    @ObservedObject private var ads = Ads.shared
    @Environment(\.uiScale) private var s

    var body: some View {
        if !ads.removed || ads.privacyOptionsRequired {
            HStack(spacing: 8 * s) {
                if !ads.removed {
                    Button("Pulihkan pembelian", action: ads.restorePurchases)
                        .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                }
                if ads.privacyOptionsRequired {
                    Button("Privasi iklan", action: ads.showPrivacyOptions)
                        .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                }
                Spacer(minLength: 0)
            }
            .lineLimit(1)
            .minimumScaleFactor(0.8)
        }
    }
}

/// "Hapus iklan" beside the banner where the ad is: the cards above keep the full height.
/// The price shows in the App Store sheet, not on the button. Only while the banner shows and
/// the App Store knows the product. Same as Android.
private struct RemoveAdsButton: View {
    @ObservedObject private var ads = Ads.shared
    @Environment(\.uiScale) private var s

    var body: some View {
        if !ads.removed && ads.started && ads.removeAdsPrice != nil {
            Button(action: ads.buyRemoveAds) {
                Text("Hapus iklan").font(Theme.font(14 * s)).foregroundStyle(Theme.accent)
                .lineLimit(1)
                .padding(.horizontal, 14 * s)
                .frame(height: 50 * s)
                .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14 * s))
                .overlay(RoundedRectangle(cornerRadius: 14 * s).stroke(Theme.accent, lineWidth: 1.5))
            }
            .buttonStyle(.plain)
        }
    }
}

/// Where the game is played; the play card's first choice.
private enum Place { case phone, nearby, online }

/// One tile per seat of a game on this phone: a person, a computer, or empty (dashed). A tap on
/// a seat in `tappable` moves it on (see `LocalSeats.next`). Same as Android.
private struct SeatTiles: View {
    let seats: [LocalSeat]
    let colors: [Color]
    let tappable: Set<Int>
    let onTap: (Int) -> Void
    @Environment(\.uiScale) private var s

    var body: some View {
        HStack(spacing: 8 * s) {
            ForEach(seats.indices, id: \.self) { i in tile(i) }
        }
    }

    private func tile(_ i: Int) -> some View {
        let kind = seats[i]
        let empty = kind == .empty
        let shape = RoundedRectangle(cornerRadius: 12 * s)
        let dash = StrokeStyle(lineWidth: 1.5, dash: [6 * s, 4 * s])
        let (label, icon): (LocalizedStringKey, String) = switch kind {
        case .person: ("Orang", "person.2.fill")
        case .computer: ("Komputer", "desktopcomputer")
        default: ("Kosong", "plus")
        }
        return Button { Sounds.shared.play(.tap); onTap(i) } label: {
            VStack(spacing: 3 * s) {
                ZStack {
                    if empty {
                        Circle().stroke(Theme.border, style: dash)
                    } else {
                        Circle().fill(colors[i])
                    }
                    Image(systemName: icon).font(.system(size: 12 * s, weight: .bold))
                        .foregroundStyle(empty ? Theme.muted : light(colors[i]) ? Theme.inkOnLight : .white)
                }
                .frame(width: 28 * s, height: 28 * s)
                Text(label).font(Theme.font(12 * s, .bold)).foregroundStyle(empty ? Theme.muted : Theme.ink)
                    .lineLimit(1).minimumScaleFactor(0.8)
            }
            .padding(.horizontal, 4 * s)
            .frame(maxWidth: .infinity)
            .frame(height: 60 * s)
            .background(empty ? .clear : Theme.surfaceSoft, in: shape)
            .overlay {
                if empty { shape.stroke(Theme.line, style: dash) } else { shape.stroke(Theme.line, lineWidth: 1) }
            }
            .contentShape(shape)
        }
        .buttonStyle(.plain)
        .disabled(!tappable.contains(i))
        .accessibilityLabel(Text("Kursi \(i + 1)"))
        .accessibilityValue(Text(label))
        .accessibilityHint(tappable.contains(i) ? Text("Ganti") : Text(""))
    }

    /// Dark ink on a light circle (yellow pawn, seed), white on the rest.
    private func light(_ color: Color) -> Bool {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0
        UIColor(color).getRed(&r, green: &g, blue: &b, alpha: nil)
        return r * 0.3 + g * 0.59 + b * 0.11 > 0.6
    }
}

/// A small label above its control; no label keeps the rows' spacing. Same as Android.
private struct SettingRow<Content: View>: View {
    let label: LocalizedStringKey?
    @ViewBuilder let content: Content
    @Environment(\.uiScale) private var s

    var body: some View {
        VStack(alignment: .leading, spacing: 4 * s) {
            Text(label ?? " ").font(Theme.font(13 * s, .bold)).foregroundStyle(Theme.muted).lineLimit(1)
                .accessibilityHidden(label == nil)
            content.frame(maxWidth: .infinity, minHeight: 44 * s, alignment: .leading)
        }
    }
}

/// Segmented picker on a track, the options sharing its full width: the chosen one is a
/// raised surface chip, with an optional SF Symbol before its text.
private struct Segmented<T: Hashable>: View {
    let options: [(T, LocalizedStringKey, String?)]
    @Binding var selection: T
    let label: LocalizedStringKey
    @Environment(\.uiScale) private var s

    var body: some View {
        HStack(spacing: 2 * s) {
            ForEach(options.indices, id: \.self) { i in option(options[i]) }
        }
        .padding(3 * s)
        .background(Theme.track, in: RoundedRectangle(cornerRadius: 12 * s))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text(label))
    }

    private func option(_ o: (T, LocalizedStringKey, String?)) -> some View {
        let on = selection == o.0
        return Button { selection = o.0 } label: {
            HStack(spacing: 6 * s) {
                if let icon = o.2 {
                    Image(systemName: icon).font(.system(size: 13 * s, weight: .semibold))
                        .foregroundStyle(on ? Theme.accent : Theme.muted)
                }
                Text(o.1).font(Theme.font(14 * s)).foregroundStyle(on ? Theme.ink : Theme.muted)
            }
            .lineLimit(1)
            .minimumScaleFactor(0.75)
            .padding(.horizontal, 6 * s)
            .frame(maxWidth: .infinity)
            .frame(height: 38 * s)
            .background(on ? Theme.raised : .clear, in: RoundedRectangle(cornerRadius: 10 * s))
            .shadow(color: on ? Theme.ink.opacity(0.18) : .clear, radius: 1.5, y: 1)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// The games the left card switches between, until the game list exists. Same as Android.
enum MenuGame: CaseIterable {
    case congklak, ularTangga

    var title: LocalizedStringKey { self == .congklak ? "Congklak" : "Ular tangga" }
    var players: LocalizedStringKey { self == .congklak ? "2 pemain" : "2–4 pemain" }
    var tagline: LocalizedStringKey { self == .congklak ? "Kumpulkan biji terbanyak di lumbungmu." : "Naiki tangga, hindari ular." }
    var face: Color { self == .congklak ? Theme.wood : Color(hex: 0x4F8A3B) }
    var edge: Color { self == .congklak ? Theme.woodShadow : Color(hex: 0x3B6A2B) }
}

/// The game tile on the home screen, with a picture of the real board; the arrows (or a
/// swipe) switch to the other game, and the play card on the right follows.
private struct GameCard: View {
    @Binding var game: MenuGame
    /// Called once the first board snapshot is on screen.
    let onReady: () -> Void
    @State private var forward = true
    @Environment(\.uiScale) private var s

    var body: some View {
        ZStack(alignment: .topTrailing) {
            GameCardFace(game: game, onReady: onReady)
                .id(game)
                .transition(.asymmetric(insertion: .move(edge: forward ? .trailing : .leading), removal: .move(edge: forward ? .leading : .trailing)))
            // The arrows stay put above the sliding cards.
            HStack(spacing: 6 * s) {
                arrow("chevron.left", label: "Permainan sebelumnya") { step(-1) }
                arrow("chevron.right", label: "Permainan berikutnya") { step(1) }
            }
            .padding(.top, 12 * s)
            .padding(.trailing, 10 * s)
        }
        .clipShape(RoundedRectangle(cornerRadius: 22 * s))
        .frame(maxHeight: .infinity)
        .gesture(DragGesture(minimumDistance: 20).onEnded { v in
            if abs(v.translation.width) > 40 && abs(v.translation.width) > abs(v.translation.height) { step(v.translation.width < 0 ? 1 : -1) }
        })
    }

    private func step(_ d: Int) {
        let all = MenuGame.allCases
        let i = all.firstIndex(of: game)!
        forward = d > 0
        withAnimation(.easeInOut(duration: 0.38)) { game = all[(i + d + all.count) % all.count] }
    }

    private func arrow(_ icon: String, label: LocalizedStringKey, action: @escaping () -> Void) -> some View {
        Button {
            Sounds.shared.play(.tap)
            action()
        } label: {
            Image(systemName: icon).font(.system(size: 15 * s, weight: .bold))
                .foregroundStyle(Color(hex: 0xFFF6E8))
                .frame(width: 34 * s, height: 34 * s)
                .background(Color.white.opacity(0.18), in: Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
    }
}

private struct GameCardFace: View {
    let game: MenuGame
    let onReady: () -> Void
    @Environment(\.uiScale) private var s

    var body: some View {
        VStack(alignment: .leading, spacing: 10 * s) {
            // Room on the right for the arrows, which GameCard draws above the faces.
            VStack(alignment: .leading, spacing: 0) {
                Text(game.title).font(Theme.font(20 * s, .black)).foregroundStyle(Color(hex: 0xFFF6E8)).lineLimit(2)
                Text(game.players).font(Theme.font(13 * s, .bold)).foregroundStyle(Color(hex: 0xF3DDBE)).lineLimit(1)
            }
            .padding(.trailing, 80 * s)
            .frame(minHeight: 34 * s)
            Group {
                switch game {
                // The real board, cropped to its left end; it takes whatever height the card has.
                case .congklak: BoardPreview(onReady: onReady)
                case .ularTangga: UlarTanggaPreview(onReady: onReady)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 80 * s, maxHeight: .infinity)
            .clipShape(RoundedRectangle(cornerRadius: 14 * s))
            Text(game.tagline)
                .font(Theme.font(13 * s, .semibold))
                .foregroundStyle(Color(hex: 0xF3DDBE))
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.leading, 16 * s)
        .padding(.trailing, 16 * s)
        .padding(.top, 12 * s)
        .padding(.bottom, 14 * s)
        .frame(maxHeight: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 22 * s).fill(game.edge)
                .overlay(RoundedRectangle(cornerRadius: 22 * s).fill(game.face).padding(.bottom, 6 * s))
        )
    }
}

/// Ular tangga's rule options as toggle chips, saved as they change. Same as Android.
private struct UlarTanggaOptionChips: View {
    @AppStorage(UlarTanggaSettings.sixRollsAgainKey) private var sixRollsAgain = true
    @AppStorage(UlarTanggaSettings.sixToEnterKey) private var sixToEnter = false
    @AppStorage(UlarTanggaSettings.bounceBackKey) private var bounceBack = false
    @AppStorage(UlarTanggaSettings.firstFinishEndsKey) private var firstFinishEnds = false
    /// The chip tapped last; its meaning shows under the chips. Same as Android.
    @State private var last: Int?
    @Environment(\.uiScale) private var s

    var body: some View {
        VStack(alignment: .leading, spacing: 6 * s) {
            FlowRow(spacing: 6 * s) {
                chip(0, "6 lempar lagi", $sixRollsAgain)
                chip(1, "6 untuk mulai", $sixToEnter)
                chip(2, "Pantul di 100", $bounceBack)
                chip(3, "Juara 1 selesai", $firstFinishEnds)
            }
            RuleHint(text: hint)
        }
    }

    /// What the last tapped chip means now that it is on or off.
    private var hint: LocalizedStringKey {
        switch last {
        case 0: sixRollsAgain ? "6 lempar lagi: dapat 6, lempar dadu sekali lagi." : "6 lempar lagi mati: dapat 6, giliran tetap pindah."
        case 1: sixToEnter ? "6 untuk mulai: pion baru masuk ke kotak 1 kalau dapat 6." : "6 untuk mulai mati: pion langsung jalan dari start."
        case 2: bounceBack ? "Pantul di 100: kalau lemparan lewat dari 100, pion memantul mundur sisanya." : "Pantul di 100 mati: kalau lemparan lewat dari 100, pion diam di tempat."
        case 3: firstFinishEnds ? "Juara 1 selesai: permainan berakhir saat pemain pertama sampai 100." : "Juara 1 selesai mati: main terus sampai tinggal satu pemain."
        default: "Ketuk aturan untuk menyalakan atau mematikannya; penjelasannya muncul di sini."
        }
    }

    private func chip(_ index: Int, _ label: LocalizedStringKey, _ on: Binding<Bool>) -> some View {
        Button { last = index; on.wrappedValue.toggle() } label: {
            Text(label).font(Theme.font(13 * s)).foregroundStyle(on.wrappedValue ? Theme.accent : Theme.muted)
                .lineLimit(1)
                .padding(.horizontal, 12 * s)
                .frame(height: 34 * s)
                .background(on.wrappedValue ? Theme.chip : Theme.surface, in: RoundedRectangle(cornerRadius: 10 * s))
                .overlay(RoundedRectangle(cornerRadius: 10 * s).stroke(on.wrappedValue ? Theme.accent : Theme.line, lineWidth: 1.5))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on.wrappedValue ? .isSelected : [])
    }
}

/// A short explanation under a rule control. Same as Android.
private struct RuleHint: View {
    let text: LocalizedStringKey
    @Environment(\.uiScale) private var s

    var body: some View {
        Text(text).font(Theme.font(12 * s, .semibold)).foregroundStyle(Theme.muted)
            .lineLimit(2).fixedSize(horizontal: false, vertical: true)
    }
}

/// Lays its children out in rows, wrapping to a new row when one is full.
private struct FlowRow: Layout {
    var spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(width: proposal.width ?? .infinity, subviews: subviews)
        let width = rows.map { $0.width }.max() ?? 0
        let height = rows.map(\.height).reduce(0, +) + spacing * CGFloat(max(rows.count - 1, 0))
        return CGSize(width: proposal.width ?? width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in arrange(width: bounds.width, subviews: subviews) {
            var x = bounds.minX
            for i in row.items {
                let size = subviews[i].sizeThatFits(.unspecified)
                subviews[i].place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
            y += row.height + spacing
        }
    }

    private func arrange(width: CGFloat, subviews: Subviews) -> [(items: [Int], width: CGFloat, height: CGFloat)] {
        var rows: [(items: [Int], width: CGFloat, height: CGFloat)] = []
        for i in subviews.indices {
            let size = subviews[i].sizeThatFits(.unspecified)
            if let last = rows.last, last.width + spacing + size.width <= width {
                rows[rows.count - 1] = (last.items + [i], last.width + spacing + size.width, max(last.height, size.height))
            } else {
                rows.append(([i], size.width, size.height))
            }
        }
        return rows
    }
}

/// A nearby room: the lobby (or a guest's connecting card) until the game starts, then the board.
private struct RoomView: View {
    @ObservedObject var room: NearbyRoom
    /// The game ended or was left: back to the menu.
    let onExit: () -> Void
    /// Back from the lobby or the connecting card.
    let onLeave: () -> Void
    let onRetry: () -> Void

    var body: some View {
        if let table = room.congklak {
            CongklakView(table: table, onExit: onExit)
                .id(ObjectIdentifier(table))
        } else if let table = room.ular {
            UlarTanggaView(table: table, onExit: onExit)
                .id(ObjectIdentifier(table))
        } else if (room.isHost && !room.failed) || room.stage == .lobby {
            RoomLobby(room: room, onLeave: onLeave)
        } else {
            ConnectingScreen(room: room, onCancel: onLeave, onRetry: onRetry)
        }
    }
}
