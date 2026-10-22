package com.ghostthief.game.game

import com.ghostthief.game.data.ProgressRepository
import com.ghostthief.game.data.Settings
import com.ghostthief.game.logic.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.min
import kotlin.random.Random

enum class GameMessage { CAUGHT, CAUGHT_BONUS, ACHIEVEMENT, CIRCLE_FAILED, ESCAPED, TRY_AGAIN, SEEK_START, FOUND, NOT_HERE, OUCH, POLICE_ARREST, STOLEN, RECOVERED, HEIST_START, RESCUED, ESCAPED_LOOT, STASHED, DROPPED_LOOT }

interface GameListener {
    fun onMessage(msg: GameMessage, points: Int = 0)
    fun onSound(effect: SoundEffect)
    /** رسائل السرقة التي تحتوي اسم الغنيمة. */
    fun onLootMessage(msg: GameMessage, name: String, points: Int = 0) {}
    /** تشغيل/إيقاف صوت متكرر (يُستدعى عند تغيّر الحالة فقط، وليس كل إطار). rate = سرعة التشغيل (طبقة المحرك). */
    fun onLoop(sound: LoopSound, on: Boolean, rate: Float = 1f) {}
}

enum class SoundEffect { POP, CATCH, FAIL, ESCAPE, OUCH, CLANG, DING }

/** أصوات متكررة (حلقات): الصفارة ومحرك السيارة. */
enum class LoopSound { SIREN, ENGINE }

