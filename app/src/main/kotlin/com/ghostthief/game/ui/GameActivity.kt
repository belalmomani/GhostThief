package com.ghostthief.game.ui

import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)
        app = GhostThiefApp.of(this)
        engine = GameEngine(app.settings, app.progress)
        gameView = findViewById(R.id.gameView)
        gameView.engine = engine

        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { leave() }
        findViewById<MaterialButton>(R.id.btnToggleJail).setOnClickListener {
            app.settings.jailVisible = !app.settings.jailVisible // إخفاء مؤقت لا يلغي حالة القبض
            gameView.invalidate()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })

        val container = findViewById<ViewGroup>(R.id.adContainer)
        app.ads.init(this)
        app.ads.attachBanner(container) // لا يعمل إلا في نكهة ads وبعد تفعيل المستخدم/الأب
    }

    override fun onResume() {
        super.onResume()
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
        app.ads.detachBanner()
        super.onDestroy()
    }

    /** الخروج هو نقطة الانتقال الطبيعية الوحيدة التي قد يظهر عندها إعلان بيني. */
    private fun leave() {
        app.ads.maybeShowInterstitial(this, engine.isBusy()) { if (!isFinishing) finish() }
    }
}
