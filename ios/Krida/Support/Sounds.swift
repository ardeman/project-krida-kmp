import AVFoundation
import SwiftUI

/// Krida's sounds: effects and voice lines in Indonesian
/// and English (`Audio/*.m4a`, made with ElevenLabs in
/// project-pamerstudio-node/krida), and looping background music. The ambient session mixes
/// with other apps' audio and follows the silent switch. Sound and music are toggled apart.
@MainActor
final class Sounds: ObservableObject {
    static let shared = Sounds()

    enum Effect: String, CaseIterable {
        case sow = "sfx_sow", store = "sfx_store", capture = "sfx_capture", turn = "sfx_turn"
        case win = "sfx_win", lose = "sfx_lose", tap = "sfx_tap"
        case die = "sfx_die", step = "sfx_step"
    }

    enum Line: String, CaseIterable {
        case yourTurn = "vo_your_turn", theirTurn = "vo_their_turn", shoot = "vo_shoot"
        case win = "vo_win", lose = "vo_lose", draw = "vo_draw"
        case pick = "vo_pick", rollAgain = "vo_roll_again", captured = "vo_captured", home = "vo_home"
        case ladder = "vo_ladder", snake = "vo_snake", pass = "vo_pass"
    }

    @AppStorage("soundOn") var soundOn = true {
        willSet { objectWillChange.send() }
    }
    @AppStorage("musicOn") var musicOn = true {
        willSet { objectWillChange.send() }
        didSet { updateMusic() }
    }

    /// A few players per effect so quick seed drops can overlap.
    private var effects: [Effect: [AVAudioPlayer]] = [:]
    /// Each line in Indonesian and in English (`<name>_en`), spoken in the app language.
    private var lines: [Line: AVAudioPlayer] = [:]
    private var linesEn: [Line: AVAudioPlayer] = [:]
    private var music: AVAudioPlayer?
    private var active = false

    private init() {
        try? AVAudioSession.sharedInstance().setCategory(.ambient)
        for effect in Effect.allCases {
            effects[effect] = (0..<(effect == .sow || effect == .step || effect == .die ? 4 : 2)).compactMap { _ in Self.player(effect.rawValue) }
        }
        for line in Line.allCases {
            lines[line] = Self.player(line.rawValue)
            linesEn[line] = Self.player(line.rawValue + "_en")
        }
        music = Self.player("bgm")
        music?.numberOfLoops = -1
        music?.volume = 0.35
    }

    private static func player(_ name: String) -> AVAudioPlayer? {
        guard let url = Bundle.main.url(forResource: name, withExtension: "m4a") else { return nil }
        let player = try? AVAudioPlayer(contentsOf: url)
        player?.prepareToPlay()
        return player
    }

    /// Seed clicks repeat many times a move, so they sit under the voice and the music.
    private static let volume: [Effect: Float] = [.sow: 0.45, .store: 0.6, .tap: 0.4, .die: 0.6, .step: 0.45]

    func play(_ effect: Effect) {
        guard soundOn, let pool = effects[effect], let player = pool.first(where: { !$0.isPlaying }) ?? pool.first else { return }
        player.volume = Self.volume[effect] ?? 1
        player.currentTime = 0
        player.play()
    }

    /// Leaving a board stops its effects and voice without interrupting menu music or taps.
    func stopGameAudio() {
        for (effect, pool) in effects where effect != .tap {
            for player in pool { player.stop() }
        }
        for player in Array(lines.values) + Array(linesEn.values) { player.stop() }
    }

    /// A voice line cuts off the one before it, so two never talk over each other.
    func say(_ line: Line) {
        let english = Language.shared.code == .en
        guard soundOn, let player = english ? linesEn[line] : lines[line] else { return }
        (Array(lines.values) + Array(linesEn.values)).filter(\.isPlaying).forEach { $0.stop() }
        player.currentTime = 0
        player.play()
    }

    /// Music plays only while the app is in the foreground.
    func setActive(_ value: Bool) {
        active = value
        if value { try? AVAudioSession.sharedInstance().setActive(true) }
        updateMusic()
    }

    private func updateMusic() {
        guard let music else { return }
        if active && musicOn {
            if !music.isPlaying { music.play() }
        } else if music.isPlaying {
            music.pause()
        }
    }
}

/// Sound and music on/off, as two round buttons.
struct SoundToggles: View {
    @ObservedObject private var sounds = Sounds.shared
    @Environment(\.uiScale) private var s

    var body: some View {
        HStack(spacing: 8 * s) {
            CircleIconButton(systemImage: sounds.soundOn ? "speaker.wave.2.fill" : "speaker.slash.fill",
                             label: sounds.soundOn ? "Turn sound off" : "Turn sound on") { sounds.soundOn.toggle() }
            // SF Symbols has no slashed note, so music off is the note struck through.
            CircleIconButton(systemImage: "music.note", label: sounds.musicOn ? "Turn music off" : "Turn music on",
                             struck: !sounds.musicOn) { sounds.musicOn.toggle() }
        }
    }
}
