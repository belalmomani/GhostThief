package com.ghostthief.game.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.ghostthief.game.BuildConfig
import com.ghostthief.game.data.Settings
import com.startapp.sdk.ads.banner.Banner
import com.startapp.sdk.adsbase.Ad
import com.startapp.sdk.adsbase.StartAppAd
import com.startapp.sdk.adsbase.StartAppSDK
import com.startapp.sdk.adsbase.VideoListener
import com.startapp.sdk.adsbase.adlisteners.AdDisplayListener
import com.startapp.sdk.adsbase.adlisteners.AdEventListener

/** نكهة ads: تكامل Start.io. معرّف الاختبار الرسمي يُستخدم إذا لم تضبط STARTIO_APP_ID. */
object AdsFactory {
    fun create(context: Context, settings: Settings): AdsController =
        SafeAdsController(StartIoAds(context.applicationContext, settings, AdPolicy()))
}

private const val DEMO_APP_ID = "205489527" // معرّف الاختبار المنشور في مستودع Start.io التجريبي الرسمي

private class StartIoAds(
    private val appContext: Context,
    private val settings: Settings,
    private val policy: AdPolicy
) : AdsController {

    override val available = true
    private var sdkReady = false
    private var interstitial: StartAppAd? = null
    @Volatile private var interstitialLoaded = false
    private var banner: Banner? = null

    private fun useTestAds() = BuildConfig.STARTIO_APP_ID.isBlank() || BuildConfig.DEBUG

    override fun init(activity: Activity) {
        if (!settings.adsEnabled || sdkReady) return
        val id = BuildConfig.STARTIO_APP_ID.ifBlank { DEMO_APP_ID }
        StartAppSDK.setTestAdsEnabled(useTestAds())
        StartAppSDK.initParams(appContext, id)
            .setReturnAdsEnabled(false)
            .setCallback { sdkReady = true; preloadInterstitial(activity) }
            .init()
    }

    private fun preloadInterstitial(activity: Activity) {
        val ad = StartAppAd(activity)
        interstitialLoaded = false
        ad.loadAd(StartAppAd.AdMode.AUTOMATIC, object : AdEventListener {
            override fun onReceiveAd(p0: Ad) { interstitialLoaded = true }
            override fun onFailedToReceiveAd(p0: Ad?) { interstitialLoaded = false }
        })
        interstitial = ad
    }

    override fun attachBanner(container: ViewGroup) {
        if (!settings.adsEnabled) { container.visibility = View.GONE; return }
        container.visibility = View.VISIBLE
        if (banner != null) return
        val b = Banner(container.context)
        container.addView(b, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        banner = b
    }

    override fun detachBanner() {
        banner?.let { (it.parent as? ViewGroup)?.removeView(it) }
        banner = null
    }

    override fun maybeShowInterstitial(activity: Activity, gameBusy: Boolean, onDone: () -> Unit) {
        val ad = interstitial
        if (ad == null || !policy.canShowInterstitial(settings.adsEnabled, interstitialLoaded, gameBusy)) { onDone(); return }
        policy.markShown()
        interstitialLoaded = false
        val shown = ad.showAd(object : AdDisplayListener {
            override fun adHidden(p0: Ad?) { preloadInterstitial(activity); onDone() }
            override fun adDisplayed(p0: Ad?) {}
            override fun adClicked(p0: Ad?) {}
            override fun adNotDisplayed(p0: Ad?) { onDone() }
        })
        if (!shown) onDone()
    }

    override val rewardedSupported = true

    /** إعلان فيديو مكافأة (اختياري): لا مكافأة إلا بعد اكتمال الفيديو وإغلاقه. مهلة 12 ثانية للتحميل. */
    override fun showRewarded(activity: Activity, onReward: () -> Unit, onFail: () -> Unit) {
        if (!settings.adsEnabled || !sdkReady) { onFail(); return }
        val ad = StartAppAd(activity)
        var completed = false
        var finished = false
        val handler = Handler(Looper.getMainLooper())
        fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            if (ok) onReward() else onFail()
        }
        handler.postDelayed({ finish(false) }, 12_000L)
        ad.setVideoListener(VideoListener { completed = true })
        ad.loadAd(StartAppAd.AdMode.REWARDED_VIDEO, object : AdEventListener {
            override fun onReceiveAd(p0: Ad) {
                if (finished) return
                val shown = ad.showAd(object : AdDisplayListener {
                    override fun adHidden(p0: Ad?) { finish(completed) }
                    override fun adDisplayed(p0: Ad?) { handler.removeCallbacksAndMessages(null) }
                    override fun adClicked(p0: Ad?) {}
                    override fun adNotDisplayed(p0: Ad?) { finish(false) }
                })
                if (!shown) finish(false)
            }
            override fun onFailedToReceiveAd(p0: Ad?) { finish(false) }
        })
    }
}
