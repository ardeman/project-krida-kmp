@preconcurrency import GoogleMobileAds
import SwiftUI
@preconcurrency import UserMessagingPlatform

/// AdMob: a banner on the menus, an interstitial after every [interstitialEvery]th finished game,
/// and a rewarded ad that pays for a hint. Owning "Remove ads" ([removed]) turns all of it off;
/// hints are then free. Nothing is requested before the UMP consent flow allows it.
/// Mirrors androidApp/.../ads/Ads.kt. Ad unit ids live in AdIds.swift.
@MainActor
final class Ads: NSObject, ObservableObject {
    static let shared = Ads()

    private static let interstitialEvery = 2
    private static let removedKey = "ads.removed"

    /// The player owns "Remove ads". Cached so ads never flash on before the App Store answers.
    @Published private(set) var removed = UserDefaults.standard.bool(forKey: removedKey)
    /// The consent rules for this user require a way back into the consent form.
    @Published private(set) var privacyOptionsRequired = false
    @Published private(set) var started = false
    /// Price of "Remove ads" from the App Store; nil hides the buy button.
    @Published private(set) var removeAdsPrice: String?

    private var store: Store!
    private var interstitial: InterstitialAd?
    private var rewarded: RewardedAd?
    private var loadingInterstitial = false
    private var loadingRewarded = false
    private var finishedGames = 0
    /// UI tests / store screenshots: keep the paid "Remove ads" layout; ignore StoreKit.
    private var skipAds = false
    /// Runs once when the full-screen ad on screen goes away, shown or not.
    private var onClosed: (() -> Void)?

    private override init() {
        super.init()
        store = Store { [weak self] owned in self?.updateOwned(owned) }
        store.onChange = { [weak self] in self?.updatePrice() }
    }

    /// Call once at launch: asks for consent where the rules require it, then starts AdMob.
    func start() {
        // UI tests / store shots (`-skipAds`): paid "Remove ads" layout — no UMP, no ad units.
        if ProcessInfo.processInfo.arguments.contains("-skipAds") {
            skipAds = true
            removed = true
            started = true
            removeAdsPrice = nil
            return
        }
        // Consent given in an earlier session lets ads start without waiting for the update.
        startAds()
        Task {
            let consent = ConsentInformation.shared
            if (try? await consent.requestConsentInfoUpdate(with: RequestParameters())) != nil {
                try? await ConsentForm.loadAndPresentIfRequired(from: nil)
                privacyOptionsRequired = consent.privacyOptionsRequirementStatus == .required
            }
            startAds()
        }
    }

    /// Re-reads purchases, e.g. when the app comes back to the foreground.
    func refresh() { Task { await store.refresh() } }

    func buyRemoveAds() { Task { await store.buy() } }

    func restorePurchases() { Task { await store.restore() } }

    func showPrivacyOptions() { ConsentForm.presentPrivacyOptionsForm(from: nil) { _ in } }

    /// Call when the player leaves a finished game; [then] runs after the interstitial,
    /// if one was due and ready, is closed.
    func afterGame(then: @escaping () -> Void) {
        finishedGames += 1
        guard !removed, let ad = interstitial, finishedGames % Self.interstitialEvery == 0 else {
            loadInterstitial()
            return then()
        }
        interstitial = nil
        onClosed = { [weak self] in self?.loadInterstitial(); then() }
        ad.present(from: nil)
    }

    /// Runs [onReward] once the player has watched a rewarded ad. Free when ads are removed,
    /// and when no ad could be loaded (offline, no fill): the player asked, so don't punish them.
    func rewarded(onReward: @escaping () -> Void) {
        guard !removed, let ad = rewarded else {
            loadRewarded()
            return onReward()
        }
        rewarded = nil
        var earned = false
        onClosed = { [weak self] in
            self?.loadRewarded()
            if earned { onReward() }
        }
        ad.present(from: nil) { earned = true }
    }

    private func startAds() {
        guard !started, !removed, ConsentInformation.shared.canRequestAds else { return }
        started = true
        MobileAds.shared.start()
        loadInterstitial()
        loadRewarded()
    }

    private func updateOwned(_ value: Bool) {
        if skipAds { return }
        guard value != removed else { return }
        removed = value
        UserDefaults.standard.set(value, forKey: Self.removedKey)
        if value {
            interstitial = nil
            rewarded = nil
        } else {
            startAds()
        }
        updatePrice()
    }

    private func updatePrice() {
        removeAdsPrice = removed ? nil : store.price
    }

    private func loadInterstitial() {
        guard started, !removed, interstitial == nil, !loadingInterstitial else { return }
        loadingInterstitial = true
        Task {
            let ad = try? await InterstitialAd.load(with: AdIds.interstitial, request: Request())
            ad?.fullScreenContentDelegate = self
            interstitial = removed ? nil : ad
            loadingInterstitial = false
        }
    }

    private func loadRewarded() {
        guard started, !removed, rewarded == nil, !loadingRewarded else { return }
        loadingRewarded = true
        Task {
            let ad = try? await RewardedAd.load(with: AdIds.rewarded, request: Request())
            ad?.fullScreenContentDelegate = self
            rewarded = removed ? nil : ad
            loadingRewarded = false
        }
    }

    private func closed() {
        let done = onClosed
        onClosed = nil
        done?()
    }
}

extension Ads: FullScreenContentDelegate {
    nonisolated func adDidDismissFullScreenContent(_ ad: FullScreenPresentingAd) {
        Task { @MainActor in closed() }
    }

    nonisolated func ad(_ ad: FullScreenPresentingAd, didFailToPresentFullScreenContentWithError error: Error) {
        Task { @MainActor in closed() }
    }
}

/// Standard 320×50 banner, centered: the adaptive sizes run ~90 pt tall in landscape and would
/// push the mode cards off a phone screen. Nothing when ads are off or not allowed yet.
struct AdBanner: View {
    @ObservedObject private var ads = Ads.shared

    var body: some View {
        if !ads.removed && ads.started {
            BannerHost()
                .frame(width: AdSizeBanner.size.width, height: AdSizeBanner.size.height)
                .frame(maxWidth: .infinity)
        }
    }
}

private struct BannerHost: UIViewRepresentable {
    func makeUIView(context: Context) -> BannerView {
        let view = BannerView(adSize: AdSizeBanner)
        view.adUnitID = AdIds.banner
        view.load(Request())
        return view
    }

    func updateUIView(_ view: BannerView, context: Context) {}
}
