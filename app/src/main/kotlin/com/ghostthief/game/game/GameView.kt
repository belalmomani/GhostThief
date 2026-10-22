package com.ghostthief.game.game

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.ghostthief.game.R
import com.ghostthief.game.logic.JailTimer
import com.ghostthief.game.logic.Loot
import com.ghostthief.game.logic.LootKind
import com.ghostthief.game.logic.LootRules
import com.ghostthief.game.logic.ThiefBrain
import com.ghostthief.game.logic.ThiefState
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * عرض اللعبة. الخلفية شفافة تماماً (لا نرسم أي خلفية).
 * FULL: داخل التطبيق (ساحة = حجم العرض).
 * ACTOR: نافذة عائمة صغيرة تتبع الحرامي (الساحة = الشاشة كلها).
 * JAIL: نافذة عائمة صغيرة للسجن (قابلة للسحب) مع زر إغلاق ✕ ملاصق له.
 */
class GameView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs), Choreographer.FrameCallback, GameListener {

    enum class Role { FULL, ACTOR, JAIL }
    var role = Role.FULL

    var engine: GameEngine? = null
        set(value) {
            field = value
            if (role != Role.JAIL) value?.listener = this
            if (role == Role.FULL) sizeEngine()
        }

    /** موضع النافذة على الشاشة (للأدوار العائمة). */
    var originX = 0f
    var originY = 0f
    var onFrame: (() -> Unit)? = null
    var onToast: ((String) -> Unit)? = null
    var onCloseRequested: (() -> Unit)? = null
    var onJailDrag: ((Float, Float) -> Unit)? = null
    var onJailDragEnd: (() -> Unit)? = null

    companion object {
        /** ارتفاع الشرطي نسبةً إلى ارتفاع الحرامي الواقف كما يُرسم (قيِّم هذه النسبة لتكبير/تصغير الشرطي). */
        const val OFFICER_HEIGHT_RATIO = 0.78f
        /** أبعاد صور الشرطي: مستوى القدمين وارتفاع المحتوى داخل اللوحة 256×320. */
        private const val POLICE_FEET_Y = 310f
        private const val POLICE_CONTENT_H = 285f
        /** أسفل العجلات داخل صورة السيارة 440×230. */
        private const val CAR_WHEEL_BOTTOM_Y = 216f
    }

