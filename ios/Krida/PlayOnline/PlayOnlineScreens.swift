import Foundation
import Shared
import SwiftUI

private func matchString(_ key: String) -> String {
    Bundle.app.localizedString(forKey: key, value: nil, table: nil)
}

struct PlayOnlineView: View {
    let game: String
    let onCancel: () -> Void
    let createHost: (String, Int, Int, @escaping () -> Void) -> NearbyRoom
    let onHost: (NearbyRoom) -> Void
    let onJoin: (String) -> Void
    let onComputer: (Int) -> Void

    @State private var playerCount: Int
    @State private var searching = false
    @State private var attempt = 0
    @Environment(\.uiScale) private var s

    init(game: String, onCancel: @escaping () -> Void,
         createHost: @escaping (String, Int, Int, @escaping () -> Void) -> NearbyRoom,
         onHost: @escaping (NearbyRoom) -> Void, onJoin: @escaping (String) -> Void,
         onComputer: @escaping (Int) -> Void) {
        self.game = game
        self.onCancel = onCancel
        self.createHost = createHost
        self.onHost = onHost
        self.onJoin = onJoin
        self.onComputer = onComputer
        _playerCount = State(initialValue: game == UlarTanggaGame.shared.id ? 4 : 2)
    }

    var body: some View {
        NearbyFrame(title: gameTitle(game), subtitle: placeLine(true), online: true,
                    backLabel: "Batal", onBack: onCancel) {
            Group {
                if searching {
                    MatchSearch(game: game, target: playerCount, onCancel: onCancel,
                                onRetry: { attempt += 1 }, createHost: createHost,
                                onHost: onHost, onJoin: onJoin, onComputer: onComputer)
                        .id(attempt)
                } else {
                    SelectPlayersCard(game: game, playerCount: $playerCount,
                                      onStart: { searching = true }, onCancel: onCancel)
                }
            }
            .frame(width: 480 * s)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        #if DEBUG
        .onAppear {
            if ProcessInfo.processInfo.arguments.contains("-searchQuickMatch") { searching = true }
        }
        #endif
    }
}

private struct SelectPlayersCard: View {
    let game: String
    @Binding var playerCount: Int
    let onStart: () -> Void
    let onCancel: () -> Void
    @Environment(\.uiScale) private var s
    
    var body: some View {
        VStack(spacing: 12 * s) {
            VStack(spacing: 8 * s) {
                Text(matchString("play_online_quick_match"))
                    .font(Theme.font(22 * s, .black))
                
                Text(matchString("play_online_description"))
                    .font(Theme.font(14 * s))
                    .foregroundStyle(Theme.muted)
            }
            .multilineTextAlignment(.center)
            
            if game == UlarTanggaGame.shared.id {
                VStack(spacing: 12 * s) {
                    Text(matchString("play_players"))
                        .font(Theme.font(13 * s, .semibold))
                        .frame(maxWidth: .infinity, alignment: .leading)
                    
                    Segmented(options: (2...4).map { ($0, LocalizedStringKey("\($0)"), nil) },
                              selection: $playerCount, label: "Pemain")
                }
            } else {
                Text(matchString("match_two_players"))
                    .font(Theme.font(14 * s))
                    .foregroundStyle(Theme.muted)
            }
            
            Text(matchString("match_fallback_hint"))
                .font(Theme.font(14 * s))
                .foregroundStyle(Theme.muted)
            
            HStack(spacing: 10 * s) {
                Button(matchString("nearby_cancel"), action: onCancel)
                    .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                Button(matchString("button_search"), action: onStart)
                    .buttonStyle(PrimaryButtonStyle())
            }
        }
        .padding(.horizontal, 24 * s)
        .padding(.vertical, 22 * s)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 20 * s))
        .overlay(RoundedRectangle(cornerRadius: 20 * s).stroke(Theme.line, lineWidth: 1.5))
    }
}

private struct MatchSearch: View {
    let game: String
    let target: Int
    let onCancel: () -> Void
    let onRetry: () -> Void
    let createHost: (String, Int, Int, @escaping () -> Void) -> NearbyRoom
    let onHost: (NearbyRoom) -> Void
    let onJoin: (_ code: String) -> Void
    let onComputer: (_ seats: Int) -> Void
    
    @State private var client: QuickMatchClient?
    @State private var humans = 1
    @State private var seconds = 20
    @State private var countdownVersion = 0
    @State private var offer = false
    @State private var accepted = false
    @State private var revision = 0
    @State private var agreed = 0
    @State private var acceptedHumans = false
    @State private var agreedHumans = 0
    @State private var soloSeats = 2
    @State private var preparing = false
    @State private var failed = false
    @State private var transferred = false
    @State private var pendingRoom: NearbyRoom?
    
    @Environment(\.uiScale) private var s
    
    var body: some View {
        VStack(spacing: offer && !preparing && !failed ? 6 * s : 12 * s) {
            // Icon
            ZStack {
                if failed {
                    Circle()
                        .fill(Theme.error.opacity(0.14))
                        .frame(width: 32 * s, height: 32 * s)
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 16 * s, weight: .semibold))
                        .foregroundStyle(Theme.error)
                } else if offer && !preparing {
                    Circle()
                        .fill(Theme.chip)
                        .frame(width: 32 * s, height: 32 * s)
                    Image(systemName: "laptopcomputer")
                        .font(.system(size: 16 * s, weight: .semibold))
                        .foregroundStyle(Theme.accent)
                } else {
                    LoadingIndicator()
                        .frame(width: 64 * s, height: 64 * s)
                }
            }
            .frame(height: offer && !preparing && !failed ? 32 * s : 64 * s)
            
