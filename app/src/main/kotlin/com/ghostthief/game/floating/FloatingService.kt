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
import android.os.IBinder
import android.provider.Settings as SysSettings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.ghostthief.game.GhostThiefApp
import com.ghostthief.game.R
import com.ghostthief.game.game.GameEngine
import com.ghostthief.game.game.GameView
import com.ghostthief.game.ui.MainActivity

/** نافذة عائمة صغيرة وشفافة تعرض الحرامي والسجن. لا تعمل إلا بموافقة المستخدم على صلاحية النظام. */
class FloatingService : Service() {
    private var view: GameView? = null
    private var wm: WindowManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_HIDE -> { view?.visibility = View.GONE; if (view == null) stopSelf(); return START_NOT_STICKY }
            ACTION_SHOW -> { view?.visibility = View.VISIBLE; if (view == null) stopSelf(); return START_NOT_STICKY }
        }
        goForeground()
        val app = GhostThiefApp.of(this)
        if (!app.settings.floatingEnabled || !SysSettings.canDrawOverlays(this)) { shutdown(); return START_NOT_STICKY }
        if (view == null) addOverlay(app)
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

    private fun addOverlay(app: GhostThiefApp) {
        val d = resources.displayMetrics
        val w = minOf((320 * d.density).toInt(), (d.widthPixels * 0.92f).toInt())
        val h = (240 * d.density).toInt()
        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = (8 * d.density).toInt(); y = (96 * d.density).toInt()
        }
        val v = GameView(this).apply {
            arenaMode = true
            engine = GameEngine(app.settings, app.progress)
            onCloseRequested = { shutdown() }
        }
        try {
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm?.addView(v, lp)
            view = v
        } catch (t: Throwable) {
            shutdown() // مثلاً: النظام رفض النافذة. لا نعطّل التطبيق.
        }
    }

    private fun shutdown() {
        GhostThiefApp.of(this).settings.floatingEnabled = false
        view?.let { try { wm?.removeView(it) } catch (_: Throwable) {} }
        view = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        view?.let { try { wm?.removeView(it) } catch (_: Throwable) {} }
        view = null
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
