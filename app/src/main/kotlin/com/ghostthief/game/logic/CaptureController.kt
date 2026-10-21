package com.ghostthief.game.logic

enum class CaptureSource { CIRCLE, LONG_PRESS_DRAG, POLICE }

/** يضمن أن القبض يُسجَّل مرة واحدة فقط حتى لو تداخلت أحداث اللمس. */
class CaptureController {
    var inProgress = false
        private set
    var lastSource: CaptureSource? = null
        private set
    var totalStarted = 0
        private set

    /** يرجع true مرة واحدة فقط حتى يُستدعى [finish]. */
    @Synchronized
    fun tryBegin(source: CaptureSource): Boolean {
        if (inProgress) return false
        inProgress = true
        lastSource = source
        totalStarted++
        return true
    }

    @Synchronized
    fun finish() { inProgress = false }
}
