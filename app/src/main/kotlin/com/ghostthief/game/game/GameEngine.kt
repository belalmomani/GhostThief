package com.ghostthief.game.game

import com.ghostthief.game.data.ProgressRepository
import com.ghostthief.game.data.Settings
import com.ghostthief.game.logic.*
import kotlin.math.hypot
import kotlin.random.Random

enum class GameMessage { CAUGHT, CAUGHT_BONUS, ACHIEVEMENT, CIRCLE_FAILED, ESCAPED, TRY_AGAIN }

interface GameListener {
    fun onMessage(msg: GameMessage, points: Int = 0)
    fun onSound(effect: SoundEffect)
}

enum class SoundEffect { POP, CATCH, FAIL, ESCAPE }

/**
 * محرك اللعبة (بلا اعتماد على Android View): يجمع الحرامي، اللمس، القبض، السجن، النقاط.
 * كل أبعاد الساحة بالبكسل.
 */
class GameEngine(
    val settings: Settings,
    val progress: ProgressRepository,
    private val clock: Clock = Clock.System,
    rnd: Random = Random.Default
) {
    val brain = ThiefBrain(rnd, MotionConfig())
    val capture = CaptureController()
    val jail = JailTimer(clock)
    var listener: GameListener? = null

    var arenaW = 800f; private set
    var arenaH = 600f; private set
    var thiefRadius = 60f
    var jailW = 130f; var jailH = 160f
    var jailMargin = 16f

    // لمس
    private val path = ArrayList<PathPoint>()
    private var pointerDown = false
    private var downOnThief = false
    private var downTime = 0f
    private var moved = false
    private var pathLength = 0f
    var threadProgress = 0f; private set
    val fingerPath: List<PathPoint> get() = path

    var lastTickMs = 0L
    var paused = false

    init {
        val saved = progress.jailEndAt
        if (saved > 0L) {
            if (saved > clock.nowMillis()) {
                jail.restore(saved)
                brain.restoreJailed(jailCenterX(), jailCenterY())
            } else {
                progress.jailEndAt = 0L
            }
        }
        applySettings()
    }

    fun applySettings() {
        brain.config.speedScale = settings.speedPercent / 100f
    }

    fun setArena(w: Float, h: Float, density: Float) {
        arenaW = w; arenaH = h
        thiefRadius = 48f * density
        jailW = 76f * density; jailH = 96f * density
        jailMargin = 10f * density
        brain.setArena(w, h)
        if (brain.state == ThiefState.JAILED) brain.restoreJailed(jailCenterX(), jailCenterY())
    }

    fun jailLeft(): Float = if (settings.jailCorner % 2 == 0) arenaW - jailW - jailMargin else jailMargin
    fun jailTop(): Float = if (settings.jailCorner < 2) jailMargin + topInset else arenaH - jailH - jailMargin
    var topInset = 0f
    fun jailCenterX() = jailLeft() + jailW / 2f
    fun jailCenterY() = jailTop() + jailH / 2f

    fun isJailed() = brain.state == ThiefState.JAILED || brain.state == ThiefState.CAUGHT
    /** مشغول: لا نعرض إعلانات أثناء القبض أو فتح السجن. */
    fun isBusy() = capture.inProgress || brain.state == ThiefState.CAUGHT || brain.state == ThiefState.ESCAPING || brain.state == ThiefState.HELD

    /** يُستدعى من كل إطار. يتجاهل الاستدعاءات المكررة ضمن نفس اللحظة. */
    fun tick(nowMs: Long) {
        if (paused) { lastTickMs = nowMs; return }
        if (lastTickMs == 0L) { lastTickMs = nowMs; return }
        val dt = (nowMs - lastTickMs) / 1000f
        if (dt < 0.004f) return
        lastTickMs = nowMs
        update(dt)
    }

    fun update(dt: Float) {
        if (pointerDown && downOnThief && !moved && downTime >= 0f) {
            downTime += dt
            if (downTime >= LONG_PRESS_SEC && brain.hold()) {
                listener?.onSound(SoundEffect.POP)
            }
        }
        brain.update(dt)

        if (brain.arrivedInJail) {
            capture.finish()
            listener?.onSound(SoundEffect.CATCH)
        }
        if (brain.state == ThiefState.JAILED && jail.isExpired()) {
            jail.clear()
            progress.jailEndAt = 0L
            brain.escape(arenaW / 2f, arenaH / 2f)
            listener?.onMessage(GameMessage.ESCAPED)
            listener?.onSound(SoundEffect.ESCAPE)
        }
    }

    fun hitsThief(x: Float, y: Float): Boolean =
        brain.alpha > 0.2f && hypot(x - brain.x, y - brain.y) <= thiefRadius * 1.4f * brain.scale.coerceAtLeast(0.6f)

    fun touchDown(x: Float, y: Float) {
        if (isJailed()) return
        pointerDown = true
        path.clear(); path.add(PathPoint(x, y))
        pathLength = 0f; moved = false; threadProgress = 0f
        downOnThief = hitsThief(x, y)
        downTime = 0f
    }

    fun touchMove(x: Float, y: Float) {
        if (!pointerDown) return
        val last = path.lastOrNull()
        if (last != null) {
            val seg = hypot(x - last.x, y - last.y)
            if (seg < 2f) return
            pathLength += seg
            if (pathLength > MOVE_SLOP_PX) moved = true
        }
        path.add(PathPoint(x, y))
        if (path.size > 400) path.removeAt(0)

        if (brain.state == ThiefState.HELD) { brain.moveHeld(x, y); return }
        if (!moved || !brain.isCatchable()) return

        // أبطئ الحرامي قليلاً أثناء اللف ليسهل الإمساك به
        val res = CircleGesture.evaluate(path, brain.x, brain.y, thiefRadius)
        threadProgress = res.progress
        brain.slowFactor = if (res.progress > 0.1f) 0.35f else 1f
        if (res.success) beginCapture(CaptureSource.CIRCLE)
    }

    fun touchUp(x: Float, y: Float) {
        if (!pointerDown) return
        pointerDown = false
        brain.slowFactor = 1f
        when {
            brain.state == ThiefState.HELD -> {
                val inJail = x in (jailLeft() - jailW * 0.4f)..(jailLeft() + jailW * 1.4f) &&
                    y in (jailTop() - jailH * 0.4f)..(jailTop() + jailH * 1.4f)
                if (inJail) beginCapture(CaptureSource.LONG_PRESS_DRAG) else { brain.releaseHeld(); listener?.onMessage(GameMessage.TRY_AGAIN) }
            }
            !moved && downOnThief && downTime < LONG_PRESS_SEC -> {
                if (brain.poke()) listener?.onSound(SoundEffect.POP)
            }
            moved && threadProgress > 0.3f && brain.isCatchable() -> {
                listener?.onMessage(GameMessage.CIRCLE_FAILED)
                listener?.onSound(SoundEffect.FAIL)
                brain.poke()
            }
        }
        threadProgress = 0f
        path.clear()
    }

    fun touchCancel() {
        pointerDown = false; brain.slowFactor = 1f
        if (brain.state == ThiefState.HELD) brain.releaseHeld()
        path.clear(); threadProgress = 0f
    }

    /** يبدأ القبض مرة واحدة فقط ويسجّل النقاط فوراً. */
    fun beginCapture(source: CaptureSource): Boolean {
        if (!capture.tryBegin(source)) return false
        if (!brain.capture(jailCenterX(), jailCenterY())) { capture.finish(); return false }
        jail.start(settings.jailMinutes * 60_000L)
        progress.jailEndAt = jail.endAtMillis
        val reward = progress.recordCapture(source == CaptureSource.CIRCLE)
        pointerDown = false; threadProgress = 0f; path.clear()
        listener?.onMessage(if (reward.bonusTier) GameMessage.CAUGHT_BONUS else GameMessage.CAUGHT, reward.pointsGained)
        if (reward.newAchievements.isNotEmpty()) listener?.onMessage(GameMessage.ACHIEVEMENT, reward.newAchievements.size)
        return true
    }

    companion object {
        const val LONG_PRESS_SEC = 0.5f
        const val MOVE_SLOP_PX = 24f
    }
}
