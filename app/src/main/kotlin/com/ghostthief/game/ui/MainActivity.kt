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

class MainActivity : AppCompatActivity() {
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
        swFloating = findViewById(R.id.swFloating)
        swJail = findViewById(R.id.swJail)

        findViewById<MaterialButton>(R.id.btnStart).setOnClickListener { startActivity(Intent(this, GameActivity::class.java)) }
        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener {
            controller.disable()
            refresh()
            Toast.makeText(this, R.string.play_stopped, Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<MaterialButton>(R.id.btnPrivacy).setOnClickListener { TextPageActivity.open(this, TextPageActivity.PRIVACY) }
        findViewById<MaterialButton>(R.id.btnAbout).setOnClickListener { TextPageActivity.open(this, TextPageActivity.ABOUT) }

        swJail.setOnCheckedChangeListener { _, on -> if (!binding) app.settings.jailVisible = on }
        swFloating.setOnCheckedChangeListener { _, on ->
            if (binding) return@setOnCheckedChangeListener
            if (on) askFloating() else controller.disable()
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
}
