import Foundation
import Shared
import SwiftUI

/// Quick match for online play: select game and player count, connect to the matchmaker,
/// wait for a match, then transition to hosting or joining a room.
struct PlayOnlineView: View {
    let game: String
    let onCancel: () -> Void
    let onHost: (_ code: String, _ game: String) -> Void
    let onJoin: (_ code: String, _ game: String) -> Void
    
    @State private var playerCount = 2
    @State private var state = MatchState.selectPlayers
    @Environment(\.uiScale) private var s
    
    var body: some View {
        ZStack {
            Theme.backdropGradient.ignoresSafeArea()
            
            VStack(spacing: 24 * s) {
                switch state {
                case .selectPlayers:
                    SelectPlayersCard(
                        game: game,
                        playerCount: $playerCount,
                        onStart: { state = .waiting },
                        onCancel: onCancel
                    )
                    .padding(.horizontal, 24 * s)
                
                case .waiting:
                    WaitingForMatchCard(
                        game: game,
                        playerCount: playerCount,
                        onMatched: { role, code in
                            if role == "host" {
                                onHost(code, game)
                            } else {
                                onJoin(code, game)
                            }
                        },
                        onCancel: {
                            state = .selectPlayers
                            onCancel()
                        }
                    )
                    .padding(.horizontal, 24 * s)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
        }
    }
}

enum MatchState {
    case selectPlayers
    case waiting
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
                Text("Play Online")
                    .font(Theme.font(24 * s, .black))
                
                Text("Find a player and play through the internet")
                    .font(Theme.font(14 * s))
                    .foregroundStyle(Theme.muted)
            }
            .multilineTextAlignment(.center)
            
            VStack(spacing: 12 * s) {
                Text("Players")
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
            
            HStack(spacing: 10 * s) {
                Button("Cancel", action: onCancel)
                    .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                Button("Search", action: onStart)
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

private struct WaitingForMatchCard: View {
    let game: String
    let playerCount: Int
    let onMatched: (_ role: String, _ code: String) -> Void
    let onCancel: () -> Void
    
    @State private var seconds = 20
    @State private var errorMessage: String?
    @State private var matchmaker: QuickMatchClient?
    @Environment(\.uiScale) private var s
    
    var body: some View {
        Group {
            if let error = errorMessage, error == "timeout" {
                timeoutCard
            } else if errorMessage != nil {
                errorCard
            } else {
                searchingCard
            }
        }
        .onAppear {
            let client = QuickMatchClient(game: game, playerCount: playerCount)
            self.matchmaker = client
            
            client.start(
                onMatched: { role, code in
                    onMatched(role, code)
                },
                onError: { error in
                    errorMessage = error
                }
            )
            
            // Start countdown
            startCountdown()
        }
        .onDisappear {
            matchmaker?.close()
        }
    }
    
    private var searchingCard: some View {
        VStack(spacing: 20 * s) {
            LoadingIndicator()
            
            VStack(spacing: 8 * s) {
                Text("Finding a player…")
                    .font(Theme.font(18 * s, .black))
                
                Text("Waiting for \(playerCount) players")
                    .font(Theme.font(14 * s))
                    .foregroundStyle(Theme.muted)
            }
            .multilineTextAlignment(.center)
            
            Text("\(seconds)")
                .font(Theme.font(32 * s, .black))
                .foregroundStyle(Theme.accent)
            
            Button("Cancel", action: onCancel)
                .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
        }
        .padding(20 * s)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 20 * s))
        .overlay(RoundedRectangle(cornerRadius: 20 * s).stroke(Theme.line, lineWidth: 1.5))
    }
    
    private var timeoutCard: some View {
        VStack(spacing: 20 * s) {
            VStack(spacing: 8 * s) {
                Text("No one around")
                    .font(Theme.font(20 * s, .black))
                
                Text("We couldn't find another player. Play against the computer instead?")
                    .font(Theme.font(14 * s))
                    .foregroundStyle(Theme.muted)
            }
            .multilineTextAlignment(.center)
            
            Button("Play Computer", action: onCancel)
                .buttonStyle(PrimaryButtonStyle())
        }
        .padding(20 * s)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 20 * s))
        .overlay(RoundedRectangle(cornerRadius: 20 * s).stroke(Theme.line, lineWidth: 1.5))
    }
    
    private var errorCard: some View {
        VStack(spacing: 20 * s) {
            VStack(spacing: 8 * s) {
                Text("Something went wrong")
                    .font(Theme.font(20 * s, .black))
                
                Text(errorMessage ?? "")
                    .font(Theme.font(14 * s))
                    .foregroundStyle(Theme.muted)
            }
            .multilineTextAlignment(.center)
            
            HStack(spacing: 10 * s) {
                Button("Cancel", action: onCancel)
                    .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                Button("Retry", action: {
                    // Retry logic would go here
                })
                    .buttonStyle(PrimaryButtonStyle())
            }
        }
        .padding(20 * s)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 20 * s))
        .overlay(RoundedRectangle(cornerRadius: 20 * s).stroke(Theme.line, lineWidth: 1.5))
    }
    
    private func startCountdown() {
        Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in
            if seconds > 0 {
                seconds -= 1
            } else if errorMessage == nil {
                errorMessage = "timeout"
                matchmaker?.close()
            }
        }
    }
}

#Preview {
    PlayOnlineView(
        game: "congklak",
        onCancel: {},
        onHost: { _, _ in },
        onJoin: { _, _ in }
    )
}
