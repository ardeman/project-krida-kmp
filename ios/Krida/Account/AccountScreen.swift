import AuthenticationServices
import SwiftUI

/// Google's sign-in branding: the "G" mark on the button's own fill, outline and text
/// colours (light and dark), at the same size as Apple's button next to it.
private struct GoogleButtonStyle: ButtonStyle {
    let dark: Bool
    let corner: CGFloat

    func makeBody(configuration: Configuration) -> some View {
        let shape = RoundedRectangle(cornerRadius: corner)
        configuration.label
            .foregroundStyle(dark ? Color(red: 0.89, green: 0.89, blue: 0.89) : Color(red: 0.12, green: 0.12, blue: 0.12))
            .background(dark ? Color(red: 0.075, green: 0.075, blue: 0.078) : .white, in: shape)
            .overlay(shape.stroke(dark ? Color(red: 0.557, green: 0.569, blue: 0.561) : Color(red: 0.455, green: 0.467, blue: 0.459), lineWidth: 1))
            .opacity(configuration.isPressed ? 0.8 : 1)
    }
}

/// Sign in with Apple's look: black with white content, or white on black in the dark theme.
private struct AppleButtonStyle: ButtonStyle {
    let dark: Bool
    let corner: CGFloat

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(dark ? Color.black : Color.white)
            .background(dark ? Color.white : Color.black, in: RoundedRectangle(cornerRadius: corner))
            .opacity(configuration.isPressed ? 0.8 : 1)
    }
}

/// Sign in, or see the signed-in account with sign out and delete: one status card.
struct AccountScreen: View {
    let onBack: () -> Void
    @ObservedObject private var account = Account.shared
    @State private var confirmingDelete = false
    @Environment(\.uiScale) private var s
    @Environment(\.colorScheme) private var scheme

    /// The question only makes sense while signed in and idle.
    private var confirming: Bool { confirmingDelete && account.user != nil && !account.busy }

    private var title: Text {
        if confirming { return Text("Delete your account?") }
        guard let user = account.user else { return Text("Sign in to Krida") }
        return user.name.isEmpty ? Text("Player") : Text(verbatim: user.name)
    }

    private var detail: LocalizedStringKey {
        if confirming { return "Your account and name are removed from Krida. This can't be undone." }
        guard let user = account.user else { return "Sign in to add friends and invite them to play. Friends are coming soon." }
        return user.provider == "apple" ? "Signed in with Apple" : "Signed in with Google"
    }

    var body: some View {
        NearbyFrame(title: Text("Account"), subtitle: placeLine(true), online: true, backLabel: "Back", onBack: {
            if confirming { confirmingDelete = false } else { onBack() }
        }) {
            VStack(spacing: 12 * s) {
                Group {
                    if account.busy {
                        LoadingIndicator()
                    } else if confirming {
                        Image(systemName: "exclamationmark.circle.fill")
                            .font(.system(size: 30 * s, weight: .bold))
                            .foregroundStyle(Theme.error)
                    } else {
                        Image(systemName: "person.fill")
                            .font(.system(size: 28 * s, weight: .bold))
                            .foregroundStyle(Theme.muted)
                    }
                }
                .frame(width: 64 * s, height: 64 * s)
                .background(account.busy ? Color.clear : confirming ? Theme.error.opacity(0.14) : Theme.chip, in: Circle())
                title.font(Theme.font(22 * s, .black)).lineLimit(1)
                Text(detail).font(Theme.font(14 * s, .bold)).foregroundStyle(Theme.muted)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                if account.failed {
                    Text("That didn't work. Check your connection and try again.")
                        .font(Theme.font(14 * s, .bold)).foregroundStyle(Theme.error)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                }
                HStack(spacing: 10 * s) {
                    if confirming {
                        Button { confirmingDelete = false } label: { Text("Cancel").frame(minWidth: 94 * s) }
                            .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                        Button { confirmingDelete = false; account.delete() } label: { Text("Delete account").frame(minWidth: 94 * s) }
                            .buttonStyle(PrimaryButtonStyle())
                    } else if account.user != nil {
                        Button { confirmingDelete = true } label: { Text("Delete account").frame(minWidth: 94 * s) }
                            .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                        Button { account.signOut() } label: { Text("Sign out").frame(minWidth: 94 * s) }
                            .buttonStyle(PrimaryButtonStyle())
                    } else {
                        // Drawn here to Apple's button guidelines (logo, black or white) instead of
                        // the system button, whose title ignores the in-app language until a relaunch.
                        Button {
                            Sounds.shared.play(.tap)
                            account.signInWithApple()
                        } label: {
                            HStack(spacing: 6 * s) {
                                Image(systemName: "apple.logo").font(.system(size: 17 * s, weight: .medium))
                                Text("Sign in with Apple").font(Theme.font(16 * s, .bold)).lineLimit(1).minimumScaleFactor(0.8)
                            }
                            .padding(.horizontal, 12 * s)
                            .frame(width: 200 * s, height: 44 * s)
                        }
                        .buttonStyle(AppleButtonStyle(dark: scheme == .dark, corner: 14 * s))
                        Button {
                            Sounds.shared.play(.tap)
                            account.signInWithGoogle()
                        } label: {
                            HStack(spacing: 8 * s) {
                                Image("GoogleG").resizable().frame(width: 18 * s, height: 18 * s)
                                Text("Sign in with Google").font(Theme.font(16 * s, .bold)).lineLimit(1).minimumScaleFactor(0.8)
                            }
                            .padding(.horizontal, 12 * s)
                            .frame(width: 200 * s, height: 44 * s)
                        }
                        .buttonStyle(GoogleButtonStyle(dark: scheme == .dark, corner: 14 * s))
                    }
                }
                .disabled(account.busy)
                .padding(.top, 4 * s)
            }
            .padding(.horizontal, 24 * s)
            .padding(.vertical, 22 * s)
            .frame(width: 480 * s)
            .nearbyCard(s)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}
