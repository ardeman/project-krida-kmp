import Shared
import SwiftUI

private enum AvatarTab: CaseIterable {
    case shape, background, symbol

    var title: LocalizedStringKey {
        switch self {
        case .shape: "Shape"
        case .background: "Background"
        case .symbol: "Symbol"
        }
    }
}

/// Edit profile: name field on the left, shape / background / symbol picker on the right
/// (docs/mockups/profile.md). Same as Android (`account/AccountEdit.kt`).
struct AccountEdit: View {
    let user: Account.User
    let onDone: () -> Void
    @ObservedObject private var account = Account.shared
    @Environment(\.uiScale) private var s

    @State private var name: String
    @State private var mix: Avatar.Mix
    @State private var useGoogle: Bool
    @State private var tab: AvatarTab = .shape

    init(user: Account.User, onDone: @escaping () -> Void) {
        self.user = user
        self.onDone = onDone
        let initial: Avatar.Mix = {
            if let mix = Avatar.shared.parseMix(value: user.avatar) { return mix }
            return Avatar.shared.defaultMix(accountId: user.id)
        }()
        _name = State(initialValue: user.name)
        _mix = State(initialValue: initial)
        _useGoogle = State(initialValue: Avatar.shared.isGooglePictureUrl(value: user.avatar))
    }

    private var google: Bool {
        user.provider == "google" && Avatar.shared.isGooglePictureUrl(value: user.picture)
    }

    private var canSave: Bool { !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !account.savingProfile }

    var body: some View {
        HStack(spacing: 14 * s) {
            editCard
            pickerCard
        }
    }

