import Foundation
import Shared
import SwiftUI

struct PlayOnlineView: View {
    let game: String
    let onCancel: () -> Void
    let onHost: (_ code: String, _ game: String) -> Void
    let onJoin: (_ code: String, _ game: String) -> Void
    
    @State private var playerCount = 2
    @State private var searching = false
    @State private var attempt = 0
    @Environment(\.uiScale) private var s
    
    var body: some View {
        ZStack {
            Theme.backdropGradient.ignoresSafeArea()
            
            VStack(spacing: 24 * s) {
                if searching {
                    MatchSearch(
                        game: game,
                        target: playerCount,
                        onCancel: {
                            searching = false
                            onCancel()
                        },
                        onRetry: { attempt += 1 },
                        onHost: { code in
                            onHost(code, game)
                        },
                        onJoin: { code in
                            onJoin(code, game)
                        },
                        onComputer: { _ in
                            // For now, just go back - will need to be integrated with game start
                            onCancel()
                        }
                    )
                    .padding(.horizontal, 24 * s)
                } else {
                    SelectPlayersCard(
                        game: game,
                        playerCount: $playerCount,
                        onStart: { searching = true },
                        onCancel: onCancel
                    )
                    .padding(.horizontal, 24 * s)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
        }
    }
}

private struct SelectPlayersCard: View {
    let game: String
    @Binding var playerCount: Int
    let onStart: () -> Void
    let onCancel: () -> Void
    @Environment(\.uiScale) private var s
    
    var body: some View {
        VStack(spacing: 20 * s) {
            VStack(spacing: 8 * s) {
                Text(NSLocalizedString("play_online_quick_match", comment: ""))
                    .font(Theme.font(24 * s, .black))
                
                Text(NSLocalizedString("play_online_description", comment: ""))
                    .font(Theme.font(14 * s))
                    .foregroundStyle(Theme.muted)
            }
            .multilineTextAlignment(.center)
            
            if game == "ular-tangga" {
                VStack(spacing: 12 * s) {
                    Text(NSLocalizedString("play_players", comment: ""))
                        .font(Theme.font(13 * s, .semibold))
                        .frame(maxWidth: .infinity, alignment: .leading)
                    
                    HStack(spacing: 8 * s) {
                        ForEach(2...4, id: \.self) { count in
                            PlayerCountButton(
                                count: count,
                                selected: playerCount == count,
                                action: { playerCount = count }
                            )
                        }
                    }
                }
            } else {
                Text(NSLocalizedString("match_two_players", comment: ""))
                    .font(Theme.font(14 * s))
                    .foregroundStyle(Theme.muted)
            }
            
            Text(NSLocalizedString("match_fallback_hint", comment: ""))
                .font(Theme.font(14 * s))
                .foregroundStyle(Theme.muted)
            
            HStack(spacing: 10 * s) {
                Button(NSLocalizedString("nearby_cancel", comment: ""), action: onCancel)
                    .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                Button(NSLocalizedString("button_search", comment: ""), action: onStart)
                    .buttonStyle(PrimaryButtonStyle())
            }
        }
        .padding(20 * s)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 20 * s))
        .overlay(RoundedRectangle(cornerRadius: 20 * s).stroke(Theme.line, lineWidth: 1.5))
    }
}

private struct PlayerCountButton: View {
    let count: Int
    let selected: Bool
    let action: () -> Void
    @Environment(\.uiScale) private var s
    
    var body: some View {
        Button(action: action) {
            Text("\(count)")
                .font(Theme.font(16 * s, .semibold))
                .foregroundStyle(selected ? .white : Theme.ink)
                .frame(maxWidth: .infinity)
                .frame(height: 44 * s)
                .background(selected ? Theme.accent : Theme.surface, in: RoundedRectangle(cornerRadius: 12 * s))
                .overlay(RoundedRectangle(cornerRadius: 12 * s).stroke(
                    selected ? Theme.accent : Theme.line,
                    lineWidth: 1.5
                ))
        }
    }
}

private struct MatchSearch: View {
    let game: String
    let target: Int
    let onCancel: () -> Void
    let onRetry: () -> Void
    let onHost: (_ code: String) -> Void
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
    
    @Environment(\.uiScale) private var s
    
