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

    private var menu: some View {
        HStack(alignment: .top, spacing: 24) {
            VStack(alignment: .leading, spacing: 14) {
                // Light variant always: the menu stays cream even when the system is dark.
                Image("Wordmark").resizable().scaledToFit().frame(height: 40).accessibilityLabel("Krida")
                    .environment(\.colorScheme, .light)
                CongklakCard()
                SoundToggles()
            }
            .frame(width: 236)
            VStack(spacing: 10) {
                ScrollView {
                    VStack(spacing: 10) {
                        passAndPlayCard
                        vsComputerCard
                        nearbyCard
                        AdButtons()
                    }
                    .frame(maxWidth: .infinity)
                }
                .scrollBounceBehavior(.basedOnSize)
                AdBanner()
            }
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Theme.cream)
        .foregroundStyle(Theme.ink)
    }

    private var passAndPlayCard: some View {
        Button(action: startPassAndPlay) {
            ModeCard(icon: "person.2") {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Satu perangkat").font(Theme.font(18))
                    Text("Berdua, gantian di satu HP").font(Theme.font(14, .semibold)).foregroundStyle(Theme.muted)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right").font(.system(size: 16, weight: .bold)).foregroundStyle(Theme.muted)
            }
        }
        .buttonStyle(.plain)
    }

    private var vsComputerCard: some View {
        ModeCard(icon: "desktopcomputer") {
            VStack(alignment: .leading, spacing: 8) {
                Text("Lawan komputer").font(Theme.font(18)).lineLimit(1)
                LevelPicker(level: $level)
            }
            Spacer(minLength: 8)
            Button("Mulai") { startVsComputer(level) }
                .buttonStyle(PrimaryButtonStyle(height: 48))
        }
    }

    private var nearbyCard: some View {
        ModeCard(icon: "antenna.radiowaves.left.and.right") {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text("Main bareng dekat").font(Theme.font(18))
                    Text("Bluetooth, tanpa internet").font(Theme.font(14, .semibold)).foregroundStyle(Theme.muted)
                }
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                if joining {
                    HStack(spacing: 8) {
                        TextField("Kode room", text: $roomCode)
                            .font(Theme.font(17))
                            .tracking(2)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                            .padding(.horizontal, 12)
                            .frame(height: 44)
                            .background(Color(hex: 0xFFFDF9), in: RoundedRectangle(cornerRadius: 12))
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(hex: 0xD9C6AF), lineWidth: 1.5))
                            .accessibilityLabel(Text("Kode room dari temanmu"))
                            .onChange(of: roomCode) { _, code in
                                let clean = String(code.uppercased().prefix(4))
                                if clean != code { roomCode = clean }
                            }
                        Button { joining = false } label: {
                            Image(systemName: "xmark").font(.system(size: 15, weight: .bold))
                                .foregroundStyle(Theme.muted)
                                .frame(width: 44, height: 44)
                                .background(Theme.track, in: RoundedRectangle(cornerRadius: 14))
                        }
                        .accessibilityLabel(Text("Batal"))
                        Button("Masuk") { room = NearbyRoom.join(code: roomCode) }
                            .buttonStyle(PrimaryButtonStyle())
                            .disabled(!RoomCode.shared.isValid(code: roomCode))
                            .opacity(RoomCode.shared.isValid(code: roomCode) ? 1 : 0.5)
                    }
                } else {
                    HStack(spacing: 8) {
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

    var body: some View {
        if !ads.removed || ads.privacyOptionsRequired {
            HStack(spacing: 8) {
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

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(Theme.accent)
                .frame(width: 44, height: 44)
                .background(Theme.chip, in: RoundedRectangle(cornerRadius: 14))
            content
        }
        .padding(.leading, 18)
        .padding(.trailing, 14)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, minHeight: 76, alignment: .leading)
        .background(.white, in: RoundedRectangle(cornerRadius: 20))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(Theme.line, lineWidth: 1.5))
        .contentShape(RoundedRectangle(cornerRadius: 20))
    }
}

/// Mudah / Sedang / Sulit segmented picker.
private struct LevelPicker: View {
    @Binding var level: CongklakBot.Level

    var body: some View {
        HStack(spacing: 2) {
            option(.easy, "Mudah")
            option(.normal, "Sedang")
            option(.hard, "Sulit")
        }
        .padding(3)
        .background(Theme.track, in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text("Tingkat kesulitan"))
    }

    private func option(_ value: CongklakBot.Level, _ label: LocalizedStringKey) -> some View {
        let on = level == value
        return Button { level = value } label: {
            Text(label)
                .font(Theme.font(14))
                .foregroundStyle(on ? Theme.ink : Theme.muted)
                .padding(.horizontal, 14)
                .frame(height: 38)
                .background(on ? Color.white : .clear, in: RoundedRectangle(cornerRadius: 10))
                .shadow(color: on ? Theme.ink.opacity(0.18) : .clear, radius: 1.5, y: 1)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// The congklak tile on the home screen: a flat drawing of the board.
private struct CongklakCard: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text("Congklak").font(Theme.font(22, .black)).foregroundStyle(Color(hex: 0xFFF6E8))
                Spacer()
                Text("2 pemain").font(Theme.font(13, .bold)).foregroundStyle(Color(hex: 0xF3DDBE))
            }
            HStack(spacing: 8) {
                store
                VStack(spacing: 6) { row; row }
                store
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.woodDark, in: Capsule())
            .accessibilityHidden(true)
            Text("Kumpulkan biji terbanyak di lumbungmu.")
                .font(Theme.font(13, .semibold))
                .foregroundStyle(Color(hex: 0xF3DDBE))
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .frame(maxHeight: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 22).fill(Theme.woodShadow)
                .overlay(RoundedRectangle(cornerRadius: 22).fill(Theme.wood).padding(.bottom, 6))
        )
    }

    private var store: some View {
        RoundedRectangle(cornerRadius: 12).fill(Theme.pit).frame(width: 18, height: 56)
    }

    private var row: some View {
        HStack(spacing: 5) {
            ForEach(0..<7, id: \.self) { _ in
                Circle().fill(Theme.pit)
                    .overlay(RoundedRectangle(cornerRadius: 1.5).fill(Theme.seed).frame(width: 5, height: 5))
            }
        }
    }
}

/// A nearby room: the lobby until the game starts, then the board.
private struct RoomView: View {
    @ObservedObject var room: NearbyRoom
    let onExit: () -> Void

    var body: some View {
        if let table = room.table {
            CongklakView(table: table, onExit: onExit)
                .id(ObjectIdentifier(table))
        } else {
            lobby
        }
    }

    private var lobby: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Congklak").font(Theme.font(24, .black))
            Text("Kode room").font(.subheadline)
            Text(room.code).font(.system(size: 44, weight: .bold, design: .monospaced))
            Text(room.status)
            ForEach(room.players, id: \.seat) { p in
                Text("\(p.seat + 1). \(p.name)")
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
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .background(Theme.cream)
        .foregroundStyle(Theme.ink)
    }
}
