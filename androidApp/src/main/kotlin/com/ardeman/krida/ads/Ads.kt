package com.ardeman.krida.ads

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ardeman.krida.R
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * AdMob: a banner on the menus, an interstitial after every [INTERSTITIAL_EVERY]th finished
 * game, and a rewarded ad that pays for a hint. Owning "Hapus iklan" ([removed]) turns all of
 * it off; hints are then free. Nothing is requested before the UMP consent flow allows it.
 * Ad unit ids live in res/values/ads.xml.
 */
object Ads {

    private const val INTERSTITIAL_EVERY = 2
    private const val PREFS = "ads"
    private const val KEY_REMOVED = "removed"

    /** The player owns "Hapus iklan". Cached so ads never flash on before Play answers. */
    var removed by mutableStateOf(false)
        private set

    /** The consent rules for this user require a way back into the consent form. */
    var privacyOptionsRequired by mutableStateOf(false)
        private set

    private var started by mutableStateOf(false)
    private lateinit var app: Context
    private var interstitial: InterstitialAd? = null
    private var rewarded: RewardedAd? = null
    private var loadingInterstitial = false
    private var loadingRewarded = false
    private var finishedGames = 0

    /** Price of "Hapus iklan" from Play; null hides the button. */
    val removeAdsPrice: String? get() = if (removed) null else Billing.price

    fun init(activity: Activity) {
        app = activity.applicationContext
        removed = prefs().getBoolean(KEY_REMOVED, false)
        Billing.connect(app, ::updateOwned)

        val consent = UserMessagingPlatform.getConsentInformation(activity)
        consent.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    privacyOptionsRequired = consent.privacyOptionsRequirementStatus ==
                        ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
                    start(consent)
                }
            },
            { start(consent) },
        )
        // Consent given in an earlier session lets ads start without waiting for the update.
        start(consent)
    }

    /** Re-reads purchases, e.g. when the app comes back to the foreground. */
    fun refresh() = Billing.refresh()

    fun buyRemoveAds(activity: Activity) = Billing.buy(activity)

    fun showPrivacyOptions(activity: Activity) = UserMessagingPlatform.showPrivacyOptionsForm(activity) { }

    /**
     * Call when the player leaves a finished game; [then] runs after the interstitial,
     * if one was due and ready, is closed.
     */
    fun afterGame(activity: Activity, then: () -> Unit) {
        finishedGames++
        val ad = interstitial
        if (removed || ad == null || finishedGames % INTERSTITIAL_EVERY != 0) {
            loadInterstitial()
            return then()
        }
        interstitial = null
        ad.fullScreenContentCallback = closed { loadInterstitial(); then() }
        ad.show(activity)
    }

    /**
     * Runs [onReward] once the player has watched a rewarded ad. Free when ads are removed,
     * and when no ad could be loaded (offline, no fill): the player asked, so don't punish them.
     */
    fun rewarded(activity: Activity, onReward: () -> Unit) {
        val ad = rewarded
        if (removed || ad == null) {
            loadRewarded()
            return onReward()
        }
        rewarded = null
        var earned = false
        ad.fullScreenContentCallback = closed { loadRewarded(); if (earned) onReward() }
        ad.show(activity) { earned = true }
    }

    /**
     * Anchored adaptive banner across the given width, with [trailing] beside it (the menu's
     * "Hapus iklan"); nothing when ads are off or not allowed yet.
     */
    @Composable
    fun Banner(modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
        if (removed || !started) return
        val unit = stringResource(R.string.admob_banner)
        val context = LocalContext.current
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BoxWithConstraints(Modifier.weight(1f)) {
                val width = maxWidth.value.toInt()
                val size = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, width)
                key(width) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().height(size.height.dp),
                        factory = { ctx ->
                            AdView(ctx).apply {
                                setAdSize(size)
                                adUnitId = unit
                                loadAd(AdRequest.Builder().build())
                            }
                        },
                        onRelease = { it.destroy() },
                    )
                }
            }
            trailing()
        }
    }

    private fun start(consent: ConsentInformation) {
        if (started || removed || !consent.canRequestAds()) return
        started = true
        MobileAds.initialize(app) { }
        loadInterstitial()
        loadRewarded()
    }

    private fun updateOwned(value: Boolean) {
        if (value == removed) return
        removed = value
        prefs().edit().putBoolean(KEY_REMOVED, value).apply()
        if (value) {
            interstitial = null
            rewarded = null
        }
    }

    private fun loadInterstitial() {
        if (!started || removed || interstitial != null || loadingInterstitial) return
        loadingInterstitial = true
        InterstitialAd.load(app, app.getString(R.string.admob_interstitial), AdRequest.Builder().build(), object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) { interstitial = ad; loadingInterstitial = false }
            override fun onAdFailedToLoad(error: LoadAdError) { loadingInterstitial = false }
        })
    }

    private fun loadRewarded() {
        if (!started || removed || rewarded != null || loadingRewarded) return
        loadingRewarded = true
        RewardedAd.load(app, app.getString(R.string.admob_rewarded), AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
            override fun onAdLoaded(ad: RewardedAd) { rewarded = ad; loadingRewarded = false }
            override fun onAdFailedToLoad(error: LoadAdError) { loadingRewarded = false }
        })
    }

    /** Calls [done] once when a full-screen ad goes away, shown or not. */
    private fun closed(done: () -> Unit) = object : FullScreenContentCallback() {
        override fun onAdDismissedFullScreenContent() = done()
        override fun onAdFailedToShowFullScreenContent(error: AdError) = done()
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
