import SwiftUI
import Shared

/// Menu: congklak pass-and-play or vs computer on this device, or a nearby room over BLE (host or join),
/// iOS and Android alike. Then the lobby, then the board.
struct ContentView: View {
    @Binding var roomCode: String
    @State private var passAndPlay: CongklakTable?
    @State private var room: NearbyRoom?
    @State private var level: CongklakBot.Level = .normal
    @State private var joining = false
    @State private var opponent: Opponent = .phone
    /// The tembak rule for every mode started here (and rooms this device hosts).
    @AppStorage(CongklakSettings.tembakAfterStoreKey) private var tembakAfterStore = true
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

    var body: some View {
        Group {
            if let table = passAndPlay {
                CongklakView(table: table) { passAndPlay = nil }
                    .id(ObjectIdentifier(table))
            } else if let room {
                RoomView(room: room) { self.room = nil }
                    .id(ObjectIdentifier(room))
            } else {
                menu
            }
        }
        // `-openCongklak` launch argument: straight to the board (simulator screenshots, UI tests).
        .onAppear { if ProcessInfo.processInfo.arguments.contains("-openCongklak") { startPassAndPlay() } }
        // A room link opens the app ready to join: the code field on "Teman dekat".
        .onAppear { if !roomCode.isEmpty { openJoin() } }
        .onChange(of: roomCode) { _, code in if !code.isEmpty && !joining { openJoin() } }
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
        opponent = .nearby
        joining = true
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
                // Light variant always: the menu stays cream even when the system is dark.
                Image("Wordmark").resizable().scaledToFit().frame(height: 40 * s).accessibilityLabel("Krida")
                    .environment(\.colorScheme, .light)
                    .padding(.leading, max((hole?.leading ?? 0) - 24 * s, 0))
                CongklakCard { menuReady = true }
                HStack { SoundToggles(); Spacer(minLength: 8 * s); LanguagePicker() }
            }
            .containerRelativeFrame(.horizontal) { width, _ in min(236 * s, width * 0.3) }
            VStack(spacing: 10 * s) {
                ScrollViewReader { proxy in
                    ScrollView {
                        VStack(spacing: 10 * s) {
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
        .background(Theme.cream)
        .foregroundStyle(Theme.ink)
    }

    /// The cards can end exactly at the column's bottom edge, so nothing tells a first-time
    /// player there is more below. While the column can still scroll: a cream fade over the
    /// bottom edge and a chevron that scrolls to the end.
    @ViewBuilder
    private func moreBelowHint(_ proxy: ScrollViewProxy) -> some View {
        let moreBelow = menuViewport > 0 && menuEnd > menuViewport + 1
        ZStack(alignment: .bottom) {
            LinearGradient(colors: [Theme.cream.opacity(0), Theme.cream], startPoint: .top, endPoint: .bottom)
                .frame(height: 44 * s)
                .allowsHitTesting(false)
            Button {
                withAnimation(.easeInOut(duration: 0.35)) { proxy.scrollTo("menuContent", anchor: .bottom) }
            } label: {
                Image(systemName: "chevron.down")
                    .font(.system(size: 15 * s, weight: .bold))
                    .foregroundStyle(Theme.accent)
                    .frame(width: 32 * s, height: 32 * s)
                    .background(.white, in: Circle())
                    .shadow(color: .black.opacity(0.15), radius: 2, y: 1)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("Pilihan lain di bawah"))
        }
        .opacity(moreBelow ? 1 : 0)
        .allowsHitTesting(moreBelow)
        .animation(.easeOut(duration: 0.2), value: moreBelow)
    }

    /// One card for every way to play congklak: pick the opponent (a friend on this phone, the
    /// computer, or a friend nearby over Bluetooth), the computer's level, the tembak rule, then
    /// Mulai. Joining someone else's room with a code is a link on the card for "Teman dekat".
    /// Same as Android.
    private var playCard: some View {
        VStack(alignment: .leading, spacing: 10 * s) {
            SettingRow(label: "Lawan") {
                Segmented(
                    options: [(.phone, "HP ini", "person.2"), (.computer, "Komputer", "desktopcomputer"), (.nearby, "Teman dekat", "antenna.radiowaves.left.and.right")],
                    selection: $opponent,
                    label: "Lawan"
                )
            }
            SettingRow(label: opponent == .computer ? "Tingkat" : nil) {
                switch opponent {
                case .computer:
                    Segmented(options: [(.easy, "Mudah", nil), (.normal, "Sedang", nil), (.hard, "Sulit", nil)], selection: $level, label: "Tingkat kesulitan")
                case .phone:
                    detail("Berdua, gantian di satu HP")
                case .nearby:
                    detail("Bluetooth, tanpa internet")
                }
            }
            SettingRow(label: "Tembak") {
                Segmented(options: [(true, "Lewat lumbung", nil), (false, "Bebas", nil)], selection: $tembakAfterStore, label: "Aturan tembak")
            }
            Rectangle().fill(Theme.line).frame(height: 1.5)
            if joining {
                HStack(spacing: 8 * s) {
                    TextField("Kode room", text: $roomCode)
                        .font(Theme.font(17 * s))
                        .tracking(2 * s)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .padding(.horizontal, 12 * s)
                        .frame(height: 44 * s)
                        .background(Color(hex: 0xFFFDF9), in: RoundedRectangle(cornerRadius: 12 * s))
                        .overlay(RoundedRectangle(cornerRadius: 12 * s).stroke(Color(hex: 0xD9C6AF), lineWidth: 1.5))
                        .accessibilityLabel(Text("Kode room dari temanmu"))
                        .onChange(of: roomCode) { _, code in
                            let clean = String(code.uppercased().prefix(4))
                            if clean != code { roomCode = clean }
                        }
                    Button { joining = false } label: {
                        Image(systemName: "xmark").font(.system(size: 15 * s, weight: .bold))
                            .foregroundStyle(Theme.muted)
                            .frame(width: 44 * s, height: 44 * s)
                            .background(Theme.track, in: RoundedRectangle(cornerRadius: 14 * s))
                    }
                    .accessibilityLabel(Text("Batal"))
                    Button("Masuk") { room = NearbyRoom.join(code: roomCode) }
                        .buttonStyle(PrimaryButtonStyle(height: 48))
                        .disabled(!RoomCode.shared.isValid(code: roomCode))
                        .opacity(RoomCode.shared.isValid(code: roomCode) ? 1 : 0.5)
                }
            } else {
                HStack(spacing: 8 * s) {
                    // Joining someone's room only makes sense when playing a friend nearby.
                    if opponent == .nearby {
                        Button("Gabung pakai kode") { joining = true }
                            .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                    }
                    Spacer(minLength: 0)
                    Button(opponent == .nearby ? "Buat room" : "Mulai", action: start)
                        .buttonStyle(PrimaryButtonStyle(height: 48))
                }
                .lineLimit(1)
            }
        }
        .padding(.leading, 18 * s)
        .padding(.trailing, 14 * s)
        .padding(.vertical, 14 * s)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.white, in: RoundedRectangle(cornerRadius: 20 * s))
        .overlay(RoundedRectangle(cornerRadius: 20 * s).stroke(Theme.line, lineWidth: 1.5))
    }

    private func detail(_ text: LocalizedStringKey) -> some View {
        Text(text).font(Theme.font(14 * s, .semibold)).foregroundStyle(Theme.muted).lineLimit(2)
    }

    private func start() {
        switch opponent {
        case .phone: startPassAndPlay()
        case .computer: startVsComputer(level)
        case .nearby: room = NearbyRoom.host()
        }
    }

    private func startVsComputer(_ level: CongklakBot.Level) {
        let name = switch level {
        case .easy: String(localized: "Mudah", bundle: .app)
        case .hard: String(localized: "Sulit", bundle: .app)
        default: String(localized: "Sedang", bundle: .app)
        }
        passAndPlay = HostTable.vsComputer(you: String(localized: "Kamu", bundle: .app), computer: String(localized: "Komputer", bundle: .app), level: level, levelName: name)
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
                .background(.white, in: RoundedRectangle(cornerRadius: 14 * s))
                .overlay(RoundedRectangle(cornerRadius: 14 * s).stroke(Theme.accent, lineWidth: 1.5))
            }
            .buttonStyle(.plain)
        }
    }
}

