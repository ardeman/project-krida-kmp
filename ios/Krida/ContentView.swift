import SwiftUI
import Shared

/// Menu: congklak pass-and-play or vs computer on this device, or a nearby room over BLE (host or join),
/// iOS and Android alike. Then the lobby, then the board.
struct ContentView: View {
    @Binding var roomCode: String
    @State private var passAndPlay: CongklakTable?
    @State private var ularTable: UlarTanggaTable?
    @State private var ludoTable: LudoTable?
    /// The game on the left card; the play card follows it.
    @State private var game: MenuGame = .congklak
    /// Ular tangga's seat tiles for a game on this phone, as `LocalSeats.encode` text.
    @AppStorage(UlarTanggaSettings.seatsKey) private var ularSeatsText = ""
    /// Ludo's seat tiles for a game on this phone, as `LocalSeats.encode` text.
    @AppStorage(LudoSettings.seatsKey) private var ludoSeatsText = ""
    /// Congklak's seats: the first is always a person, the second a person or the computer.
    @State private var congklakSeats: [LocalSeat] = [.person, .person]
    @State private var room: NearbyRoom?
    @State private var level: CongklakBot.Level = .normal
    /// The join screen (scan the host's QR or type the code) is up.
    @State private var joining = false
    /// The join screen takes a 6-letter online code instead of a 4-letter nearby one.
    @State private var joinOnline = false
    @State private var creatingOnlineRoom = false
    @State private var showingAccount = false
    @State private var playingQuickMatch = false
    @State private var quickMatchGame: String = CongklakGame.shared.id
    /// Total seats the join screen searches for.
    @State private var quickMatchTarget = 4
    /// Under the join screen's code: the scanner can't open or read another QR.
    @State private var joinError: LocalizedStringKey?
    @State private var scanning = false
    @State private var place: Place = .phone
    /// Lone person vs computers on this phone: preferred pawn colour (`-1` = seat colour).
    @State private var personColor = -1
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

    /// Board or room (lobby / connecting): keep the screen awake between turns and while waiting.
    private var keepAwake: Bool {
        passAndPlay != nil || ularTable != nil || ludoTable != nil || room != nil
    }

