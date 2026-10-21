package com.ghostthief.game.logic

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/** مراحل عملية القبض بالشرطي والسيارة. */
enum class PoliceStage { PATROL, ARRESTING, CAR_COMING, BOARDING, DRIVING, DROP, CAR_LEAVING }

/**
 * الشرطي (يتحكم به الطفل) وسيارة الشرطة: منطق نقي بلا Android.
 * التسلسل: الشرطي يلمس الحرامي ← يُكبَّل ← تأتي السيارة ← يصعدان ← السيارة تقود إلى السجن
 * ← يُسلَّم الحرامي (dropped) ← يخرج الشرطي وتغادر السيارة ← يعود الشرطي للدورية.
 */
class PoliceSquad(@Suppress("unused") private val rnd: Random = Random.Default) {
    var stage = PoliceStage.PATROL
        private set
    var stageTime = 0f
        private set

    // الشرطي
    var ox = 100f
    var oy = 300f
    var ofacing = 1
        private set
    var officerVisible = true
        private set
    var officerScale = 1f
        private set
    var walkClock = 0f
        private set
    var moving = false
        private set
    var speedPx = 260f

    // السيارة
    var cx = -500f
        private set
    var cy = 0f
        private set
    var cfacing = 1
        private set
    var carVisible = false
        private set
    var lightsClock = 0f
        private set
    var carWidth = 200f

    // الحرامي أثناء القبض (يقرؤه المحرك)
    var thiefX = 0f
        private set
    var thiefY = 0f
        private set
    var thiefScale = 1f
        private set
    var thiefVisible = true
        private set

    /** true لمرة واحدة عند وصول السيارة إلى السجن (يُستهلك بـ [takeDropped]). */
    private var dropped = false
    fun takeDropped(): Boolean { val d = dropped; dropped = false; return d }

    private var arenaW = 800f
    private var arenaH = 600f
    private var minY = 0f
    private var hasTarget = false
    private var tx = 0f
    private var ty = 0f
    private var destX = 0f
    private var destY = 0f
    private var carTargetX = 0f
    private var startOX = 0f; private var startOY = 0f
    private var startTX = 0f; private var startTY = 0f

    fun setArena(w: Float, h: Float, top: Float) { arenaW = w; arenaH = h; minY = top; clampOfficer() }

    fun place(x: Float, y: Float) { ox = x; oy = y; hasTarget = false; clampOfficer() }

    /** هدف حركة الشرطي (إصبع الطفل). */
    fun moveTo(x: Float, y: Float) { tx = x; ty = y; hasTarget = true }
    fun stop() { hasTarget = false }

    /** يبدأ القبض: يُكبَّل الحرامي في مكانه ثم تأتي السيارة. */
    fun arrest(thiefAtX: Float, thiefAtY: Float, jailDestX: Float, jailDestY: Float): Boolean {
        if (stage != PoliceStage.PATROL) return false
        thiefX = thiefAtX; thiefY = thiefAtY; thiefScale = 1f; thiefVisible = true
        destX = jailDestX; destY = jailDestY
        hasTarget = false; moving = false
        // الشرطي ينظر نحو الحرامي
        ofacing = if (thiefAtX >= ox) 1 else -1
        enter(PoliceStage.ARRESTING)
        return true
    }

    private fun enter(s: PoliceStage) { stage = s; stageTime = 0f }

    /** يلغي أي عملية جارية (عند إطفاء وضع الشرطي). */
    fun reset() {
        stage = PoliceStage.PATROL; stageTime = 0f; carVisible = false
        officerVisible = true; officerScale = 1f; thiefVisible = true; thiefScale = 1f
        dropped = false; hasTarget = false; moving = false
    }

    fun isBusy() = stage != PoliceStage.PATROL && stage != PoliceStage.CAR_LEAVING