/// Who you play congklak against; the play card's first choice.
private enum Opponent { case phone, computer, nearby }

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
/// raised white chip, with an optional SF Symbol before its text.
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
            .background(on ? Color.white : .clear, in: RoundedRectangle(cornerRadius: 10 * s))
            .shadow(color: on ? Theme.ink.opacity(0.18) : .clear, radius: 1.5, y: 1)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// The congklak tile on the home screen, with a picture of the real board.
private struct CongklakCard: View {
    /// Called once the board snapshot is on screen.
    let onReady: () -> Void
    @Environment(\.uiScale) private var s

    var body: some View {
        VStack(alignment: .leading, spacing: 10 * s) {
            // A narrow card (a foldable's cover screen) drops "2 pemain" before shrinking the title.
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .firstTextBaseline) {
                    title
                    Spacer(minLength: 8 * s)
                    Text("2 pemain").font(Theme.font(13 * s, .bold)).foregroundStyle(Color(hex: 0xF3DDBE))
                }
                title
            }
            .lineLimit(1)
            // The real board, cropped to its left end; it takes whatever height the card has.
            BoardPreview(onReady: onReady)
                .frame(maxWidth: .infinity, minHeight: 80 * s, maxHeight: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: 14 * s))
            Text("Kumpulkan biji terbanyak di lumbungmu.")
                .font(Theme.font(13 * s, .semibold))
                .foregroundStyle(Color(hex: 0xF3DDBE))
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16 * s)
        .frame(maxHeight: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 22 * s).fill(Theme.woodShadow)
                .overlay(RoundedRectangle(cornerRadius: 22 * s).fill(Theme.wood).padding(.bottom, 6 * s))
        )
    }

    private var title: some View {
        Text("Congklak").font(Theme.font(22 * s, .black)).foregroundStyle(Color(hex: 0xFFF6E8))
    }
}