    var body: some View {
        VStack(spacing: if offer && !preparing && !failed { 6 * s } else { 12 * s }) {
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
            .frame(height: 64 * s)
            
            // Title
            Text(titleText)
                .font(Theme.font(22 * s, .black))
                .multilineTextAlignment(.center)
            
            // Detail
            Text(detailText)
                .font(Theme.font(14 * s, .semibold))
                .foregroundStyle(Theme.muted)
                .multilineTextAlignment(.center)
            
            // Solo seat selector
            if offer && !preparing && !failed && humans == 1 && game == "ular-tangga" {
                HStack(spacing: 8 * s) {
                    ForEach(2...4, id: \.self) { count in
                        PlayerCountButton(
                            count: count,
                            selected: soloSeats == count,
                            action: { soloSeats = count }
                        )
                    }
                }
                .frame(maxWidth: .infinity)
            }
            
            // Offer buttons
            if offer && !preparing && !failed {
                if humans >= 2 {
                    HStack(spacing: 10 * s) {
                        Button(action: {
                            client?.acceptHumans(revision: revision)
                        }) {
                            Text(acceptedHumans ? String(format: NSLocalizedString("match_agreed", comment: ""), agreedHumans, humans) : String(format: NSLocalizedString("match_play_humans", comment: ""), humans))
                                .font(Theme.font(14 * s, .semibold))
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(acceptedHumans)
                        
                        Button(action: {
                            client?.acceptBots(revision: revision)
                        }) {
                            Text(accepted ? String(format: NSLocalizedString("match_agreed", comment: ""), agreed, humans) : NSLocalizedString("match_play_bots", comment: ""))
                                .font(Theme.font(14 * s, .semibold))
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
                            Text(NSLocalizedString("match_play_bots", comment: ""))
                                .font(Theme.font(14 * s, .semibold))
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(PrimaryButtonStyle())
                    }
                }
                
                HStack(spacing: 10 * s) {
                    Button(NSLocalizedString("nearby_cancel", comment: ""), action: onCancel)
                        .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                    Button(NSLocalizedString("match_keep_searching", comment: ""), action: {
                        client?.keepSearching()
                    })
                        .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                }
            } else if !offer || preparing || failed {
                HStack(spacing: 10 * s) {
                    Button(NSLocalizedString(failed ? "congklak_back" : "nearby_cancel", comment: ""), action: onCancel)
                        .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                    if failed {
                        Button(NSLocalizedString("nearby_try_again", comment: ""), action: onRetry)
                            .buttonStyle(PrimaryButtonStyle())
                    }
                }
            }
        }
        .padding(if offer && !preparing && !failed { 16 * s } else { 22 * s })
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
                    preparing = true
                    
                case "preparing":
                    preparing = true
                    
                case "match_error":
                    failed = true
                    newClient.close()
                    
                case "matched":
                    newClient.close()
                    let role = event["role"] as? String ?? ""
                    switch role {
                    case "solo":
                        let seats = number(key: "seats")
                        onComputer(seats > 0 ? seats : target)
                    case "host":
                        let code = event["code"] as? String ?? ""
                        transferred = true
                        onHost(code)
                    case "guest":
                        let code = event["code"] as? String ?? ""
                        transferred = true
                        onJoin(code)
                    default:
                        failed = true
                    }
                    
                default:
                    break
                }
            }, onError: {
                failed = true
                newClient.close()
            })
        }
        .onDisappear {
            if !transferred {
                client?.close()
            }
        }
        .task(id: countdownVersion) {
            while !offer && !preparing && !failed && seconds > 0 {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                seconds -= 1
            }
        }
    }
    
    private var titleText: String {
        if failed {
            return NSLocalizedString("match_error_title", comment: "")
        } else if preparing {
            return NSLocalizedString("match_preparing", comment: "")
        } else if offer {
            return NSLocalizedString("match_offer_title", comment: "")
        } else {
            return NSLocalizedString("play_online_searching_title", comment: "")
        }
    }
    
    private var detailText: String {
        if failed {
            return NSLocalizedString("match_error_detail", comment: "")
        } else if preparing {
            return NSLocalizedString("match_preparing_detail", comment: "")
        } else if offer && humans == 1 {
            return NSLocalizedString("match_solo_detail", comment: "")
        } else if offer {
            return String(format: NSLocalizedString("match_offer_detail", comment: ""), humans, target, target - humans)
        } else {
            return String(format: NSLocalizedString("match_search_detail", comment: ""), humans, target, seconds)
        }
    }
}

#Preview {
    PlayOnlineView(
        game: "ular-tangga",
        onCancel: {},
        onHost: { _, _ in },
        onJoin: { _, _ in }
    )
}
