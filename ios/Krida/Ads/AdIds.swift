/// AdMob and App Store ids: the one place to change them (the app id is `GADApplicationIdentifier`
/// in project.yml). These are Google's public TEST ids; replace them with the real ones from the
/// AdMob console before release. Shipping test ids earns nothing; using real ids while developing
/// risks the account, so add test devices first.
enum AdIds {
    static let banner = "ca-app-pub-3940256099942544/2934735716"
    static let interstitial = "ca-app-pub-3940256099942544/4411468910"
    static let rewarded = "ca-app-pub-3940256099942544/1712485313"
    /// Non-consumable in-app purchase in App Store Connect, priced Rp 15.000 (same id as on Play).
    static let removeAds = "hapus_iklan"
}
