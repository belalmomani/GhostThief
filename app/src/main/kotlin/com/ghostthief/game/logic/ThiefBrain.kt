package com.ghostthief.game.logic

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** حالات الحرامي. الاختفاء/الظهور الشفاف: FADE_OUT ثم HIDDEN ثم FADE_IN. */
enum class ThiefState {
    RUN, STAND, LOOK, FADE_OUT, HIDDEN, FADE_IN,
    SURPRISED, FLEE, HELD, CAUGHT, JAILED, ESCAPING
}

/** قيم قابلة للضبط (السرعة والمدة). */
class MotionConfig(
    var speedScale: Float = 1f,
    var hideSeconds: Float = 2.5f,
    var baseSpeedPxPerSec: Float = 140f
)

/**
 * آلة حالات الحرامي: لا تعتمد على Android ولا على الرسم، لذلك يمكن اختبارها مباشرة.
 * الإحداثيات بالبكسل داخل "الساحة" (arena).
 */
class ThiefBrain(
    private val rnd: Random = Random.Default,
    val config: MotionConfig = MotionConfig()
) {
    var state: ThiefState = ThiefState.RUN
        private set
    var stateTime = 0f
        private set

    var x = 120f
    var y = 120f
    /** +1 ينظر يميناً، -1 يساراً. */
    var facing = 1
        private set
    var alpha = 1f
        private set
    var scale = 1f
        private set
    /** شفافية المجلد الذي يختبئ فيه. */
    var hideFolderAlpha = 0f
        private set
    var hideX = 0f
        private set
    var hideY = 0f
        private set
    var hideVariant = 0
        private set
    /** يزيد أثناء الركض ويُستخدم لتبديل إطارات الركض. */
    var runClock = 0f
        private set
    /** أثناء محاولة اللف يبطئ الحرامي ليسهل الإمساك به. */
    var slowFactor = 1f

    /** يصبح true لمرة واحدة عندما يصل الحرامي إلى السجن بعد القبض. */
    var arrivedInJail = false
        private set

    private var arenaW = 800f
    private var arenaH = 600f
    private var stateDuration = 2f
    private var dirY = 0f
    private var startX = 0f
    private var startY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var jailScale = 0.45f

    fun setArena(w: Float, h: Float) {
        arenaW = w.coerceAtLeast(50f)
        arenaH = h.coerceAtLeast(50f)
        x = x.coerceIn(margin(), arenaW - margin())
        y = y.coerceIn(margin(), arenaH - margin())
    }

    private fun margin() = 40f

    private fun enter(s: ThiefState, duration: Float) {
        state = s
        stateTime = 0f
        stateDuration = duration
    }

    private fun speed() = config.baseSpeedPxPerSec * config.speedScale.coerceIn(0.2f, 3f) * slowFactor

    fun isCatchable(): Boolean = when (state) {
        ThiefState.RUN, ThiefState.STAND, ThiefState.LOOK, ThiefState.SURPRISED,
        ThiefState.FLEE, ThiefState.FADE_IN, ThiefState.HELD -> true
        else -> false
    }

    fun update(dt: Float) {
        val d = dt.coerceIn(0f, 0.1f)
        stateTime += d
        arrivedInJail = false
        when (state) {
            ThiefState.RUN -> {
                move(d, speed())
                if (stateTime >= stateDuration) pickNextAfterRun()
            }
            ThiefState.FLEE -> {
                move(d, speed() * 2.2f)
                if (stateTime >= stateDuration) startRun()
            }
            ThiefState.STAND -> {
                if (stateTime >= stateDuration) {
                    if (rnd.nextFloat() < 0.5f) enter(ThiefState.LOOK, 1.4f) else startRun()
                }
            }
            ThiefState.LOOK -> {
                if (stateTime >= stateDuration / 2f && stateTime - d < stateDuration / 2f) facing = -facing
                if (stateTime >= stateDuration) startRun()
            }
            ThiefState.SURPRISED -> {
                if (stateTime >= stateDuration) {
                    facing = if (rnd.nextBoolean()) 1 else -1
                    dirY = (rnd.nextFloat() - 0.5f) * 0.6f
                    enter(ThiefState.FLEE, 1.2f)
                }
            }
            ThiefState.FADE_OUT -> {
                val p = (stateTime / stateDuration).coerceIn(0f, 1f)
                alpha = 1f - p
                hideFolderAlpha = p
                if (p >= 1f) {
                    alpha = 0f
                    enter(ThiefState.HIDDEN, config.hideSeconds.coerceAtLeast(0.5f))
                }
            }
            ThiefState.HIDDEN -> {
                alpha = 0f
                hideFolderAlpha = 1f
                if (stateTime >= stateDuration) {
                    x = randomBetween(margin(), arenaW - margin())
                    y = randomBetween(margin(), arenaH - margin())
                    facing = if (x < arenaW / 2) 1 else -1
                    enter(ThiefState.FADE_IN, 0.7f)
                }
            }
            ThiefState.FADE_IN -> {
                val p = (stateTime / stateDuration).coerceIn(0f, 1f)
                alpha = p
                hideFolderAlpha = 1f - p
                if (p >= 1f) {
                    alpha = 1f
                    hideFolderAlpha = 0f
                    startRun()
                }
            }
            ThiefState.HELD -> { /* يتبع الإصبع */ }
            ThiefState.CAUGHT -> {
                val p = (stateTime / stateDuration).coerceIn(0f, 1f)
                val e = p * p * (3 - 2 * p)
                x = startX + (targetX - startX) * e
                y = startY + (targetY - startY) * e
                scale = 1f + (jailScale - 1f) * e
                if (p >= 1f) {
                    x = targetX; y = targetY; scale = jailScale
                    enter(ThiefState.JAILED, Float.MAX_VALUE)
                    arrivedInJail = true
                }
            }
            ThiefState.JAILED -> { /* ينتظر انتهاء المدة */ }
            ThiefState.ESCAPING -> {
                val p = (stateTime / stateDuration).coerceIn(0f, 1f)
                x = startX + (targetX - startX) * p
                y = startY + (targetY - startY) * p - 70f * sin(p * PI.toFloat())
                scale = jailScale + (1f - jailScale) * p
                if (p >= 1f) {
                    x = targetX; y = targetY; scale = 1f
                    startRun()
                }
            }
        }
    }

    private fun move(d: Float, v: Float) {
        runClock += d * (v / config.baseSpeedPxPerSec).coerceIn(0.3f, 4f)
        x += facing * v * d
        y += dirY * v * d
        val m = margin()
        if (x < m) { x = m; facing = 1 }
        if (x > arenaW - m) { x = arenaW - m; facing = -1 }
        if (y < m) { y = m; dirY = abs(dirY) }
        if (y > arenaH - m) { y = arenaH - m; dirY = -abs(dirY) }
    }

    private fun randomBetween(a: Float, b: Float) = if (b <= a) a else a + rnd.nextFloat() * (b - a)

    private fun startRun() {
        alpha = 1f
        hideFolderAlpha = 0f
        scale = 1f
        dirY = (rnd.nextFloat() - 0.5f) * 0.5f
        enter(ThiefState.RUN, randomBetween(1.5f, 3.5f))
    }

    private fun pickNextAfterRun() {
        val r = rnd.nextFloat()
        when {
            r < 0.30f -> { facing = -facing; startRun() }
            r < 0.55f -> enter(ThiefState.STAND, randomBetween(0.6f, 1.2f))
            else -> beginHide()
        }
    }

    private fun beginHide() {
        hideX = x
        hideY = y
        hideVariant = rnd.nextInt(3)
        enter(ThiefState.FADE_OUT, 0.7f)
    }

    /** يُستدعى لفرض الاختباء (للاختبار أو لمنطق مخصص). */
    fun forceHide() {
        if (state == ThiefState.RUN || state == ThiefState.STAND || state == ThiefState.LOOK) beginHide()
    }

    /** لمسة بسيطة على الحرامي: دهشة ثم هروب. */
    fun poke(): Boolean {
        if (state != ThiefState.RUN && state != ThiefState.STAND && state != ThiefState.LOOK) return false
        enter(ThiefState.SURPRISED, 0.4f)
        return true
    }

    fun hold(): Boolean {
        if (!isCatchable() || state == ThiefState.HELD) return false
        alpha = 1f; hideFolderAlpha = 0f
        enter(ThiefState.HELD, Float.MAX_VALUE)
        return true
    }

    fun moveHeld(px: Float, py: Float) {
        if (state != ThiefState.HELD) return
        x = px; y = py
    }

    /** ترك الإمساك دون سجن: يهرب. */
    fun releaseHeld() {
        if (state != ThiefState.HELD) return
        enter(ThiefState.SURPRISED, 0.3f)
    }

    /** يبدأ القبض: ينتقل إلى CAUGHT ثم يسحب إلى السجن. */
    fun capture(jailX: Float, jailY: Float, jailScaleFactor: Float = 0.45f): Boolean {
        if (!isCatchable()) return false
        startX = x; startY = y
        targetX = jailX; targetY = jailY
        jailScale = jailScaleFactor
        alpha = 1f; hideFolderAlpha = 0f
        enter(ThiefState.CAUGHT, 0.9f)
        return true
    }

    /** استعادة حالة السجن من التخزين (التطبيق أُعيد فتحه والمدة لم تنتهِ). */
    fun restoreJailed(jailX: Float, jailY: Float, jailScaleFactor: Float = 0.45f) {
        x = jailX; y = jailY; scale = jailScaleFactor; jailScale = jailScaleFactor
        targetX = jailX; targetY = jailY
        alpha = 1f; hideFolderAlpha = 0f
        enter(ThiefState.JAILED, Float.MAX_VALUE)
    }

    /** الهروب بعد انتهاء المدة. */
    fun escape(toX: Float = arenaW / 2f, toY: Float = arenaH / 2f): Boolean {
        if (state != ThiefState.JAILED) return false
        startX = x; startY = y
        targetX = toX; targetY = toY
        facing = if (toX >= x) 1 else -1
        enter(ThiefState.ESCAPING, 1.0f)
        return true
    }

    /** مدة الحالة الحالية (للاختبار). */
    fun currentStateDuration() = stateDuration
}
