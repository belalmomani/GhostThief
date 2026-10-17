package com.ghostthief.game.game

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.ghostthief.game.R
import com.ghostthief.game.logic.JailTimer
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
        }
        post {
            msgText = text; msgLife = 2.4f
            if (e.settings.effectsEnabled) when (msg) {
                GameMessage.CAUGHT, GameMessage.CAUGHT_BONUS -> burst(e.jailCenterX(), e.jailCenterY(), true)
                GameMessage.CIRCLE_FAILED, GameMessage.TRY_AGAIN -> burst(e.brain.x, e.brain.y, false)
                GameMessage.ESCAPED -> burst(e.jailCenterX(), e.jailCenterY(), false)
                else -> {}
            }
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
        drawThief(c, e, sp)
        drawTrail(c, e)
        for (q in particles) {
            p.color = q.color; p.alpha = (255 * (q.life.coerceIn(0f, 1f))).toInt()
            c.drawCircle(q.x, q.y, q.size, p)
        }
        p.alpha = 255
        drawMessage(c)
        if (arenaMode) drawClose(c)
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
            ThiefState.RUN, ThiefState.FLEE -> drawSprite(c, sp.run[(b.runClock * 10f).toInt() % 4], x, y, b.scale, flip, 1f)
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
            ThiefState.HIDDEN -> drawFolder(c, sp, b.hideX, b.hideY, b.hideVariant, b.hideFolderAlpha)
            ThiefState.HELD -> drawSprite(c, sp.caught, x, y, 0.8f, false, 1f)
            ThiefState.CAUGHT -> drawSprite(c, sp.caught, x, y, b.scale, false, 1f)
            ThiefState.JAILED -> {}
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
                if (arenaMode) {
                    val cx = width - closeR - 4f * density; val cy = closeR + 4f * density
                    if (Math.hypot((ev.x - cx).toDouble(), (ev.y - cy).toDouble()) <= closeR * 1.6) {
                        onCloseRequested?.invoke(); return true
                    }
                }
                touching = true; e.touchDown(ev.x, ev.y)
            }
            MotionEvent.ACTION_MOVE -> e.touchMove(ev.x, ev.y)
            MotionEvent.ACTION_UP -> { touching = false; e.touchUp(ev.x, ev.y); performClick() }
            MotionEvent.ACTION_CANCEL -> { touching = false; e.touchCancel() }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
