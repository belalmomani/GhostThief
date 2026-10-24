package com.ghostthief.game.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings as SysSettings
import android.view.Gravity
import android.view.View
import android.widget.Toast
import com.ghostthief.game.game.LootLoader
import com.ghostthief.game.logic.ThiefState
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.ghostthief.game.GhostThiefApp
import com.ghostthief.game.R
import com.ghostthief.game.game.GameEngine
import com.ghostthief.game.game.GameView
import com.ghostthief.game.ui.AppVisibility
import com.ghostthief.game.ui.MainActivity

/**
 * الوضع العائم المدمج: نافذتان صغيرتان فقط بدل نافذة كبيرة:
 *  1) نافذة صغيرة تتبع الحرامي أينما ذهب على الشاشة (ولا تغطي بقية الشاشة).
 *  2) نافذة السجن الصغيرة (قابلة للسحب) وعليها زر ✕ لإغلاق كل شيء.
 * بقية الشاشة تبقى قابلة للاستخدام بشكل طبيعي.
 */
class FloatingService : Service() {
    private var actor: GameView? = null
    private var jailView: GameView? = null
    private var actorLp: WindowManager.LayoutParams? = null
    private var jailLp: WindowManager.LayoutParams? = null
    private var engine: GameEngine? = null
    private var wm: WindowManager? = null
    private var suspended = false
    private var actorSize = 0
    private var screenW = 0
    private var screenH = 0
    private var actorShown = true
    private var officerView: GameView? = null
    private var carView: GameView? = null
    private var officerLp: WindowManager.LayoutParams? = null
    private var carLp: WindowManager.LayoutParams? = null
    private var officerSize = 0
    private var carW = 0
    private var carH = 0
    private var officerShown = false
    private var carShown = false
    private var jailShown = true
    private val handler = Handler(Looper.getMainLooper())
    /** يخفي النافذتين تلقائياً عندما تكون واجهة اللعبة نفسها ظاهرة، ويعيدهما عند الخروج منها. */
    private val visibilityPoll = object : Runnable {
        override fun run() {
            val s = AppVisibility.started > 0
            if (s != suspended) { suspended = s; applyVisibility() }
            handler.postDelayed(this, 300)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_HIDE -> { suspended = true; applyVisibility(); if (actor == null) stopSelf(); return START_NOT_STICKY }
            ACTION_SHOW -> { suspended = false; applyVisibility(); if (actor == null) stopSelf(); return START_NOT_STICKY }
        }
        goForeground()
        val app = GhostThiefApp.of(this)
        if (!app.settings.floatingEnabled || !SysSettings.canDrawOverlays(this)) { shutdown(); return START_NOT_STICKY }
        if (actor == null) addOverlay(app)
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, FloatingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_thief)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun addOverlay(app: GhostThiefApp) {
        val dm = resources.displayMetrics
        val density = dm.density
        screenW = dm.widthPixels; screenH = dm.heightPixels
        val sb = dimenPx("status_bar_height"); val nb = dimenPx("navigation_bar_height")
        val eng = GameEngine(app.settings, app.progress).also {
            it.compact = true
            it.topInset = sb.toFloat(); it.bottomInset = nb.toFloat() // لا نغطي شريط الحالة ولا أزرار التنقل
            it.sizeFactor = when (app.settings.floatSize) { 1 -> 0.8f; 2 -> 1f; else -> 0.65f }
            it.setArena(screenW.toFloat(), screenH.toFloat(), density)
            LootLoader.loadAsync(this, app.settings) { loots -> it.loots = loots }
        }
        engine = eng
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // نافذة الحرامي: مربع صغير يتبعه
        actorSize = (eng.thiefRadius * 4.6f).toInt()
        val aLp = overlayParams(actorSize, actorSize).apply {
            x = (eng.brain.x - actorSize / 2f).toInt().coerceIn(0, (screenW - actorSize).coerceAtLeast(0))
            y = (eng.brain.y - actorSize / 2f).toInt().coerceIn(0, (screenH - actorSize).coerceAtLeast(0))
        }
        val av = GameView(this).apply {
            role = GameView.Role.ACTOR
            engine = eng
            originX = aLp.x.toFloat(); originY = aLp.y.toFloat()
            onFrame = { followAll() }
            onToast = { msg -> try { Toast.makeText(this@FloatingService, msg, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {} }
        }

        // نافذة السجن: صغيرة وقابلة للسحب، وعليها ✕
        val pad = 10f * density
        val jw = (eng.jailW + 2 * pad).toInt()
        val jh = (eng.jailH + pad + 22f * density).toInt()
        val jLp = overlayParams(jw, jh).apply {
            x = (eng.jailLeft() - pad).toInt().coerceIn(0, (screenW - jw).coerceAtLeast(0))
            y = (eng.jailTop() - pad).toInt().coerceIn(0, (screenH - jh).coerceAtLeast(0))
        }
        val jailMinY = (sb - pad.toInt()).coerceAtLeast(0)
        val jailMaxY = (screenH - nb - jh).coerceAtLeast(jailMinY)
        val jv = GameView(this).apply {
            role = GameView.Role.JAIL
            engine = eng
            originX = jLp.x.toFloat(); originY = jLp.y.toFloat()
            onCloseRequested = { shutdown() }
            onFrame = { followAll() }
            onPoliceToggle = {
                eng.setPoliceMode(!eng.policeMode)
                try { Toast.makeText(this@FloatingService, if (eng.policeMode) R.string.police_float_on else R.string.police_float_off, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
                applyVisibility()
            }
            onJailDrag = { dx, dy ->
                jLp.x = (jLp.x + dx.toInt()).coerceIn(0, (screenW - jw).coerceAtLeast(0))
                jLp.y = (jLp.y + dy.toInt()).coerceIn(jailMinY, jailMaxY)
                eng.dragJailTo(jLp.x + pad, jLp.y + pad)
                jLp.x = (eng.jailLeft() - pad).toInt(); jLp.y = (eng.jailTop() - pad).toInt() // مطابقة حدود المحرك
                originX = jLp.x.toFloat(); originY = jLp.y.toFloat()
                try { wm?.updateViewLayout(this, jLp) } catch (_: Throwable) {}
            }
            onJailDragEnd = { eng.commitJailPosition() }
        }

        // نافذة الشرطي (تتبعه) ونافذة سيارة الشرطة (لا تستقبل اللمس): مخفيتان حتى يُفعَّل وضع الشرطي
        val rr = eng.thiefRadius
        officerSize = (rr * 3.0f).toInt()
        val oLp = overlayParams(officerSize, officerSize)
        val ov = GameView(this).apply {
            role = GameView.Role.OFFICER
            engine = eng
            visibility = View.GONE
            onFrame = { followAll() }
        }
        carW = (eng.squad.carWidth * 1.2f).toInt(); carH = (eng.squad.carWidth * 0.7f).toInt()
        val cLp = overlayParams(carW, carH).apply { flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE }
        val cv = GameView(this).apply {
            role = GameView.Role.CAR
            engine = eng
            visibility = View.GONE
            onFrame = { followAll() }
        }

        try {
            wm?.addView(jv, jLp)
            wm?.addView(cv, cLp)
            wm?.addView(av, aLp)
            wm?.addView(ov, oLp)
            actor = av; jailView = jv; actorLp = aLp; jailLp = jLp
            officerView = ov; carView = cv; officerLp = oLp; carLp = cLp
            suspended = AppVisibility.started > 0 // لا نظهر النافذتين فوق واجهة اللعبة نفسها
            applyVisibility()
            handler.removeCallbacks(visibilityPoll); handler.post(visibilityPoll)
        } catch (t: Throwable) {
            shutdown() // النظام رفض النافذة: لا نعطّل التطبيق
        }
    }

    /** تُستدعى كل إطار من أي نافذة ظاهرة: كل نافذة صغيرة تتبع شخصيتها. */
    private fun followAll() {
        val eng = engine ?: return
        followThief(eng)
        followPolice(eng)
        val showJail = eng.settings.jailVisible
        if (showJail != jailShown) { jailShown = showJail; applyVisibility() }
    }

    /** نافذة الحرامي تتبعه وتُخفى أثناء وجوده في السجن. */
    private fun followThief(eng: GameEngine) {
        val av = actor ?: return
        val lp = actorLp ?: return
        val b = eng.brain
        val inJail = b.state == ThiefState.JAILED
        if (inJail == actorShown) { actorShown = !inJail; applyVisibility() }
        if (inJail || suspended) return
        val nx = (b.x - actorSize / 2f).toInt().coerceIn(0, (screenW - actorSize).coerceAtLeast(0))
        val ny = (b.y - actorSize / 2f).toInt().coerceIn(0, (screenH - actorSize).coerceAtLeast(0))
        if (nx != lp.x || ny != lp.y) {
            lp.x = nx; lp.y = ny
            av.originX = nx.toFloat(); av.originY = ny.toFloat()
            try { wm?.updateViewLayout(av, lp) } catch (_: Throwable) {}
        }
    }

    /** نافذتا الشرطي والسيارة: تظهران فقط في وضع الشرطي، وتتبعان موضع كل منهما. */
    private fun followPolice(eng: GameEngine) {
        val sq = eng.squad
        val r = eng.thiefRadius
        val offVis = eng.policeActive && sq.officerVisible
        val carVis = eng.policeActive && sq.carVisible
        if (offVis != officerShown) { officerShown = offVis; applyVisibility() }
        if (carVis != carShown) { carShown = carVis; applyVisibility() }
        if (suspended) return
        val ov = officerView; val olp = officerLp
        if (offVis && ov != null && olp != null) {
            val nx = (sq.ox - officerSize / 2f).toInt().coerceIn(0, (screenW - officerSize).coerceAtLeast(0))
            val ny = (sq.oy + r * 0.25f - officerSize / 2f).toInt().coerceIn(0, (screenH - officerSize).coerceAtLeast(0))
            if (nx != olp.x || ny != olp.y) {
                olp.x = nx; olp.y = ny
                ov.originX = nx.toFloat(); ov.originY = ny.toFloat()
                try { wm?.updateViewLayout(ov, olp) } catch (_: Throwable) {}
            }
        }
        val cv = carView; val clp = carLp
        if (carVis && cv != null && clp != null) {
            val groundY = sq.cy + r * 1.19f
            val nx = (sq.cx - carW / 2f).toInt().coerceIn(0, (screenW - carW).coerceAtLeast(0))
            val ny = (groundY - sq.carWidth * 0.23f - carH / 2f).toInt().coerceIn(0, (screenH - carH).coerceAtLeast(0))
            if (nx != clp.x || ny != clp.y) {
                clp.x = nx; clp.y = ny
                cv.originX = nx.toFloat(); cv.originY = ny.toFloat()
                try { wm?.updateViewLayout(cv, clp) } catch (_: Throwable) {}
            }
        }
    }

    private fun applyVisibility() {
        actor?.visibility = if (suspended || !actorShown) View.GONE else View.VISIBLE
        jailView?.visibility = if (suspended || !jailShown) View.GONE else View.VISIBLE
        officerView?.visibility = if (suspended || !officerShown) View.GONE else View.VISIBLE
        carView?.visibility = if (suspended || !carShown) View.GONE else View.VISIBLE
    }

    private fun dimenPx(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun removeViews() {
        handler.removeCallbacks(visibilityPoll)
        actor?.let { try { wm?.removeView(it) } catch (_: Throwable) {} }
        jailView?.let { try { wm?.removeView(it) } catch (_: Throwable) {} }
        officerView?.let { try { wm?.removeView(it) } catch (_: Throwable) {} }
        carView?.let { try { wm?.removeView(it) } catch (_: Throwable) {} }
        actor = null; jailView = null; officerView = null; carView = null
    }

    private fun shutdown() {
        GhostThiefApp.of(this).settings.floatingEnabled = false
        removeViews()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        removeViews()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.ghostthief.game.STOP_FLOATING"
        const val ACTION_HIDE = "com.ghostthief.game.HIDE_FLOATING"
        const val ACTION_SHOW = "com.ghostthief.game.SHOW_FLOATING"
        private const val CHANNEL = "floating"
        private const val NOTIF_ID = 42
    }
}

class AndroidOverlayEnvironment(private val context: Context) : OverlayEnvironment {
    override fun canDrawOverlays() = SysSettings.canDrawOverlays(context)
    override fun startOverlay() {
        androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, FloatingService::class.java))
    }
    override fun stopOverlay() {
        context.startService(Intent(context, FloatingService::class.java).setAction(FloatingService.ACTION_STOP))
    }

    companion object {
        fun permissionIntent(context: Context): Intent =
            Intent(SysSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

        fun sendToService(context: Context, action: String) {
            try { context.startService(Intent(context, FloatingService::class.java).setAction(action)) } catch (_: Throwable) {}
        }
    }
}
