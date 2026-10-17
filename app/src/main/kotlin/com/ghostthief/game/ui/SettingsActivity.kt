package com.ghostthief.game.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.ghostthief.game.BuildConfig
import com.ghostthief.game.GhostThiefApp
import com.ghostthief.game.R
import com.ghostthief.game.cloud.SyncStatus
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

/** صفحة الإعدادات (مبنية برمجياً لتبقى مرنة وتدعم RTL). */
class SettingsActivity : AppCompatActivity() {
    private lateinit var app: GhostThiefApp
    private lateinit var cloudStatus: TextView
    private val jailOptions = intArrayOf(1, 5, 15, 30, 60, 120, 180)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = GhostThiefApp.of(this)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(col) })
        val s = app.settings

        fun label(res: Int) = TextView(this).apply {
            text = getString(res); textSize = 17f; setTextColor(0xFF263238.toInt()); setPadding(0, (16 * d).toInt(), 0, (4 * d).toInt())
        }.also { col.addView(it) }

        fun seek(res: Int, min: Int, max: Int, value: Int, onChange: (Int) -> Unit) {
            label(res)
            col.addView(SeekBar(this).apply {
                this.max = max - min; progress = value - min
                minimumHeight = (48 * d).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p + min) }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            })
        }

        fun toggle(res: Int, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit): MaterialSwitch =
            MaterialSwitch(this).apply {
                text = getString(res); textSize = 17f; isChecked = value; isEnabled = enabled
                minHeight = (56 * d).toInt()
                setOnCheckedChangeListener { _, on -> onChange(on) }
            }.also { col.addView(it) }

        seek(R.string.s_speed, 50, 200, s.speedPercent) { s.speedPercent = it }
        seek(R.string.s_volume, 0, 100, s.volumePercent) { s.volumePercent = it }

        label(R.string.s_jail_duration)
        val durLabels = jailOptions.map {
            when { it == 60 -> getString(R.string.hour_fmt); it > 60 -> getString(R.string.hours_fmt, it / 60); else -> getString(R.string.min_fmt, it) }
        }
        col.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, durLabels)
            val idx = jailOptions.indexOf(s.jailMinutes).let { if (it >= 0) it else jailOptions.indexOf(60) }
            setSelection(idx)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { s.jailMinutes = jailOptions[pos] }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })

        label(R.string.s_jail_corner)
        val corners = listOf(R.string.corner_tr, R.string.corner_tl, R.string.corner_br, R.string.corner_bl).map { getString(it) }
        col.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, corners)
            setSelection(s.jailCorner)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { s.jailCorner = pos }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })

        toggle(R.string.s_sound, s.soundEnabled) { s.soundEnabled = it }
        toggle(R.string.s_effects, s.effectsEnabled) { s.effectsEnabled = it }
        toggle(R.string.s_timer, s.showJailTimer) { s.showJailTimer = it }

        val cloudSwitch = toggle(R.string.s_cloud, s.cloudSyncEnabled) { on ->
            s.cloudSyncEnabled = on
            if (on) app.sync.syncNow { runOnUiThread { updateCloud() } } else updateCloud()
        }
        cloudStatus = TextView(this).apply { textSize = 14f; setTextColor(0xFF546E7A.toInt()) }
        col.addView(cloudStatus)
        col.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.s_sync_now)
            setOnClickListener { app.sync.syncNow { runOnUiThread { updateCloud() } } }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * d).toInt()))
        cloudSwitch.isEnabled = true

        val adsAvail = app.ads.available
        toggle(R.string.s_ads, s.adsEnabled && adsAvail, adsAvail) { s.adsEnabled = it }
        if (!adsAvail) col.addView(TextView(this).apply { text = getString(R.string.s_ads_unavailable); textSize = 13f })

        col.addView(MaterialButton(this).apply {
            text = getString(R.string.s_reset); setBackgroundColor(0xFFC62828.toInt())
            setOnClickListener { confirmReset() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (56 * d).toInt()).apply { topMargin = (24 * d).toInt() })

        updateCloud()
    }

    private fun updateCloud() {
        val st = when (app.sync.currentStatus()) {
            SyncStatus.DISABLED -> getString(R.string.s_cloud_disabled)
            SyncStatus.NOT_CONFIGURED -> getString(R.string.s_cloud_not_configured)
            SyncStatus.SYNCING -> getString(R.string.s_cloud_syncing)
            SyncStatus.SYNCED -> getString(R.string.s_cloud_synced)
            SyncStatus.OFFLINE_PENDING -> getString(R.string.s_cloud_pending)
        }
        cloudStatus.text = getString(R.string.s_cloud_status, st)
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.s_reset)
            .setMessage(R.string.s_reset_confirm)
            .setPositiveButton(R.string.s_reset_yes) { _, _ ->
                app.progress.resetProgress()
                app.sync.syncNow()
                Toast.makeText(this, R.string.s_reset_done, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
