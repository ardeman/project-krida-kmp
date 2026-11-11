import AuthenticationServices
import CryptoKit
import Foundation
import Security
import Shared
import UIKit

/// The signed-in Krida account ("Accounts" in docs/protocol.md): Sign in with Apple, Google
/// sign-in through the browser and the relay, the session token, sign out, account
/// deletion and the friends list. Same endpoints and stored fields as Android (`account/Account.kt`).
@MainActor
final class Account: NSObject, ObservableObject {
    static let shared = Account()

    struct User: Codable, Equatable {
        let id: String
        let name: String
        let provider: String
    }

    private struct SignedIn: Decodable {
        let token: String
        let user: User
    }

    private struct Me: Decodable {
        let user: User
    }

    struct Friend: Decodable, Equatable, Identifiable {
        let id: String
        let name: String
    }

    /// Why the last add was refused; `failed` also covers an unreachable relay.
    enum FriendError {
        case notFound, own, already, full, tooMany, failed
    }

    private struct FriendList: Decodable {
        let code: String
        let friends: [Friend]
    }

    private struct Added: Decodable {
        let friend: Friend
    }

    private struct Refused: Decodable {
        let error: String
    }

    private static let userKey = "account.user"
    private static let keychainService = "com.ardeman.krida.account"
    private static let api = Krida.shared.RELAY_URL.replacingOccurrences(of: "wss://", with: "https://")

    /// The signed-in player; nil when signed out.
    @Published private(set) var user: User?
    /// A request is running: the screen shows the loading indicator and disables its buttons.
    @Published private(set) var busy = false
    /// The last sign-in, sign-out or deletion failed; cleared by the next attempt.
    @Published private(set) var failed = false

    /// This account's friend code; nil until the list has loaded.
    @Published private(set) var friendCode: String?
    @Published private(set) var friends: [Friend] = []
    /// The code being typed on the account screen; a friend link fills it in.
    @Published var friendDraft = ""
    /// An add is running.
    @Published private(set) var addingFriend = false
    @Published var friendError: FriendError?

    private var token: String?
    private var webSession: ASWebAuthenticationSession?

    private override init() {
        super.init()
        token = Self.loadToken()
        if token != nil, let data = UserDefaults.standard.data(forKey: Self.userKey) {
            user = try? JSONDecoder().decode(User.self, from: data)
        }
        // A session ended elsewhere (deleted account) signs this phone out; offline keeps it.
        guard let saved = token else { return }
        Task {
            guard let (status, data) = await send("GET", "/v1/me", token: saved), token == saved else { return }
            if status == 401 {
                clear()
            } else if status == 200, let me = try? JSONDecoder().decode(Me.self, from: data) {
                store(token: saved, user: me.user)
            }
        }
    }

    /// Opens the system's Sign in with Apple sheet.
    func signInWithApple() {
        failed = false
        let request = ASAuthorizationAppleIDProvider().createRequest()
        request.requestedScopes = [.fullName]
        let controller = ASAuthorizationController(authorizationRequests: [request])
        controller.delegate = self
        controller.presentationContextProvider = self
        controller.performRequests()
    }

    /// The result of the system's Sign in with Apple sheet.
    func finishApple(_ result: Result<ASAuthorization, Error>) {
        guard case .success(let authorization) = result,
              let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
              let identityToken = credential.identityToken.flatMap({ String(data: $0, encoding: .utf8) })
        else {
            // Closing the sheet is not an error worth a message.
            if case .failure(let error) = result, (error as? ASAuthorizationError)?.code == .canceled { return }
            failed = true
            return
        }
        var body = ["identityToken": identityToken]
        if let code = credential.authorizationCode.flatMap({ String(data: $0, encoding: .utf8) }) {
            body["authorizationCode"] = code
        }
        // Apple hands over the name only the first time; the relay keeps it after that.
        if let name = credential.fullName?.givenName, !name.isEmpty { body["name"] = name }
        Task { await signIn(path: "/v1/auth/apple", body: body) }
    }

    /// Opens Google's sign-in page in the system browser sheet and finishes with its code.
    func signInWithGoogle() {
        failed = false
        let verifier = Self.randomToken()
        let challenge = Self.base64Url(Data(SHA256.hash(data: Data(verifier.utf8))))
        guard let url = URL(string: "\(Self.api)/v1/auth/google/start?challenge=\(challenge)") else { return }
        let session = ASWebAuthenticationSession(url: url, callbackURLScheme: "krida") { [weak self] callback, _ in
            Task { @MainActor in
                guard let self else { return }
                self.webSession = nil
                // No callback: the player closed the sheet.
                guard let callback, let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems else { return }
                guard let code = items.first(where: { $0.name == "code" })?.value else {
                    self.failed = items.first(where: { $0.name == "error" })?.value != "cancelled"
                    return
                }
                await self.signIn(path: "/v1/auth/exchange", body: ["code": code, "verifier": verifier])
            }
        }
        session.presentationContextProvider = self
        webSession = session
        if !session.start() { failed = true }
    }

    func signOut() {
        guard let saved = token else { return }
        failed = false
        // Best effort: the phone forgets the session even when the relay can't be reached.
        Task { _ = await send("POST", "/v1/auth/signout", token: saved) }
        clear()
    }

    /// Deletes the account on the relay, then signs out here.
    func delete() {
        guard let saved = token, !busy else { return }
        failed = false
        busy = true
        Task {
            let status = await send("DELETE", "/v1/me", token: saved)?.0
            busy = false
            // 401: the account is already gone.
            if status == 204 || status == 401 { clear() } else { failed = true }
        }
    }

