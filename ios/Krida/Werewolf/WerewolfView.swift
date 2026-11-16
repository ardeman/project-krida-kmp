import Shared
import SwiftUI

@MainActor
final class WerewolfGameModel: ObservableObject {
    @Published private(set) var state: WerewolfState
    @Published private(set) var phaseSeconds = 0
    @Published private(set) var auto = false
    @Published var showRole = false
    @Published var witchHealChoice: Bool?
    @Published var witchPoisonTarget: Int?

    let table: WerewolfTable
    var loading = false
    private let game = WerewolfGame.shared
    private let sounds = Sounds.shared
    private let lifetime = GameLifetime()
    private var phaseKey: String?
    private var roleShown = false
    @Published private(set) var selectedTarget: Int?

    private static let botPause: Double = 0.5

    private func makeMove(target: Int? = nil, witchHeal: Bool? = nil, done: Bool = false, advance: Bool = false) -> WerewolfMove {
        WerewolfMove(
            target: target.map { KotlinInt(int: Int32($0)) },
            witchHeal: witchHeal.map { KotlinBoolean(bool: $0) },
            done: done,
            advance: advance
        )
    }

    init(table: WerewolfTable) {
        self.table = table
        state = table.latest
        table.onUpdate = { [weak self] update in
            guard let self, self.lifetime.active else { return }
            self.state = update.state
            self.syncPhaseTimer()
            self.maybeShowRole()
            if update.fresh { self.scheduleBot() }
        }
        syncPhaseTimer()
        maybeShowRole()
        scheduleBot()
    }

    var me: Int { table.mySeat }
    var alive: Bool { state.alive.indices.contains(me) && state.alive[me].boolValue }
    var myRole: String { state.roles.indices.contains(me) ? state.roles[me] : "" }
    var acting: Bool { game.actingSeats(state: ruleState).contains { $0.intValue == me } }
    var finished: Bool { state.phase == WerewolfPhase.shared.FINISHED || state.winnerSide != nil }

    private var ruleState: WerewolfState { table.authority ?? state }

    func phaseTotal() -> Int {
        switch state.phase {
        case WerewolfPhase.shared.NIGHT:
            return Int(Krida.shared.WEREWOLF_NIGHT_MS / 1000)
        case WerewolfPhase.shared.DISCUSS:
            return Int(Krida.shared.WEREWOLF_DISCUSS_MS / 1000)
        default:
            return Int(Krida.shared.WEREWOLF_VOTE_MS / 1000)
        }
    }

    private func maybeShowRole() {
        guard !roleShown, !myRole.isEmpty, state.day <= 1 else { return }
        roleShown = true
        showRole = true
    }

    private func scheduleBot() {
        lifetime.launch("bot") { [self] in
            while loading { try await Task.sleep(for: .milliseconds(100)) }
            try await Task.sleep(for: .seconds(Self.botPause))
            table.stepBot()
        }
    }

    private func syncPhaseTimer() {
        let hostFlag = table.isHost ? "h" : "g"
        let actors = game.actingSeats(state: ruleState).map(\.intValue).sorted()
        let key = "\(state.phase)-\(state.day)-\(hostFlag)-\(actors)"
        guard key != phaseKey else { return }
        phaseKey = key
        lifetime.cancel("phase")
        phaseSeconds = phaseTotal()
        witchHealChoice = nil
        witchPoisonTarget = nil
        selectedTarget = nil
        auto = false

        lifetime.launch("phase") { [self] in
            let total = phaseTotal()
            var left = total
            while left > 0 {
                try await Task.sleep(for: .seconds(1))
                guard lifetime.active else { return }
                if phaseKey != key { return }
                left -= 1
                phaseSeconds = left
            }
            resolveTimeouts()
        }
    }

    private func resolveTimeouts() {
        let full = ruleState
        if table.isHost {
            if full.phase == WerewolfPhase.shared.DISCUSS {
                _ = table.act(seat: 0, move: makeMove(advance: true))
                return
            }
            for seat in game.actingSeats(state: full) {
                let s = Int(seat.int32Value)
                if pending(full, seat: s),
                   let move = game.timeoutMove(state: full, seat: Int32(s), random: KotlinRandom.companion) {
                    _ = table.act(seat: s, move: move)
                }
            }
            scheduleBot()
            return
        }
        guard acting, alive else { return }
        auto = true
        lifetime.launch("auto") { [self] in
            try await Task.sleep(for: .seconds(Double(Krida.shared.AUTO_PLAY_MS) / 1000))
            guard acting, alive else { return }
            if let move = game.timeoutMove(state: ruleState, seat: Int32(me), random: KotlinRandom.companion) {
                _ = table.act(seat: me, move: move)
            }
        }
    }

