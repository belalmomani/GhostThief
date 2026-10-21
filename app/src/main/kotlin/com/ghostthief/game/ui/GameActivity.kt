package com.ghostthief.game.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.ghostthief.game.GhostThiefApp
import com.ghostthief.game.R
import com.ghostthief.game.floating.AndroidOverlayEnvironment
import com.ghostthief.game.floating.FloatingService
import com.ghostthief.game.game.GameEngine
import com.ghostthief.game.game.GameView
import com.google.android.material.button.MaterialButton

class GameActivity : BaseActivity() {
    private lateinit var app: GhostThiefApp
    private lateinit var engine: GameEngine
    private lateinit var gameView: GameView
    private var kid = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kid = intent.getBooleanExtra(EXTRA_KID, false)
        if (kid) enableLockScreenPlay()
        setContentView(R.layout.activity_game)
        app = GhostThiefApp.of(this)
        engine = GameEngine(app.settings, app.progress)
        gameView = findViewById(R.id.gameView)
        gameView.engine = engine

        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { leave() }
        val btnPolice = findViewById<MaterialButton>(R.id.btnPolice)
        fun policeLabel() { btnPolice.text = getString(if (engine.policeMode) R.string.police_toggle_on else R.string.police_toggle) }
        btnPolice.setOnClickListener {
            engine.setPoliceMode(!engine.policeMode)
            policeLabel()
            if (engine.policeMode) android.widget.Toast.makeText(this, R.string.police_hint, android.widget.Toast.LENGTH_LONG).show()
        }
        policeLabel()
        findViewById<MaterialButton>(R.id.btnToggleJail).setOnClickListener {
            app.settings.jailVisible = !app.settings.jailVisible // إخفاء مؤقت لا يلغي حالة القبض
            gameView.invalidate()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })

        if (!kid) { // لا إعلانات في وضع الطفل (فوق شاشة القفل)
            val container = findViewById<ViewGroup>(R.id.adContainer)
            app.ads.init(this)
            app.ads.attachBanner(container)
        }
    }

    private var pinRequested = false

    override fun onResume() {
        super.onResume()
        if (kid && !pinRequested) { pinRequested = true; pinScreen() }
        engine.applySettings()
        if (app.settings.seekEnabled) {
            com.ghostthief.game.game.HideSpots.loadAsync(this, app.settings.seekFolderUri) { names -> engine.seekNames = names }
        } else engine.seekNames = emptyList()
        com.ghostthief.game.game.LootLoader.loadAsync(this, app.settings) { engine.loots = it }
        gameView.start()
    }

    override fun onPause() {
        gameView.pause() // لا نستهلك البطارية خارج اللعبة
        app.sync.syncNow()
        super.onPause()
    }

    override fun onDestroy() {
        if (!kid) app.ads.detachBanner()
        super.onDestroy()
    }

    /** الخروج هو نقطة الانتقال الطبيعية الوحيدة التي قد يظهر عندها إعلان بيني. */
    private fun leave() {
        if (kid) { // الخروج من وضع الطفل يحتاج رمز الأهل
            PinDialogs.ask(this, app.parentLock) { unpinScreen(); finish() }
            return
        }
        app.ads.maybeShowInterstitial(this, engine.isBusy()) { if (!isFinishing) finish() }
    }

    /** تظهر اللعبة فوق شاشة القفل وتوقظ الشاشة؛ الهاتف يبقى مقفلاً فلا يصل الطفل لبقية التطبيقات. */
    private fun enableLockScreenPlay() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** تثبيت الشاشة: يعطّل أزرار الرئيسية والتطبيقات الأخيرة حتى يُلغى التثبيت (Android يطلب موافقة المستخدم). */
    private fun pinScreen() { try { startLockTask() } catch (_: Throwable) {} }
    private fun unpinScreen() { try { stopLockTask() } catch (_: Throwable) {} }

    companion object {
        private const val EXTRA_KID = "kid"
        fun intent(ctx: Context, kid: Boolean = false): Intent = Intent(ctx, GameActivity::class.java).putExtra(EXTRA_KID, kid)
    }
}