    /// The code in "https://krida.ardeman.com/f/K7M2XQ4D" or "krida://friend?code=K7M2XQ4D";
    /// nil when `link` is not a friend link.
    static func friendCode(in link: URL) -> String? {
        let raw: String?
        if link.scheme == "krida", link.host == "friend" {
            raw = URLComponents(url: link, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "code" })?.value
        } else if link.scheme == "https", link.host == "krida.ardeman.com", link.pathComponents.dropFirst().first == "f" {
            raw = link.pathComponents.dropFirst(2).first
        } else {
            return nil
        }
        return cleanFriendCode(raw ?? "")
    }

    /// Fills in the code from a friend link. True when `link` was one.
    func handleFriendLink(_ link: URL) -> Bool {
        guard let code = Self.friendCode(in: link) else { return false }
        friendDraft = code
        friendError = nil
        return true
    }

    /// Upper-case letters and digits only, at most the code's length.
    static func cleanFriendCode(_ text: String) -> String {
        String(text.uppercased().filter { $0.isASCII && ($0.isLetter || $0.isNumber) }.prefix(Int(Krida.shared.FRIEND_CODE_LENGTH)))
    }

    /// Fetches the friend code and the list; the screen calls it when it opens.
    func loadFriends() {
        guard let saved = token else { return }
        Task {
            // An answer this build can't read leaves the list as it was.
            guard let (status, data) = await send("GET", "/v1/friends", token: saved), token == saved, status == 200,
                  let list = try? JSONDecoder().decode(FriendList.self, from: data) else { return }
            friendCode = list.code
            friends = list.friends
        }
    }

    /// Adds the player with `friendDraft` as a friend; instant and mutual.
    func addFriend() {
        guard let saved = token, !addingFriend, friendDraft.count == Int(Krida.shared.FRIEND_CODE_LENGTH) else { return }
        friendError = nil
        addingFriend = true
        Task {
            let reply = await send("POST", "/v1/friends", token: saved, body: ["code": friendDraft])
            guard token == saved else { return }
            addingFriend = false
            if let (status, data) = reply, status == 200, let added = try? JSONDecoder().decode(Added.self, from: data).friend {
                friends = (friends.filter { $0.id != added.id } + [added]).sorted { $0.name.lowercased() < $1.name.lowercased() }
                friendDraft = ""
            } else {
                switch reply.flatMap({ try? JSONDecoder().decode(Refused.self, from: $0.1) })?.error {
                case "code_not_found": friendError = .notFound
                case "own_code": friendError = .own
                case "already_friends": friendError = .already
                case "friends_full": friendError = .full
                case "too_many_tries": friendError = .tooMany
                default: friendError = .failed
                }
            }
        }
    }

    /// Ends the friendship for both players.
    func removeFriend(_ id: String) {
        guard let saved = token else { return }
        let before = friends
        friendError = nil
        friends.removeAll { $0.id == id }
        Task {
            let status = await send("DELETE", "/v1/friends/\(id)", token: saved)?.0
            guard token == saved, status != 204 else { return }
            friends = before
            friendError = .failed
        }
    }

    private func signIn(path: String, body: [String: String]) async {
        busy = true
        let reply = await send("POST", path, body: body)
        busy = false
        if let (status, data) = reply, status == 200, let signedIn = try? JSONDecoder().decode(SignedIn.self, from: data) {
            store(token: signedIn.token, user: signedIn.user)
        } else {
            failed = true
        }
    }

    private func store(token: String, user: User) {
        self.token = token
        self.user = user
        Self.saveToken(token)
        UserDefaults.standard.set(try? JSONEncoder().encode(user), forKey: Self.userKey)
    }

    private func clear() {
        token = nil
        user = nil
        friendCode = nil
        friends = []
        addingFriend = false
        friendError = nil
        Self.saveToken(nil)
        UserDefaults.standard.removeObject(forKey: Self.userKey)
    }

    /// The status and body, or nil when the relay wasn't reached.
    private func send(_ method: String, _ path: String, token: String? = nil, body: [String: String]? = nil) async -> (Int, Data)? {
        guard let url = URL(string: Self.api + path) else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = method
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try? JSONEncoder().encode(body)
        }
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              let status = (response as? HTTPURLResponse)?.statusCode else { return nil }
        return (status, data)
    }

    private static func randomToken() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return base64Url(Data(bytes))
    }

    private static func base64Url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    // The session token lives in the Keychain, not UserDefaults.

    private static var keychainQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: keychainService, kSecAttrAccount as String: "token"]
    }

    private static func loadToken() -> String? {
        var query = keychainQuery
        query[kSecReturnData as String] = true
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private static func saveToken(_ token: String?) {
        SecItemDelete(keychainQuery as CFDictionary)
        guard let token else { return }
        var item = keychainQuery
        item[kSecValueData as String] = Data(token.utf8)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(item as CFDictionary, nil)
    }
}

extension Account: ASAuthorizationControllerDelegate {
    nonisolated func authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
        Task { @MainActor in finishApple(.success(authorization)) }
    }

    nonisolated func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        Task { @MainActor in finishApple(.failure(error)) }
    }
}

extension Account: ASWebAuthenticationPresentationContextProviding, ASAuthorizationControllerPresentationContextProviding {
    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated { Self.keyWindow }
    }

    nonisolated func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        MainActor.assumeIsolated { Self.keyWindow }
    }

    private static var keyWindow: ASPresentationAnchor {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.flatMap(\.windows).first(where: \.isKeyWindow) ?? ASPresentationAnchor()
    }
}