    private val density = resources.displayMetrics.density
    val jailPad: Float get() = 10f * density
    private var sprites: Sprites? = null
    private var fx: SoundFx? = null
    private var running = false
    private var lastInvalidate = 0L
    private var touching = false
    private var jailDragging = false
    private var lastRawX = 0f
    private var lastRawY = 0f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val color: Int, val size: Float)
    private val particles = ArrayList<Particle>()
    private var msgText: String? = null
    private var msgLife = 0f
    private val rnd = Random(System.nanoTime())

    fun start() {
        if (running) return
        if (sprites == null) sprites = Sprites(context)
        if (fx == null && role != Role.JAIL) engine?.let { fx = SoundFx(context, it.settings) }
        running = true
        engine?.lastTickMs = 0L
        engine?.paused = false
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** إيقاف الحركة لتوفير البطارية. */
    fun pause() {
        engine?.stopAudio() // الصفارة والمحرك لا يستمران خارج اللعبة
        fx?.stopLoops()
        running = false
        engine?.paused = true
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (visibility == VISIBLE) start() }
    override fun onDetachedFromWindow() {
        pause(); fx?.release(); fx = null; sprites = null
        super.onDetachedFromWindow()
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) start() else if (visibility != VISIBLE) pause()
    }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); sizeEngine() }
    private fun sizeEngine() {
        if (role == Role.FULL && width > 0 && height > 0) engine?.setArena(width.toFloat(), height.toFloat(), density)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val e = engine
        if (e != null) {
            val nowMs = frameTimeNanos / 1_000_000L
            e.tick(nowMs)
            updateEffects(0.016f)
            onFrame?.invoke()
            val st = e.brain.state
            val animating = st == ThiefState.CAUGHT || st == ThiefState.ESCAPING
            val interval = if (role == Role.JAIL && !animating) 500L else 32L
            if (touching || nowMs - lastInvalidate >= interval) { lastInvalidate = nowMs; invalidate() }
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun updateEffects(dt: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val q = it.next()
            q.life -= dt; q.x += q.vx * dt; q.y += q.vy * dt; q.vy += 200f * dt
            if (q.life <= 0f) it.remove()
        }
        if (msgLife > 0f) msgLife -= dt
    }

    // ---- GameListener ----
    private fun show(text: String) {
        post {
            if (role == Role.ACTOR) onToast?.invoke(text) else { msgText = text; msgLife = 2.6f }
        }
    }

    override fun onMessage(msg: GameMessage, points: Int) {
        val e = engine ?: return
        val text = when (msg) {
            GameMessage.CAUGHT -> context.getString(R.string.msg_caught, points)
            GameMessage.CAUGHT_BONUS -> context.getString(R.string.msg_caught_bonus, points)
            GameMessage.ACHIEVEMENT -> context.getString(R.string.msg_achievement)
            GameMessage.CIRCLE_FAILED -> context.getString(R.string.msg_circle_failed)
            GameMessage.ESCAPED -> context.getString(R.string.msg_escaped)
            GameMessage.TRY_AGAIN -> context.getString(R.string.msg_try_again)
            GameMessage.SEEK_START -> context.getString(R.string.msg_seek_start)
            GameMessage.FOUND -> context.getString(R.string.msg_found, points)
            GameMessage.NOT_HERE -> context.getString(R.string.msg_not_here)
            GameMessage.OUCH -> context.getString(R.string.msg_ouch)
            GameMessage.POLICE_ARREST -> context.getString(R.string.msg_police_arrest)
            GameMessage.RECOVERED -> context.getString(R.string.msg_recovered, points)
            GameMessage.STOLEN, GameMessage.HEIST_START, GameMessage.RESCUED, GameMessage.ESCAPED_LOOT,
            GameMessage.STASHED, GameMessage.DROPPED_LOOT -> ""
        }
        if (text.isEmpty()) return
        show(text)
        post {
            if (e.settings.effectsEnabled) when (msg) {
                GameMessage.CAUGHT, GameMessage.CAUGHT_BONUS -> burst(e.jailCenterX(), e.jailCenterY(), true)
                GameMessage.CIRCLE_FAILED, GameMessage.TRY_AGAIN -> burst(e.brain.x, e.brain.y, false)
                GameMessage.ESCAPED -> burst(e.jailCenterX(), e.jailCenterY(), false)
                GameMessage.FOUND -> burst(e.brain.x, e.brain.y, true)
                GameMessage.RECOVERED -> for (ic in e.sceneIcons) if (ic.pop > 0f) burst(ic.x, ic.y, true)
                GameMessage.OUCH -> burst(e.brain.x, e.brain.y, false)
                else -> {}
            }
        }
    }

    override fun onLootMessage(msg: GameMessage, name: String, points: Int) {
        val e = engine ?: return
        val text = when (msg) {
            GameMessage.HEIST_START -> context.getString(R.string.msg_heist_start, name)
            GameMessage.STOLEN -> context.getString(R.string.msg_stolen, name)
            GameMessage.RESCUED -> context.getString(R.string.msg_rescued, name, points)
            GameMessage.ESCAPED_LOOT -> context.getString(R.string.msg_escaped_loot, name)
            GameMessage.STASHED -> context.getString(R.string.msg_stashed, name)
            GameMessage.DROPPED_LOOT -> context.getString(R.string.msg_dropped, name, points)
            else -> return
        }
        show(text)
        post { if (e.settings.effectsEnabled && (msg == GameMessage.RESCUED || msg == GameMessage.DROPPED_LOOT)) burst(e.brain.x, e.brain.y, true) }
    }

    override fun onSound(effect: SoundEffect) { fx?.play(effect) }

    override fun onLoop(sound: LoopSound, on: Boolean, rate: Float) { fx?.loop(sound, on, rate) }

    private fun burst(cx: Float, cy: Float, happy: Boolean) {
        val colors = if (happy) intArrayOf(0xFFFFD54F.toInt(), 0xFF4FC3F7.toInt(), 0xFFFF8A65.toInt(), 0xFF81C784.toInt())
        else intArrayOf(0xFFE0E0E0.toInt(), 0xFFBDBDBD.toInt())
        repeat(if (happy) 26 else 12) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val s = (60f + rnd.nextFloat() * 160f) * density / 2f
            particles.add(Particle(cx, cy, kotlin.math.cos(a) * s, kotlin.math.sin(a) * s - 60f, 0.8f + rnd.nextFloat() * 0.6f, colors[rnd.nextInt(colors.size)], (3f + rnd.nextFloat() * 4f) * density))
        }
    }

    // ---- الرسم ----
    override fun onDraw(c: Canvas) {
        val e = engine ?: return
        val sp = sprites ?: return
        when (role) {
            Role.FULL -> {
                drawJail(c, e, sp)
                drawSceneIcons(c, e)
                drawActors(c, e, sp) // ترتيب العمق: الأدنى على الشاشة يُرسم أمام
                drawDraggedLoot(c, e)
                drawStolenBadge(c, e)
                drawTrail(c, e)
                drawParticles(c)
                drawMessage(c)
            }
            Role.ACTOR -> {
                c.save(); c.translate(-originX, -originY)
                drawThief(c, e, sp)
                drawTrail(c, e)
                drawParticles(c)
                c.restore()
            }
            Role.JAIL -> {
                c.save(); c.translate(-originX, -originY)
                drawJail(c, e, sp)
                c.restore()
                drawChip(c, e)
            }
        }
    }

    private fun drawParticles(c: Canvas) {
        for (q in particles) {
            p.color = q.color; p.alpha = (255 * (q.life.coerceIn(0f, 1f))).toInt()
            c.drawCircle(q.x, q.y, q.size, p)
        }
        p.alpha = 255
    }

    private fun thiefRadius() = engine?.thiefRadius ?: 60f
    private fun engineThiefHeight() = thiefRadius() * 2.3f

    private fun drawSprite(c: Canvas, b: Bitmap, cx: Float, cy: Float, scale: Float, flip: Boolean, alpha: Float) {
        val s = (sprites?.refHeight ?: 305f).let { (engineThiefHeight() / it) * scale }
        p.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        c.save()
        c.translate(cx, cy)
        c.scale(if (flip) -s else s, s)
        c.drawBitmap(b, -b.width / 2f, -b.height / 2f, p)
        c.restore()
        p.alpha = 255
    }

    private fun drawThief(c: Canvas, e: GameEngine, sp: Sprites) {
        val b = e.brain
        val flip = b.facing < 0
        val x = b.x; val y = b.y
        when (b.state) {
            ThiefState.SNEAK -> { // يتوقف عند الأيقونة ويتلفّت ليتأكد أن لا أحد يراه
                drawSprite(c, sp.stand, x, y + bob(b.stateTime), 1f, flip, 1f)
                drawFx(c, sp.question, x + (if (flip) -1 else 1) * thiefRadius() * 0.9f, y - thiefRadius() * 1.5f, thiefRadius() * 0.9f, 0f, 1f)
            }
            ThiefState.GRAB -> drawSprite(c, sp.hide, x, y + thiefRadius() * 0.25f, 1f, flip, 1f) // ينحني ويسحبها داخل كيسه
            ThiefState.RUN, ThiefState.FLEE, ThiefState.HEIST_GO, ThiefState.CARRY ->
                drawSprite(c, sp.run[(b.runClock * 10f).toInt() % 4], x, y, b.scale, flip, 1f)
            ThiefState.DRAG -> { // يسحب بجهد: حركة بطيئة واهتزاز
                val shake = (sin(b.stateTime * 28f) * 2.5f * density)
                drawSprite(c, sp.run[(b.runClock * 6f).toInt() % 4], x + shake, y, b.scale, flip, 1f)
            }
            ThiefState.ESCAPING -> drawSprite(c, sp.run[(b.stateTime * 12f).toInt() % 4], x, y, b.scale, flip, 1f)
            ThiefState.STAND -> drawSprite(c, sp.stand, x, y + bob(b.stateTime), 1f, flip, 1f)
            ThiefState.LOOK -> { // ينظر حوله: يلتفت وتظهر علامة استفهام
                drawSprite(c, sp.stand, x, y + bob(b.stateTime), 1f, flip, 1f)
                drawFx(c, sp.question, x + (if (flip) -1 else 1) * thiefRadius() * 0.9f, y - thiefRadius() * 1.5f, thiefRadius() * 0.9f, 0f, 1f)
            }
            ThiefState.SURPRISED -> { // ضربة خفيفة: نجوم تدور فوق رأسه
                drawSprite(c, sp.stand, x, y, 1f, flip, 1f)
                drawFx(c, sp.stars, x, y - thiefRadius() * 1.15f, thiefRadius() * 1.5f, b.stateTime * 420f, 1f)
            }
            ThiefState.FADE_OUT, ThiefState.FADE_IN -> {
                val a = b.alpha
                drawSprite(c, sp.run[0], x, y, 1f, flip, a)
                drawSprite(c, sp.ghost, x, y, 1f, flip, 4f * a * (1f - a))
                drawFolder(c, sp, b.hideX, b.hideY, b.hideVariant, b.hideFolderAlpha)
                drawFx(c, sp.poof, x, y, thiefRadius() * (1.4f + 0.8f * (1f - a)), 0f, 4f * a * (1f - a))
            }
            ThiefState.HIDDEN -> if (e.seekActive) drawSeekSpots(c, e) else drawFolder(c, sp, b.hideX, b.hideY, b.hideVariant, b.hideFolderAlpha)
            ThiefState.HELD -> drawSprite(c, sp.caught, x, y, 0.8f, false, 1f)
            ThiefState.CAUGHT -> drawSprite(c, sp.caught, x, y, b.scale, false, 1f)
            ThiefState.ARRESTED -> if (e.squad.thiefVisible) { // مكبَّل بجانب الشرطي
                drawSprite(c, sp.stand, x, y, b.scale, flip, 1f)
                drawHandcuffs(c, x, y + thiefRadius() * 0.25f * b.scale, thiefRadius() * 0.5f * b.scale)
            }
            ThiefState.JAILED -> {}
        }
    }

    // ---- الشرطي وسيارة الشرطة ----
    /** ارتفاع الحرامي الواقف كما يُرسم فعلاً: مرجع حجم الشرطي وخط الأرض المشترك. */
    private fun thiefStandDrawnH(sp: Sprites) = sp.stand.height * engineThiefHeight() / sp.refHeight

    /** يرسم الحرامي والشرطي والسيارة مرتبين بحسب العمق (y) فلا يغطي أحدها الآخر خطأً. */
    private fun drawActors(c: Canvas, e: GameEngine, sp: Sprites) {
        val items = ArrayList<Pair<Float, () -> Unit>>(3)
        items.add(e.brain.y to { drawThief(c, e, sp) })
        if (e.policeActive) {
            val sq = e.squad
            if (sq.carVisible) items.add((sq.cy + 1f) to { drawCar(c, sq, sp) })
            if (sq.officerVisible) items.add(sq.oy to { drawOfficer(c, sq, sp) })
        }
        items.sortBy { it.first }
        for (it in items) it.second()
    }

    private fun drawCar(c: Canvas, sq: com.ghostthief.game.logic.PoliceSquad, sp: Sprites) {
        val bmp = sp.policeCar
        val s = sq.carWidth / bmp.width
        val flip = sq.cfacing < 0
        val groundY = sq.cy + thiefStandDrawnH(sp) / 2f            // العجلات على نفس خط أرض الشخصيات
        c.save(); c.translate(sq.cx, groundY); c.scale(if (flip) -s else s, s)
        c.drawBitmap(bmp, -bmp.width / 2f, -CAR_WHEEL_BOTTOM_Y, p)
        c.restore()
        // أضواء الطوارئ تومض (أحمر/أزرق) فوق شريط الأضواء
        val redOn = ((sq.lightsClock * 5f).toInt() % 2) == 0
        val lx = ((if (redOn) 195f else 249f) - bmp.width / 2f) * s * (if (flip) -1f else 1f)
        val ly = (29f - CAR_WHEEL_BOTTOM_Y) * s
        val col = if (redOn) Color.rgb(255, 70, 70) else Color.rgb(70, 120, 255)
        for ((r, a) in listOf(30f to 45, 19f to 90, 10f to 190)) {
            p.color = col; p.alpha = a; c.drawCircle(sq.cx + lx, groundY + ly, r * s * 1.8f, p)
        }
        p.alpha = 255
    }

    private fun drawOfficer(c: Canvas, sq: com.ghostthief.game.logic.PoliceSquad, sp: Sprites) {
        // سرعة عالية = ركض، بطيئة (قرب الهدف) = مشي، ثابت = وقوف
        val frame = when {
            !sq.moving -> sp.policeStand
            sq.speedRatio > 0.7f -> sp.policeRun[(sq.walkClock * 11f).toInt() % 8]
            else -> sp.policeWalk[(sq.walkClock * 8f).toInt() % 8]
        }
        val standH = thiefStandDrawnH(sp)
        val groundY = sq.oy + standH / 2f                            // نفس خط أرض الحرامي
        val s = (OFFICER_HEIGHT_RATIO * standH) / POLICE_CONTENT_H * sq.officerScale
        c.save(); c.translate(sq.ox, groundY); c.scale(if (sq.ofacing < 0) -s else s, s)
        c.drawBitmap(frame, -frame.width / 2f, -POLICE_FEET_Y, p)
        c.restore()
    }

    /** أصفاد مرسومة (حلقتان وسلسلة) بدل أي رمز تعبيري. */
    private fun drawHandcuffs(c: Canvas, cx: Float, cy: Float, size: Float) {
        p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.22f
        p.color = Color.rgb(207, 214, 226)
        val r = size * 0.42f; val dx = size * 0.62f
        c.drawCircle(cx - dx, cy, r, p); c.drawCircle(cx + dx, cy, r, p)
        p.strokeWidth = size * 0.16f; p.color = Color.rgb(150, 160, 178)
        c.drawLine(cx - dx + r, cy, cx + dx - r, cy, p)
        p.style = Paint.Style.FILL
    }

    private fun drawAppPlaceholder(c: Canvas, loot: Loot, r: RectF, a: Int) {
        val palette = intArrayOf(0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFF8E24AA.toInt(), 0xFFF4511E.toInt(), 0xFF00897B.toInt(), 0xFF5D4037.toInt())
        p.style = Paint.Style.FILL; p.color = palette[(loot.name.hashCode() and 0x7fffffff) % palette.size]; p.alpha = a
        c.drawRoundRect(r, r.width() * 0.22f, r.width() * 0.22f, p)
        textPaint.textSize = r.height() * 0.55f; textPaint.color = Color.argb(a, 255, 255, 255)
        c.drawText(loot.name.take(1).uppercase(), r.centerX(), r.centerY() + r.height() * 0.2f, textPaint)
        p.alpha = 255
    }

    private fun bob(t: Float) = (sin(t * 6f) * 2f * density)

    private fun drawFolder(c: Canvas, sp: Sprites, x: Float, y: Float, variant: Int, a: Float) {
        val fb = sp.folders[variant % sp.folders.size]
        val s = engineThiefHeight() * 0.85f / fb.height
        p.alpha = (a.coerceIn(0f, 1f) * 255).toInt()
        c.save(); c.translate(x, y); c.scale(s, s)
        c.drawBitmap(fb, -fb.width / 2f, -fb.height / 2f, p)
        c.restore(); p.alpha = 255
    }

    /** مجلدات فارغة مرسومة برمجياً بأسماء من مجلد المستخدم؛ الحرامي في أحدها. */
    private fun drawSeekSpots(c: Canvas, e: GameEngine) {
        val w = e.thiefRadius * 1.9f; val h = e.thiefRadius * 1.3f
        for (sp in e.seekSpots) {
            val a = if (sp.wrong) 90 else 255
            val l = sp.x - w / 2f; val t = sp.y - h / 2f
            p.style = Paint.Style.FILL; p.color = Color.argb(a, 255, 193, 7)
            c.drawRoundRect(l, t - h * 0.12f, l + w * 0.4f, t + h * 0.3f, 6f * density, 6f * density, p)
            c.drawRoundRect(l, t, l + w, t + h, 8f * density, 8f * density, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * density; p.color = Color.argb(a, 230, 150, 0)
            c.drawRoundRect(l, t, l + w, t + h, 8f * density, 8f * density, p)
            p.style = Paint.Style.FILL
            textPaint.textSize = 12f * density; textPaint.color = Color.argb(a, 40, 40, 40)
            c.drawText(if (sp.wrong) "✗" else "?", sp.x, sp.y + 5f * density, textPaint)
            textPaint.textSize = 11f * density; textPaint.color = Color.argb(255, 13, 71, 161)
            c.drawText(sp.name, sp.x, t + h + 14f * density, textPaint)
        }
        p.alpha = 255
    }

    // ---- الغنائم: الشكل الحقيقي للتطبيق، وأيقونات الملفات بحسب نوعها ----
    private fun lootColor(k: LootKind) = when (k) {
        LootKind.SMALL -> Color.rgb(67, 160, 71)
        LootKind.MEDIUM -> Color.rgb(251, 140, 0)
        LootKind.LARGE -> Color.rgb(229, 57, 53)
    }

    private fun fileColor(ext: String): Int = when (ext) {
        "PDF" -> 0xFFE53935.toInt()
        "DOC", "DOCX", "TXT" -> 0xFF1E88E5.toInt()
        "XLS", "XLSX", "CSV" -> 0xFF43A047.toInt()
        "MP4", "MKV", "AVI", "MOV" -> 0xFF8E24AA.toInt()
        "MP3", "WAV", "M4A", "OGG" -> 0xFFF4511E.toInt()
        "JPG", "JPEG", "PNG", "GIF", "WEBP" -> 0xFF00ACC1.toInt()
        "ZIP", "RAR", "7Z" -> 0xFF6D4C41.toInt()
        "APK" -> 0xFF3DDC84.toInt()
        else -> 0xFF607D8B.toInt()
    }

    private fun drawFileIcon(c: Canvas, loot: Loot, r: RectF, a: Int) {
        val ext = loot.name.substringAfterLast('.', "").uppercase().take(4)
        val w = r.width() * 0.78f; val h = r.height() * 0.92f
        val l = r.centerX() - w / 2f; val t = r.centerY() - h / 2f
        val fold = w * 0.3f
        val path = Path().apply {
            moveTo(l, t); lineTo(l + w - fold, t); lineTo(l + w, t + fold); lineTo(l + w, t + h); lineTo(l, t + h); close()
        }
        p.style = Paint.Style.FILL; p.color = fileColor(ext); p.alpha = a
        c.drawPath(path, p)
        p.color = Color.argb((a * 0.55f).toInt(), 255, 255, 255)
        c.drawPath(Path().apply { moveTo(l + w - fold, t); lineTo(l + w, t + fold); lineTo(l + w - fold, t + fold); close() }, p)
        textPaint.textSize = r.height() * 0.26f; textPaint.color = Color.argb(a, 255, 255, 255)
        c.drawText(if (ext.isEmpty()) "FILE" else ext, r.centerX(), r.centerY() + h * 0.18f, textPaint)
        p.alpha = 255
    }

    private fun drawLootCard(c: Canvas, loot: Loot, cx: Float, cy: Float, scale: Float, alpha: Float) {
        val side = thiefRadius() * 1.25f * scale
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        val r = RectF(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
        val icon = LootCatalog.icons[loot.name]
        if (icon != null) { // أيقونة التطبيق الحقيقية
            p.alpha = a; c.drawBitmap(icon, null, r, p); p.alpha = 255
        } else if (loot.isApp) drawAppPlaceholder(c, loot, r, a) else drawFileIcon(c, loot, r, a)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * density; p.color = lootColor(loot.kind); p.alpha = a
        c.drawRoundRect(r, side * 0.2f, side * 0.2f, p)
        p.style = Paint.Style.FILL; p.alpha = 255
        textPaint.textSize = 10f * density; textPaint.color = Color.argb(a, 13, 71, 161)
        c.drawText(loot.name + " • " + LootRules.formatSize(loot.sizeBytes), cx, cy + side / 2f + 12f * density, textPaint)
    }

    /** أيقونات الغنائم الظاهرة في المشهد (غير المسروقة). */
    private fun drawSceneIcons(c: Canvas, e: GameEngine) {
        if (e.sceneIcons.isEmpty()) return
        val b = e.brain
        val target = e.targetIcon
        for (ic in e.sceneIcons) {
            if (ic.stolen) continue                                  // داخل الكيس: لا تُرسم في أي مكان
            if (ic === target && b.state == ThiefState.DRAG) continue // تُرسم مسحوبة بالحبل
            var cx = ic.x; var cy = ic.y; var sc = 1f; var al = 1f
            if (ic === target && b.state == ThiefState.GRAB) {
                // تطير داخل الكيس (خلف الحرامي) وتصغر حتى تختفي
                val pr = (b.stateTime / ThiefBrain.GRAB_SECONDS).coerceIn(0f, 1f)
                val e2 = pr * pr * (3f - 2f * pr)
                val bagX = b.x - b.facing * thiefRadius() * 0.55f; val bagY = b.y + thiefRadius() * 0.1f
                cx = ic.x + (bagX - ic.x) * e2; cy = ic.y + (bagY - ic.y) * e2
                sc = 1f - 0.85f * e2; al = 1f - 0.5f * e2
            } else {
                cy += sin(System.nanoTime() / 4.0e8 + ic.x).toFloat() * 2f * density   // طفو خفيف
                if (ic === target && b.state == ThiefState.SNEAK) { // الأيقونة التي ينوي سرقتها تتوهج
                    p.style = Paint.Style.STROKE; p.strokeWidth = 3f * density; p.color = Color.argb(160, 255, 193, 7)
                    c.drawCircle(cx, cy, thiefRadius() * 0.95f, p); p.style = Paint.Style.FILL
                }
            }
            sc *= 1f + ic.pop * 0.35f
            drawLootCard(c, ic.loot, cx, cy, sc, al)
        }
    }

    /** أيقونة كبيرة يسحبها الحرامي بحبل. */
    private fun drawDraggedLoot(c: Canvas, e: GameEngine) {
        val ic = e.targetIcon ?: return
        val b = e.brain
        if (b.state != ThiefState.DRAG) return
        val hx = b.x - b.facing * thiefRadius() * 0.35f; val hy = b.y + thiefRadius() * 0.1f
        val path = Path(); path.moveTo(hx, hy)
        path.quadTo((hx + b.lootX) / 2f, (hy + b.lootY) / 2f + 14f * density, b.lootX, b.lootY)
        trailPaint.color = Color.rgb(121, 85, 72); trailPaint.strokeWidth = 5f * density
        c.drawPath(path, trailPaint)
        trailPaint.color = Color.rgb(188, 143, 110); trailPaint.strokeWidth = 2f * density
        c.drawPath(path, trailPaint)
        drawLootCard(c, ic.loot, b.lootX, b.lootY, 1.15f, 1f)
    }

    /** شارة عدد المسروقات في الكيس (تعود كلها عند القبض على الحرامي). */
    private fun drawStolenBadge(c: Canvas, e: GameEngine) {
        val n = e.stolenCount
        if (n <= 0) return
        val t = context.getString(R.string.hud_stolen, n)
        textPaint.textSize = 13f * density; textPaint.color = Color.WHITE
        val w = textPaint.measureText(t) + 20f * density
        val l = 8f * density; val top = 8f * density
        p.style = Paint.Style.FILL; p.color = Color.argb(215, 183, 28, 28)
        c.drawRoundRect(l, top, l + w, top + 26f * density, 13f * density, 13f * density, p)
        c.drawText(t, l + w / 2f, top + 18f * density, textPaint)
    }

    private fun drawJail(c: Canvas, e: GameEngine, sp: Sprites) {
        if (!e.settings.jailVisible) return
        val l = e.jailLeft(); val t = e.jailTop(); val w = e.jailW; val h = e.jailH
        val st = e.brain.state
        if (st == ThiefState.JAILED) {
            c.drawBitmap(sp.cageLocked, null, RectF(l, t, l + w, t + h), p)
            if (e.settings.showJailTimer && e.jail.isJailed) {
                textPaint.textSize = 13f * density; textPaint.color = Color.WHITE
                textPaint.setShadowLayer(3f * density, 0f, 0f, Color.BLACK)
                c.drawText(JailTimer.format(e.jail.remainingMillis()), l + w / 2f, t + h + 14f * density, textPaint)
                textPaint.clearShadowLayer()
            }
        } else if (st == ThiefState.CAUGHT && e.brain.stateTime > 0.75f) {
            drawEmptyJail(c, l, t, w, h, 1f - ((e.brain.stateTime - 0.75f) / 0.15f).coerceIn(0f, 1f), sp)
            p.alpha = (((e.brain.stateTime - 0.75f) / 0.15f).coerceIn(0f, 1f) * 255).toInt()
            c.drawBitmap(sp.cageClosing, null, RectF(l, t, l + w, t + h), p); p.alpha = 255
        } else if (st == ThiefState.ESCAPING && e.brain.stateTime < 0.9f) { // الباب مفتوح وسحابة غبار
            c.drawBitmap(sp.jailOpen, null, RectF(l, t, l + w, t + h), p)
            drawFx(c, sp.poof, l + w / 2f, t + h / 2f, w * 1.6f, 0f, 1f - e.brain.stateTime / 0.9f)
        } else {
            drawEmptyJail(c, l, t, w, h, 1f, sp)
        }
    }

    /** السجن الفارغ (صورة شفافة بنفس ألوان السجن الأصلي). */
    private fun drawEmptyJail(c: Canvas, l: Float, t: Float, w: Float, h: Float, a: Float, sp: Sprites) {
        if (a <= 0f) return
        p.alpha = (a.coerceIn(0f, 1f) * 255).toInt()
        c.drawBitmap(sp.jailEmpty, null, RectF(l, t, l + w, t + h), p)
        p.alpha = 255
    }

    /** زر ✕ ملاصق لزاوية السجن (يغلق الوضع العائم كله). */
    private fun drawChip(c: Canvas, e: GameEngine) {
        val cx = e.jailLeft() + e.jailW - originX
        val cy = e.jailTop() - originY
        val r = 9f * density
        p.style = Paint.Style.FILL; p.color = Color.argb(225, 230, 70, 70); c.drawCircle(cx, cy, r, p)
        p.color = Color.WHITE; p.strokeWidth = 2.2f * density; p.style = Paint.Style.STROKE
        val d = r * 0.42f
        c.drawLine(cx - d, cy - d, cx + d, cy + d, p); c.drawLine(cx - d, cy + d, cx + d, cy - d, p)
        p.style = Paint.Style.FILL
    }

    private fun drawTrail(c: Canvas, e: GameEngine) {
        if (!e.settings.effectsEnabled) return
        val pts = e.fingerPath
        if (pts.size < 2) return
        val path = Path(); path.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
        trailPaint.color = Color.argb(70, 30, 136, 229); trailPaint.strokeWidth = 14f * density
        c.drawPath(path, trailPaint)
        trailPaint.color = Color.argb(230, 130, 210, 255); trailPaint.strokeWidth = 5f * density
        c.drawPath(path, trailPaint)
    }

    private fun drawMessage(c: Canvas) {
        val t = msgText ?: return
        if (msgLife <= 0f) return
        textPaint.textSize = 18f * density; textPaint.color = Color.WHITE
        val tw = textPaint.measureText(t)
        val cx = width / 2f; val cy = height * 0.18f
        val a = (msgLife.coerceAtMost(0.5f) / 0.5f)
        p.color = Color.argb((210 * a).toInt(), 30, 100, 200)
        c.drawRoundRect(cx - tw / 2f - 18f * density, cy - 24f * density, cx + tw / 2f + 18f * density, cy + 14f * density, 20f * density, 20f * density, p)
        textPaint.alpha = (255 * a).toInt()
        c.drawText(t, cx, cy, textPaint)
        textPaint.alpha = 255
    }

    // ---- اللمس ----
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val e = engine ?: return false
        if (role == Role.JAIL) return jailTouch(ev, e)
        // في النافذة العائمة نستخدم إحداثيات الشاشة المطلقة (الساحة = الشاشة)
        val x = if (role == Role.FULL) ev.x else ev.rawX
        val y = if (role == Role.FULL) ev.y else ev.rawY
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { touching = true; e.touchDown(x, y) }
            MotionEvent.ACTION_MOVE -> e.touchMove(x, y)
            MotionEvent.ACTION_UP -> { touching = false; e.touchUp(x, y); performClick() }
            MotionEvent.ACTION_CANCEL -> { touching = false; e.touchCancel() }
        }
        return true
    }

    private fun jailTouch(ev: MotionEvent, e: GameEngine): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val cx = e.jailLeft() + e.jailW - originX; val cy = e.jailTop() - originY
                if (hypot((ev.x - cx).toDouble(), (ev.y - cy).toDouble()) <= jailPad * 1.7) { onCloseRequested?.invoke(); return true }
                jailDragging = true; lastRawX = ev.rawX; lastRawY = ev.rawY
            }
            MotionEvent.ACTION_MOVE -> if (jailDragging) {
                onJailDrag?.invoke(ev.rawX - lastRawX, ev.rawY - lastRawY)
                lastRawX = ev.rawX; lastRawY = ev.rawY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (jailDragging) { jailDragging = false; onJailDragEnd?.invoke() }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
