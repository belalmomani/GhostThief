package com.ghostthief.game.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ghostthief.game.GhostThiefApp
import com.ghostthief.game.R
import com.ghostthief.game.floating.AndroidOverlayEnvironment
import com.ghostthief.game.floating.EnableResult
import com.ghostthief.game.floating.FloatingModeController
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import android.widget.TextView

class MainActivity : BaseActivity() {
    private lateinit var app: GhostThiefApp
    private lateinit var controller: FloatingModeController
    private lateinit var swFloating: MaterialSwitch
    private lateinit var swJail: MaterialSwitch
    private var awaitingOverlayPermission = false
    private var binding = false

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* اختياري: الإشعار يظهر إن وُافق */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        app = GhostThiefApp.of(this)
        controller = FloatingModeController(AndroidOverlayEnvironment(this), app.settings)
        findViewById<TextView>(R.id.tvVersion).text = "v${com.ghostthief.game.BuildConfig.VERSION_NAME} (${com.ghostthief.game.BuildConfig.VERSION_CODE})"
        swFloating = findViewById(R.id.swFloating)
        swJail = findViewById(R.id.swJail)

        findViewById<MaterialButton>(R.id.btnStart).setOnClickListener { startActivity(GameActivity.intent(this)) }
        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener {
            controller.disable()
            refresh()
            Toast.makeText(this, R.string.play_stopped, Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.btnSettings).setOnClickListener {
            PinDialogs.ask(this, app.parentLock) { startActivity(Intent(this, SettingsActivity::class.java)) }
        }
        findViewById<MaterialButton>(R.id.btnKid).setOnClickListener { startKidMode() }
        findViewById<MaterialButton>(R.id.btnAchievements).setOnClickListener { showAchievements() }
        if (!app.settings.onboardingDone) startActivity(Intent(this, OnboardingActivity::class.java))
        findViewById<MaterialButton>(R.id.btnPrivacy).setOnClickListener { TextPageActivity.open(this, TextPageActivity.PRIVACY) }
        findViewById<MaterialButton>(R.id.btnAbout).setOnClickListener { TextPageActivity.open(this, TextPageActivity.ABOUT) }

        swJail.setOnCheckedChangeListener { _, on -> if (!binding) app.settings.jailVisible = on }
        swFloating.setOnCheckedChangeListener { _, on ->
            if (binding) return@setOnCheckedChangeListener
            // تشغيل/إيقاف الوضع العائم محميان بقفل الأهل (إن كان مفعّلاً)
            PinDialogs.ask(this, app.parentLock, onCancel = { refresh() }) {
                if (on) askFloating() else { controller.disable(); refresh() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        controller.onResumeCheck()
        if (awaitingOverlayPermission) {
            awaitingOverlayPermission = false
            tryEnable()
        }
        refresh()
        app.sync.syncNow()
    }

    private fun refresh() {
        binding = true
        val p = app.progress.load()
        findViewById<TextView>(R.id.tvStats).text = getString(R.string.stats, p.captures, p.points, p.achievements.size)
        swFloating.isChecked = app.settings.floatingEnabled
        swJail.isChecked = app.settings.jailVisible
        binding = false
    }

    private fun askFloating() {
        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_dialog_title)
            .setMessage(R.string.overlay_dialog_msg)
            .setPositiveButton(R.string.continue_btn) { _, _ -> requestNotificationIfNeeded(); tryEnable() }
            .setNegativeButton(R.string.cancel) { _, _ -> refresh() }
            .setOnCancelListener { refresh() }
            .show()
    }

    private fun tryEnable() {
        when (controller.enable()) {
            EnableResult.ENABLED -> {}
            EnableResult.NEEDS_PERMISSION -> {
                awaitingOverlayPermission = true
                Toast.makeText(this, R.string.overlay_needs_permission, Toast.LENGTH_LONG).show()
                try { startActivity(AndroidOverlayEnvironment.permissionIntent(this)) } catch (_: Throwable) { awaitingOverlayPermission = false }
            }
            EnableResult.FAILED -> Toast.makeText(this, R.string.overlay_failed, Toast.LENGTH_LONG).show()
        }
        refresh()
    }

    private fun requestNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** وضع الطفل: تظهر اللعبة فوق شاشة القفل ولا يخرج منها إلا بالرمز. */
    private fun startKidMode() {
        if (!app.parentLock.isSet()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.kid_need_pin_title)
                .setMessage(R.string.kid_need_pin_msg)
                .setPositiveButton(R.string.continue_btn) { _, _ -> PinDialogs.setNew(this, app.parentLock) { showKidInfo() } }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else showKidInfo()
    }

    private fun showKidInfo() {
        AlertDialog.Builder(this)
            .setTitle(R.string.kid_mode)
            .setMessage(R.string.kid_info_msg)
            .setPositiveButton(R.string.kid_start) { _, _ -> startActivity(GameActivity.intent(this, kid = true)) }
            .setNeutralButton(R.string.kid_open_settings) { _, _ ->
                try { startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)) } catch (_: Throwable) {}
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAchievements() {
        val have = app.progress.load().achievements
        val rows = listOf(
            "first_catch" to R.string.ach_first_catch, "five_catches" to R.string.ach_five_catches,
            "twenty_five_catches" to R.string.ach_25, "hundred_catches" to R.string.ach_100,
            "circle_master" to R.string.ach_circle, "first_rescue" to R.string.ach_first_rescue,
            "large_rescue" to R.string.ach_large_rescue, "sharp_eyes" to R.string.ach_sharp_eyes
        ).map { (id, res) -> (if (id in have) "✓ " else "○ ") + getString(res) }
        AlertDialog.Builder(this)
            .setTitle(R.string.achievements)
            .setItems(rows.toTypedArray(), null)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
