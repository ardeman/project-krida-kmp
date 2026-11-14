import AuthenticationServices
import Shared
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

/// Sign in, or see the signed-in account with sign out and delete: one status card. Signed in,
/// the Friends card sits beside it; Edit opens the name and avatar picker (docs/mockups/profile.md).
struct AccountScreen: View {
    let onBack: () -> Void
    @ObservedObject private var account = Account.shared
    @State private var confirmingDelete = false
    @State private var editing = ProcessInfo.processInfo.arguments.contains("-openProfileEdit")
    @Environment(\.uiScale) private var s
    @Environment(\.colorScheme) private var scheme

    /// The question only makes sense while signed in and idle.
    private var confirming: Bool { confirmingDelete && account.user != nil && !account.busy }
    /// Friends (or the avatar picker while editing) show while signed in, except over delete.
    private var withFriends: Bool { account.user != nil && !confirming }

    private var title: Text {
        if confirming { return Text("Delete your account?") }
        guard let user = account.user else { return Text("Sign in to Krida") }
        return user.name.isEmpty ? Text("Player") : Text(verbatim: user.name)
    }

    private var detail: LocalizedStringKey {
        if confirming { return "Your account and name are removed from Krida. This can't be undone." }
        guard let user = account.user else { return "Sign in to add friends. Playing never needs an account." }
        return user.provider == "apple" ? "Signed in with Apple" : "Signed in with Google"
    }

