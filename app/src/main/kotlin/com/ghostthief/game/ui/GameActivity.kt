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
    private lateinit var padView: GameView
    private lateinit var padView2: GameView
    private lateinit var btnThiefHide: MaterialButton
    private lateinit var btnTwoPlayer: MaterialButton
    private lateinit var btnPolice: MaterialButton
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    /** يحدّث زر الاختباء (العدّ التنازلي) أثناء اللعب الثنائي. */
    private val hideButtonTicker = object : Runnable {
        override fun run() {
            updateHideButton()
            handler.postDelayed(this, 200)
        }
    }
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
        padView = findViewById(R.id.padView)
        padView.role = GameView.Role.PAD
        padView.engine = engine
        padView2 = findViewById(R.id.padView2)
        padView2.role = GameView.Role.PAD2
        padView2.engine = engine
        btnThiefHide = findViewById(R.id.btnThiefHide)
        btnThiefHide.setOnClickListener { engine.thiefHide(); updateHideButton() }

        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { leave() }
        btnPolice = findViewById(R.id.btnPolice)
        btnPolice.setOnClickListener {
            if (engine.twoPlayer) { setTwoPlayer(false) } // إطفاء الشرطي يُنهي اللعب الثنائي أيضاً
            engine.setPoliceMode(!engine.policeMode)
            refreshControls()
            if (engine.policeMode) android.widget.Toast.makeText(this, R.string.police_hint, android.widget.Toast.LENGTH_LONG).show()
        }
        btnTwoPlayer = findViewById(R.id.btnTwoPlayer)
        btnTwoPlayer.setOnClickListener {
            setTwoPlayer(!engine.twoPlayer)
            if (engine.twoPlayer) android.widget.Toast.makeText(this, R.string.two_player_hint, android.widget.Toast.LENGTH_LONG).show()
        }
        findViewById<MaterialButton>(R.id.btnControls).setOnClickListener {
            app.settings.showControls = !app.settings.showControls // إظهار الأزرار اختياري
            refreshControls()
        }
        refreshControls()
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
        refreshControls()
    }

    override fun onPause() {
        engine.policeDirection(0f, 0f) // يتوقف الشرطي عند مغادرة الشاشة
        handler.removeCallbacks(hideButtonTicker)
        engine.thiefDirection(0f, 0f)
        padView.pause(); padView2.pause()
        gameView.pause() // لا نستهلك البطارية خارج اللعبة
        app.sync.syncNow()
        super.onPause()
    }

    override fun onDestroy() {
        if (!kid) app.ads.detachBanner()
        super.onDestroy()
    }

    private fun setTwoPlayer(on: Boolean) {
        engine.setTwoPlayer(on)
        refreshControls()
    }

    /** يظهر/يخفي لوحات الأسهم وزر الاختباء بحسب الأوضاع وخيار «إظهار الأزرار». */
    private fun refreshControls() {
        val show = app.settings.showControls
        padView.visibility = if (engine.policeActive && show) View.VISIBLE else View.GONE
        padView2.visibility = if (engine.twoPlayer && show) View.VISIBLE else View.GONE
        btnThiefHide.visibility = if (engine.twoPlayer && show) View.VISIBLE else View.GONE
        btnPolice.text = getString(if (engine.policeMode) R.string.police_toggle_on else R.string.police_toggle)
        btnTwoPlayer.text = getString(if (engine.twoPlayer) R.string.two_player_on else R.string.two_player)
        if (padView.visibility == View.VISIBLE) padView.start() else padView.pause()
        if (padView2.visibility == View.VISIBLE) padView2.start() else padView2.pause()
        if (!show) { engine.policeDirection(0f, 0f); engine.thiefDirection(0f, 0f) }
        handler.removeCallbacks(hideButtonTicker)
        if (engine.twoPlayer && show) handler.post(hideButtonTicker)
    }

    private fun updateHideButton() {
        val cd = engine.brain.hideCooldownSeconds
        btnThiefHide.isEnabled = engine.brain.hideReady
        btnThiefHide.text = if (cd > 0.05f) getString(R.string.thief_hide_wait, Math.ceil(cd.toDouble()).toInt()) else getString(R.string.thief_hide)
    }

    // ---- لوحة مفاتيح / يد تحكم (اختياري): الأسهم = الشرطي، WASD = الحرامي، المسافة = اختباء ----
    private val held = HashSet<Int>()

    override fun dispatchKeyEvent(ev: android.view.KeyEvent): Boolean {
        val code = ev.keyCode
        val officerKeys = setOf(android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        val thiefKeys = setOf(android.view.KeyEvent.KEYCODE_W, android.view.KeyEvent.KEYCODE_A, android.view.KeyEvent.KEYCODE_S, android.view.KeyEvent.KEYCODE_D)
        if (code !in officerKeys && code !in thiefKeys && code != android.view.KeyEvent.KEYCODE_SPACE) return super.dispatchKeyEvent(ev)
        when (ev.action) {
            android.view.KeyEvent.ACTION_DOWN -> {
                held.add(code)
                if (code == android.view.KeyEvent.KEYCODE_SPACE && ev.repeatCount == 0) { engine.thiefHide(); updateHideButton() }
            }
            android.view.KeyEvent.ACTION_UP -> held.remove(code)
        }
        fun axis(neg: Int, pos: Int) = (if (pos in held) 1f else 0f) - (if (neg in held) 1f else 0f)
        engine.policeDirection(axis(android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT), axis(android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN))
        engine.thiefDirection(axis(android.view.KeyEvent.KEYCODE_A, android.view.KeyEvent.KEYCODE_D), axis(android.view.KeyEvent.KEYCODE_W, android.view.KeyEvent.KEYCODE_S))
        return true
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
