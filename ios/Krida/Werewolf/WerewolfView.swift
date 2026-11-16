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
            var guardCount = 0
            while lifetime.active && guardCount < 48 {
                try await Task.sleep(for: .seconds(Self.botPause))
                guardCount += 1
                if !table.stepBot() { break }
            }
        }
    }

    private func syncPhaseTimer() {
        let hostFlag = table.isHost ? "h" : "g"
        let actors = game.actingSeats(state: ruleState).map(\.intValue).sorted()
        let key = "\(state.phase)-\(state.day)-\(hostFlag)-\(actors)"
        guard key != phaseKey else { return }
        phaseKey = key
        lifetime.cancel("phase")
        lifetime.cancel("discuss-bots")
        phaseSeconds = phaseTotal()
        witchHealChoice = nil
        witchPoisonTarget = nil
        selectedTarget = nil
        auto = false

        if table.isHost, state.phase == WerewolfPhase.shared.DISCUSS {
            lifetime.launch("discuss-bots") { [self] in
                try await Task.sleep(for: .seconds(2))
                while lifetime.active, phaseKey == key {
                    _ = table.stepDiscussClaim()
                    try await Task.sleep(for: .seconds(3.5))
                }
            }
        }

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
    /// Day/night ink follows the phase, not system appearance (day sky is always light).
    private var boardInk: Color { night ? Color(hex: 0xF6EADC) : Color(hex: 0x2B1D14) }
    private var boardMuted: Color { night ? Color(hex: 0xB7A593) : Color(hex: 0x5A4537) }
    private var boardSurface: Color { night ? Color(hex: 0x243044).opacity(0.92) : Color(hex: 0xFFFBF5) }
    /// Day chips are white with a stronger rim so they don’t melt into the cream panel.
    private var boardChip: Color { night ? Color.white.opacity(0.22) : Color.white }
    private var boardLine: Color { night ? Color.white.opacity(0.4) : Color(hex: 0x8B6F52) }

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
        .foregroundStyle(boardInk)
        .onDisappear { model.stop() }
        .sheet(isPresented: $showRules) { WerewolfRulesSheet() }
    }

    private var atmosphere: some View {
        let top = night ? Color(hex: 0x1A2332) : Color(hex: 0xB9D4EF)
        let bottom = night ? Color(hex: 0x0F1520) : Color(hex: 0xF3E6D2)
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
            Text(phaseTitle).font(Theme.font(12 * s, .bold)).foregroundStyle(boardMuted).lineLimit(2)
            Text(String(localized: "Day \(state.day)", bundle: .app))
                .font(Theme.font(12 * s, .bold))
                .foregroundStyle(boardMuted)
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

    /// Distinct seat tints so bots aren’t a wall of identical computer icons.
    private func seatTint(_ seat: Int) -> Color {
        let colors: [UInt32] = [
            0x4A5D78, 0x6B4E3D, 0x3D5A45, 0x5A4568,
            0x6B5A3D, 0x3D5568, 0x684545, 0x456068,
        ]
        return Color(hex: colors[seat % colors.count])
    }

    private func seatTile(_ seat: Int) -> some View {
        let alive = state.alive.indices.contains(seat) && state.alive[seat].boolValue
        let sheriff = state.sheriff?.intValue == seat
        let voted = seatHasVoted(seat)
        let bot = table.botSeats.contains(seat)
        let avatar = table.players.avatar(of: seat)
        let shape = RoundedRectangle(cornerRadius: 12 * s)
        let number = "\(seat + 1)"
        return VStack(spacing: 4 * s) {
            ZStack(alignment: .topTrailing) {
                ZStack(alignment: .bottomTrailing) {
                    if bot {
                        BotFace(avatar: avatar, size: 32)
                            .opacity(alive ? 1 : 0.35)
                    } else if let avatar {
                        AvatarBadge(avatar: avatar, size: 32)
                            .opacity(alive ? 1 : 0.35)
                    } else {
                        Text(number)
                            .font(Theme.font(16 * s, .black))
                            .foregroundStyle(Color(hex: 0xFFF6E8).opacity(alive ? 1 : 0.5))
                            .frame(width: 32 * s, height: 32 * s)
                            .background(seatTint(seat).opacity(alive ? 1 : 0.45), in: Circle())
                            .overlay(Circle().stroke(boardLine, lineWidth: 1))
                    }
                    if bot || avatar != nil {
                        Text(number)
                            .font(Theme.font(9 * s, .black))
                            .foregroundStyle(Color.white)
                            .padding(.horizontal, 4 * s)
                            .background(seatTint(seat), in: Capsule())
                            .offset(x: 2 * s, y: 2 * s)
                    }
                }
                if sheriff {
                    Image(systemName: "star.fill")
                        .font(.system(size: 10 * s))
                        .foregroundStyle(Theme.gold)
                        .offset(x: 4 * s, y: -4 * s)
                }
            }
            Text(seatName(seat))
                .font(Theme.font(10 * s, .bold))
                .lineLimit(1)
                .minimumScaleFactor(0.65)
            if seat == table.mySeat, !model.myRole.isEmpty, !model.finished {
                Text(roleName(model.myRole))
                    .font(Theme.font(9 * s, .bold))
                    .foregroundStyle(Theme.accent)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            if voted {
                Text(String(localized: "Voted", bundle: .app))
                    .font(Theme.font(9 * s, .semibold))
                    .foregroundStyle(Theme.accent)
            }
            if !alive {
                Text(String(localized: "Eliminated", bundle: .app))
                    .font(Theme.font(9 * s, .semibold))
                    .foregroundStyle(boardMuted)
            }
        }
        .padding(6 * s)
        .frame(maxWidth: .infinity)
        .background((voted ? boardChip : boardSurface).opacity(alive ? 1 : 0.55), in: shape)
        .overlay(shape.stroke(voted ? Theme.accent : boardLine, lineWidth: voted ? 2 : 1))
        .accessibilityLabel("\(number), \(seatName(seat))")
        .onTapGesture {
            guard seat == table.mySeat, !model.myRole.isEmpty else { return }
            model.showRole = true
        }
    }

    private func seatHasVoted(_ seat: Int) -> Bool {
        let voting = state.phase == WerewolfPhase.shared.VOTE
            || state.phase == WerewolfPhase.shared.SHERIFF_ELECT
        guard voting,
              state.alive.indices.contains(seat), state.alive[seat].boolValue,
              state.votes.indices.contains(seat)
        else { return false }
        return (state.votes[seat] as? KotlinInt) != nil
    }

    private var centerPanel: some View {
        let roleTint = night ? Color(hex: 0xFFF0D0) : Color(hex: 0x7A4A1E)
        return VStack(spacing: 12 * s) {
            Text(phaseHeadline).font(Theme.font(22 * s, .black)).multilineTextAlignment(.center)
            if let body = phaseBody {
                Text(body).font(Theme.font(14 * s, .semibold)).foregroundStyle(boardMuted).multilineTextAlignment(.center)
            }
            if !model.myRole.isEmpty, !model.finished {
                Button {
                    model.showRole = true
                } label: {
                    VStack(spacing: 4 * s) {
                        Text(String(localized: "Your role: \(roleName(model.myRole))", bundle: .app))
                            .font(Theme.font(14 * s, .bold))
                            .foregroundStyle(roleTint)
                        Text(roleBlurb(model.myRole))
                            .font(Theme.font(12 * s, .bold))
                            .foregroundStyle(boardMuted)
                            .multilineTextAlignment(.center)
                    }
                }
                .buttonStyle(.plain)
                .accessibilityHint(String(localized: "Tap to see your role again", bundle: .app))
            }
            dawnBanner
            actionArea
        }
        .padding(16 * s)
        .background(boardSurface, in: RoundedRectangle(cornerRadius: 20 * s))
        .overlay(RoundedRectangle(cornerRadius: 20 * s).stroke(boardLine, lineWidth: 1.5))
    }

    @ViewBuilder
    private var dawnBanner: some View {
        if !state.lastDeaths.isEmpty, state.phase != WerewolfPhase.shared.NIGHT {
            let names = state.lastDeaths.map { table.name($0.intValue) }.joined(separator: ", ")
            Text(String(localized: "Dawn · \(names) eliminated", bundle: .app))
                .font(Theme.font(13 * s, .bold))
                .foregroundStyle(Color.white)
                .padding(.horizontal, 12 * s)
                .padding(.vertical, 6 * s)
                .background(Color(hex: 0xC0392B), in: Capsule())
        } else if state.phase != WerewolfPhase.shared.NIGHT, state.lastDeaths.isEmpty, state.day > 0 {
            Text(String(localized: "Peaceful night", bundle: .app))
                .font(Theme.font(13 * s, .bold))
                .foregroundStyle(boardInk)
                .padding(.horizontal, 12 * s)
                .padding(.vertical, 6 * s)
                .background(boardChip, in: Capsule())
        }
    }

    @ViewBuilder
    private var actionArea: some View {
        if !model.alive {
            Text(String(localized: "You died", bundle: .app)).font(Theme.font(16 * s, .black))
        } else if state.phase == WerewolfPhase.shared.DISCUSS {
            Text(String(localized: "Use a claim chip (and emotes), then vote when time ends.", bundle: .app))
                .font(Theme.font(14 * s, .semibold))
                .foregroundStyle(boardMuted)
                .multilineTextAlignment(.center)
            if table.canClaim, model.alive {
                ClaimChipsView(table: table, me: model.me, ink: boardInk, muted: boardMuted, chip: boardChip, line: boardLine, s: s)
            }
        } else if state.phase == WerewolfPhase.shared.VOTE || state.phase == WerewolfPhase.shared.SHERIFF_ELECT {
            let living = (0..<Int(state.players)).filter {
                state.alive.indices.contains($0) && state.alive[$0].boolValue
            }
            let cast = living.filter { seatHasVoted($0) }.count
            Text(String(localized: "Voted \(cast) / \(living.count)", bundle: .app))
                .font(Theme.font(12 * s, .bold))
                .foregroundStyle(boardMuted)
            if model.acting {
                Text(actionPrompt).font(Theme.font(14 * s, .bold))
                Text(String(localized: "Tap a name below to choose.", bundle: .app))
                    .font(Theme.font(12 * s, .semibold))
                    .foregroundStyle(boardMuted)
                targetGrid(allowSelf: false) { seat in
                    model.pickTarget(seat)
                    model.confirmTarget()
                }
                if canAbstain {
                    boardSecondary(String(localized: "Abstain", bundle: .app)) { model.abstain() }
                }
            } else {
                Text(String(localized: "Waiting for others…", bundle: .app))
                    .font(Theme.font(14 * s, .semibold))
                    .foregroundStyle(boardMuted)
            }
        } else if !model.acting {
            Text(String(localized: "Waiting for others…", bundle: .app))
                .font(Theme.font(14 * s, .semibold))
                .foregroundStyle(boardMuted)
        } else if model.myRole == WerewolfRole.shared.WITCH && state.phase == WerewolfPhase.shared.NIGHT {
            witchActions
        } else {
            Text(actionPrompt).font(Theme.font(14 * s, .bold))
            Text(String(localized: "Tap a name below to choose.", bundle: .app))
                .font(Theme.font(12 * s, .semibold))
                .foregroundStyle(boardMuted)
            targetGrid(allowSelf: model.myRole == WerewolfRole.shared.DOCTOR && state.phase == WerewolfPhase.shared.NIGHT) { seat in
                model.pickTarget(seat)
                model.confirmTarget()
            }
            HStack(spacing: 10 * s) {
                if canAbstain {
                    boardSecondary(String(localized: "Abstain", bundle: .app)) { model.abstain() }
                }
                if state.phase == WerewolfPhase.shared.NIGHT {
                    boardSecondary(String(localized: "Pass tonight", bundle: .app)) { model.skipNight() }
                }
            }
        }
        if let peek = seerPeekText {
            Text(peek)
                .font(Theme.font(13 * s, .bold))
                .foregroundStyle(boardInk)
                .padding(.horizontal, 10 * s)
                .padding(.vertical, 6 * s)
                .background(boardChip, in: Capsule())
                .multilineTextAlignment(.center)
        }
    }

    private func boardSecondary(_ title: String, action: @escaping () -> Void) -> some View {
        Button(title, action: action)
            .font(Theme.font(16 * s))
            .foregroundStyle(boardInk)
            .padding(.horizontal, 18 * s)
            .frame(height: 44 * s)
            .background(boardChip, in: RoundedRectangle(cornerRadius: 14 * s))
            .overlay(RoundedRectangle(cornerRadius: 14 * s).stroke(boardLine, lineWidth: 1.5))
    }

    private var witchActions: some View {
        let needHeal = state.witchHealLeft && model.witchHealChoice == nil
        return VStack(spacing: 8 * s) {
            if needHeal {
                Text(String(localized: "Heal the victim", bundle: .app)).font(Theme.font(13 * s, .bold))
                HStack(spacing: 10 * s) {
                    boardSecondary(String(localized: "Heal the victim", bundle: .app)) {
                        model.setWitchHeal(true)
                        if !state.witchPoisonLeft { model.confirmWitch() }
                    }
                    boardSecondary(String(localized: "Skip heal", bundle: .app)) {
                        model.setWitchHeal(false)
                        if !state.witchPoisonLeft { model.confirmWitch() }
                    }
                }
            } else if state.witchPoisonLeft {
                Text(String(localized: "Poison someone", bundle: .app)).font(Theme.font(13 * s, .bold))
                Text(String(localized: "Tap a name below to choose.", bundle: .app))
                    .font(Theme.font(12 * s, .semibold))
                    .foregroundStyle(boardMuted)
                targetGrid(allowSelf: false) { seat in
                    model.pickTarget(seat)
                    model.confirmWitch()
                }
                boardSecondary(String(localized: "Skip poison", bundle: .app)) {
                    model.witchPoisonTarget = -1
                    model.confirmWitch()
                }
            } else {
                boardSecondary(String(localized: "Pass tonight", bundle: .app)) { model.skipNight() }
            }
        }
    }

    private func targetGrid(allowSelf: Bool, onChoose: @escaping (Int) -> Void) -> some View {
        let living = (0..<Int(state.players)).filter { seat in
            guard state.alive.indices.contains(seat), state.alive[seat].boolValue else { return false }
            return allowSelf || seat != model.me
        }
        // Full-width adaptive grid so Claims / Election / Night all pack 4–5 when they fit.
        return SeatChipGrid(table: table, seats: living, ink: boardInk, chip: boardChip, line: boardLine, s: s) { seat in
            onChoose(seat)
        } label: { seat in
            seatChipLabel(seat)
        }
    }

    /// Tight pick chips: bots are face + seat number (full “Computer N” truncates).
    private func seatChipLabel(_ seat: Int) -> String {
        if seat == table.mySeat { return seatName(seat) }
        if table.botSeats.contains(seat) { return "\(seat + 1)" }
        return "\(seat + 1) · \(seatName(seat))"
    }

    /// Claim feed labels always include the seat number (bots: “7 · Computer”).
    private func shortSeatName(_ seat: Int) -> String {
        if seat == table.mySeat { return seatName(seat) }
        let n = seat + 1
        if table.botSeats.contains(seat) {
            return "\(n) · \(String(localized: "Computer", bundle: .app))"
        }
        return "\(n) · \(seatName(seat))"
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
            claimFeed
            TurnTimer(
                seconds: model.phaseSeconds,
                total: model.phaseTotal(),
                auto: model.auto,
                size: 44,
                ink: boardInk,
                onResume: model.resumeManual
            )
            .clearPunchHole(hole, .bottomTrailing, already: 24 * s)
        }
        .frame(width: 136 * s)
    }

    @ViewBuilder
    private var claimFeed: some View {
        if table.claims.isEmpty {
            Spacer(minLength: 0)
        } else {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .trailing, spacing: 4 * s) {
                        ForEach(table.claims) { event in
                            let speaker = shortSeatName(event.seat)
                            let target = event.target.map(shortSeatName)
                            let bot = table.botSeats.contains(event.seat)
                            let avatar = table.players.avatar(of: event.seat)
                            HStack(alignment: .top, spacing: 4 * s) {
                                if bot {
                                    BotFace(avatar: avatar, size: 16)
                                } else if let avatar {
                                    AvatarBadge(avatar: avatar, size: 16)
                                }
                                Text("\(speaker): \(claimLine(event.claimId, target: target))")
                                    .font(Theme.font(11 * s, .bold))
                                    .foregroundStyle(boardInk)
                                    .multilineTextAlignment(.leading)
                            }
                            .padding(.horizontal, 8 * s)
                            .padding(.vertical, 6 * s)
                            .background(boardSurface, in: RoundedRectangle(cornerRadius: 10 * s))
                            .id(event.id)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .trailing)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .onAppear {
                    if let id = table.claims.last?.id {
                        proxy.scrollTo(id, anchor: .bottom)
                    }
                }
                .onChange(of: table.claims.last?.n) { _, _ in
                    if let id = table.claims.last?.id {
                        withAnimation(.easeOut(duration: 0.2)) {
                            proxy.scrollTo(id, anchor: .bottom)
                        }
                    }
                }
            }
        }
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
                Text(roleBlurb(model.myRole))
                    .font(Theme.font(13 * s, .semibold))
                    .foregroundStyle(Theme.muted)
                    .multilineTextAlignment(.center)
                Button(String(localized: "Done", bundle: .app)) { model.showRole = false }
                    .buttonStyle(PrimaryButtonStyle())
            }
            .padding(24 * s)
            .frame(maxWidth: 360 * s)
            // Theme.surface + Theme.ink (not boardInk): finished/day ink is dark and vanishes on a dark card.
            .foregroundStyle(Theme.ink)
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
            .foregroundStyle(Theme.ink)
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
        default: "Pick a target"
        }
    }

    private var roleActionPrompt: LocalizedStringKey {
        switch model.myRole {
        case WerewolfRole.shared.WEREWOLF: "Pick a victim"
        case WerewolfRole.shared.SEER: "Peek at someone"
        case WerewolfRole.shared.DOCTOR: "Protect someone"
        default: "Pick a target"
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

    private var phaseBody: String? { nil }

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

private func roleBlurb(_ id: String) -> String {
    switch id {
    case WerewolfRole.shared.WEREWOLF:
        String(localized: "Each night, pick one living player to eliminate. Wolves see each other.", bundle: .app)
    case WerewolfRole.shared.SEER:
        String(localized: "Each night, peek one living player’s exact role.", bundle: .app)
    case WerewolfRole.shared.DOCTOR:
        String(localized: "Each night, protect one living player (you may pick yourself).", bundle: .app)
    case WerewolfRole.shared.HUNTER:
        String(localized: "No night action. If you die, you shoot one living player.", bundle: .app)
    case WerewolfRole.shared.WITCH:
        String(localized: "Once each per game: heal tonight’s victim, and/or poison someone.", bundle: .app)
    default:
        String(localized: "No night action. Survive and vote with the village.", bundle: .app)
    }
}

struct WerewolfRulesSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.uiScale) private var s

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10 * s) {
                    Text(String(localized: "5–8 players get secret roles. Villagers win by eliminating all werewolves; wolves win when they match or outnumber the village.", bundle: .app))
                    Text(String(localized: "Werewolves choose one victim each night (majority; random tie). Wolves see each other.", bundle: .app))
                    Text(String(localized: "The Seer peeks one living seat; the Doctor protects one; the Witch may heal or poison once each per game; the Hunter shoots when eliminated.", bundle: .app))
                    Text(String(localized: "Sheriff (Kepala desa) is elected when vacant. Lynch votes count double. When the Sheriff dies, they name a successor.", bundle: .app))
                    Text(String(localized: "Night actions, dawn deaths, discussion (claim chips and emotes), then a lynch vote. Timers auto-resolve unused actions.", bundle: .app))
                }
                .font(Theme.font(14 * s, .semibold))
                .foregroundStyle(Theme.muted)
                .padding()
            }
            .navigationTitle(Text("How to play"))
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

