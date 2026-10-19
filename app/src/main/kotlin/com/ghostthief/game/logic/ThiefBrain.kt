package com.ghostthief.game.logic

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** حالات الحرامي. الاختفاء/الظهور الشفاف: FADE_OUT ثم HIDDEN ثم FADE_IN. السرقة: HEIST_GO ثم CARRY أو DRAG. */
enum class ThiefState {
    RUN, STAND, LOOK, FADE_OUT, HIDDEN, FADE_IN,
    SURPRISED, FLEE, HELD, CAUGHT, JAILED, ESCAPING,
    HEIST_GO, CARRY, DRAG
}

/** أحداث السرقة التي يستهلكها المحرك مرة واحدة. */
enum class LootEvent { NONE, PICKED, ESCAPED_WITH, STASHED, DROPPED }

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
    var hideFolderAlpha = 0f
        private set
    var hideX = 0f
        private set
    var hideY = 0f
        private set
    var hideVariant = 0
        private set
    var runClock = 0f
        private set
    /** أثناء محاولة اللف يبطئ الحرامي ليسهل الإمساك به. */
    var slowFactor = 1f
    /** عند true يبقى الحرامي مختبئاً حتى يجده اللاعب (وضع البحث). */
    var holdHidden = false

    /** يصبح true لمرة واحدة عندما يصل الحرامي إلى السجن بعد القبض. */
    var arrivedInJail = false
        private set

    // ---- السرقة ----
    var lootX = 0f
        private set
    var lootY = 0f
        private set
    /** غير null أثناء سرقة نشطة (قبل الالتقاط أو أثناء الحمل/السحب). */
    var lootKind: LootKind? = null
        private set
    /** true عندما تكون الغنيمة بحوزته الآن (يحملها أو يسحبها). */
    var carrying = false
        private set
    /** نوع الغنيمة التي استُرجعت بالقبض (لمرة واحدة). */
    var rescuedKind: LootKind? = null
        private set
    var lootEvent = LootEvent.NONE
        private set
    /** طول الحبل بالبكسل (يضبطه المحرك نسبةً لحجم الحرامي). */
    var ropeLength = 110f
    private var stashPending = false
    /** بعد الضربة بالنقر: يهرب ثم يختبئ وهو يتألم. */
    private var hurtHide = false

    /** الحد الأعلى للحركة (مثلاً أسفل شريط الحالة في الوضع العائم). */
    var minY = 0f

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
        y = y.coerceIn(minY + margin(), (arenaH - margin()).coerceAtLeast(minY + margin()))
    }

    private fun margin() = 40f.coerceAtMost(minOf(arenaW, arenaH) * 0.2f)

    private fun enter(s: ThiefState, duration: Float) {
        state = s
        stateTime = 0f
        stateDuration = duration
    }

    private fun speed() = config.baseSpeedPxPerSec * config.speedScale.coerceIn(0.2f, 3f) * slowFactor

    fun isCatchable(): Boolean = when (state) {
        ThiefState.RUN, ThiefState.STAND, ThiefState.LOOK, ThiefState.SURPRISED,
        ThiefState.FLEE, ThiefState.FADE_IN, ThiefState.HELD,
        ThiefState.HEIST_GO, ThiefState.CARRY, ThiefState.DRAG -> true
        else -> false
    }

    /** يأخذ الحدث الأخير ويصفّره. */
    fun takeLootEvent(): LootEvent { val e = lootEvent; lootEvent = LootEvent.NONE; return e }
    fun takeRescued(): LootKind? { val k = rescuedKind; rescuedKind = null; return k }

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
                if (stateTime >= stateDuration) {
                    if (hurtHide) { hurtHide = false; beginHide() } else startRun()
                }
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
                if (stateTime >= stateDuration && !holdHidden) {
                    x = randomBetween(margin(), arenaW - margin())
                    y = randomBetween(minY + margin(), arenaH - margin())
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
                    if (stashPending) { // خبّأ الغنيمة وعاد بدونها
                        stashPending = false; carrying = false; lootKind = null; lootEvent = LootEvent.STASHED
                    }
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
            ThiefState.HEIST_GO -> updateHeistGo(d)
            ThiefState.CARRY -> {
                move(d, speed() * 1.4f)
                if (stateTime >= stateDuration) {
                    if (stashPending) beginHide() // يخبّئها: يختفي ومعه الغنيمة
                    else { carrying = false; lootKind = null; lootEvent = LootEvent.ESCAPED_WITH; startRun() }
                }
            }
            ThiefState.DRAG -> {
                // يسحب غنيمة كبيرة بحبل نحو حافة الشاشة بجهد شديد (حركة بطيئة)
                x += facing * speed() * 0.35f * d
                val tx = x - facing * ropeLength
                lootX += (tx - lootX) * minOf(1f, 8f * d) // الغنيمة تتبع بحبل مشدود
                lootY += (y - lootY) * minOf(1f, 8f * d)
                runClock += d * 0.8f
                val out = (facing > 0 && x > arenaW - 8f) || (facing < 0 && x < 8f)
                if (out || stateTime >= stateDuration) {
                    carrying = false; lootKind = null; lootEvent = LootEvent.ESCAPED_WITH
                    facing = -facing
                    x = x.coerceIn(margin(), arenaW - margin())
                    startRun()
                }
            }
        }
    }

    private fun updateHeistGo(d: Float) {
        val dx = lootX - x; val dy = lootY - y
        val dist = hypot(dx, dy)
        if (dist < 28f) { pickUpLoot(); return }
        if (stateTime >= stateDuration) { lootKind = null; startRun(); return } // مهلة: يتراجع
        val v = speed() * 1.3f
        x += dx / dist * v * d
        y += dy / dist * v * d
        if (abs(dx) > 6f) facing = if (dx > 0) 1 else -1
        runClock += d * 1.3f
    }

    private fun pickUpLoot() {
        val kind = lootKind ?: run { startRun(); return }
        carrying = true
        lootEvent = LootEvent.PICKED
        when (kind) {
            LootKind.SMALL -> { facing = if (x < arenaW / 2) 1 else -1; enter(ThiefState.CARRY, 7f) }
            LootKind.MEDIUM -> { stashPending = true; enter(ThiefState.CARRY, 1.2f) }
            LootKind.LARGE -> {
                facing = if (x < arenaW / 2) -1 else 1 // نحو أقرب حافة
                enter(ThiefState.DRAG, 16f)
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
        if (y < minY + m) { y = minY + m; dirY = abs(dirY) }
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

    /** يبدأ سرقة غنيمة موضوعة عند (lx, ly). */
    fun beginHeist(lx: Float, ly: Float, kind: LootKind): Boolean {
        if (state != ThiefState.RUN && state != ThiefState.STAND && state != ThiefState.LOOK) return false
        lootX = lx.coerceIn(margin(), arenaW - margin()); lootY = ly.coerceIn(minY + margin(), (arenaH - margin()).coerceAtLeast(minY + margin()))
        lootKind = kind; carrying = false; stashPending = false
        facing = if (lootX >= x) 1 else -1
        enter(ThiefState.HEIST_GO, 12f)
        return true
    }

    private fun dropLoot() {
        if (carrying) lootEvent = LootEvent.DROPPED
        carrying = false; lootKind = null; stashPending = false
    }

    /**
     * نقرة (ضربة خفيفة) على الحرامي: يسقط ما يحمله، يصرخ من الألم، يهرب ثم يختبئ.
     * (كرتوني ومناسب للأطفال.)
     */
    fun poke(): Boolean {
        when (state) {
            ThiefState.RUN, ThiefState.STAND, ThiefState.LOOK,
            ThiefState.HEIST_GO, ThiefState.CARRY, ThiefState.DRAG -> {}
            else -> return false
        }
        dropLoot()
        hurtHide = true
        enter(ThiefState.SURPRISED, 0.4f)
        return true
    }

    fun hold(): Boolean {
        if (!isCatchable() || state == ThiefState.HELD) return false
        alpha = 1f; hideFolderAlpha = 0f
        hurtHide = false
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
        if (carrying) rescuedKind = lootKind
        carrying = false; lootKind = null; stashPending = false; hurtHide = false
        startX = x; startY = y
        targetX = jailX; targetY = jailY
        jailScale = jailScaleFactor
        alpha = 1f; hideFolderAlpha = 0f
        enter(ThiefState.CAUGHT, 0.9f)
        return true
    }

    /** استعادة حالة السجن من التخزين. */
    fun restoreJailed(jailX: Float, jailY: Float, jailScaleFactor: Float = 0.45f) {
        x = jailX; y = jailY; scale = jailScaleFactor; jailScale = jailScaleFactor
        targetX = jailX; targetY = jailY
        alpha = 1f; hideFolderAlpha = 0f
        enter(ThiefState.JAILED, Float.MAX_VALUE)
    }

    /** وضع البحث: يظهر الحرامي من مكان اختبائه الذي وجده اللاعب. */
    fun revealAt(px: Float, py: Float) {
        if (state != ThiefState.HIDDEN) return
        holdHidden = false
        x = px; y = py; hideX = px; hideY = py; hideVariant = 0
        facing = if (px < arenaW / 2f) 1 else -1
        enter(ThiefState.FADE_IN, 0.5f)
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

    fun currentStateDuration() = stateDuration

}
