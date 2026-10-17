package com.ghostthief.game.floating

import com.ghostthief.game.data.Settings

/** فصل منطق الوضع العائم عن Android ليسهل اختباره. */
interface OverlayEnvironment {
    fun canDrawOverlays(): Boolean
    fun startOverlay()
    fun stopOverlay()
}

enum class EnableResult { ENABLED, NEEDS_PERMISSION, FAILED }

class FloatingModeController(
    private val env: OverlayEnvironment,
    private val settings: Settings
) {
    /** لا يفعّل شيئاً دون صلاحية. عند الرفض يرجع NEEDS_PERMISSION بلا أي استثناء. */
    fun enable(): EnableResult {
        if (!env.canDrawOverlays()) {
            settings.floatingEnabled = false
            return EnableResult.NEEDS_PERMISSION
        }
        settings.floatingEnabled = true
        return try {
            env.startOverlay()
            EnableResult.ENABLED
        } catch (t: Throwable) {
            settings.floatingEnabled = false
            EnableResult.FAILED
        }
    }

    fun disable() {
        settings.floatingEnabled = false
        try { env.stopOverlay() } catch (_: Throwable) {}
    }

    /** إن سُحبت الصلاحية لاحقاً من إعدادات النظام نوقف الميزة بهدوء. */
    fun onResumeCheck() {
        if (settings.floatingEnabled && !env.canDrawOverlays()) disable()
    }
}
