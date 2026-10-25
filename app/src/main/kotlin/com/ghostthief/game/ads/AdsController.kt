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

    /** هل إعلانات المكافأة متاحة في هذه النسخة؟ (noads: لا). */
    val rewardedSupported: Boolean get() = false

    /**
     * إعلان مكافأة اختياري يشاهده الطفل ليفتح مركبة. [onReward] فقط إذا اكتمل الإعلان فعلاً؛ وإلا [onFail].
     * ينادَى أحدهما مرة واحدة على الأكثر.
     */
    fun showRewarded(activity: Activity, onReward: () -> Unit, onFail: () -> Unit) { onFail() }
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

    override val rewardedSupported get() = delegate.rewardedSupported && !disabled

    override fun showRewarded(activity: Activity, onReward: () -> Unit, onFail: () -> Unit) {
        if (disabled) { onFail(); return }
        try {
            delegate.showRewarded(activity, onReward, onFail)
        } catch (t: Throwable) {
            failures++
            onFail()
        }
    }

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