/// A nearby room: the lobby until the game starts, then the board.
private struct RoomView: View {
    @ObservedObject var room: NearbyRoom
    let onExit: () -> Void
    @Environment(\.uiScale) private var s

    var body: some View {
        if let table = room.table {
            CongklakView(table: table, onExit: onExit)
                .id(ObjectIdentifier(table))
        } else {
            lobby
        }
    }

    private var lobby: some View {
        VStack(alignment: .leading, spacing: 12 * s) {
            Text("Congklak").font(Theme.font(24 * s, .black))
            Text("Kode room").font(Theme.font(15 * s, .semibold))
            Text(room.code).font(.system(size: 44 * s, weight: .bold, design: .monospaced))
            Text(room.status).font(Theme.font(17 * s, .semibold))
            ForEach(room.players, id: \.seat) { p in
                Text("\(p.seat + 1). \(p.name)").font(Theme.font(17 * s, .semibold))
            }
            if room.isHost {
                Button("Mulai", action: room.start)
                    .buttonStyle(PrimaryButtonStyle())
                    .opacity(room.canStart ? 1 : 0.5)
                    .disabled(!room.canStart)
            }
            Button("Kembali") {
                room.leave()
                onExit()
            }
            .buttonStyle(SecondaryButtonStyle())
            Spacer()
            AdBanner()
        }
        .padding(24 * s)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .background(Theme.cream)
        .foregroundStyle(Theme.ink)
    }
}