    var body: some View {
        Group {
            if let table = passAndPlay {
                CongklakView(table: table) { passAndPlay = nil }
                    .id(ObjectIdentifier(table))
            } else if let table = ularTable {
                UlarTanggaView(table: table) { ularTable = nil }
                    .id(ObjectIdentifier(table))
            } else if let table = ludoTable {
                LudoView(table: table) { ludoTable = nil }
                    .id(ObjectIdentifier(table))
            } else if let room {
                RoomView(
                    room: room,
                    onExit: { self.room = nil; joining = false },
                    onLeave: { leaveRoom(room) },
                    onRetry: { self.room = room.retry() }
                )
                .id(ObjectIdentifier(room))
            } else if creatingOnlineRoom {
                CreateOnlineRoomView(
                    game: quickMatchGame,
                    onBack: { creatingOnlineRoom = false },
                    onCreate: { publicRoom, seats in
                        room = NearbyRoom.host(game: roomGame(quickMatchGame),
                                               online: true, publicRoom: publicRoom, seats: seats)
                        creatingOnlineRoom = false
                    }
                ) {
                    GameRoomOptions(rules: rules(for: quickMatchGame))
                }
            } else if playingQuickMatch {
                PlayOnlineView(
                    game: quickMatchGame,
                    target: quickMatchTarget,
                    onCancel: { playingQuickMatch = false },
                    createHost: { code, humans, bots, onReady in
                        let host = NearbyRoom.host(
                            game: roomGame(quickMatchGame),
                            online: true, code: code, onOnlineReady: onReady, standardRules: true
                        )
                        host.configureQuickMatch(humans: humans, bots: bots, botName: String(localized: "Computer", bundle: .app))
                        return host
                    },
                    onHost: { matchedRoom in
                        room = matchedRoom
                        playingQuickMatch = false
                    },
                    onJoin: { code, reservation in
                        room = NearbyRoom.join(code: code, online: true, reservation: reservation)
                        playingQuickMatch = false
                    },
                    onComputer: { count in
                        if quickMatchGame == UlarTanggaGame.shared.id || quickMatchGame == LudoGame.shared.id {
                            let you = String(localized: "You", bundle: .app)
                            let computer = String(localized: "Computer", bundle: .app)
                            let players = (0..<count).map { seat in
                                LocalPlayer(name: seat == 0 ? you : computer, computer: seat != 0, color: seat)
                            }
                            if quickMatchGame == LudoGame.shared.id {
                                ludoTable = LudoHostTable.local(players: players)
                            } else {
                                ularTable = UlarTanggaHostTable.local(players: players)
                            }
                        } else {
                            startVsComputer(.normal)
                        }
                        playingQuickMatch = false
                    }
                )
            } else if joining && joinOnline {
                JoinOnlineRoomView(
                    game: quickMatchGame, code: $roomCode, error: joinError,
                    onScan: scanRoomQr, onJoin: joinRoom,
                    onSearch: { target in quickMatchTarget = target; playingQuickMatch = true },
                    onBack: { joining = false; joinError = nil }
                )
            } else if joining {
                JoinScreen(code: $roomCode, online: joinOnline, error: joinError, onScan: scanRoomQr, onJoin: joinRoom, onBack: { joining = false; joinError = nil })
            } else if showingAccount {
                AccountScreen(onBack: { showingAccount = false })
            } else {
                menu
            }
        }
        .onAppear { UIApplication.shared.isIdleTimerDisabled = keepAwake }
        .onChange(of: keepAwake) { _, on in UIApplication.shared.isIdleTimerDisabled = on }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        // `-openCongklak` launch argument: straight to the board (simulator screenshots, UI tests).
        .onAppear { if ProcessInfo.processInfo.arguments.contains("-openCongklak") { startPassAndPlay() } }
        .onAppear { if ProcessInfo.processInfo.arguments.contains("-openUlarTangga") { game = .ularTangga; startUlarTangga() } }
        .onAppear { if ProcessInfo.processInfo.arguments.contains("-openLudo") { game = .ludo; startLudo() } }
        #if DEBUG
        // Direct entry for quick-match screenshots and native flow checks.
        .onAppear {
            let args = ProcessInfo.processInfo.arguments
            if let index = args.firstIndex(of: "-openQuickMatch"), args.indices.contains(index + 1) {
                let selected = args[index + 1]
                if [CongklakGame.shared.id, UlarTanggaGame.shared.id, LudoGame.shared.id].contains(selected) {
                    quickMatchGame = selected
                    quickMatchTarget = selected == CongklakGame.shared.id ? 2 : 4
                    playingQuickMatch = true
                }
            }
        }
        #endif
        // A friend link (https://krida.ardeman.com/f/K7M2XQ4D or krida://friend?code=…) opens
        // the Account screen with the code filled in. A running game or lobby stays; the
        // link only applies from the menus.
        .onOpenURL { url in
            let onMenus = passAndPlay == nil && ularTable == nil && ludoTable == nil && room == nil
                && !creatingOnlineRoom && !playingQuickMatch
            guard onMenus, Account.shared.handleFriendLink(url) else { return }
            joining = false
            joinError = nil
            showingAccount = true
        }
        // A room link opens the app ready to join: the code field on "Nearby" or "Online".
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
                        joinError = "That QR isn't a Krida room"
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
            if await QrScannerView.available() { scanning = true } else { joinError = "Couldn't open the QR scanner" }
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
                HStack {
                    Image("Wordmark").resizable().scaledToFit().frame(height: 40 * s).accessibilityLabel("Krida")
                        .padding(.leading, max((hole?.leading ?? 0) - 24 * s, 0))
                    Spacer(minLength: 8 * s)
                    CircleIconButton(systemImage: "person.fill", label: "Account") { showingAccount = true }
                }
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
            .accessibilityLabel(Text("More options below"))
        }
        .opacity(moreBelow ? 1 : 0)
        .allowsHitTesting(moreBelow)
        .animation(.easeOut(duration: 0.2), value: moreBelow)
    }

    /// One card for every way to play: on this phone (seat tiles for people and computers) or
    /// with friends nearby over Bluetooth or online; right below it what that means (or the computer's
    /// level) with Mulai, and Gabung for "Nearby"; then the game's rules.
    /// Same as Android.
    /// A running game this device left as a guest: go back to the same seat, or forget it.
    private func rejoinCard(_ saved: SavedRoom) -> some View {
        let name = switch saved.game {
        case UlarTanggaGame.shared.id: String(localized: "Snakes & ladders", bundle: .app)
        case LudoGame.shared.id: String(localized: "Ludo", bundle: .app)
        default: String(localized: "Congklak", bundle: .app)
        }
        return HStack(spacing: 8 * s) {
            VStack(alignment: .leading, spacing: 2 * s) {
                Text("\(name) is still on").font(Theme.font(15 * s)).lineLimit(1)
                detail("Room \(saved.code) · your seat is waiting")
            }
            Spacer(minLength: 0)
            Button("Forget") { lastRoom.clear() }
                .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
            Button("Rejoin") {
                // Backing out of a failed rejoin lands on the join screen, as after a typed code.
                joining = true
                joinOnline = saved.online
                quickMatchGame = saved.game
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
        let ludo = game == .ludo
        let multi = ular || ludo
        // A room with other phones, over Bluetooth or the internet.
        let nearby = place != .phone
        let online = place == .online
        let seats = ludo ? ludoSeats : ular ? ularSeats : congklakSeats
        let computers = seats.filter { $0 == .computer }.count
        let people = seats.filter { $0 == .person }.count
        let seatColors: [Int] = multi
            ? LocalSeats.shared.colors(seats: seats, personColor: Int32(people == 1 ? personColor : -1), colorCount: 4).map(\.intValue)
            : []
        // Congklak has no seat colours; only index seatColors for ular/ludo.
        let myColor = seats.indices.first { seats[$0] == .person && $0 < seatColors.count }.map { seatColors[$0] }
        let computerColors = Set(seats.indices.compactMap { i -> Int? in
            i < seatColors.count && seatColors[i] >= 0 && seats[i] == .computer ? seatColors[i] : nil
        })
        let places: [(Place, LocalizedStringKey, String?)] = [
            (.phone, "This phone", "person.2"), (.nearby, "Nearby", "antenna.radiowaves.left.and.right"),
            (.online, "Online", "globe"),
        ]
        return VStack(alignment: .leading, spacing: 10 * s) {
            SettingRow(label: "Play on") {
                Segmented(options: places, selection: $place, label: "Play on")
            }
            // In a nearby room everyone who joins plays, up to 4.
            if !nearby {
                SettingRow(label: "Players") {
                    SeatTiles(
                        seats: seats,
                        colors: multi
                            ? seats.indices.map { seatColors[$0] >= 0 ? pawnColor(seatColors[$0]) : Theme.border }
                            : [Theme.wood, Theme.seed],
                        tappable: multi ? Set(seats.indices) : [1]
                    ) { i in
                        if ludo {
                            ludoSeatsText = LocalSeats.shared.encode(seats: LocalSeats.shared.next(seats: seats, index: Int32(i), allowEmpty: true))
                        } else if ular {
                            ularSeatsText = LocalSeats.shared.encode(seats: LocalSeats.shared.next(seats: seats, index: Int32(i), allowEmpty: true))
                        } else {
                            congklakSeats = LocalSeats.shared.next(seats: congklakSeats, index: Int32(i), allowEmpty: false)
                        }
                    }
                }
            }
            if !nearby, multi, people == 1, computers > 0 {
                LocalColorRow(mine: myColor, computerColors: computerColors) { personColor = $0 }
            }
            // The description above equally sized Join/Create buttons, as on Android. Online
            // rules move to the Create room screen.
            SettingRow(label: !multi && !nearby && computers > 0 ? "Level" : nil) {
                VStack(alignment: .leading, spacing: 8 * s) {
                    if online {
                        detail(multi ? "Up to 4 phones over the internet, friends anywhere" : "Over the internet, friends anywhere")
                    } else if nearby {
                        detail(multi ? "Up to 4 phones over Bluetooth, no internet" : "Bluetooth, no internet")
                    } else if !multi && computers > 0 {
                        Segmented(options: [(.easy, "Easy", nil), (.normal, "Medium", nil), (.hard, "Hard", nil)], selection: $level, label: "Difficulty")
                    } else if !multi {
                        detail("Two players, taking turns on one phone")
                    } else if computers == 0 {
                        detail("Pass the phone round; everyone rolls on their turn.")
                    } else if people == 1 {
                        detail("You against the computer.")
                    } else {
                        detail("Pass the phone round; computers roll on their own.")
                    }
                    HStack(spacing: 10 * s) {
                        if nearby {
                            Button {
                                if joinOnline != online { roomCode = "" }
                                joinOnline = online
                                quickMatchGame = gameId(game)
                                joining = true
                            } label: {
                                Text(online ? "Join a room" : "Join").frame(maxWidth: .infinity)
                            }
                            .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                        }
                        Button(action: start) {
                            Text(nearby ? "Create room" : "Start").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(PrimaryButtonStyle())
                    }
                }
            }
            if !online { GameRoomOptions(rules: rules(for: game)) }
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

    /// Ludo's seat tiles. The first two start as people; empty seats are skipped.
    private var ludoSeats: [LocalSeat] {
        let fallback = (0..<4).map { $0 < 2 ? LocalSeat.person : LocalSeat.empty }
        return LocalSeats.shared.decode(text: ludoSeatsText.isEmpty ? nil : ludoSeatsText, size: 4, fallback: fallback)
    }

    private func gameId(_ menu: MenuGame) -> String {
        switch menu {
        case .ludo: LudoGame.shared.id
        case .ularTangga: UlarTanggaGame.shared.id
        case .congklak: CongklakGame.shared.id
        }
    }

    private func roomGame(_ id: String) -> NearbyRoom.Game {
        if id == LudoGame.shared.id { return .ludo }
        if id == UlarTanggaGame.shared.id { return .ularTangga }
        return .congklak
    }

    private func rules(for id: String) -> RoomRules {
        if id == LudoGame.shared.id { return .ludo }
        if id == UlarTanggaGame.shared.id { return .ularTangga }
        return .congklak
    }

    private func rules(for menu: MenuGame) -> RoomRules {
        switch menu {
        case .ludo: .ludo
        case .ularTangga: .ularTangga
        case .congklak: .congklak
        }
    }

    private func start() {
        if place == .online {
            quickMatchGame = gameId(game)
            creatingOnlineRoom = true
            return
        }
        if place == .nearby {
            room = NearbyRoom.host(game: roomGame(gameId(game)))
            return
        }
        if game == .ludo { return startLudo() }
        if game == .ularTangga { return startUlarTangga() }
        if congklakSeats.contains(.computer) { startVsComputer(level) } else { startPassAndPlay() }
    }

    private func startVsComputer(_ level: CongklakBot.Level) {
        let name = switch level {
        case .easy: String(localized: "Easy", bundle: .app)
        case .hard: String(localized: "Hard", bundle: .app)
        default: String(localized: "Medium", bundle: .app)
        }
        passAndPlay = HostTable.vsComputer(you: String(localized: "You", bundle: .app), computer: String(localized: "Computer", bundle: .app), level: level, levelName: name)
    }

    private func localPlayers(_ seats: [LocalSeat]) -> [LocalPlayer] {
        let computers = seats.filter { $0 == .computer }.count
        let people = seats.filter { $0 == .person }.count
        let colorNames = [String(localized: "Red", bundle: .app), String(localized: "Blue", bundle: .app), String(localized: "Green", bundle: .app), String(localized: "Yellow", bundle: .app)]
        let seatColors = LocalSeats.shared.colors(
            seats: seats,
            personColor: Int32(people == 1 ? personColor : -1),
            colorCount: 4,
        ).map(\.intValue)
        var nextComputer = 0
        var players: [LocalPlayer] = []
        for (i, kind) in seats.enumerated() {
            let color = seatColors[i]
            guard color >= 0 else { continue }
            switch kind {
            case .computer:
                nextComputer += 1
                let name = computers == 1 ? String(localized: "Computer", bundle: .app) : String(localized: "Computer \(nextComputer)", bundle: .app)
                players.append(LocalPlayer(name: name, computer: true, color: color))
            case .person:
                // A lone person is "You"; people passing the phone go by their pawn colour.
                let name = people == 1 ? String(localized: "You", bundle: .app) : colorNames[color]
                players.append(LocalPlayer(name: name, computer: false, color: color))
            default: break
            }
        }
        return players
    }

    private func startUlarTangga() {
        ularTable = UlarTanggaHostTable.local(players: localPlayers(ularSeats))
    }

    private func startLudo() {
        ludoTable = LudoHostTable.local(players: localPlayers(ludoSeats))
    }

    private func startPassAndPlay() {
        passAndPlay = HostTable.passAndPlay(names: [String(localized: "Player 1", bundle: .app), String(localized: "Player 2", bundle: .app)])
    }
}

/// "Restore purchases" while ads are on; "Ad privacy" where required. "Remove ads" sits beside the banner.
private struct AdButtons: View {
    @ObservedObject private var ads = Ads.shared
    @Environment(\.uiScale) private var s

    var body: some View {
        if !ads.removed || ads.privacyOptionsRequired {
            HStack(spacing: 8 * s) {
                if !ads.removed {
                    Button("Restore purchases", action: ads.restorePurchases)
                        .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                }
                if ads.privacyOptionsRequired {
                    Button("Ad privacy", action: ads.showPrivacyOptions)
                        .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                }
                Spacer(minLength: 0)
            }
            .lineLimit(1)
            .minimumScaleFactor(0.8)
        }
    }
}

/// "Remove ads" beside the banner where the ad is: the cards above keep the full height.
/// The price shows in the App Store sheet, not on the button. Only while the banner shows and
/// the App Store knows the product. Same as Android.
private struct RemoveAdsButton: View {
    @ObservedObject private var ads = Ads.shared
    @Environment(\.uiScale) private var s

    var body: some View {
        if !ads.removed && ads.started && ads.removeAdsPrice != nil {
            Button(action: ads.buyRemoveAds) {
                Text("Remove ads").font(Theme.font(14 * s)).foregroundStyle(Theme.accent)
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

/// Pawn colours for a lone person vs computers on this phone. Same as Android.
private struct LocalColorRow: View {
    let mine: Int?
    let computerColors: Set<Int>
    let onPick: (Int) -> Void
    @Environment(\.uiScale) private var s

    private let names = [
        String(localized: "Red", bundle: .app),
        String(localized: "Blue", bundle: .app),
        String(localized: "Green", bundle: .app),
        String(localized: "Yellow", bundle: .app),
    ]

    var body: some View {
        HStack(spacing: 12 * s) {
            Text("Your pawn color").font(Theme.font(13 * s, .heavy)).foregroundStyle(Theme.muted)
                .frame(maxWidth: .infinity, alignment: .leading)
            ForEach(0..<4, id: \.self) { c in
                let color = pawnColor(c)
                let name = names[c]
                let selected = c == mine
                let computer = computerColors.contains(c)
                let label: String = if selected {
                    String(localized: "\(name), chosen", bundle: .app)
                } else if computer {
                    String(localized: "\(name), switch with the computer", bundle: .app)
                } else {
                    name
                }
                Button {
                    Sounds.shared.play(.tap)
                    onPick(c)
                } label: {
                    ZStack {
                        Circle().fill(color).padding(selected ? 4 * s : 0)
                        if selected {
                            Circle().stroke(Theme.ink, lineWidth: 2)
                            Image(systemName: "checkmark").font(.system(size: 18 * s, weight: .black))
                                .foregroundStyle(inkOn(color))
                        } else if computer {
                            Image(systemName: "desktopcomputer").font(.system(size: 16 * s, weight: .bold))
                                .foregroundStyle(inkOn(color))
                        }
                    }
                    .frame(width: 44 * s, height: 44 * s)
                }
                .buttonStyle(.plain)
                .disabled(selected)
                .accessibilityLabel(label)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(.leading, 12 * s)
        .padding(.trailing, 10 * s)
        .padding(.vertical, 4 * s)
        .background(Theme.surfaceSoft, in: RoundedRectangle(cornerRadius: 14 * s))
        .overlay(RoundedRectangle(cornerRadius: 14 * s).stroke(Theme.line, lineWidth: 1))
    }

    private func inkOn(_ color: Color) -> Color {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        UIColor(color).getRed(&r, green: &g, blue: &b, alpha: &a)
        return (0.3 * r + 0.59 * g + 0.11 * b) > 0.5 ? Theme.inkOnLight : .white
    }
}

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
        case .person: ("Person", "person.2.fill")
        case .computer: ("Computer", "desktopcomputer")
        default: ("Empty", "plus")
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
        .accessibilityHint(tappable.contains(i) ? Text("Change") : Text(""))
    }

    /// Dark ink on a light circle (yellow pawn, seed), white on the rest.
    private func light(_ color: Color) -> Bool {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0
        UIColor(color).getRed(&r, green: &g, blue: &b, alpha: nil)
        return r * 0.3 + g * 0.59 + b * 0.11 > 0.6
    }
}

/// A small label above its control; no label keeps the rows' spacing. Same as Android.
/// Which game's rule controls the play card and the online create screen show.
enum RoomRules { case congklak, ularTangga, ludo }

/// The game's rule options (ular tangga or ludo chips; congklak tembak and starter), saved as they
/// change: on the play card for this phone and nearby, on Create room online.
struct GameRoomOptions: View {
    let rules: RoomRules
    @AppStorage(CongklakSettings.tembakAfterStoreKey) private var tembakAfterStore = true
    @AppStorage(CongklakSettings.starterKey) private var starter = "random"
    @Environment(\.uiScale) private var s

    var body: some View {
        switch rules {
        case .ularTangga:
            SettingRow(label: "Rules") { UlarTanggaOptionChips() }
        case .ludo:
            SettingRow(label: "Rules") { LudoOptionChips() }
        case .congklak:
            Group {
                SettingRow(label: "Shooting") {
                    VStack(alignment: .leading, spacing: 6 * s) {
                        Segmented(options: [(true, "Past store", nil), (false, "Anytime", nil)], selection: $tembakAfterStore, label: "Shooting rule")
                        RuleHint(text: tembakAfterStore
                            ? "A capture (tembak) only counts once your seeds have passed your own store this turn."
                            : "Capture anytime: your last seed in an empty hole on your side takes the seeds in the hole opposite.")
                    }
                }
                SettingRow(label: "Goes first") {
                    Segmented(options: [("me", "Me", nil), ("opponent", "Opponent", nil), ("random", "Random", nil)], selection: $starter, label: "Who goes first")
                }
            }
        }
    }
}

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
struct Segmented<T: Hashable>: View {
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
    case congklak, ularTangga, ludo

    var title: LocalizedStringKey {
        switch self {
        case .congklak: "Congklak"
        case .ularTangga: "Snakes & ladders"
        case .ludo: "Ludo"
        }
    }
    var players: LocalizedStringKey { self == .congklak ? "2 players" : "2–4 players" }
    var tagline: LocalizedStringKey {
        switch self {
        case .congklak: "Gather the most seeds in your store."
        case .ularTangga: "Climb the ladders, dodge the snakes."
        case .ludo: "Race your four tokens home."
        }
    }
    var face: Color {
        switch self {
        case .congklak: Theme.wood
        case .ularTangga: Color(hex: 0x4F8A3B)
        case .ludo: Color(hex: 0x3D6EB5)
        }
    }
    var edge: Color {
        switch self {
        case .congklak: Theme.woodShadow
        case .ularTangga: Color(hex: 0x3B6A2B)
        case .ludo: Color(hex: 0x2A5085)
        }
    }
}

/// The game tile on the home screen, with a picture of the real board; the arrows (or a
/// swipe) switch to the other game, and the play card on the right follows.
private struct GameCard: View {
    @Binding var game: MenuGame
    /// Called once the first board snapshot is on screen.
    let onReady: () -> Void
    @Environment(\.uiScale) private var s

    var body: some View {
        ZStack(alignment: .topTrailing) {
            // Page-style TabView so a swipe follows the finger, same feel as Android's pager.
            TabView(selection: $game) {
                ForEach(MenuGame.allCases, id: \.self) { g in
                    GameCardFace(game: g, onReady: onReady)
                        .tag(g)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .animation(.easeInOut(duration: 0.42), value: game)
            // The arrows stay put above the sliding cards.
            HStack(spacing: 6 * s) {
                arrow("chevron.left", label: "Previous game") { step(-1) }
                arrow("chevron.right", label: "Next game") { step(1) }
            }
            .padding(.top, 12 * s)
            .padding(.trailing, 10 * s)
        }
        .clipShape(RoundedRectangle(cornerRadius: 22 * s))
        .frame(maxHeight: .infinity)
    }

    private func step(_ d: Int) {
        let all = MenuGame.allCases
        let i = all.firstIndex(of: game)!
        withAnimation(.easeInOut(duration: 0.42)) { game = all[(i + d + all.count) % all.count] }
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
                case .ludo: LudoPreview(onReady: onReady)
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
                chip(0, "6 rolls again", $sixRollsAgain)
                chip(1, "6 to start", $sixToEnter)
                chip(2, "Bounce off 100", $bounceBack)
                chip(3, "First to 100 ends", $firstFinishEnds)
            }
            RuleHint(text: hint)
        }
    }

    /// What the last tapped chip means now that it is on or off.
    private var hint: LocalizedStringKey {
        switch last {
        case 0: sixRollsAgain ? "6 rolls again: roll a 6 and you roll once more." : "6 rolls again off: a 6 ends your turn like any roll."
        case 1: sixToEnter ? "6 to start: a pawn enters the board on square 1 only with a 6." : "6 to start off: pawns move off the start with any roll."
        case 2: bounceBack ? "Bounce off 100: a roll past 100 walks back the extra squares." : "Bounce off 100 off: a roll past 100 leaves the pawn where it is."
        case 3: firstFinishEnds ? "First to 100 ends: the game ends when the first player reaches 100." : "First to 100 ends off: play goes on until one player is left."
        default: "Tap a rule to turn it on or off; it is explained here."
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

/// Ludo's rule options as toggle chips, saved as they change. Same as Android.
private struct LudoOptionChips: View {
    @AppStorage(LudoSettings.sixToEnterKey) private var sixToEnter = true
    @AppStorage(LudoSettings.sixRollsAgainKey) private var sixRollsAgain = true
    @AppStorage(LudoSettings.captureRollsAgainKey) private var captureRollsAgain = true
    @AppStorage(LudoSettings.firstFinishEndsKey) private var firstFinishEnds = false
    /// The chip tapped last; its meaning shows under the chips. Same as Android.
    @State private var last: Int?
    @Environment(\.uiScale) private var s

    var body: some View {
        VStack(alignment: .leading, spacing: 6 * s) {
            FlowRow(spacing: 6 * s) {
                chip(0, "6 to enter", $sixToEnter)
                chip(1, "6 again", $sixRollsAgain)
                chip(2, "Capture again", $captureRollsAgain)
                chip(3, "First wins", $firstFinishEnds)
            }
            RuleHint(text: hint)
        }
    }

    private var hint: LocalizedStringKey {
        switch last {
        case 0: sixToEnter
            ? "6 to enter: a token leaves the yard only with a 6."
            : "6 to enter off: any roll can leave the yard and walk."
        case 1: sixRollsAgain
            ? "6 again: roll a 6 and you roll once more."
            : "6 again off: a 6 ends your turn like any roll."
        case 2: captureRollsAgain
            ? "Capture again: sending an opponent home grants another roll."
            : "Capture again off: a capture ends your turn."
        case 3: firstFinishEnds
            ? "First wins: the game ends when the first player gets all four home."
            : "First wins off: play goes on until one player is left."
        default: "Tap a rule to turn it on or off; it is explained here."
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
        } else if let table = room.ludo {
            LudoView(table: table, onExit: onExit)
                .id(ObjectIdentifier(table))
        } else if (room.isHost && !room.failed) || room.stage == .lobby {
            RoomLobby(room: room, onLeave: onLeave)
        } else {
            ConnectingScreen(room: room, onCancel: onLeave, onRetry: onRetry)
        }
    }
}
