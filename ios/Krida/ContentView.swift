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
    /// The loading screen at app start, over the menu while its board snapshot renders.
    @State private var starting = true
    @State private var menuReady = false
    /// Menu column height and where its content ends, for the "more below" hint.
    @State private var menuViewport: CGFloat = 0
    @State private var menuEnd: CGFloat = 0
    @State private var startMinDone = false
    @Environment(\.uiScale) private var s
    @Environment(\.punchHole) private var hole

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
                            passAndPlayCard
                            vsComputerCard
                            nearbyCard
                            tembakCard
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
                HStack(spacing: 8 * s) {
                    AdBanner()
                    RemoveAdsButton()
                }
                .padding(.trailing, max((hole?.trailing ?? 0) - 24 * s, 0))
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

    private var passAndPlayCard: some View {
        Button(action: startPassAndPlay) {
            ModeCard(icon: "person.2") {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Satu perangkat").font(Theme.font(18 * s))
                    Text("Berdua, gantian di satu HP").font(Theme.font(14 * s, .semibold)).foregroundStyle(Theme.muted)
                }
                Spacer(minLength: 8 * s)
                Image(systemName: "chevron.right").font(.system(size: 16 * s, weight: .bold)).foregroundStyle(Theme.muted)
            }
        }
        .buttonStyle(.plain)
    }

    private var vsComputerCard: some View {
        ModeCard(icon: "desktopcomputer") {
            VStack(alignment: .leading, spacing: 8 * s) {
                Text("Lawan komputer").font(Theme.font(18 * s)).lineLimit(1)
                LevelPicker(level: $level)
            }
            Spacer(minLength: 8 * s)
            Button("Mulai") { startVsComputer(level) }
                .buttonStyle(PrimaryButtonStyle(height: 48))
        }
    }

    private var nearbyCard: some View {
        ModeCard(icon: "antenna.radiowaves.left.and.right") {
            VStack(alignment: .leading, spacing: 8 * s) {
                HStack(alignment: .firstTextBaseline, spacing: 8 * s) {
                    Text("Main bareng dekat").font(Theme.font(18 * s))
                    Text("Bluetooth, tanpa internet").font(Theme.font(14 * s, .semibold)).foregroundStyle(Theme.muted)
                }
                .lineLimit(1)
                .minimumScaleFactor(0.8)
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
                            .buttonStyle(PrimaryButtonStyle())
                            .disabled(!RoomCode.shared.isValid(code: roomCode))
                            .opacity(RoomCode.shared.isValid(code: roomCode) ? 1 : 0.5)
                    }
                } else {
                    HStack(spacing: 8 * s) {
                        Button("Buat room") { room = NearbyRoom.host() }
                            .buttonStyle(PrimaryButtonStyle())
                        Button("Gabung pakai kode") { joining = true }
                            .buttonStyle(SecondaryButtonStyle())
                    }
                }
            }
            Spacer(minLength: 0)
        }
    }

    /// The tembak rule for every mode started here (and rooms this device hosts).
    private var tembakCard: some View {
        ModeCard(icon: "scope") {
            VStack(alignment: .leading, spacing: 8 * s) {
                Text("Aturan tembak").font(Theme.font(18 * s)).lineLimit(1)
                TembakPicker()
            }
            Spacer(minLength: 0)
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

/// White rounded card with an icon tile, used for each way to play.
private struct ModeCard<Content: View>: View {
    let icon: String
    @ViewBuilder let content: Content
    @Environment(\.uiScale) private var s

    var body: some View {
        HStack(spacing: 14 * s) {
            Image(systemName: icon)
                .font(.system(size: 20 * s, weight: .semibold))
                .foregroundStyle(Theme.accent)
                .frame(width: 44 * s, height: 44 * s)
                .background(Theme.chip, in: RoundedRectangle(cornerRadius: 14 * s))
            content
        }
        .padding(.leading, 18 * s)
        .padding(.trailing, 14 * s)
        .padding(.vertical, 14 * s)
        .frame(maxWidth: .infinity, minHeight: 76 * s, alignment: .leading)
        .background(.white, in: RoundedRectangle(cornerRadius: 20 * s))
        .overlay(RoundedRectangle(cornerRadius: 20 * s).stroke(Theme.line, lineWidth: 1.5))
        .contentShape(RoundedRectangle(cornerRadius: 20 * s))
    }
}

/// Mudah / Sedang / Sulit segmented picker.
private struct LevelPicker: View {
    @Binding var level: CongklakBot.Level
    @Environment(\.uiScale) private var s

    var body: some View {
        HStack(spacing: 2 * s) {
            option(.easy, "Mudah")
            option(.normal, "Sedang")
            option(.hard, "Sulit")
        }
        .padding(3 * s)
        .background(Theme.track, in: RoundedRectangle(cornerRadius: 12 * s))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text("Tingkat kesulitan"))
    }

    private func option(_ value: CongklakBot.Level, _ label: LocalizedStringKey) -> some View {
        let on = level == value
        return Button { level = value } label: {
            Text(label)
                .font(Theme.font(14 * s))
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                .foregroundStyle(on ? Theme.ink : Theme.muted)
                .padding(.horizontal, 12 * s)
                .frame(height: 38 * s)
                .background(on ? Color.white : .clear, in: RoundedRectangle(cornerRadius: 10 * s))
                .shadow(color: on ? Theme.ink.opacity(0.18) : .clear, radius: 1.5, y: 1)
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

/// The tembak rule for the next game: "Lewat lumbung" (the Indonesian rule, default:
/// only after this turn has passed your own store) or "Bebas". Stored on this device.
private struct TembakPicker: View {
    @AppStorage(CongklakSettings.tembakAfterStoreKey) private var afterStore = true
    @Environment(\.uiScale) private var s

    var body: some View {
        HStack(spacing: 2 * s) {
            option(true, "Lewat lumbung")
            option(false, "Bebas")
        }
        .padding(3 * s)
        .background(Theme.track, in: RoundedRectangle(cornerRadius: 12 * s))
        .lineLimit(1)
        .minimumScaleFactor(0.75)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text("Aturan tembak"))
    }

    private func option(_ value: Bool, _ label: LocalizedStringKey) -> some View {
        let on = afterStore == value
        return Button { afterStore = value } label: {
            Text(label)
                .font(Theme.font(14 * s))
                .foregroundStyle(on ? Theme.ink : Theme.muted)
                .padding(.horizontal, 14 * s)
                .frame(height: 38 * s)
                .background(on ? Color.white : .clear, in: RoundedRectangle(cornerRadius: 10 * s))
                .shadow(color: on ? Theme.ink.opacity(0.18) : .clear, radius: 1.5, y: 1)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
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
