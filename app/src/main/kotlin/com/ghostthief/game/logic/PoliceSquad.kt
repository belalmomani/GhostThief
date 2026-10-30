package com.ghostthief.game.logic

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/** مراحل عملية القبض بالشرطي والمركبة. */
enum class PoliceStage { PATROL, ARRESTING, CAR_COMING, BOARDING, DRIVING, DROP, CAR_LEAVING }

/**
 * الشرطي (يتحكم به الطفل بالأسهم) ومركبة الشرطة: منطق نقي بلا Android.
 * التسلسل: الشرطي يلمس الحرامي ← يُكبَّل ← تأتي المركبة ← يصعد الحرامي (والشرطي إن كانت المركبة مغلقة) ←
 * تقود إلى السجن ← يُسلَّم الحرامي (dropped) ← تغادر المركبة ← يعود الشرطي للدورية.
 *
 * مركبة مفتوحة (دراجة، مروحية): لها سائقها الخاص (يُرسم على المركبة) ويصعد الحرامي فقط، أما الشرطي
 * الذي يتحكم به الطفل فيبقى حراً في مكانه. مركبة مغلقة (سيارة): يصعد الشرطي والحرامي معاً.
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
    /** 0..1: نسبة السرعة الحالية (يبطؤ الشرطي قرب الهدف) — لاختيار المشي أو الركض. */
    var speedRatio = 0f
        private set
    /** سرعة المركبة بالبكسل/ثانية (للتحكم بصوت المحرك). */
    var carSpeed = 0f
        private set
    /** هل يصعد الشرطي مع الحرامي؟ false للدراجة والمروحية (لهما سائق خاص). */
    var officerBoards = true

    // اتجاه الأسهم (من لوحة الأسهم): (0,0) = واقف
    var dirX = 0f
        private set
    var dirY = 0f
        private set
    val hasDirection: Boolean get() = dirX != 0f || dirY != 0f

    // المركبة
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

    /** الركاب على متن المركبة الآن (يُرسمون مع المركبة في المراكب المفتوحة). */
    val passengersAboard: Boolean get() = stage == PoliceStage.DRIVING

    private var dropped = false
    /** true لمرة واحدة عند وصول المركبة إلى السجن. */
    fun takeDropped(): Boolean { val d = dropped; dropped = false; return d }

    private var hasObstacle = false
    private var obL = 0f; private var obT = 0f; private var obR = 0f; private var obB = 0f
    /** مستطيل ممنوع على الشرطي (السجن) حتى لا يتداخل معه. */
    fun setObstacle(l: Float, t: Float, r: Float, b: Float) { hasObstacle = true; obL = l; obT = t; obR = r; obB = b }
    fun clearObstacle() { hasObstacle = false }

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

    /** هدف حركة الشرطي (للاختبار أو للتحكم بالنقر). */
    fun moveTo(x: Float, y: Float) { tx = x; ty = y; hasTarget = true }
    fun stop() { hasTarget = false; dirX = 0f; dirY = 0f }

    /** اتجاه الأسهم: كل مركّبة بين -1 و1. (0,0) يوقف الشرطي. */
    fun setDirection(x: Float, y: Float) {
        dirX = x.coerceIn(-1f, 1f); dirY = y.coerceIn(-1f, 1f)
        if (hasDirection) hasTarget = false
    }

    /** يبدأ القبض: يُكبَّل الحرامي في مكانه ثم تأتي المركبة. */
    fun arrest(thiefAtX: Float, thiefAtY: Float, jailDestX: Float, jailDestY: Float): Boolean {
        if (stage != PoliceStage.PATROL) return false
        thiefX = thiefAtX; thiefY = thiefAtY; thiefScale = 1f; thiefVisible = true
        destX = jailDestX; destY = jailDestY
        hasTarget = false
        if (officerBoards) { moving = false; setDirection(0f, 0f) }
        ofacing = if (thiefAtX >= ox) 1 else -1
        enter(PoliceStage.ARRESTING)
        return true
    }

    private fun enter(s: PoliceStage) { stage = s; stageTime = 0f }

    /** يلغي أي عملية جارية (عند إطفاء وضع الشرطي). */
    fun reset() {
        stage = PoliceStage.PATROL; stageTime = 0f; carVisible = false
        officerVisible = true; officerScale = 1f; thiefVisible = true; thiefScale = 1f
        dropped = false; hasTarget = false; moving = false; dirX = 0f; dirY = 0f
    }

    fun isBusy() = stage != PoliceStage.PATROL && stage != PoliceStage.CAR_LEAVING

    fun update(dt: Float) {
        val d = dt.coerceIn(0f, 0.1f)
        stageTime += d
        lightsClock += d
        val prevCx = cx; val prevCy = cy

        // الشرطي حر في الحركة، إلا إن كان سيصعد المركبة (مركبة مغلقة) أثناء العملية
        val officerFree = stage == PoliceStage.PATROL || stage == PoliceStage.CAR_LEAVING || !officerBoards
        if (officerFree) walkOfficer(d) else { moving = false; speedRatio = 0f }

        when (stage) {
            PoliceStage.PATROL -> {}
            PoliceStage.ARRESTING -> if (stageTime >= 0.9f) startCar()
            PoliceStage.CAR_COMING -> {
                val dx = carTargetX - cx
                val step = (abs(dx) * 4f + 120f) * d // يتباطأ عند الاقتراب
                cx += (if (dx > 0) 1 else -1) * min(abs(dx), step)
                if (abs(carTargetX - cx) < 2f) {
                    cx = carTargetX; startOX = ox; startOY = oy; startTX = thiefX; startTY = thiefY
                    enter(PoliceStage.BOARDING)
                }
            }
            PoliceStage.BOARDING -> {
                val p = (stageTime / 0.7f).coerceIn(0f, 1f)
                thiefX = startTX + (cx - startTX) * p; thiefY = startTY + (cy - startTY) * p
                thiefScale = 1f - 0.6f * p
                if (officerBoards) {
                    ox = startOX + (cx - startOX) * p; oy = startOY + (cy - startOY) * p
                    officerScale = 1f - 0.6f * p
                }
                if (p >= 1f) {
                    thiefVisible = false
                    if (officerBoards) officerVisible = false
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
                    if (officerBoards) { // ينزل الشرطي بجانب المركبة
                        ox = cx + cfacing * carWidth * 0.6f; oy = cy
                        officerVisible = true; officerScale = 1f; ofacing = cfacing
                    }
                    thiefX = cx; thiefY = cy
                    dropped = true
                    enter(PoliceStage.DROP)
                } else {
                    cx += dx / dist * v; cy += dy / dist * v
                    if (abs(dx) > 4f) cfacing = if (dx > 0) 1 else -1
                    thiefX = cx; thiefY = cy
                    if (officerBoards) { ox = cx; oy = cy }
                }
            }
            PoliceStage.DROP -> if (stageTime >= 1.2f) enter(PoliceStage.CAR_LEAVING)
            PoliceStage.CAR_LEAVING -> driveCarAway(d)
        }
        carSpeed = if (carVisible && d > 0f) hypot(cx - prevCx, cy - prevCy) / d else 0f
    }

    private fun startCar() {
        val fromLeft = ox < arenaW / 2f
        cfacing = if (fromLeft) 1 else -1
        carVisible = true
        cx = if (fromLeft) -carWidth else arenaW + carWidth
        cy = (oy + carWidth * 0.12f).coerceIn(minY + carWidth * 0.3f, arenaH - carWidth * 0.3f)
        // تقف بجانب الحرامي المكبَّل من جهة قدومها
        carTargetX = thiefX - cfacing * carWidth * 0.55f
        enter(PoliceStage.CAR_COMING)
    }

    private fun driveCarAway(d: Float) {
        cx += cfacing * 600f * d
        if (cx < -carWidth * 1.2f || cx > arenaW + carWidth * 1.2f) { carVisible = false; enter(PoliceStage.PATROL) }
    }

    private fun walkOfficer(d: Float) {
        if (hasDirection) { // التحكم بالأسهم
            val len = hypot(dirX, dirY)
            ox += dirX / len * speedPx * d; oy += dirY / len * speedPx * d
            if (abs(dirX) > 0.1f) ofacing = if (dirX > 0) 1 else -1
            walkClock += d * 1.4f
            moving = true; speedRatio = 1f
            clampOfficer(); pushOutOfObstacle()
            return
        }
        if (!hasTarget) { moving = false; speedRatio = 0f; return }
        val dx = tx - ox; val dy = ty - oy
        val dist = hypot(dx, dy)
        if (dist < 8f) { hasTarget = false; moving = false; speedRatio = 0f; return }
        val arrive = (dist / 90f).coerceIn(0.25f, 1f) // يبطؤ عند الاقتراب من الهدف
        speedRatio = arrive
        val step = min(dist, speedPx * arrive * d)
        ox += dx / dist * step; oy += dy / dist * step
        if (abs(dx) > 3f) ofacing = if (dx > 0) 1 else -1
        walkClock += d * (0.6f + 0.8f * arrive) // وتيرة الخطوات تتبع السرعة
        moving = true
        clampOfficer()
        pushOutOfObstacle()
    }

    private fun pushOutOfObstacle() {
        if (!hasObstacle || ox < obL || ox > obR || oy < obT || oy > obB) return
        val dl = ox - obL; val dr = obR - ox; val dtp = oy - obT; val db = obB - oy
        when (minOf(minOf(dl, dr), minOf(dtp, db))) {
            dl -> ox = obL - 2f
            dr -> ox = obR + 2f
            dtp -> oy = obT - 2f
            else -> oy = obB + 2f
        }
        clampOfficer()
    }

    private fun clampOfficer() {
        ox = ox.coerceIn(30f, (arenaW - 30f).coerceAtLeast(30f))
        oy = oy.coerceIn(minY + 40f, (arenaH - 20f).coerceAtLeast(minY + 40f))
    }
}
