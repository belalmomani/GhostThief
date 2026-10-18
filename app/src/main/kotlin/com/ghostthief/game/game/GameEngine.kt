package com.ghostthief.game.game

import com.ghostthief.game.data.ProgressRepository
import com.ghostthief.game.data.Settings
import com.ghostthief.game.logic.*
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

enum class GameMessage { CAUGHT, CAUGHT_BONUS, ACHIEVEMENT, CIRCLE_FAILED, ESCAPED, TRY_AGAIN, SEEK_START, FOUND, NOT_HERE }

interface GameListener {
    fun onMessage(msg: GameMessage, points: Int = 0)
    fun onSound(effect: SoundEffect)
}

enum class SoundEffect { POP, CATCH, FAIL, ESCAPE }

/** مكان اختباء في وضع البحث. الاسم يأتي من مجلد اختاره المستخدم (يبقى في الذاكرة فقط). */
class SeekSpot(val x: Float, val y: Float, val name: String) { var wrong = false }

/**
 * محرك اللعبة (بلا اعتماد على Android View): الحرامي، اللمس، القبض، السجن (قابل للسحب)، النقاط، وضع البحث.
 * كل أبعاد الساحة بالبكسل.
 */
class GameEngine(
    val settings: Settings,
    val progress: ProgressRepository,
    private val clock: Clock = Clock.System,
    private val rnd: Random = Random.Default
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
    var topInset = 0f

    // موضع السجن (يُحسب من الزاوية أو من الموضع المخصص الذي سحبه اللاعب)
    private var jailL = 0f
    private var jailT = 0f
    private var draggingJail = false
    private var dragDX = 0f
    private var dragDY = 0f

    // لمس
    private val path = ArrayList<PathPoint>()
    private var pointerDown = false
    private var downOnThief = false
    private var downTime = 0f
    private var moved = false
    private var pathLength = 0f
    var threadProgress = 0f; private set
    val fingerPath: List<PathPoint> get() = path

    // وضع البحث
    /** أسماء عناصر المجلد الذي اختاره المستخدم (تُحمَّل في الذاكرة فقط). */
    var seekNames: List<String> = emptyList()
    var seekSpots: List<SeekSpot> = emptyList(); private set
    var seekActive = false; private set
    var seekRealIndex = -1; private set
    private var seekTime = 0f
    private var prevState = ThiefState.RUN

    var lastTickMs = 0L
    var paused = false

    init {
        val saved = progress.jailEndAt
        if (saved > 0L) {
            if (saved > clock.nowMillis()) {
                jail.restore(saved)
                relayoutJail()
                brain.restoreJailed(jailCenterX(), jailCenterY())
            } else {
                progress.jailEndAt = 0L
            }
        }
        applySettings()
    }

    fun applySettings() {
        brain.config.speedScale = settings.speedPercent / 100f
        relayoutJail()
    }

    fun setArena(w: Float, h: Float, density: Float) {
        arenaW = w; arenaH = h
        thiefRadius = 48f * density
        jailW = 76f * density; jailH = 96f * density
        jailMargin = 10f * density
        brain.setArena(w, h)
        relayoutJail()
        if (brain.state == ThiefState.JAILED) brain.restoreJailed(jailCenterX(), jailCenterY())
    }

    private fun relayoutJail() {
        val px = settings.jailPosX; val py = settings.jailPosY
        if (px >= 0 && py >= 0) {
            jailL = (px / 1000f) * (arenaW - jailW).coerceAtLeast(0f)
            jailT = (py / 1000f) * (arenaH - jailH).coerceAtLeast(0f)
        } else {
            jailL = if (settings.jailCorner % 2 == 0) arenaW - jailW - jailMargin else jailMargin
            jailT = if (settings.jailCorner < 2) jailMargin + topInset else arenaH - jailH - jailMargin
        }
    }

    fun jailLeft(): Float = jailL
    fun jailTop(): Float = jailT
    fun jailCenterX() = jailL + jailW / 2f
    fun jailCenterY() = jailT + jailH / 2f

    fun isJailed() = brain.state == ThiefState.JAILED || brain.state == ThiefState.CAUGHT
    /** مشغول: لا نعرض إعلانات أثناء القبض أو فتح السجن. */
    fun isBusy() = capture.inProgress || brain.state == ThiefState.CAUGHT || brain.state == ThiefState.ESCAPING || brain.state == ThiefState.HELD || draggingJail

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
            if (downTime >= LONG_PRESS_SEC && brain.hold()) listener?.onSound(SoundEffect.POP)
        }
        brain.update(dt)

        // وضع البحث: عند الاختفاء الكامل تظهر مجلدات للبحث فيها
        if (brain.state == ThiefState.HIDDEN && prevState != ThiefState.HIDDEN) maybeStartSeek()
        prevState = brain.state
        if (seekActive) {
            seekTime += dt
            if (brain.state != ThiefState.HIDDEN) seekActive = false
            else if (seekTime >= SEEK_TIMEOUT_SEC) revealReal(found = false)
        }

        if (brain.state == ThiefState.JAILED) { brain.x = jailCenterX(); brain.y = jailCenterY() }
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

    // ---------- وضع البحث ----------
    private fun seekAvailable() = settings.seekEnabled && seekNames.isNotEmpty()

    private fun maybeStartSeek() {
        if (!seekAvailable()) return
        val n = min(seekNames.size, 5)
        val names = seekNames.shuffled(rnd).take(n)
        val spots = ArrayList<SeekSpot>()
        for (i in 0 until n) {
            val col = i % 3; val row = i / 3
            val x = arenaW * (col + 1) / 4f + (rnd.nextFloat() - 0.5f) * 20f
            val y = arenaH * (0.38f + 0.28f * row) + (rnd.nextFloat() - 0.5f) * 20f
            spots.add(SeekSpot(x, y, names[i]))
        }
        seekSpots = spots
        seekRealIndex = rnd.nextInt(n)
        seekActive = true
        seekTime = 0f
        brain.holdHidden = true
        listener?.onMessage(GameMessage.SEEK_START)
    }

    private fun spotRadius() = thiefRadius * 0.95f

    /** يعالج لمسة على أماكن الاختباء. يرجع true إذا استهلكها. */
    private fun handleSeekTap(x: Float, y: Float): Boolean {
        if (!seekActive || brain.state != ThiefState.HIDDEN) return false
        val idx = seekSpots.indexOfFirst { !it.wrong && hypot(x - it.x, y - it.y) <= spotRadius() }
        if (idx < 0) return false
        if (idx == seekRealIndex) {
            revealReal(found = true)
        } else {
            seekSpots[idx].wrong = true
            listener?.onMessage(GameMessage.NOT_HERE)
            listener?.onSound(SoundEffect.FAIL)
        }
        return true
    }

    private fun revealReal(found: Boolean) {
        val s = seekSpots.getOrNull(seekRealIndex) ?: run { seekActive = false; brain.holdHidden = false; return }
        seekActive = false
        brain.revealAt(s.x, s.y)
        if (found) {
            progress.awardPoints(SEEK_BONUS)
            listener?.onMessage(GameMessage.FOUND, SEEK_BONUS)
            listener?.onSound(SoundEffect.CATCH)
        }
    }

    // ---------- اللمس ----------
    fun hitsThief(x: Float, y: Float): Boolean =
        brain.alpha > 0.2f && !isJailed() && hypot(x - brain.x, y - brain.y) <= thiefRadius * 1.4f * brain.scale.coerceAtLeast(0.6f)

    fun hitsJail(x: Float, y: Float): Boolean =
        settings.jailVisible && x in jailL..(jailL + jailW) && y in jailT..(jailT + jailH)

    private fun moveJailTo(l: Float, t: Float) {
        jailL = l.coerceIn(0f, (arenaW - jailW).coerceAtLeast(0f))
        jailT = t.coerceIn(0f, (arenaH - jailH).coerceAtLeast(0f))
        if (brain.state == ThiefState.JAILED) { brain.x = jailCenterX(); brain.y = jailCenterY() }
    }

    private fun saveJailPosition() {
        val rw = arenaW - jailW; val rh = arenaH - jailH
        if (rw <= 0f || rh <= 0f) return
        settings.jailPosX = ((jailL / rw) * 1000f).toInt().coerceIn(0, 1000)
        settings.jailPosY = ((jailT / rh) * 1000f).toInt().coerceIn(0, 1000)
    }

    fun touchDown(x: Float, y: Float) {
        if (handleSeekTap(x, y)) return
        // سحب السجن إلى أي مكان يريده اللاعب (لا يبدأ إن كان اللمس على الحرامي نفسه)
        if (brain.state != ThiefState.HELD && !hitsThief(x, y) && hitsJail(x, y)) {
            draggingJail = true; pointerDown = true
            dragDX = x - jailL; dragDY = y - jailT
            return
        }
        if (isJailed()) return
        pointerDown = true
        path.clear(); path.add(PathPoint(x, y))
        pathLength = 0f; moved = false; threadProgress = 0f
        downOnThief = hitsThief(x, y)
        downTime = 0f
    }

    fun touchMove(x: Float, y: Float) {
        if (!pointerDown) return
        if (draggingJail) { moveJailTo(x - dragDX, y - dragDY); return }
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
        if (draggingJail) { draggingJail = false; saveJailPosition(); return }
        brain.slowFactor = 1f
        when {
            brain.state == ThiefState.HELD -> {
                val inJail = x in (jailL - jailW * 0.4f)..(jailL + jailW * 1.4f) &&
                    y in (jailT - jailH * 0.4f)..(jailT + jailH * 1.4f)
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
        if (draggingJail) { draggingJail = false; saveJailPosition() }
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
        const val SEEK_TIMEOUT_SEC = 45f
        const val SEEK_BONUS = 5
    }
}
