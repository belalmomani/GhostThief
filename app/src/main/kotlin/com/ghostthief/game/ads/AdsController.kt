package com.ghostthief.game.ads

import android.app.Activity
import android.view.ViewGroup
import com.ghostthief.game.logic.Clock

interface AdsController {
    /** هل هناك شبكة إعلانات مضمّنة في هذه النسخة؟ */
    val available: Boolean
    fun init(activity: Activity)
    fun attachBanner(container: ViewGroup)
    fun detachBanner()
    /** يعرض إعلاناً بينياً فقط عند نقطة انتقال طبيعية وإن سمحت السياسة. ينادي [onDone] دائماً. */
    fun maybeShowInterstitial(activity: Activity, gameBusy: Boolean, onDone: () -> Unit)
}

/** سياسة ظهور الإعلان البيني: لا أثناء القبض/السجن، وبحد أدنى بين إعلانين. */
class AdPolicy(
    private val clock: Clock = Clock.System,
    private val minIntervalMs: Long = 3 * 60_000L
) {
    private var lastShownAt = 0L

    fun canShowInterstitial(adsEnabled: Boolean, adReady: Boolean, gameBusy: Boolean): Boolean {
        if (!adsEnabled || !adReady || gameBusy) return false
        val now = clock.nowMillis()
        return lastShownAt == 0L || now - lastShownAt >= minIntervalMs
    }

    fun markShown() { lastShownAt = clock.nowMillis() }
}

/** غلاف يمنع أي خطأ في SDK الإعلانات من تعطيل اللعبة، ويوقف الإعلانات بعد إخفاقات متكررة. */
class SafeAdsController(
    private val delegate: AdsController,
    private val maxFailures: Int = 3
) : AdsController {
    var failures = 0
        private set
    private val disabled get() = failures >= maxFailures
    override val available get() = delegate.available && !disabled

    private inline fun guarded(block: () -> Unit) {
        if (disabled) return
        try { block() } catch (t: Throwable) { failures++ }
    }

    override fun init(activity: Activity) = guarded { delegate.init(activity) }
    override fun attachBanner(container: ViewGroup) = guarded { delegate.attachBanner(container) }
    override fun detachBanner() { try { delegate.detachBanner() } catch (t: Throwable) { failures++ } }

    override fun maybeShowInterstitial(activity: Activity, gameBusy: Boolean, onDone: () -> Unit) {
        if (disabled) { onDone(); return }
        try {
            delegate.maybeShowInterstitial(activity, gameBusy, onDone)
        } catch (t: Throwable) {
            failures++
            onDone()
        }
    }
}
