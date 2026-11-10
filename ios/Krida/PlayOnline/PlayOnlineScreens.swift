import Foundation
import Shared
import SwiftUI

private func matchString(_ key: String) -> String {
    Bundle.app.localizedString(forKey: key, value: nil, table: nil)
}

/// One dot per player found, filled for each vote this option has, plus the count.
private struct VoteTally: View {
    let votes: Int
    let players: Int
    let mine: Bool
    let s: CGFloat

    var body: some View {
        HStack(spacing: 4 * s) {
            ForEach(0..<players, id: \.self) { index in
                Circle().fill(index < votes ? Theme.accent : Theme.line).frame(width: 8 * s, height: 8 * s)
            }
            Text(String(format: matchString(mine ? "Your vote · %1$d/%2$d" : "%1$d/%2$d votes"), votes, players))
                .font(Theme.font(12 * s, .bold))
                .foregroundStyle(mine ? Theme.accent : Theme.muted)
                .padding(.leading, 4 * s)
        }
    }
}

struct PlayOnlineView: View {
    let game: String
    /// Total seats to search for, chosen on the join screen.
    let target: Int
    let onCancel: () -> Void
    let createHost: (String, Int, Int, @escaping () -> Void) -> NearbyRoom
    let onHost: (NearbyRoom) -> Void
    let onJoin: (_ code: String, _ reservation: String?) -> Void
    let onComputer: (Int) -> Void

    @State private var attempt = 0
    @Environment(\.uiScale) private var s

    var body: some View {
        NearbyFrame(title: gameTitle(game), subtitle: placeLine(true), online: true,
                    backLabel: "Cancel", onBack: onCancel) {
            MatchSearch(game: game, target: target, onCancel: onCancel,
                        onRetry: { attempt += 1 }, createHost: createHost,
                        onHost: onHost, onJoin: onJoin, onComputer: onComputer)
                .id(attempt)
                .frame(width: 480 * s)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

private struct MatchSearch: View {
    let game: String
    let target: Int
    let onCancel: () -> Void
    let onRetry: () -> Void
    let createHost: (String, Int, Int, @escaping () -> Void) -> NearbyRoom
    let onHost: (NearbyRoom) -> Void
    let onJoin: (_ code: String, _ reservation: String?) -> Void
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
    /// Seconds left in the vote; nil when the relay sets no limit.
    @State private var voteSeconds: Int?
    /// Why the search started over after a vote timed out; kept until the next offer.
    @State private var restart: String?
    /// The relay's search and vote windows (relay/src/group-matchmaker.ts), for the rings.
    private let searchSecondsTotal = 20
    private let voteSecondsTotal = 15
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
                } else if preparing {
                    LoadingIndicator()
                        .frame(width: 64 * s, height: 64 * s)
                } else if offer, let voting = voteSeconds {
                    CountdownRing(seconds: voting, total: voteSecondsTotal, size: 52)
                } else if offer {
                    Circle()
                        .fill(Theme.chip)
                        .frame(width: 32 * s, height: 32 * s)
                    Image(systemName: "laptopcomputer")
                        .font(.system(size: 16 * s, weight: .semibold))
                        .foregroundStyle(Theme.accent)
                } else {
                    CountdownRing(seconds: seconds, total: searchSecondsTotal, size: 64)
                }
            }
            .frame(height: offer && !preparing && !failed ? (voteSeconds == nil ? 32 : 52) * s : 64 * s)
            
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
                          selection: $soloSeats, label: "Players")
            }
            
            // Offer buttons
            if offer && !preparing && !failed {
                if humans >= 2 {
                    // A live poll: every player sees how the group is voting before and after choosing.
                    HStack(alignment: .top, spacing: 10 * s) {
                        VStack(spacing: 6 * s) {
                            Button(action: { client?.acceptHumans(revision: revision) }) {
                                Text(String(format: matchString("Play with %1$d players"), humans)).frame(maxWidth: .infinity)
                            }
                            .buttonStyle(PrimaryButtonStyle())
                            .disabled(acceptedHumans)
                            VoteTally(votes: agreedHumans, players: humans, mine: acceptedHumans, s: s)
                        }
                        VStack(spacing: 6 * s) {
                            Button(action: { client?.acceptBots(revision: revision) }) {
                                Text(matchString("Play with computers")).frame(maxWidth: .infinity)
                            }
                            .buttonStyle(PrimaryButtonStyle())
                            .disabled(accepted)
                            VoteTally(votes: agreed, players: humans, mine: accepted, s: s)
                        }
                    }
                    let undecided = max(0, humans - agreed - agreedHumans)
                    if undecided > 0 {
                        Text(String(format: matchString(undecided == 1 ? "%d player still choosing" : "%d players still choosing"), undecided))
                            .font(Theme.font(12 * s, .bold))
                            .foregroundStyle(Theme.muted)
                            .multilineTextAlignment(.center)
                    }
                } else {
                    HStack(spacing: 10 * s) {
                        Button(action: {
                            client?.close()
                            onComputer(soloSeats)
                        }) {
                            Text(matchString("Play with computers"))
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(PrimaryButtonStyle())
                    }
                }
                
                HStack(spacing: 10 * s) {
                    Button(action: onCancel) {
                        Text(matchString("Cancel")).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                    Button(action: { client?.keepSearching() }) {
                        Text(matchString("Keep searching")).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                }
            } else if !offer || preparing || failed {
                HStack(spacing: 10 * s) {
                    Button(matchString(failed ? "Back" : "Cancel"), action: onCancel)
                        .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                    if failed {
                        Button(matchString("Try again"), action: onRetry)
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
                    voteSeconds = (event["voteRemaining"] as? NSNumber)?.intValue
                    if let why = event["restart"] as? String { restart = why }
                    if offer { restart = nil }
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
                        onJoin(code, event["reservation"] as? String)
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
            // Cosmetic only: the relay decides when the search and the vote end.
            while !preparing && !failed && ((!offer && seconds > 0) || (voteSeconds ?? 0) > 0) {
                do { try await Task.sleep(nanoseconds: 1_000_000_000) }
                catch { return }
                guard !Task.isCancelled else { return }
                if !offer { seconds = max(0, seconds - 1) }
                if let vote = voteSeconds { voteSeconds = max(0, vote - 1) }
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
            return matchString("Unable to connect")
        } else if preparing {
            return matchString("Preparing your room…")
        } else if offer {
            return matchString("Still waiting for players")
        } else {
            return matchString("Finding a player…")
        }
    }
    
    private var detailText: String {
        if failed {
            return matchString("Check your internet connection, then try again.")
        } else if preparing {
            return matchString("Players found. Connecting to the host.")
        } else if offer && humans == 1 {
            return matchString("No opponent found. Choose the total players, including you, and play against computers.")
        } else if offer {
            return String(format: matchString("%1$d of %2$d players found · %3$d empty seats.\nVote on how to start. The choice with more votes wins."), humans, target, target - humans)
        } else {
            let search = String(format: matchString("%1$d of %2$d players found"), humans, target)
            switch restart {
            case "no_votes": return matchString("No one voted. Searching again…") + "\n" + search
            case "tie": return matchString("The vote was tied. Searching again…") + "\n" + search
            default: return search
            }
        }
    }
}