            // Title
            Text(titleText)
                .font(Theme.font(22 * s, .black))
                .multilineTextAlignment(.center)
            
            // Detail
            Text(detailText)
                .font(Theme.font(14 * s, .bold))
                .foregroundStyle(Theme.muted)
                .multilineTextAlignment(.center)
            
            // Solo seat selector
            if offer && !preparing && !failed && humans == 1 && game == UlarTanggaGame.shared.id {
                Segmented(options: (2...4).map { ($0, LocalizedStringKey("\($0)"), nil) },
                          selection: $soloSeats, label: "Pemain")
            }
            
            // Offer buttons
            if offer && !preparing && !failed {
                if humans >= 2 {
                    HStack(spacing: 10 * s) {
                        Button(action: {
                            client?.acceptHumans(revision: revision)
                        }) {
                            Text(acceptedHumans ? String(format: matchString("match_agreed"), agreedHumans, humans) : String(format: matchString("match_play_humans"), humans))
                                .font(Theme.font(14 * s, .bold))
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(acceptedHumans)
                        
                        Button(action: {
                            client?.acceptBots(revision: revision)
                        }) {
                            Text(accepted ? String(format: matchString("match_agreed"), agreed, humans) : matchString("match_play_bots"))
                                .font(Theme.font(14 * s, .bold))
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(accepted)
                    }
                } else {
                    HStack(spacing: 10 * s) {
                        Button(action: {
                            client?.close()
                            onComputer(soloSeats)
                        }) {
                            Text(matchString("match_play_bots"))
                                .font(Theme.font(14 * s, .bold))
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(PrimaryButtonStyle())
                    }
                }
                
                HStack(spacing: 10 * s) {
                    Button(matchString("nearby_cancel"), action: onCancel)
                        .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                    Button(matchString("match_keep_searching"), action: {
                        client?.keepSearching()
                    })
                        .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                }
            } else if !offer || preparing || failed {
                HStack(spacing: 10 * s) {
                    Button(matchString(failed ? "congklak_back" : "nearby_cancel"), action: onCancel)
                        .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                    if failed {
                        Button(matchString("nearby_try_again"), action: onRetry)
                            .buttonStyle(PrimaryButtonStyle())
                    }
                }
            }
        }
        .padding(.horizontal, 24 * s)
        .padding(.vertical, offer && !preparing && !failed ? 16 * s : 22 * s)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 20 * s))
        .overlay(RoundedRectangle(cornerRadius: 20 * s).stroke(Theme.line, lineWidth: 1.5))
        .onAppear {
            soloSeats = target
            let newClient = QuickMatchClient(game: game, playerCount: target)
            client = newClient
            
            newClient.start(onEvent: { event in
                func number(key: String) -> Int {
                    return (event[key] as? NSNumber)?.intValue ?? 0
                }
                
                let type = event["type"] as? String
                switch type {
                case "queue":
                    humans = number(key: "humans")
                    seconds = number(key: "remaining")
                    countdownVersion += 1
                    offer = (event["offer"] as? NSNumber)?.boolValue ?? false
                    accepted = (event["accepted"] as? NSNumber)?.boolValue ?? false
                    agreed = number(key: "agreed")
                    acceptedHumans = (event["acceptedHumans"] as? NSNumber)?.boolValue ?? false
                    agreedHumans = number(key: "agreedHumans")
                    revision = number(key: "revision")
                    
                case "prepare_host":
                    guard let code = event["code"] as? String,
                          number(key: "humans") >= 2, number(key: "bots") >= 0 else {
                        fail()
                        return
                    }
                    preparing = true
                    pendingRoom = createHost(code, number(key: "humans"), number(key: "bots"), newClient.hostReady)
                    
                case "preparing":
                    preparing = true
                    
                case "match_error":
                    fail()
                    
                case "matched":
                    newClient.close()
                    let role = event["role"] as? String ?? ""
                    switch role {
                    case "solo":
                        let seats = number(key: "seats")
                        onComputer(seats > 0 ? seats : target)
                    case "host":
                        guard let room = pendingRoom else { fail(); return }
                        transferred = true
                        onHost(room)
                    case "guest":
                        guard let code = event["code"] as? String else { fail(); return }
                        onJoin(code)
                    default:
                        fail()
                    }
                    
                default:
                    break
                }
            }, onError: {
                fail()
            })
        }
        .onDisappear {
            client?.close()
            if !transferred { pendingRoom?.leave() }
        }
        .task(id: countdownVersion) {
            while !offer && !preparing && !failed && seconds > 0 {
                do { try await Task.sleep(nanoseconds: 1_000_000_000) }
                catch { return }
                guard !Task.isCancelled else { return }
                seconds = max(0, seconds - 1)
            }
        }
    }
    
    private func fail() {
        failed = true
        client?.close()
        pendingRoom?.leave()
        pendingRoom = nil
    }

    private var titleText: String {
        if failed {
            return matchString("match_error_title")
        } else if preparing {
            return matchString("match_preparing")
        } else if offer {
            return matchString("match_offer_title")
        } else {
            return matchString("play_online_searching_title")
        }
    }
    
    private var detailText: String {
        if failed {
            return matchString("match_error_detail")
        } else if preparing {
            return matchString("match_preparing_detail")
        } else if offer && humans == 1 {
            return matchString("match_solo_detail")
        } else if offer {
            return String(format: matchString("match_offer_detail"), humans, target, target - humans)
        } else {
            return String(format: matchString("match_search_detail"), humans, target, seconds)
        }
    }
}
