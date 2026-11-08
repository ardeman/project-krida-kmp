import AVFoundation
import SwiftUI

/// Krida's sounds: effects and Indonesian voice lines (`Audio/*.m4a`, made with ElevenLabs in
/// project-pamerstudio-node/krida), and looping background music. The ambient session mixes
/// with other apps' audio and follows the silent switch. Sound and music are toggled apart.
@MainActor
final class Sounds: ObservableObject {
    static let shared = Sounds()

    enum Effect: String, CaseIterable {
        case sow = "sfx_sow", store = "sfx_store", capture = "sfx_capture", turn = "sfx_turn"
        case win = "sfx_win", lose = "sfx_lose", tap = "sfx_tap"
    }

    enum Line: String, CaseIterable {
        case yourTurn = "vo_your_turn", theirTurn = "vo_their_turn", shoot = "vo_shoot"
        case win = "vo_win", lose = "vo_lose", draw = "vo_draw"
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
    private var lines: [Line: AVAudioPlayer] = [:]
    private var music: AVAudioPlayer?
    private var active = false

    private init() {
        try? AVAudioSession.sharedInstance().setCategory(.ambient)
        for effect in Effect.allCases {
            effects[effect] = (0..<(effect == .sow ? 4 : 2)).compactMap { _ in Self.player(effect.rawValue) }
        }
        for line in Line.allCases { lines[line] = Self.player(line.rawValue) }
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
    private static let volume: [Effect: Float] = [.sow: 0.45, .store: 0.6, .tap: 0.4]

    func play(_ effect: Effect) {
        guard soundOn, let pool = effects[effect], let player = pool.first(where: { !$0.isPlaying }) ?? pool.first else { return }
        player.volume = Self.volume[effect] ?? 1
        player.currentTime = 0
        player.play()
    }

    /// A voice line cuts off the one before it, so two never talk over each other.
    func say(_ line: Line) {
        guard soundOn, let player = lines[line] else { return }
        lines.values.filter(\.isPlaying).forEach { $0.stop() }
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
                             label: sounds.soundOn ? "Matikan suara" : "Nyalakan suara") { sounds.soundOn.toggle() }
            // SF Symbols has no slashed note, so music off is the note struck through.
            CircleIconButton(systemImage: "music.note", label: sounds.musicOn ? "Matikan musik" : "Nyalakan musik",
                             struck: !sounds.musicOn) { sounds.musicOn.toggle() }
        }
    }
}