    private func pending(_ state: WerewolfState, seat: Int) -> Bool {
        switch state.phase {
        case WerewolfPhase.shared.NIGHT:
            return game.nightActors(state: state).map(\.intValue).contains(seat)
                && state.nightDone.indices.contains(seat) && !state.nightDone[seat].boolValue
        case WerewolfPhase.shared.VOTE, WerewolfPhase.shared.SHERIFF_ELECT:
            // KMP exposes List<Int?> as NSArray; an unset vote is missing KotlinInt.
            return state.votes.indices.contains(seat) && (state.votes[seat] as? KotlinInt) == nil
        case WerewolfPhase.shared.HUNTER:
            return state.hunterSeat?.intValue == seat
        case WerewolfPhase.shared.SUCCESSOR:
            return state.successorFrom?.intValue == seat
        default: return false
        }
    }

    func pickTarget(_ seat: Int) {
        guard acting, alive else { return }
        selectedTarget = seat
        sounds.play(.tap)
    }

    func abstain() {
        guard acting else { return }
        _ = submit(makeMove(target: -1))
    }

    func confirmTarget() {
        guard acting, let t = selectedTarget else { return }
        _ = submit(makeMove(target: t))
    }

    func skipNight() {
        guard acting, state.phase == WerewolfPhase.shared.NIGHT else { return }
        let role = myRole
        if role == WerewolfRole.shared.WITCH {
            finishWitch(heal: false, poison: -1)
            return
        }
        if role == WerewolfRole.shared.VILLAGER || state.nightDone[me].boolValue {
            _ = submit(makeMove(done: true))
            return
        }
        _ = submit(makeMove(target: -1, done: true))
    }

    func setWitchHeal(_ heal: Bool) {
        witchHealChoice = heal
    }

    func confirmWitch() {
        let poison = witchPoisonTarget ?? selectedTarget ?? -1
        finishWitch(heal: witchHealChoice ?? false, poison: poison)
    }

    private func finishWitch(heal: Bool, poison: Int) {
        _ = submit(makeMove(target: poison >= 0 ? poison : -1, witchHeal: heal, done: true))
    }

    private func submit(_ move: WerewolfMove) -> Bool {
        auto = false
        sounds.play(.tap)
        return table.act(seat: me, move: move)
    }

    func resumeManual() {
        auto = false
        phaseSeconds = phaseTotal()
    }

    func stop() {
        lifetime.stop()
        table.leave()
        sounds.stopGameAudio()
    }
}

struct WerewolfView: View {
    let table: WerewolfTable
    let onExit: () -> Void

    var body: some View {
        WerewolfBoardView(table: table, onExit: onExit)
    }
}

private struct WerewolfBoardView: View {
    @ObservedObject var table: WerewolfTable
    @StateObject private var model: WerewolfGameModel
    @ObservedObject private var ads = Ads.shared
    @State private var showRules = false
    @Environment(\.uiScale) private var s
    @Environment(\.punchHole) private var hole
    let onExit: () -> Void

    init(table: WerewolfTable, onExit: @escaping () -> Void) {
        self.table = table
        _model = StateObject(wrappedValue: WerewolfGameModel(table: table))
        self.onExit = onExit
    }

    private var state: WerewolfState { model.state }
    private var night: Bool { state.phase == WerewolfPhase.shared.NIGHT }

    var body: some View {
        ZStack {
            atmosphere
            HStack(alignment: .top, spacing: 0) {
                leftColumn
                Spacer(minLength: 12 * s)
                centerPanel.frame(maxWidth: .infinity, maxHeight: .infinity)
                Spacer(minLength: 12 * s)
                rightColumn
            }
            .padding(.horizontal, 24 * s)
            .padding(.vertical, 14 * s)
            notices
            if model.showRole { roleCard }
            if model.finished { resultCard }
        }
        .foregroundStyle(Theme.ink)
        .onDisappear { model.stop() }
        .sheet(isPresented: $showRules) { WerewolfRulesSheet() }
    }