    var body: some View {
        NearbyFrame(title: Text("Account"), subtitle: placeLine(true), online: true, backLabel: "Back", onBack: {
            if confirming { confirmingDelete = false }
            else if editing { editing = false }
            else { onBack() }
        }) {
            if editing, let user = account.user {
                AccountEdit(user: user, onDone: { editing = false })
            } else if withFriends {
                // Scrolls only when the card is too short for it (large text, small phones).
                ViewThatFits(in: .vertical) {
                    card
                    ScrollView { card }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .nearbyCard(s)
                FriendsCard()
            } else {
                card
                    .frame(width: 480 * s)
                    .nearbyCard(s)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task(id: account.user?.id) {
            account.loadFriends()
            if !ProcessInfo.processInfo.arguments.contains("-openProfileEdit") {
                editing = false
            }
        }
    }

    private var card: some View {
        let avatar: CGFloat = withFriends ? 48 : 64
        return VStack(spacing: (withFriends ? 8 : 12) * s) {
                ZStack(alignment: .topTrailing) {
                    Group {
                        if account.busy {
                            LoadingIndicator()
                                .frame(width: avatar * s, height: avatar * s)
                        } else if confirming {
                            Image(systemName: "exclamationmark.circle.fill")
                                .font(.system(size: 30 * s, weight: .bold))
                                .foregroundStyle(Theme.error)
                                .frame(width: avatar * s, height: avatar * s)
                                .background(Theme.error.opacity(0.14), in: Circle())
                        } else if let user = account.user {
                            AvatarBadge(avatar: user.avatar, accountId: user.id, picture: user.picture, size: avatar)
                        } else {
                            Image(systemName: "person.fill")
                                .font(.system(size: avatar * 0.44 * s, weight: .bold))
                                .foregroundStyle(Theme.muted)
                                .frame(width: avatar * s, height: avatar * s)
                                .background(Theme.chip, in: Circle())
                        }
                    }
                    .frame(maxWidth: .infinity)
                    if withFriends, !account.busy {
                        Button { editing = true } label: { Text("Edit") }
                            .buttonStyle(SecondaryButtonStyle())
                    }
                }
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
                if withFriends { OwnCode(code: account.friendCode) }
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
            .padding(.horizontal, (withFriends ? 20 : 24) * s)
            .padding(.vertical, (withFriends ? 16 : 22) * s)
            .frame(maxWidth: .infinity)
    }
}

/// A 44-point chip with the screen's outline: the code boxes and the friend rows.
private extension View {
    func friendChip(_ s: CGFloat) -> some View {
        frame(height: 44 * s)
            .background(Theme.chip, in: RoundedRectangle(cornerRadius: 12 * s))
            .overlay(RoundedRectangle(cornerRadius: 12 * s).stroke(Theme.line, lineWidth: 1.5))
    }
}

/// The player's own friend code in two groups of four, with a Share pill for its link.
private struct OwnCode: View {
    let code: String?
    @Environment(\.uiScale) private var s

    var body: some View {
        Text("Your friend code").font(Theme.font(13 * s, .bold)).foregroundStyle(Theme.muted)
        HStack(spacing: 10 * s) {
            Text(verbatim: code.map { "\($0.prefix(4)) \($0.dropFirst(4))" } ?? "…")
                .font(.system(size: 18 * s, weight: .black, design: .monospaced))
                .lineLimit(1)
                .padding(.horizontal, 14 * s)
                .frame(minWidth: 170 * s)
                .friendChip(s)
            if let code, let url = URL(string: Krida.shared.FRIEND_URL_PREFIX + code) {
                // Krida opens the link as a universal link, like a room link.
                ShareLink(item: url, message: Text("Add me as a friend on Krida!")) { Text("Share") }
                    .buttonStyle(SecondaryButtonStyle())
            }
        }
    }
}

/// Add a friend by code, then the list; a row's × asks before removing.
private struct FriendsCard: View {
    @ObservedObject private var account = Account.shared
    @State private var removing: String?
    @Environment(\.uiScale) private var s

    private var ready: Bool { account.friendDraft.count == Int(Krida.shared.FRIEND_CODE_LENGTH) && !account.addingFriend }

    private var errorText: LocalizedStringKey? {
        switch account.friendError {
        case .notFound: "No player has that code."
        case .own: "That's your own code."
        case .already: "You're already friends."
        case .full: "The friends list is full."
        case .tooMany: "Too many tries. Wait a few minutes."
        case .failed: "That didn't work. Check your connection and try again."
        case nil: nil
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8 * s) {
            Text("Friends").font(Theme.font(20 * s, .black))
            HStack(spacing: 10 * s) {
                TextField("", text: $account.friendDraft, prompt: Text("Friend's code").font(Theme.font(16 * s, .bold)).foregroundStyle(Theme.muted))
                    .font(.system(size: 18 * s, weight: .black, design: .monospaced))
                    .tint(Theme.accent)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .keyboardType(.asciiCapable)
                    .submitLabel(.done)
                    .onSubmit { if ready { account.addFriend() } }
                    .onChange(of: account.friendDraft) { old, new in
                        let clean = Account.cleanFriendCode(new)
                        if clean != new { account.friendDraft = clean }
                        if clean != old { account.friendError = nil }
                    }
                    .padding(.horizontal, 14 * s)
                    .friendChip(s)
                Button { account.addFriend() } label: { Text("Add") }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(!ready)
            }
            if let errorText {
                Text(errorText).font(Theme.font(13 * s, .bold)).foregroundStyle(Theme.error)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if account.friends.isEmpty {
                VStack(spacing: 4 * s) {
                    // Until the first answer arrives the list is unknown, not empty.
                    if account.friendCode == nil {
                        LoadingIndicator()
                    } else {
                        Text("No friends yet").font(Theme.font(16 * s, .black))
                        Text("Share your code, or type a friend's code.").font(Theme.font(14 * s, .bold)).foregroundStyle(Theme.muted)
                    }
                }
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    VStack(spacing: 6 * s) {
                        ForEach(account.friends) { friend in
                            FriendRow(
                                friend: friend,
                                asking: removing == friend.id,
                                onAsk: { removing = friend.id },
                                onCancel: { removing = nil },
                                onRemove: { removing = nil; account.removeFriend(friend.id) }
                            )
                        }
                    }
                    // Room for the rows' outlines inside the scroll view's clip.
                    .padding(1)
                }
                .frame(maxHeight: .infinity)
            }
        }
        .padding(.horizontal, 20 * s)
        .padding(.vertical, 16 * s)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .nearbyCard(s)
    }
}

private struct FriendRow: View {
    let friend: Account.Friend
    let asking: Bool
    let onAsk: () -> Void
    let onCancel: () -> Void
    let onRemove: () -> Void
    @Environment(\.uiScale) private var s

    private var name: String { friend.name.isEmpty ? String(localized: "Player", bundle: .app) : friend.name }

    var body: some View {
        HStack(spacing: 8 * s) {
            if asking {
                Text("Remove \(name)?").font(Theme.font(15 * s, .bold)).lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                action("Cancel", Theme.muted, onCancel)
                action("Remove", Theme.error, onRemove)
            } else {
                AvatarBadge(avatar: friend.avatar, accountId: friend.id, size: 26)
                Text(verbatim: name).font(Theme.font(16 * s, .bold)).lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button {
                    Sounds.shared.play(.tap)
                    onAsk()
                } label: {
                    Image(systemName: "xmark").font(.system(size: 13 * s, weight: .bold)).foregroundStyle(Theme.muted)
                        .frame(width: 44 * s, height: 44 * s)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Remove \(name)"))
            }
        }
        .padding(.leading, 12 * s)
        .friendChip(s)
    }

    /// A text action inside the row, as tall as the row.
    private func action(_ title: LocalizedStringKey, _ color: Color, _ run: @escaping () -> Void) -> some View {
        Button {
            Sounds.shared.play(.tap)
            run()
        } label: {
            Text(title).font(Theme.font(14 * s, .heavy)).foregroundStyle(color).lineLimit(1)
                .padding(.horizontal, 10 * s)
                .frame(height: 44 * s)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