private func claimLine(_ id: String, target: String?) -> String {
    switch id {
    case "suspect":
        String(localized: "I suspect \(target ?? "…")", bundle: .app)
    case "trust":
        String(localized: "I trust \(target ?? "…")", bundle: .app)
    case "saw_wolf":
        String(localized: "\(target ?? "…") looked like a wolf", bundle: .app)
    case "i_seer":
        String(localized: "I am the Seer", bundle: .app)
    case "i_doctor":
        String(localized: "I am the Doctor", bundle: .app)
    case "i_villager":
        String(localized: "I am a Villager", bundle: .app)
    case "i_hunter":
        String(localized: "I am the Hunter", bundle: .app)
    default:
        String(localized: "Nothing to add", bundle: .app)
    }
}

private struct ClaimChipsView: View {
    @ObservedObject var table: WerewolfTable
    let me: Int
    let ink: Color
    let muted: Color
    let chip: Color
    let line: Color
    let s: CGFloat
    @State private var pending: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6 * s) {
            Text(String(localized: "Claims", bundle: .app))
                .font(Theme.font(12 * s, .bold))
                .foregroundStyle(muted)
            if let pending, WerewolfClaims.shared.needsTarget(id: pending) {
                Text(String(localized: "Who?", bundle: .app))
                    .font(Theme.font(12 * s, .bold))
                targetRow(for: pending)
                Button(String(localized: "Done", bundle: .app)) { self.pending = nil }
                    .font(Theme.font(16 * s))
                    .foregroundStyle(ink)
                    .frame(maxWidth: .infinity)
                    .frame(height: 44 * s)
                    .background(Color.white, in: RoundedRectangle(cornerRadius: 14 * s))
                    .overlay(RoundedRectangle(cornerRadius: 14 * s).stroke(line, lineWidth: 1.5))
            } else {
                chipFlow
            }
        }
    }

    private var chipFlow: some View {
        let items = Array(WerewolfClaims.shared.ALL)
        let rows = stride(from: 0, to: items.count, by: 2).map { Array(items[$0..<min($0 + 2, items.count)]) }
        return VStack(alignment: .leading, spacing: 6 * s) {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                HStack(spacing: 6 * s) {
                    ForEach(row, id: \.id) { item in
                        Button {
                            if item.needsTarget {
                                pending = item.id
                            } else {
                                table.sendClaim(item.id)
                            }
                        } label: {
                            Text(claimLine(item.id, target: "…"))
                                .font(Theme.font(11 * s, .bold))
                                .foregroundStyle(ink)
                                .lineLimit(1)
                                .minimumScaleFactor(0.8)
                                .padding(.horizontal, 10 * s)
                                .padding(.vertical, 8 * s)
                                .frame(maxWidth: .infinity)
                                .background(chip, in: RoundedRectangle(cornerRadius: 10 * s))
                                .overlay(RoundedRectangle(cornerRadius: 10 * s).stroke(line, lineWidth: 1))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    private func targetRow(for claimId: String) -> some View {
        let living = (0..<Int(table.latest.players)).filter { seat in
            guard table.latest.alive.indices.contains(seat), table.latest.alive[seat].boolValue else { return false }
            return seat != me
        }
        return SeatChipGrid(table: table, seats: living, ink: ink, chip: chip, line: line, s: s) { seat in
            table.sendClaim(claimId, target: seat)
            pending = nil
        } label: { seat in
            if seat == me {
                String(localized: "You", bundle: .app)
            } else if table.botSeats.contains(seat) {
                "\(seat + 1)"
            } else {
                "\(seat + 1) · \(table.name(seat))"
            }
        }
    }
}

/// Face + short label chips that pack across Claims “Who?”, votes, and night.
private struct SeatChipGrid: View {
    let table: WerewolfTable
    let seats: [Int]
    let ink: Color
    let chip: Color
    let line: Color
    let s: CGFloat
    let onChoose: (Int) -> Void
    let label: (Int) -> String

    var body: some View {
        LazyVGrid(
            columns: [GridItem(.adaptive(minimum: 56 * s), spacing: 6 * s)],
            spacing: 6 * s
        ) {
            ForEach(seats, id: \.self) { seat in
                Button {
                    onChoose(seat)
                } label: {
                    HStack(spacing: 4 * s) {
                        chipFace(seat)
                        Text(label(seat))
                            .font(Theme.font(12 * s, .bold))
                            .foregroundStyle(ink)
                            .lineLimit(1)
                            .minimumScaleFactor(0.75)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, 8 * s)
                    .padding(.vertical, 6 * s)
                    .background(chip, in: RoundedRectangle(cornerRadius: 10 * s))
                    .overlay(RoundedRectangle(cornerRadius: 10 * s).stroke(line, lineWidth: 1.5))
                }
                .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func chipFace(_ seat: Int) -> some View {
        let avatar = table.players.avatar(of: seat)
        if table.botSeats.contains(seat) {
            BotFace(avatar: avatar, size: 18)
        } else if let avatar {
            AvatarBadge(avatar: avatar, size: 18)
        }
    }
}