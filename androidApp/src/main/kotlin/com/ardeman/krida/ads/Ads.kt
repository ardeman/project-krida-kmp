package com.ardeman.krida.ads

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ardeman.krida.R
import com.ardeman.krida.ui.PrimaryButton
import com.ardeman.krida.ui.SecondaryButton
import com.ardeman.krida.ui.Theme
import com.google.android.gms.ads.AdListener
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
 * AdMob: a banner on the menus, an interstitial when leaving a game or restarting after a
 * finish (when one is ready), and a rewarded ad that pays for a hint. Owning "Hapus iklan"
 * ([removed]) turns all of it off; hints are then free. Nothing is requested before the UMP
 * consent flow allows it. Interstitials show as soon as one is ready; rewarded ads still open
 * behind a short gate with "Hapus iklan" (AdMob forbids overlaying the creative). Ad unit ids
 * live in res/values/ads.xml (real ids in release; Google's test ids in debug).
 */
object Ads {

    private const val PREFS = "ads"
    private const val KEY_REMOVED = "removed"
    private const val KEY_DEBUG_REMOVED = "debugRemoved"
    private const val EXTRA_DEBUG_REMOVED = "adsRemoved"
    /** Cap retries so a long NO_FILL stretch does not spin forever. */
    private const val MAX_LOAD_ATTEMPTS = 5

    /** The player owns "Hapus iklan". Cached so ads never flash on before Play answers. */
    var removed by mutableStateOf(false)
        private set

    /** The consent rules for this user require a way back into the consent form. */
    var privacyOptionsRequired by mutableStateOf(false)
        private set

    private var started by mutableStateOf(false)
    private var debugRemoved = false
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var interstitial: InterstitialAd? = null
    private var rewarded: RewardedAd? = null
    private var loadingInterstitial = false
    private var loadingRewarded = false
    private var gateView: ComposeView? = null
    private var gateOnSkip: (() -> Unit)? = null

    /** Price of "Hapus iklan" from Play; null hides the button. */
    val removeAdsPrice: String? get() = if (removed) null else Billing.price

    fun init(activity: Activity) {
        app = activity.applicationContext
        // Debug builds only, to test the ad-free layout without a Play purchase:
        // `adb shell am start -S -n com.ardeman.krida/.MainActivity --ez adsRemoved true`
        // (false turns it off again). Kept across launches.
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            activity.intent?.takeIf { it.hasExtra(EXTRA_DEBUG_REMOVED) }?.let {
                prefs().edit().putBoolean(KEY_DEBUG_REMOVED, it.getBooleanExtra(EXTRA_DEBUG_REMOVED, false)).apply()
            }
            debugRemoved = prefs().getBoolean(KEY_DEBUG_REMOVED, false)
        }
        removed = debugRemoved || prefs().getBoolean(KEY_REMOVED, false)
        Billing.connect(app, ::updateOwned)

        // UI / emulator e2e (`--ez adsRemoved true`): no UMP sheet covering the play card.
        if (removed) {
            started = true
            return
        }

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
     * Call when the player leaves a game (back / Menu) or restarts after a finish; [then]
     * runs after the interstitial closes, or immediately if ads are off / none ready yet.
     * No confirm gate — the creative plays straight away when one is loaded.
     */
    fun afterGame(activity: Activity, then: () -> Unit) {
        val ad = interstitial
        if (removed || ad == null) {
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
     * When an ad is ready and "Hapus iklan" is available, a gate offers remove-ads first.
     */
    fun rewarded(activity: Activity, onReward: () -> Unit) {
        val ad = rewarded
        if (removed || ad == null) {
            loadRewarded()
            return onReward()
        }
        presentGate(
            activity,
            title = R.string.ads_gate_rewarded_title,
            body = R.string.ads_gate_rewarded_body,
            continueLabel = R.string.ads_gate_watch,
            onContinue = {
                rewarded = null
                var earned = false
                ad.fullScreenContentCallback = closed { loadRewarded(); if (earned) onReward() }
                ad.show(activity) { earned = true }
            },
            onSkip = onReward,
        )
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
                                val retry = Runnable {
                                    if (!removed && started) loadAd(AdRequest.Builder().build())
                                }
                                tag = retry
                                adListener = object : AdListener() {
                                    override fun onAdFailedToLoad(error: LoadAdError) {
                                        // NO_FILL and brief network blips: try again shortly.
                                        main.removeCallbacks(retry)
                                        main.postDelayed(retry, 30_000L)
                                    }
                                }
                                loadAd(AdRequest.Builder().build())
                            }
                        },
                        onRelease = { view ->
                            (view.tag as? Runnable)?.let(main::removeCallbacks)
                            view.destroy()
                        },
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

    private fun updateOwned(owned: Boolean) {
        val value = owned || debugRemoved
        if (value == removed) return
        removed = value
        prefs().edit().putBoolean(KEY_REMOVED, owned).apply()
        if (value) {
            interstitial = null
            rewarded = null
            main.removeCallbacksAndMessages(null)
            // Purchase completed while the pre-ad gate is up: skip the creative.
            finishGateAsSkipped()
        }
    }

    /**
     * Branded sheet before a full-screen ad with "Hapus iklan". Always offered when a
     * creative is ready (including closed testers) so purchase isn't gated on a price string.
     */
    private fun presentGate(
        activity: Activity,
        title: Int,
        body: Int,
        continueLabel: Int,
        onContinue: () -> Unit,
        onSkip: () -> Unit,
    ) {
        dismissGateView()
        gateOnSkip = onSkip
        val parent = activity.findViewById<ViewGroup>(android.R.id.content)
        val view = ComposeView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                LaunchedEffect(removed) {
                    if (removed) finishGateAsSkipped()
                }
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    val shape = RoundedCornerShape(20.dp)
                    Column(
                        Modifier
                            .widthIn(max = 420.dp)
                            .padding(24.dp)
                            .clip(shape)
                            .background(Theme.surface)
                            .border(1.5.dp, Theme.line, shape)
                            .padding(horizontal = 24.dp, vertical = 22.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(title), style = Theme.font(20, FontWeight.Black, Theme.ink))
                        Text(stringResource(body), style = Theme.font(14, FontWeight.Bold, Theme.muted))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SecondaryButton(
                                stringResource(R.string.shop_remove_ads),
                                onClick = { buyRemoveAds(activity) },
                                Modifier.weight(1f),
                            )
                            PrimaryButton(
                                stringResource(continueLabel),
                                onClick = {
                                    dismissGateView()
                                    onContinue()
                                },
                                Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        gateView = view
        parent.addView(view)
    }

    private fun finishGateAsSkipped() {
        val skip = gateOnSkip
        dismissGateView()
        skip?.invoke()
    }

    private fun dismissGateView() {
        gateView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        gateView = null
        gateOnSkip = null
    }

    private fun loadInterstitial(attempt: Int = 0) {
        if (!started || removed || interstitial != null || loadingInterstitial) return
        loadingInterstitial = true
        InterstitialAd.load(
            app,
            app.getString(R.string.admob_interstitial),
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitial = ad
                    loadingInterstitial = false
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadingInterstitial = false
                    retryLater(attempt) { loadInterstitial(attempt + 1) }
                }
            },
        )
    }

    private fun loadRewarded(attempt: Int = 0) {
        if (!started || removed || rewarded != null || loadingRewarded) return
        loadingRewarded = true
        RewardedAd.load(
            app,
            app.getString(R.string.admob_rewarded),
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewarded = ad
                    loadingRewarded = false
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadingRewarded = false
                    retryLater(attempt) { loadRewarded(attempt + 1) }
                }
            },
        )
    }

    /** Back off after a failed load (common while AdMob has no fill yet). */
    private fun retryLater(attempt: Int, load: () -> Unit) {
        if (attempt >= MAX_LOAD_ATTEMPTS - 1) return
        val delayMs = (8_000L * (1L shl attempt)).coerceAtMost(60_000L)
        main.postDelayed({ if (started && !removed) load() }, delayMs)
    }

    /** Calls [done] on the main thread once when a full-screen ad goes away, shown or not. */
    private fun closed(done: () -> Unit) = object : FullScreenContentCallback() {
        override fun onAdDismissedFullScreenContent() {
            main.post(done)
        }
        override fun onAdFailedToShowFullScreenContent(error: AdError) {
            main.post(done)
        }
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
