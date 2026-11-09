import Foundation
import Shared

/// The running game this device left as a guest (`SavedRoom`), so the menu can offer to go
/// back and joining its code again rejoins the same seat. Same key and format as Android.
@MainActor
final class LastRoom: ObservableObject {
    static let shared = LastRoom()
    private static let key = "rooms.last"

    /// The saved seat while it is fresh; nil when there is none.
    @Published private(set) var current: SavedRoom?

    private init() {
        let saved = SavedRoom.companion.decode(text: UserDefaults.standard.string(forKey: Self.key))
        current = saved.flatMap { $0.isFresh(now: Self.now) ? $0 : nil }
    }

    private static var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    func save(code: String, online: Bool, game: String, token: String) {
        let room = SavedRoom(code: code, online: online, game: game, token: token, savedAt: Self.now)
        current = room
        UserDefaults.standard.set(room.encode(), forKey: Self.key)
    }

    /// The saved token for `code`, to send when joining it.
    func token(for code: String, online: Bool) -> String? {
        guard let room = current, room.code == code, room.online == online, room.isFresh(now: Self.now) else { return nil }
        return room.token
    }

    /// Forgets the seat in room `code` (any room when nil).
    func clear(_ code: String? = nil) {
        guard let room = current, code == nil || room.code == code else { return }
        current = nil
        UserDefaults.standard.removeObject(forKey: Self.key)
    }
}