/** أيقونة «غنيمة» داخل مشهد اللعبة (تمثيلية فقط؛ لا علاقة لها بأيقونات شاشة الهاتف الحقيقية). */
class SceneIcon(val loot: Loot) {
    var x = 0f
    var y = 0f
    /** true = داخل كيس الحرامي (تختفي من المشهد حتى يُقبض عليه). */
    var stolen = false
    /** وميض عند عودتها بعد استعادتها (1 → 0). */
    var pop = 0f
}

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
    var bottomInset = 0f

    /** الوضع العائم المدمج: الساحة = الشاشة كلها، ونوافذ صغيرة تتبع الحرامي. */
    var compact = false
    /** مقياس حجم الحرامي (للنافذة العائمة). */
    var sizeFactor = 1f

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

    // السرقة التمثيلية (اختيارية)
    /** الغنائم المتاحة (أسماء وأحجام فقط، في الذاكرة). */
    var loots: List<Loot> = emptyList()
        set(v) { field = v; rebuildSceneIcons() }
    /** أيقونات الغنائم الظاهرة في المشهد (حتى 8). المسروقة منها لا تُرسم حتى يُقبض على الحرامي. */
    var sceneIcons: List<SceneIcon> = emptyList(); private set
    var targetIcon: SceneIcon? = null; private set
    val stolenCount: Int get() = sceneIcons.count { it.stolen }
    private var heistTimer = 8f + rnd.nextFloat() * 8f

    // وضع الشرطي: الطفل يحرّك الشرطي بإصبعه للقبض على الحرامي ثم تأخذهما سيارة الشرطة إلى السجن
    val squad = PoliceSquad(rnd)
    var policeMode = false; private set
    val policeActive: Boolean get() = policeMode && !compact
    private var policeDown = false
    private var policePlaced = false

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
        policeMode = settings.policeMode
        brain.hideChance = if (policeActive) 0.2f else 0.45f
        relayoutJail()
    }

    /** يشغّل/يطفئ وضع الشرطي (ويحفظه في الإعدادات). */
    fun setPoliceMode(on: Boolean) {
        settings.policeMode = on
        policeMode = on
        brain.hideChance = if (policeActive) 0.2f else 0.45f
        if (!on) { squad.reset(); brain.clearThreat(); brain.cancelArrest(); stopAudio() }
    }

    fun setArena(w: Float, h: Float, density: Float) {
        arenaW = w; arenaH = h
        // الأحجام تتناسب مع مساحة الساحة (مهم للنافذة العائمة الصغيرة)
        val baseRadius = minOf(48f * density, h * 0.17f).coerceAtLeast(20f * density)
        thiefRadius = baseRadius * sizeFactor
        jailH = minOf(72f * density, h * 0.36f) * maxOf(sizeFactor, 0.8f)
        jailW = jailH * 0.78f
        brain.ropeLength = thiefRadius * 1.3f
        jailMargin = 8f * density
        brain.minY = topInset
        brain.setArena(w, h - bottomInset)
        squad.carWidth = thiefRadius * 4.1f
        squad.setArena(w, h - bottomInset, topInset)
        if (!policePlaced) { squad.place(w * 0.2f, h * 0.7f); policePlaced = true }
        relayoutJail()
        layoutSceneIcons()
        if (brain.state == ThiefState.JAILED) brain.restoreJailed(jailCenterX(), jailCenterY())
    }

    private fun rangeW() = (arenaW - jailW).coerceAtLeast(0f)
    private fun rangeH() = (arenaH - bottomInset - topInset - jailH).coerceAtLeast(0f)

    private fun relayoutJail() {
        val px = settings.jailPosX; val py = settings.jailPosY
        if (px >= 0 && py >= 0) {
            jailL = (px / 1000f) * rangeW()
            jailT = topInset + (py / 1000f) * rangeH()
        } else {
            jailL = if (settings.jailCorner % 2 == 0) arenaW - jailW - jailMargin else jailMargin
            jailT = if (settings.jailCorner < 2) jailMargin + topInset else arenaH - bottomInset - jailH - jailMargin
        }
    }

    fun jailLeft(): Float = jailL
    fun jailTop(): Float = jailT
    fun jailCenterX() = jailL + jailW / 2f
    fun jailCenterY() = jailT + jailH / 2f

    fun isJailed() = brain.state == ThiefState.JAILED || brain.state == ThiefState.CAUGHT
    /** مشغول: لا نعرض إعلانات أثناء القبض أو فتح السجن. */
    fun isBusy() = squad.isBusy() || capture.inProgress || brain.state == ThiefState.CAUGHT || brain.state == ThiefState.ESCAPING || brain.state == ThiefState.HELD || draggingJail

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

        updateHeist(dt)
        if (policeActive) updatePolice(dt)

        if (brain.state == ThiefState.JAILED) { brain.x = jailCenterX(); brain.y = jailCenterY() }
        if (brain.arrivedInJail) {
            capture.finish()
            listener?.onSound(SoundEffect.CLANG) // صوت إغلاق السجن
        }
        if (brain.state == ThiefState.JAILED && jail.isExpired()) {
            jail.clear()
            progress.jailEndAt = 0L
            brain.escape(arenaW / 2f, arenaH / 2f)
            listener?.onMessage(GameMessage.ESCAPED)
            listener?.onSound(SoundEffect.ESCAPE)
        }
    }

    // ---------- وضع الشرطي ----------
    private fun carDestination(): Pair<Float, Float> {
        val side = if (jailCenterX() < arenaW / 2f) 1 else -1
        val dx = (jailCenterX() + side * (jailW / 2f + squad.carWidth * 0.55f)).coerceIn(squad.carWidth / 2f, (arenaW - squad.carWidth / 2f).coerceAtLeast(squad.carWidth / 2f))
        val dy = (jailT + jailH - squad.carWidth * 0.12f).coerceIn(topInset + squad.carWidth * 0.3f, (arenaH - bottomInset - squad.carWidth * 0.2f).coerceAtLeast(topInset + squad.carWidth * 0.3f))
        return dx to dy
    }

    // حالة الأصوات المتكررة (لمنع إعادة التشغيل في كل إطار)
    private var sirenOn = false
    private var engineOn = false
    private var engineRate = 0f

    /** يوقف الصفارة والمحرك (عند الإيقاف المؤقت أو إطفاء الوضع). */
    fun stopAudio() {
        if (sirenOn) { sirenOn = false; listener?.onLoop(LoopSound.SIREN, false) }
        if (engineOn) { engineOn = false; engineRate = 0f; listener?.onLoop(LoopSound.ENGINE, false) }
    }

    private fun updatePoliceAudio() {
        val st = squad.stage
        // الصفارة أثناء المطاردة فقط: وصول السيارة والصعود والقيادة إلى السجن
        val chase = st == PoliceStage.CAR_COMING || st == PoliceStage.BOARDING || st == PoliceStage.DRIVING
        if (chase != sirenOn) { sirenOn = chase; listener?.onLoop(LoopSound.SIREN, chase) }
        val car = squad.carVisible
        if (car != engineOn) {
            engineOn = car; engineRate = 0f
            if (!car) listener?.onLoop(LoopSound.ENGINE, false)
        }
        if (engineOn) { // طبقة المحرك تتبع السرعة: تسارع ثم تباطؤ ثم توقف
            val rate = 0.75f + 0.85f * minOf(1f, squad.carSpeed / 520f)
            if (kotlin.math.abs(rate - engineRate) > 0.05f) { engineRate = rate; listener?.onLoop(LoopSound.ENGINE, true, rate) }
        }
    }

    private fun updatePolice(dt: Float) {
        squad.update(dt)
        if (settings.jailVisible) { // لا يتداخل الشرطي مع السجن
            val m = thiefRadius * 0.5f
            squad.setObstacle(jailL - m, jailT - m, jailL + jailW + m, jailT + jailH + m)
        } else squad.clearObstacle()
        updatePoliceAudio()
        val patrol = squad.stage == PoliceStage.PATROL
        val near = hypot(squad.ox - brain.x, squad.oy - brain.y)
        // الحرامي يهرب من الشرطي إذا اقترب
        if (patrol && near < thiefRadius * 5f && brain.alpha > 0.5f) brain.setThreat(squad.ox, squad.oy) else brain.clearThreat()
        // اللمس = قبض
        if (patrol && brain.alpha > 0.5f && brain.isCatchable() && brain.state != ThiefState.HELD &&
            near < thiefRadius * 1.2f && !isJailed()) startArrest()
        if (brain.state == ThiefState.ARRESTED) brain.setArrestPose(squad.thiefX, squad.thiefY, squad.thiefScale)
        if (squad.takeDropped()) beginCapture(CaptureSource.POLICE)
    }

    private fun startArrest() {
        if (!brain.arrest()) return
        recoverStolen()
        val (dx, dy) = carDestination()
        squad.arrest(brain.x, brain.y, dx, dy)
        listener?.onMessage(GameMessage.POLICE_ARREST)
    }

    // ---------- الغنائم التمثيلية: أيقونات في المشهد يسرقها الحرامي ----------
    private fun rebuildSceneIcons() {
        targetIcon = null
        if (compact || loots.isEmpty()) { sceneIcons = emptyList(); return }
        sceneIcons = loots.shuffled(rnd).take(MAX_SCENE_ICONS).map { SceneIcon(it) }
        layoutSceneIcons()
    }

    /** يوزّع الأيقونات على شبكة بعيداً عن السجن. */
    private fun layoutSceneIcons() {
        if (sceneIcons.isEmpty()) return
        val m = thiefRadius * 1.1f
        val cols = floatArrayOf(0.15f, 0.38f, 0.62f, 0.85f)
        val rows = floatArrayOf(0.20f, 0.33f, 0.46f, 0.59f)
        val h = arenaH - topInset - bottomInset
        val slots = ArrayList<Pair<Float, Float>>()
        for (ry in rows) for (cx in cols) {
            val x = arenaW * cx; val y = topInset + h * ry
            val overlapsJail = settings.jailVisible && x + m > jailL && x - m < jailL + jailW && y + m > jailT && y - m < jailT + jailH
            if (!overlapsJail) slots.add(x to y)
        }
        sceneIcons.forEachIndexed { i, ic ->
            val sl = slots.getOrNull(i) ?: (arenaW * 0.5f to topInset + h * 0.4f)
            ic.x = sl.first; ic.y = sl.second
        }
    }

    /** استعادة كل المسروقات عند القبض على الحرامي (بالإمساك اليدوي أو بالشرطي). */
    private fun recoverStolen() {
        val taken = sceneIcons.filter { it.stolen }
        if (taken.isEmpty()) return
        var total = 0; var large = false
        for (ic in taken) {
            ic.stolen = false; ic.pop = 1f
            total += LootRules.rescueBonus(ic.loot.kind)
            if (ic.loot.kind == LootKind.LARGE) large = true
        }
        progress.awardPoints(total)
        listener?.onMessage(GameMessage.RECOVERED, total)
        listener?.onSound(SoundEffect.DING)
        unlock(Achievement.FIRST_RESCUE)
        if (large) unlock(Achievement.LARGE_RESCUE)
    }

    private fun updateHeist(dt: Float) {
        for (ic in sceneIcons) if (ic.pop > 0f) ic.pop = maxOf(0f, ic.pop - dt * 1.5f)
        if (settings.heistEnabled && !compact && targetIcon == null && sceneIcons.any { !it.stolen } &&
            brain.state == ThiefState.RUN && !seekActive && !isJailed()) {
            heistTimer -= dt
            if (heistTimer <= 0f) startHeist()
        }
        val ev = brain.takeLootEvent()
        val ic = targetIcon ?: return
        when (ev) {
            LootEvent.STOLEN -> { // اختفت الأيقونة: صارت في الكيس
                ic.stolen = true; targetIcon = null
                heistTimer = 12f + rnd.nextFloat() * 14f
                listener?.onLootMessage(GameMessage.STOLEN, ic.loot.name)
            }
            LootEvent.ABORTED -> { targetIcon = null; heistTimer = 8f + rnd.nextFloat() * 8f }
            LootEvent.NONE -> {}
        }
    }

    /** للاختبار/التشغيل اليدوي: يبدأ سرقة أيقونة الآن. */
    fun triggerHeist() = startHeist()

    private fun startHeist() {
        val free = sceneIcons.filter { !it.stolen }
        if (free.isEmpty()) { heistTimer = 5f; return }
        val ic = free.random(rnd)
        if (brain.beginHeist(ic.x, ic.y, ic.loot.kind)) {
            targetIcon = ic
            listener?.onLootMessage(GameMessage.HEIST_START, ic.loot.name)
        } else heistTimer = 3f
    }

    // ---------- وضع البحث ----------
    private fun unlock(a: Achievement) {
        if (progress.unlock(a.id)) listener?.onMessage(GameMessage.ACHIEVEMENT, 1)
    }

    private fun seekAvailable() = settings.seekEnabled && seekNames.isNotEmpty() && !compact

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
            // كلما وجدته أسرع زادت النقاط
            val total = ScoreRules.SEEK_BASE + ScoreRules.seekSpeedBonus(seekTime)
            progress.awardPoints(total)
            listener?.onMessage(GameMessage.FOUND, total)
            listener?.onSound(SoundEffect.DING)
            if (seekTime <= ScoreRules.SHARP_EYES_SECONDS) unlock(Achievement.SHARP_EYES)
        }
    }

    // ---------- اللمس ----------
    fun hitsThief(x: Float, y: Float): Boolean =
        brain.alpha > 0.2f && !isJailed() && hypot(x - brain.x, y - brain.y) <= thiefRadius * 1.4f * brain.scale.coerceAtLeast(0.6f)

    fun hitsJail(x: Float, y: Float): Boolean =
        settings.jailVisible && x in jailL..(jailL + jailW) && y in jailT..(jailT + jailH)

    private fun moveJailTo(l: Float, t: Float) {
        jailL = l.coerceIn(0f, rangeW())
        jailT = t.coerceIn(topInset, topInset + rangeH())
        if (brain.state == ThiefState.JAILED) { brain.x = jailCenterX(); brain.y = jailCenterY() }
    }

    /** تحريك السجن من نافذة السجن العائمة (إحداثيات الشاشة). */
    fun dragJailTo(l: Float, t: Float) = moveJailTo(l, t)
    fun commitJailPosition() { saveJailPosition(); layoutSceneIcons() }

    private fun saveJailPosition() {
        val rw = rangeW(); val rh = rangeH()
        if (rw <= 0f || rh <= 0f) return
        settings.jailPosX = ((jailL / rw) * 1000f).toInt().coerceIn(0, 1000)
        settings.jailPosY = (((jailT - topInset) / rh) * 1000f).toInt().coerceIn(0, 1000)
    }

    fun touchDown(x: Float, y: Float) {
        if (handleSeekTap(x, y)) return
        // سحب السجن إلى أي مكان يريده اللاعب (لا يبدأ إن كان اللمس على الحرامي نفسه)
        if (brain.state != ThiefState.HELD && !hitsThief(x, y) && hitsJail(x, y)) {
            draggingJail = true; pointerDown = true
            dragDX = x - jailL; dragDY = y - jailT
            return
        }
        if (policeActive) { // الإصبع يحرّك الشرطي
            policeDown = true; pointerDown = true; squad.moveTo(x, y); return
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
        if (policeDown) { squad.moveTo(x, y); return }
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
        if (policeDown) { policeDown = false; return }
        brain.slowFactor = 1f
        when {
            brain.state == ThiefState.HELD -> {
                val inJail = x in (jailL - jailW * 0.4f)..(jailL + jailW * 1.4f) &&
                    y in (jailT - jailH * 0.4f)..(jailT + jailH * 1.4f)
                if (inJail) beginCapture(CaptureSource.LONG_PRESS_DRAG) else { brain.releaseHeld(); listener?.onMessage(GameMessage.TRY_AGAIN) }
            }
            !moved && downOnThief && downTime < LONG_PRESS_SEC -> {
                if (brain.poke()) { // ضربة خفيفة: يصرخ «آي!» ثم يهرب ويختبئ
                    listener?.onSound(SoundEffect.OUCH)
                    listener?.onMessage(GameMessage.OUCH)
                }
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
        policeDown = false
        pointerDown = false; brain.slowFactor = 1f
        if (brain.state == ThiefState.HELD) brain.releaseHeld()
        path.clear(); threadProgress = 0f
    }

    /** يبدأ القبض مرة واحدة فقط ويسجّل النقاط فوراً. */
    fun beginCapture(source: CaptureSource): Boolean {
        if (!capture.tryBegin(source)) return false
        val ok = if (source == CaptureSource.POLICE) brain.deliverToJail(jailCenterX(), jailCenterY())
                 else brain.capture(jailCenterX(), jailCenterY())
        if (!ok) { capture.finish(); return false }
        jail.start(settings.jailMinutes * 60_000L)
        progress.jailEndAt = jail.endAtMillis
        if (source != CaptureSource.POLICE) recoverStolen()
        val reward = progress.recordCapture(source == CaptureSource.CIRCLE)
        pointerDown = false; threadProgress = 0f; path.clear()
        listener?.onSound(SoundEffect.CATCH)
        listener?.onMessage(if (reward.bonusTier) GameMessage.CAUGHT_BONUS else GameMessage.CAUGHT, reward.pointsGained)
        if (reward.newAchievements.isNotEmpty()) listener?.onMessage(GameMessage.ACHIEVEMENT, reward.newAchievements.size)
        return true
    }

    companion object {
        const val LONG_PRESS_SEC = 0.5f
        const val MOVE_SLOP_PX = 24f
        const val SEEK_TIMEOUT_SEC = 45f
        const val SEEK_BONUS = 5
        const val MAX_SCENE_ICONS = 8
    }
}