    private var editCard: some View {
        VStack(spacing: 8 * s) {
            if useGoogle, let picture = user.picture, google {
                AvatarBadge(avatar: picture, accountId: user.id, picture: picture, size: 64)
            } else {
                AvatarBadge(avatar: mix.encode(), accountId: user.id, size: 64)
            }
            nameField
            Text("Friends and players in your rooms see this.")
                .font(Theme.font(13 * s, .bold))
                .foregroundStyle(Theme.muted)
                .multilineTextAlignment(.center)
            if let message = profileErrorText {
                Text(message).font(Theme.font(13 * s, .bold)).foregroundStyle(Theme.error)
                    .multilineTextAlignment(.center)
            }
            HStack(spacing: 10 * s) {
                Button(action: onDone) { Text("Cancel").frame(minWidth: 130 * s) }
                    .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                    .disabled(account.savingProfile)
                Button {
                    Task {
                        let avatar = useGoogle && google ? Avatar.shared.GOOGLE : mix.encode()
                        if await account.saveProfile(name: name.trimmingCharacters(in: .whitespacesAndNewlines), avatar: avatar) {
                            onDone()
                        }
                    }
                } label: {
                    Text("Save").frame(minWidth: 130 * s)
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(!canSave)
            }
            .padding(.top, 4 * s)
        }
        .padding(.horizontal, 20 * s)
        .padding(.vertical, 16 * s)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .nearbyCard(s)
    }

    private var nameField: some View {
        let clipped = String(name.prefix(24))
        return HStack {
            TextField("", text: Binding(
                get: { clipped },
                set: { name = String($0.prefix(24)); account.profileError = nil }
            ))
            .font(Theme.font(16 * s, .bold))
            .tint(Theme.accent)
            Text("\(clipped.count)/24")
                .font(Theme.font(13 * s, .bold))
                .foregroundStyle(Theme.muted)
        }
        .padding(.horizontal, 14 * s)
        .frame(height: 44 * s)
        .background(Theme.chip, in: RoundedRectangle(cornerRadius: 12 * s))
        .overlay(RoundedRectangle(cornerRadius: 12 * s).stroke(Theme.accent, lineWidth: 2))
    }

    private var profileErrorText: LocalizedStringKey? {
        switch account.profileError {
        case .badName: "Enter a name of at most 24 characters."
        case .badAvatar: "That avatar couldn't be saved. Try another."
        case .failed: "That didn't work. Check your connection and try again."
        case nil: nil
        }
    }

    private var pickerCard: some View {
        VStack(alignment: .leading, spacing: 10 * s) {
            Text("Avatar").font(Theme.font(20 * s, .black))
            HStack(spacing: 8 * s) {
                ForEach(AvatarTab.allCases, id: \.self) { t in
                    let on = t == tab
                    Button {
                        Sounds.shared.play(.tap)
                        tab = t
                    } label: {
                        Text(t.title)
                            .font(Theme.font(15 * s, .bold))
                            .foregroundStyle(on ? Color.white : Theme.muted)
                            .frame(maxWidth: .infinity)
                            .frame(height: 40 * s)
                            .background(on ? Theme.accent : Theme.chip, in: Capsule())
                            .overlay(Capsule().stroke(on ? Theme.accent : Theme.line, lineWidth: 1.5))
                    }
                    .buttonStyle(.plain)
                }
            }
            ScrollView {
                switch tab {
                case .shape: shapeGrid
                case .background:
                    colourGrid(selected: Int(mix.background), forbidden: Int(mix.symbol)) { i in
                        mix = Avatar.Mix(shape: mix.shape, background: Int32(i), symbol: mix.symbol)
                        useGoogle = false
                        account.profileError = nil
                    }
                case .symbol:
                    colourGrid(selected: Int(mix.symbol), forbidden: Int(mix.background)) { i in
                        mix = Avatar.Mix(shape: mix.shape, background: mix.background, symbol: Int32(i))
                        useGoogle = false
                        account.profileError = nil
                    }
                }
            }
            .frame(maxHeight: .infinity)
            if google, let picture = user.picture {
                Button {
                    Sounds.shared.play(.tap)
                    useGoogle = true
                    account.profileError = nil
                } label: {
                    HStack(spacing: 10 * s) {
                        AvatarBadge(avatar: picture, size: 28)
                        Text("Use my Google picture")
                            .font(Theme.font(15 * s, .bold))
                            .foregroundStyle(Theme.muted)
                            .lineLimit(1)
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 12 * s)
                    .frame(height: 44 * s)
                    .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14 * s))
                    .overlay(RoundedRectangle(cornerRadius: 14 * s).stroke(useGoogle ? Theme.accent : Theme.line, lineWidth: 1.5))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 20 * s)
        .padding(.vertical, 16 * s)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .nearbyCard(s)
    }

    private var shapeGrid: some View {
        let count = Int(Avatar.shared.SHAPE_COUNT)
        let rows = stride(from: 0, to: count, by: 7).map { Array($0..<min($0 + 7, count)) }
        return VStack(spacing: 10 * s) {
            ForEach(rows, id: \.first!) { row in
                HStack {
                    ForEach(row, id: \.self) { shape in
                        let selected = shape == Int(mix.shape)
                        let cell = Avatar.Mix(shape: Int32(shape), background: mix.background, symbol: mix.symbol)
                        Button {
                            Sounds.shared.play(.tap)
                            mix = cell
                            useGoogle = false
                            account.profileError = nil
                        } label: {
                            AvatarBadge(avatar: cell.encode(), size: selected ? 38 : 44, ring: selected)
                                .frame(width: 48 * s, height: 48 * s)
                        }
                        .buttonStyle(.plain)
                    }
                    ForEach(0..<(7 - row.count), id: \.self) { _ in
                        Color.clear.frame(width: 48 * s, height: 48 * s)
                    }
                }
                .frame(maxWidth: .infinity)
            }
        }
    }

    private func colourGrid(selected: Int, forbidden: Int, onPick: @escaping (Int) -> Void) -> some View {
        let count = Int(Avatar.shared.COLOUR_COUNT)
        let rows = stride(from: 0, to: count, by: 7).map { Array($0..<min($0 + 7, count)) }
        return VStack(spacing: 10 * s) {
            ForEach(rows, id: \.first!) { row in
                HStack {
                    ForEach(row, id: \.self) { i in
                        let disabled = i == forbidden
                        let on = i == selected
                        let argb = Avatar.shared.COLOURS.get(index: Int32(i))
                        Button {
                            Sounds.shared.play(.tap)
                            onPick(i)
                        } label: {
                            Circle()
                                .fill(avatarColor(argb))
                                .frame(width: (on ? 38 : 44) * s, height: (on ? 38 : 44) * s)
                                .overlay(Circle().stroke(Theme.line, lineWidth: 1.5))
                                .overlay { if on { Circle().stroke(Theme.accent, lineWidth: 3 * s) } }
                                .frame(width: 48 * s, height: 48 * s)
                                .opacity(disabled ? 0.25 : 1)
                        }
                        .buttonStyle(.plain)
                        .disabled(disabled)
                    }
                    ForEach(0..<(7 - row.count), id: \.self) { _ in
                        Color.clear.frame(width: 48 * s, height: 48 * s)
                    }
                }
                .frame(maxWidth: .infinity)
            }
        }
    }
}