    private var atmosphere: some View {
        let top = night ? Color(hex: 0x1A2332) : Color(hex: 0xC9D4E2)
        let bottom = night ? Color(hex: 0x0F1520) : Color(hex: 0xE8EEF5)
        return LinearGradient(colors: [top, bottom], startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea()
    }

    private func exit() {
        model.stop()
        ads.afterGame(then: onExit)
    }

    private func seatName(_ seat: Int) -> String {
        seat == table.mySeat ? String(localized: "You", bundle: .app) : table.name(seat)
    }

    private var leftColumn: some View {
        let count = Int(state.players)
        let rows = stride(from: 0, to: count, by: 2).map { Array($0..<min($0 + 2, count)) }
        return VStack(alignment: .leading, spacing: 8 * s) {
            CircleIconButton(systemImage: "chevron.left", label: "Back to menu", action: exit)
                .clearPunchHole(hole, .topLeading, already: 24 * s)
            Text("Werewolf").font(Theme.font(18 * s, .black))
            Text(phaseTitle).font(Theme.font(12 * s, .bold)).foregroundStyle(Theme.muted).lineLimit(2)
            Text(String(localized: "Day \(state.day)", bundle: .app))
                .font(Theme.font(12 * s, .bold))
                .foregroundStyle(Theme.muted)
            ScrollView {
                VStack(spacing: 8 * s) {
                    ForEach(rows, id: \.self) { pair in
                        HStack(spacing: 8 * s) {
                            ForEach(pair, id: \.self) { seat in
                                seatTile(seat)
                            }
                            if pair.count == 1 { Spacer(minLength: 0) }
                        }
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .frame(width: 148 * s, alignment: .leading)
    }

    private func seatTile(_ seat: Int) -> some View {
        let alive = state.alive.indices.contains(seat) && state.alive[seat].boolValue
        let sheriff = state.sheriff?.intValue == seat
        let bot = table.botSeats.contains(seat)
        let avatar = bot ? nil : table.players.avatar(of: seat)
        let shape = RoundedRectangle(cornerRadius: 12 * s)
        let sel = model.selectedTarget == seat
        return Button {
            if model.acting && alive { model.pickTarget(seat) }
        } label: {
            VStack(spacing: 4 * s) {
                ZStack(alignment: .topTrailing) {
                    if let avatar {
                        AvatarBadge(avatar: avatar, size: 32)
                            .opacity(alive ? 1 : 0.35)
                    } else if bot {
                        Image(systemName: "desktopcomputer")
                            .font(.system(size: 14 * s, weight: .semibold))
                            .frame(width: 32 * s, height: 32 * s)
                            .background(Theme.track, in: Circle())
                    } else {
                        Circle().fill(Theme.chip).frame(width: 32 * s, height: 32 * s)
                    }
                    if sheriff {
                        Image(systemName: "star.fill")
                            .font(.system(size: 10 * s))
                            .foregroundStyle(Theme.gold)
                            .offset(x: 4 * s, y: -4 * s)
                    }
                }
                Text(seatName(seat)).font(Theme.font(10 * s, .bold)).lineLimit(1)
                if !alive {
                    Text(String(localized: "Eliminated", bundle: .app))
                        .font(Theme.font(9 * s, .semibold))
                        .foregroundStyle(Theme.muted)
                }
            }
            .padding(6 * s)
            .frame(maxWidth: .infinity)
            .background(sel ? Theme.accent.opacity(0.2) : Theme.surface.opacity(alive ? 0.92 : 0.5), in: shape)
            .overlay(shape.stroke(sel ? Theme.accent : Theme.line, lineWidth: sel ? 2 : 1))
        }
        .buttonStyle(.plain)
        .disabled(!alive)
    }

    private var centerPanel: some View {
        VStack(spacing: 12 * s) {
            Text(phaseHeadline).font(Theme.font(22 * s, .black)).multilineTextAlignment(.center)
            if let body = phaseBody {
                Text(body).font(Theme.font(14 * s, .semibold)).foregroundStyle(Theme.muted).multilineTextAlignment(.center)
            }
            dawnBanner
            actionArea
        }
        .padding(16 * s)
        .background(Theme.surface.opacity(0.88), in: RoundedRectangle(cornerRadius: 20 * s))
        .overlay(RoundedRectangle(cornerRadius: 20 * s).stroke(Theme.line, lineWidth: 1.5))
    }

    @ViewBuilder
    private var dawnBanner: some View {
        if !state.lastDeaths.isEmpty, state.phase != WerewolfPhase.shared.NIGHT {
            let names = state.lastDeaths.map { table.name($0.intValue) }.joined(separator: ", ")
            Text(String(localized: "Dawn · \(names) eliminated", bundle: .app))
                .font(Theme.font(13 * s, .bold))
                .foregroundStyle(Theme.error)
                .padding(.horizontal, 12 * s)
                .padding(.vertical, 6 * s)
                .background(Theme.chip, in: Capsule())
        } else if state.phase != WerewolfPhase.shared.NIGHT, state.lastDeaths.isEmpty, state.day > 0 {
            Text(String(localized: "Peaceful night", bundle: .app))
                .font(Theme.font(13 * s, .bold))
                .foregroundStyle(Theme.muted)
        }
    }

    @ViewBuilder
    private var actionArea: some View {
        if !model.alive {
            Text(String(localized: "You died", bundle: .app)).font(Theme.font(16 * s, .black))
        } else if !model.acting {
            Text(String(localized: "Waiting for others…", bundle: .app))
                .font(Theme.font(14 * s, .semibold))
                .foregroundStyle(Theme.muted)
        } else if model.myRole == WerewolfRole.shared.WITCH && state.phase == WerewolfPhase.shared.NIGHT {
            witchActions
        } else {
            HStack(spacing: 10 * s) {
                if canAbstain {
                    Button(String(localized: "Abstain", bundle: .app)) { model.abstain() }
                        .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                }
                Button(String(localized: "Skip", bundle: .app)) { model.skipNight() }
                    .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                Button(actionPrompt) { model.confirmTarget() }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(model.selectedTarget == nil && !canAbstain)
            }
        }
        if let peek = seerPeekText {
            Text(peek).font(Theme.font(12 * s, .bold)).foregroundStyle(Theme.accent).multilineTextAlignment(.center)
        }
    }

    private var witchActions: some View {
        VStack(spacing: 8 * s) {
            if state.witchHealLeft {
                Text(String(localized: "Heal the victim", bundle: .app)).font(Theme.font(13 * s, .bold))
                HStack {
                    Button(String(localized: "Heal the victim", bundle: .app)) { model.setWitchHeal(true) }
                        .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                    Button(String(localized: "Skip", bundle: .app)) { model.setWitchHeal(false) }
                        .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                }
            }
            if state.witchPoisonLeft {
                Text(String(localized: "Poison someone", bundle: .app)).font(Theme.font(13 * s, .bold))
            }
            Button(String(localized: "Done", bundle: .app)) { model.confirmWitch() }
                .buttonStyle(PrimaryButtonStyle())
        }
    }

    private var rightColumn: some View {
        VStack(alignment: .trailing, spacing: 8 * s) {
            HStack(spacing: 8 * s) {
                SoundToggles()
                CircleIconButton(systemImage: "questionmark", label: "How to play") { showRules = true }
            }
            if table.canEmote {
                EmoteHud(canEmote: true, emotes: table.emotes, playerName: seatName, onSend: table.sendEmote, s: s)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
            Spacer(minLength: 0)
            TurnTimer(
                seconds: model.phaseSeconds,
                total: model.phaseTotal(),
                auto: model.auto,
                size: 44,
                onResume: model.resumeManual
            )
            .clearPunchHole(hole, .bottomTrailing, already: 24 * s)
        }
        .frame(width: 136 * s)
    }

    @ViewBuilder
    private var notices: some View {
        Group {
            if let away = table.waiting {
                CountdownNotice(text: away.text, seconds: away.seconds, total: Int(Krida.shared.HANDOFF_WAIT_MS / 1000), fontSize: 14)
            } else if let notice = table.notice {
                Text(notice)
                    .font(Theme.font(14 * s, .bold))
                    .foregroundStyle(Theme.error)
                    .padding(.horizontal, 14 * s)
                    .padding(.vertical, 8 * s)
                    .background(Theme.surface.opacity(0.92), in: RoundedRectangle(cornerRadius: 12 * s))
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 12 * s)
    }

    private var roleCard: some View {
        ZStack {
            Color.black.opacity(0.35).ignoresSafeArea()
            VStack(spacing: 10 * s) {
                Text(String(localized: "Your role", bundle: .app)).font(Theme.font(20 * s, .black))
                Text(roleName(model.myRole)).font(Theme.font(18 * s, .bold))
                Button(String(localized: "Done", bundle: .app)) { model.showRole = false }
                    .buttonStyle(PrimaryButtonStyle())
            }
            .padding(24 * s)
            .frame(maxWidth: 360 * s)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 20 * s))
        }
    }

    private var resultCard: some View {
        let town = state.winnerSide == "town"
        let side = state.teamOf(seat: Int32(model.me))
        let mine = town ? side == "town" : side == "wolves"
        let title = town
            ? String(localized: "Town wins", bundle: .app)
            : String(localized: "Wolves win", bundle: .app)
        return ZStack {
            Color.black.opacity(0.35).ignoresSafeArea()
            VStack(spacing: 12 * s) {
                Text(title).font(Theme.font(22 * s, .black))
                Text(mine ? String(localized: "You won", bundle: .app) : String(localized: "You lost", bundle: .app))
                    .font(Theme.font(15 * s, .bold))
                    .foregroundStyle(Theme.muted)
                Button(String(localized: "Back to menu", bundle: .app), action: exit)
                    .buttonStyle(PrimaryButtonStyle())
            }
            .padding(24 * s)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 20 * s))
        }
    }

    private var canAbstain: Bool {
        state.phase == WerewolfPhase.shared.VOTE
    }

    private var actionPrompt: LocalizedStringKey {
        switch state.phase {
        case WerewolfPhase.shared.NIGHT: roleActionPrompt
        case WerewolfPhase.shared.VOTE: "Vote to lynch"
        case WerewolfPhase.shared.SHERIFF_ELECT: "Vote for Sheriff"
        case WerewolfPhase.shared.HUNTER: "Shoot someone"
        case WerewolfPhase.shared.SUCCESSOR: "Name a successor"
        default: "Confirm"
        }
    }

    private var roleActionPrompt: LocalizedStringKey {
        switch model.myRole {
        case WerewolfRole.shared.WEREWOLF: "Pick a victim"
        case WerewolfRole.shared.SEER: "Peek at someone"
        case WerewolfRole.shared.DOCTOR: "Protect someone"
        default: "Confirm"
        }
    }

    private var phaseTitle: String {
        switch state.phase {
        case WerewolfPhase.shared.NIGHT: String(localized: "Night", bundle: .app)
        case WerewolfPhase.shared.DISCUSS: String(localized: "Discussion", bundle: .app)
        case WerewolfPhase.shared.VOTE: String(localized: "Vote", bundle: .app)
        case WerewolfPhase.shared.SHERIFF_ELECT: String(localized: "Election", bundle: .app)
        case WerewolfPhase.shared.HUNTER: String(localized: "Hunter", bundle: .app)
        case WerewolfPhase.shared.SUCCESSOR: String(localized: "Successor", bundle: .app)
        default: ""
        }
    }

    private var phaseHeadline: String { phaseTitle }

    private var phaseBody: String? {
        if state.phase == WerewolfPhase.shared.DISCUSS {
            return String(localized: "No chat — use emotes, then vote.", bundle: .app)
        }
        if model.acting, state.phase == WerewolfPhase.shared.DISCUSS {
            return String(localized: "No chat — use emotes, then vote.", bundle: .app)
        }
        return nil
    }

    private var seerPeekText: String? {
        guard model.myRole == WerewolfRole.shared.SEER,
              let t = state.seerPeekTarget,
              let role = state.seerPeekRole, !role.isEmpty else { return nil }
        return "\(table.name(t.intValue)): \(roleName(role))"
    }
}

private func roleName(_ id: String) -> String {
    switch id {
    case WerewolfRole.shared.WEREWOLF: String(localized: "Werewolf", bundle: .app)
    case WerewolfRole.shared.SEER: String(localized: "Seer", bundle: .app)
    case WerewolfRole.shared.DOCTOR: String(localized: "Doctor", bundle: .app)
    case WerewolfRole.shared.HUNTER: String(localized: "Hunter", bundle: .app)
    case WerewolfRole.shared.WITCH: String(localized: "Witch", bundle: .app)
    default: String(localized: "Villager", bundle: .app)
    }
}

struct WerewolfRulesSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.uiScale) private var s

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10 * s) {
                    Text(String(localized: "Find the wolves before they outnumber the village.", bundle: .app))
                    Text(String(localized: "No chat — use emotes, then vote.", bundle: .app))
                }
                .font(Theme.font(14 * s, .semibold))
                .padding()
            }
            .navigationTitle(Text("Werewolf"))
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}