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
                CongklakCard()
                SoundToggles()
            }
            .containerRelativeFrame(.horizontal) { width, _ in min(236 * s, width * 0.3) }
            VStack(spacing: 10 * s) {
                ScrollView {
                    VStack(spacing: 10 * s) {
                        passAndPlayCard
                        vsComputerCard
                        nearbyCard
                        AdButtons()
                    }
                    .frame(maxWidth: .infinity)
                }
                .scrollBounceBehavior(.basedOnSize)
                AdBanner()
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

    private func startVsComputer(_ level: CongklakBot.Level) {
        let name = switch level {
        case .easy: String(localized: "Mudah")
        case .hard: String(localized: "Sulit")
        default: String(localized: "Sedang")
        }
        passAndPlay = HostTable.vsComputer(you: String(localized: "Kamu"), computer: String(localized: "Komputer"), level: level, levelName: name)
    }

    private func startPassAndPlay() {
        passAndPlay = HostTable.passAndPlay(names: [String(localized: "Pemain 1"), String(localized: "Pemain 2")])
    }
}

/// "Hapus iklan · Rp 15.000" and "Pulihkan pembelian" while ads are on; "Privasi iklan" where required.
private struct AdButtons: View {
    @ObservedObject private var ads = Ads.shared
    @Environment(\.uiScale) private var s

    var body: some View {
        if !ads.removed || ads.privacyOptionsRequired {
            HStack(spacing: 8 * s) {
                if let price = ads.removeAdsPrice {
                    Button("Hapus iklan · \(price)", action: ads.buyRemoveAds)
                        .buttonStyle(SecondaryButtonStyle())
                }
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
            BoardPreview()
                .frame(maxWidth: .infinity, minHeight: 80 * s, maxHeight: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: 14 * s))
            Text("Kumpulkan biji terbanyak di lumbungmu.")
                .font(Theme.font(13 * s, .semibold))
                .foregroundStyle(Color(hex: 0xF3DDBE))
                .fixedSize(horizontal: false, vertical: true)
            TembakPicker()
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

/// The tembak rule for the next game (pass-and-play, vs computer, or a room you host):
/// "Lewat lumbung" (the Indonesian rule, default) or "Bebas". Stored on this device.
private struct TembakPicker: View {
    @AppStorage(CongklakSettings.tembakAfterStoreKey) private var afterStore = true
    @Environment(\.uiScale) private var s

    var body: some View {
        HStack(spacing: 8 * s) {
            Text("Tembak").font(Theme.font(13 * s, .bold)).foregroundStyle(Color(hex: 0xF3DDBE))
            HStack(spacing: 2 * s) {
                option(true, "Lewat lumbung")
                option(false, "Bebas")
            }
            .padding(3 * s)
            .background(Theme.woodDark, in: RoundedRectangle(cornerRadius: 11 * s))
        }
        .lineLimit(1)
        .minimumScaleFactor(0.75)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text("Aturan tembak"))
    }

    private func option(_ value: Bool, _ label: LocalizedStringKey) -> some View {
        let on = afterStore == value
        return Button {
            Sounds.shared.play(.tap)
            afterStore = value
        } label: {
            Text(label)
                .font(Theme.font(13 * s))
                .foregroundStyle(on ? Theme.ink : Color(hex: 0xF3DDBE))
                .padding(.horizontal, 10 * s)
                .frame(maxWidth: .infinity)
                .frame(height: 32 * s)
                .background(on ? Theme.seed : .clear, in: RoundedRectangle(cornerRadius: 8 * s))
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
