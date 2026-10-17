package com.ghostthief.game.logic

/** ساعة قابلة للاستبدال لتسهيل الاختبار. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}
