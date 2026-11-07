/// AdMob and App Store ids: the one place to change them (the app id is `GADApplicationIdentifier`
/// in project.yml). Release builds use the real ids from the AdMob console (publisher
/// pub-4887874546732768); Debug builds use Google's public TEST ids, so developing never requests
/// real ads (clicking your own real ads risks the account).
enum AdIds {
    #if DEBUG
    static let banner = "ca-app-pub-3940256099942544/2934735716"
    static let interstitial = "ca-app-pub-3940256099942544/4411468910"
    static let rewarded = "ca-app-pub-3940256099942544/1712485313"
    #else
    static let banner = "ca-app-pub-4887874546732768/9076967050"
    static let interstitial = "ca-app-pub-4887874546732768/8336786361"
    static let rewarded = "ca-app-pub-4887874546732768/6304473214"
    #endif
    /// Non-consumable in-app purchase in App Store Connect, priced Rp 15.000 (same id as on Play).
    static let removeAds = "hapus_iklan"
}