    fun update(dt: Float) {
        val d = dt.coerceIn(0f, 0.1f)
        stageTime += d
        lightsClock += d
        when (stage) {
            PoliceStage.PATROL, PoliceStage.CAR_LEAVING -> {
                walkOfficer(d)
                if (stage == PoliceStage.CAR_LEAVING) driveCarAway(d)
            }
            PoliceStage.ARRESTING -> {
                moving = false
                if (stageTime >= 0.9f) startCar()
            }
            PoliceStage.CAR_COMING -> {
                val dx = carTargetX - cx
                val step = (abs(dx) * 4f + 120f) * d // يتباطأ عند الاقتراب
                cx += (if (dx > 0) 1 else -1) * min(abs(dx), step)
                if (abs(carTargetX - cx) < 2f) { cx = carTargetX; startOX = ox; startOY = oy; startTX = thiefX; startTY = thiefY; enter(PoliceStage.BOARDING) }
            }
            PoliceStage.BOARDING -> {
                val p = (stageTime / 0.7f).coerceIn(0f, 1f)
                ox = startOX + (cx - startOX) * p; oy = startOY + (cy - startOY) * p
                thiefX = startTX + (cx - startTX) * p; thiefY = startTY + (cy - startTY) * p
                officerScale = 1f - 0.6f * p; thiefScale = 1f - 0.6f * p
                if (p >= 1f) {
                    officerVisible = false; thiefVisible = false
                    cfacing = if (destX >= cx) 1 else -1
                    enter(PoliceStage.DRIVING)
                }
            }
            PoliceStage.DRIVING -> {
                val dx = destX - cx; val dy = destY - cy
                val dist = hypot(dx, dy)
                val v = 520f * d
                if (dist <= v + 2f) {
                    cx = destX; cy = destY
                    ox = cx + cfacing * carWidth * 0.6f; oy = cy
                    officerVisible = true; officerScale = 1f; ofacing = cfacing
                    dropped = true
                    enter(PoliceStage.DROP)
                } else {
                    cx += dx / dist * v; cy += dy / dist * v
                    if (abs(dx) > 4f) cfacing = if (dx > 0) 1 else -1
                    ox = cx; oy = cy; thiefX = cx; thiefY = cy
                }
            }
            PoliceStage.DROP -> {
                moving = false
                if (stageTime >= 1.2f) { enter(PoliceStage.CAR_LEAVING) }
            }
        }
    }

    private fun startCar() {
        val fromLeft = ox < arenaW / 2f
        cfacing = if (fromLeft) 1 else -1
        carVisible = true
        cx = if (fromLeft) -carWidth else arenaW + carWidth
        cy = (oy + carWidth * 0.12f).coerceIn(minY + carWidth * 0.3f, arenaH - carWidth * 0.3f)
        // تقف بجانب الشرطي من جهة قدومها
        carTargetX = ox - cfacing * carWidth * 0.55f
        enter(PoliceStage.CAR_COMING)
    }

    private fun driveCarAway(d: Float) {
        cx += cfacing * 600f * d
        if (cx < -carWidth * 1.2f || cx > arenaW + carWidth * 1.2f) { carVisible = false; enter(PoliceStage.PATROL) }
    }

    private fun walkOfficer(d: Float) {
        if (!hasTarget) { moving = false; return }
        val dx = tx - ox; val dy = ty - oy
        val dist = hypot(dx, dy)
        if (dist < 8f) { hasTarget = false; moving = false; return }
        val step = min(dist, speedPx * d)
        ox += dx / dist * step; oy += dy / dist * step
        if (abs(dx) > 3f) ofacing = if (dx > 0) 1 else -1
        walkClock += d
        moving = true
        clampOfficer()
    }

    private fun clampOfficer() {
        ox = ox.coerceIn(30f, (arenaW - 30f).coerceAtLeast(30f))
        oy = oy.coerceIn(minY + 40f, (arenaH - 20f).coerceAtLeast(minY + 40f))
    }
}
