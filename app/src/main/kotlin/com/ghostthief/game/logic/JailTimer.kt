package com.ghostthief.game.logic

/** مؤقت السجن. يخزّن وقت الانتهاء المطلق حتى يصمد أمام إغلاق التطبيق. */
class JailTimer(private val clock: Clock = Clock.System) {
    var endAtMillis: Long = 0L
        private set

    val isJailed: Boolean get() = endAtMillis > 0L

    fun start(durationMillis: Long) {
        endAtMillis = clock.nowMillis() + durationMillis.coerceAtLeast(1000L)
    }

    fun restore(savedEndAt: Long) {
        endAtMillis = if (savedEndAt > 0L) savedEndAt else 0L
    }

    fun remainingMillis(): Long =
        if (!isJailed) 0L else (endAtMillis - clock.nowMillis()).coerceAtLeast(0L)

    fun isExpired(): Boolean = isJailed && clock.nowMillis() >= endAtMillis

    fun clear() { endAtMillis = 0L }

    companion object {
        fun format(ms: Long): String {
            val total = ms / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        }
    }
}
