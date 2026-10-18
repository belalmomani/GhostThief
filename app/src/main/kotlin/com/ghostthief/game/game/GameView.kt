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
import com.ghostthief.game.logic.ThiefState
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * عرض اللعبة. الخلفية شفافة تماماً (لا نرسم أي خلفية) فتظهر خلفية الشاشة أو التطبيق الآخر خلفه.
 * يُستخدم في GameActivity وفي نافذة الوضع العائم.
 */
class GameView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs), Choreographer.FrameCallback, GameListener {

    var engine: GameEngine? = null
        set(value) { field = value; value?.listener = this; sizeEngine() }
    /** وضع النافذة العائمة: نرسم زر إغلاق ولا نرسم أي شيء خارج الساحة. */
    var arenaMode = false
    var onCloseRequested: (() -> Unit)? = null
    /** يُستدعى عند سحب مقبض تحريك النافذة العائمة (فرق الإحداثيات بالبكسل). */
    var onWindowDrag: ((Float, Float) -> Unit)? = null
    private var draggingWindow = false
    private var lastRawX = 0f
    private var lastRawY = 0f

    private val density = resources.displayMetrics.density
    private var sprites: Sprites? = null
    private var fx: SoundFx? = null
    private var running = false
    private var lastInvalidate = 0L
    private var touching = false

    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val color: Int, val size: Float)
    private val particles = ArrayList<Particle>()
    private var msgText: String? = null
    private var msgLife = 0f
    private val rnd = Random(System.nanoTime())
    private val closeR get() = 16f * density

    fun start() {
        if (running) return
        if (sprites == null) sprites = Sprites(context)
        if (fx == null) engine?.let { fx = SoundFx(context, it.settings) }
        running = true
        engine?.lastTickMs = 0L
        engine?.paused = false
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** إيقاف الحركة لتوفير البطارية. */
    fun pause() {
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
    private fun sizeEngine() { if (width > 0 && height > 0) engine?.setArena(width.toFloat(), height.toFloat(), density) }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val e = engine
        if (e != null) {
            val nowMs = frameTimeNanos / 1_000_000L
            e.tick(nowMs)
            updateEffects(0.016f)
            // 30 إطاراً/ثانية عند الخمول، و60 أثناء اللمس
            if (touching || nowMs - lastInvalidate >= 32) { lastInvalidate = nowMs; invalidate() }
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
            GameMessage.HEIST_START, GameMessage.RESCUED, GameMessage.ESCAPED_LOOT,
            GameMessage.STASHED, GameMessage.DROPPED_LOOT -> ""
        }
        if (text.isEmpty()) return
        post {
            msgText = text; msgLife = 2.4f
            if (e.settings.effectsEnabled) when (msg) {
                GameMessage.CAUGHT, GameMessage.CAUGHT_BONUS -> burst(e.jailCenterX(), e.jailCenterY(), true)
                GameMessage.CIRCLE_FAILED, GameMessage.TRY_AGAIN -> burst(e.brain.x, e.brain.y, false)
                GameMessage.ESCAPED -> burst(e.jailCenterX(), e.jailCenterY(), false)
                GameMessage.FOUND -> burst(e.brain.x, e.brain.y, true)
                GameMessage.OUCH -> burst(e.brain.x, e.brain.y, false)
                else -> {}
            }
        }
    }

    override fun onLootMessage(msg: GameMessage, name: String, points: Int) {
        val e = engine ?: return
        val text = when (msg) {
            GameMessage.HEIST_START -> context.getString(R.string.msg_heist_start, name)
            GameMessage.RESCUED -> context.getString(R.string.msg_rescued, name, points)
            GameMessage.ESCAPED_LOOT -> context.getString(R.string.msg_escaped_loot, name)
            GameMessage.STASHED -> context.getString(R.string.msg_stashed, name)
            GameMessage.DROPPED_LOOT -> context.getString(R.string.msg_dropped, name, points)
            else -> return
        }
        post {
            msgText = text; msgLife = 2.6f
            if (e.settings.effectsEnabled && (msg == GameMessage.RESCUED || msg == GameMessage.DROPPED_LOOT)) burst(e.brain.x, e.brain.y, true)
        }
    }

    override fun onSound(effect: SoundEffect) { fx?.play(effect) }

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
        drawJail(c, e, sp)
        drawLootBefore(c, e)
        drawThief(c, e, sp)
        drawLootCarried(c, e)
        drawTrail(c, e)
        for (q in particles) {
            p.color = q.color; p.alpha = (255 * (q.life.coerceIn(0f, 1f))).toInt()
            c.drawCircle(q.x, q.y, q.size, p)
        }
        p.alpha = 255
        drawMessage(c)
        if (arenaMode) { drawClose(c); drawGrip(c) }
    }

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

    private fun engineThiefHeight() = (engine?.thiefRadius ?: 60f) * 2.3f

    private fun drawThief(c: Canvas, e: GameEngine, sp: Sprites) {
        val b = e.brain
        val flip = b.facing < 0
        val x = b.x; val y = b.y
        when (b.state) {
            ThiefState.RUN, ThiefState.FLEE, ThiefState.HEIST_GO, ThiefState.CARRY ->
                drawSprite(c, sp.run[(b.runClock * 10f).toInt() % 4], x, y, b.scale, flip, 1f)
            ThiefState.DRAG -> { // يسحب بجهد: حركة بطيئة واهتزاز
                val shake = (sin(b.stateTime * 28f) * 2.5f * density)
                drawSprite(c, sp.run[(b.runClock * 6f).toInt() % 4], x + shake, y, b.scale, flip, 1f)
            }
            ThiefState.ESCAPING -> drawSprite(c, sp.run[(b.stateTime * 12f).toInt() % 4], x, y, b.scale, flip, 1f)
            ThiefState.STAND -> drawSprite(c, sp.stand, x, y + bob(b.stateTime), 1f, flip, 1f)
            ThiefState.LOOK -> drawSprite(c, sp.stand, x, y + bob(b.stateTime), 1f, flip, 1f)
            ThiefState.SURPRISED -> {
                drawSprite(c, sp.stand, x, y, 1f, flip, 1f)
                p.color = Color.parseColor("#1E88E5"); p.strokeWidth = 4f * density; p.style = Paint.Style.STROKE
                val ox = x + (if (flip) -1 else 1) * thiefRadius() * 0.9f; val oy = y - thiefRadius() * 1.1f
                c.drawLine(ox, oy, ox + 10f * density, oy - 12f * density, p)
                c.drawLine(ox + 14f * density, oy + 4f * density, ox + 28f * density, oy - 4f * density, p)
                p.style = Paint.Style.FILL
            }
            ThiefState.FADE_OUT, ThiefState.FADE_IN -> {
                val a = b.alpha
                drawSprite(c, sp.run[0], x, y, 1f, flip, a)
                drawSprite(c, sp.ghost, x, y, 1f, flip, 4f * a * (1f - a))
                drawFolder(c, sp, b.hideX, b.hideY, b.hideVariant, b.hideFolderAlpha)
            }
            ThiefState.HIDDEN -> if (e.seekActive) drawSeekSpots(c, e) else drawFolder(c, sp, b.hideX, b.hideY, b.hideVariant, b.hideFolderAlpha)
            ThiefState.HELD -> drawSprite(c, sp.caught, x, y, 0.8f, false, 1f)
            ThiefState.CAUGHT -> drawSprite(c, sp.caught, x, y, b.scale, false, 1f)
            ThiefState.JAILED -> {}
        }
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

    private fun lootColor(k: LootKind) = when (k) {
        LootKind.SMALL -> Color.rgb(67, 160, 71)
        LootKind.MEDIUM -> Color.rgb(251, 140, 0)
        LootKind.LARGE -> Color.rgb(229, 57, 53)
    }

    /** بطاقة غنيمة: أيقونة التطبيق (أو رمز ملف) + الاسم + الحجم. */
    private fun drawLootCard(c: Canvas, loot: Loot, cx: Float, cy: Float, scale: Float, alpha: Float) {
        val side = thiefRadius() * 1.25f * scale
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        p.style = Paint.Style.FILL; p.color = Color.argb((a * 0.92f).toInt(), 255, 255, 255)
        c.drawRoundRect(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f, 8f * density, 8f * density, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f * density; p.color = lootColor(loot.kind); p.alpha = a
        c.drawRoundRect(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f, 8f * density, 8f * density, p)
        p.style = Paint.Style.FILL; p.alpha = 255
        val icon = LootCatalog.icons[loot.name]
        if (icon != null) {
            val r = RectF(cx - side * 0.36f, cy - side * 0.36f, cx + side * 0.36f, cy + side * 0.36f)
            p.alpha = a; c.drawBitmap(icon, null, r, p); p.alpha = 255
        } else {
            textPaint.textSize = side * 0.55f; textPaint.color = Color.argb(a, 60, 60, 60)
            c.drawText(if (loot.isApp) "📦" else "📄", cx, cy + side * 0.2f, textPaint)
        }
        textPaint.textSize = 10f * density; textPaint.color = Color.argb(a, 13, 71, 161)
        c.drawText(loot.name + " • " + LootRules.formatSize(loot.sizeBytes), cx, cy + side / 2f + 12f * density, textPaint)
    }

    /** الغنيمة قبل الالتقاط أو أثناء سحبها بحبل. */
    private fun drawLootBefore(c: Canvas, e: GameEngine) {
        val loot = e.currentLoot ?: return
        val b = e.brain
        when (b.state) {
            ThiefState.HEIST_GO -> drawLootCard(c, loot, b.lootX, b.lootY, 1f, 1f)
            ThiefState.DRAG -> {
                val hx = b.x - b.facing * thiefRadius() * 0.35f; val hy = b.y + thiefRadius() * 0.1f
                val path = Path(); path.moveTo(hx, hy)
                path.quadTo((hx + b.lootX) / 2f, (hy + b.lootY) / 2f + 14f * density, b.lootX, b.lootY)
                trailPaint.color = Color.rgb(121, 85, 72); trailPaint.strokeWidth = 5f * density
                c.drawPath(path, trailPaint)
                trailPaint.color = Color.rgb(188, 143, 110); trailPaint.strokeWidth = 2f * density
                c.drawPath(path, trailPaint)
                drawLootCard(c, loot, b.lootX, b.lootY, 1.35f, 1f)
            }
            else -> {}
        }
    }

    /** الغنيمة المحمولة فوق رأس الحرامي. */
    private fun drawLootCarried(c: Canvas, e: GameEngine) {
        val loot = e.currentLoot ?: return
        val b = e.brain
        if (b.state == ThiefState.CARRY || (b.carrying && b.alpha > 0.05f && b.state != ThiefState.DRAG)) {
            drawLootCard(c, loot, b.x, b.y - thiefRadius() * 1.75f, 0.7f, b.alpha.coerceAtLeast(0.0f).let { if (b.state == ThiefState.CARRY) 1f else it })
        }
    }

    private fun thiefRadius() = engine?.thiefRadius ?: 60f
    private fun bob(t: Float) = (sin(t * 6f) * 2f * density)

    private fun drawFolder(c: Canvas, sp: Sprites, x: Float, y: Float, variant: Int, a: Float) {
        val fb = sp.folders[variant % sp.folders.size]
        val s = engineThiefHeight() * 0.85f / fb.height
        p.alpha = (a.coerceIn(0f, 1f) * 255).toInt()
        c.save(); c.translate(x, y); c.scale(s, s)
        c.drawBitmap(fb, -fb.width / 2f, -fb.height / 2f, p)
        c.restore(); p.alpha = 255
    }

    private fun drawJail(c: Canvas, e: GameEngine, sp: Sprites) {
        if (!e.settings.jailVisible) return
        val l = e.jailLeft(); val t = e.jailTop(); val w = e.jailW; val h = e.jailH
        val st = e.brain.state
        if (st == ThiefState.JAILED) {
            val dst = RectF(l, t, l + w, t + h)
            c.drawBitmap(sp.cageLocked, null, dst, p)
            if (e.settings.showJailTimer && e.jail.isJailed) {
                textPaint.textSize = 13f * density; textPaint.color = Color.WHITE
                textPaint.setShadowLayer(3f * density, 0f, 0f, Color.BLACK)
                c.drawText(JailTimer.format(e.jail.remainingMillis()), l + w / 2f, t + h + 14f * density, textPaint)
                textPaint.clearShadowLayer()
            }
        } else if (st == ThiefState.CAUGHT && e.brain.stateTime > 0.75f) {
            drawEmptyJail(c, l, t, w, h, 1f - ((e.brain.stateTime - 0.75f) / 0.15f).coerceIn(0f, 1f))
            p.alpha = (((e.brain.stateTime - 0.75f) / 0.15f).coerceIn(0f, 1f) * 255).toInt()
            c.drawBitmap(sp.cageClosing, null, RectF(l, t, l + w, t + h), p); p.alpha = 255
        } else {
            drawEmptyJail(c, l, t, w, h, 1f)
        }
    }

    /** سجن فارغ مرسوم برمجياً (شفاف، بقضبان) — استبدله لاحقاً بأصل فني مخصص إن رغبت. */
    private fun drawEmptyJail(c: Canvas, l: Float, t: Float, w: Float, h: Float, a: Float) {
        if (a <= 0f) return
        p.style = Paint.Style.FILL; p.color = Color.argb((50 * a).toInt(), 150, 200, 255)
        c.drawRoundRect(l, t, l + w, t + h, 8f * density, 8f * density, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f * density; p.color = Color.argb((200 * a).toInt(), 120, 175, 235)
        c.drawRoundRect(l, t, l + w, t + h, 8f * density, 8f * density, p)
        p.strokeWidth = 2.5f * density
        for (i in 1..4) { val bx = l + w * i / 5f; c.drawLine(bx, t + 4f * density, bx, t + h - 4f * density, p) }
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

    private fun gripRect() = RectF(4f * density, 4f * density, 44f * density, 28f * density)

    /** مقبض تحريك النافذة العائمة. */
    private fun drawGrip(c: Canvas) {
        val r = gripRect()
        p.color = Color.argb(150, 33, 33, 33); c.drawRoundRect(r, 10f * density, 10f * density, p)
        p.color = Color.WHITE
        for (i in -1..1) c.drawCircle(r.centerX() + i * 8f * density, r.centerY(), 2.2f * density, p)
    }

    private fun drawClose(c: Canvas) {
        val cx = width - closeR - 4f * density; val cy = closeR + 4f * density
        p.color = Color.argb(200, 230, 70, 70); c.drawCircle(cx, cy, closeR, p)
        p.color = Color.WHITE; p.strokeWidth = 3f * density; p.style = Paint.Style.STROKE
        val d = closeR * 0.4f
        c.drawLine(cx - d, cy - d, cx + d, cy + d, p); c.drawLine(cx - d, cy + d, cx + d, cy - d, p)
        p.style = Paint.Style.FILL
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val e = engine ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (arenaMode && gripRect().contains(ev.x, ev.y)) {
                    draggingWindow = true; lastRawX = ev.rawX; lastRawY = ev.rawY; return true
                }
                if (arenaMode) {
                    val cx = width - closeR - 4f * density; val cy = closeR + 4f * density
                    if (Math.hypot((ev.x - cx).toDouble(), (ev.y - cy).toDouble()) <= closeR * 1.6) {
                        onCloseRequested?.invoke(); return true
                    }
                }
                touching = true; e.touchDown(ev.x, ev.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingWindow) {
                    onWindowDrag?.invoke(ev.rawX - lastRawX, ev.rawY - lastRawY)
                    lastRawX = ev.rawX; lastRawY = ev.rawY
                } else e.touchMove(ev.x, ev.y)
            }
            MotionEvent.ACTION_UP -> {
                if (draggingWindow) { draggingWindow = false; return true }
                touching = false; e.touchUp(ev.x, ev.y); performClick()
            }
            MotionEvent.ACTION_CANCEL -> { draggingWindow = false; touching = false; e.touchCancel() }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
