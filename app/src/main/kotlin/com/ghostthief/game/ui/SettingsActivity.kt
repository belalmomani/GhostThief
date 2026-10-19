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
    private lateinit var seekSwitch: MaterialSwitch
    private lateinit var seekLabel: TextView
    private lateinit var heistSwitch: MaterialSwitch
    private val folderPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) { app.settings.seekEnabled = false; refreshSeek(); return@registerForActivityResult }
        try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
        app.settings.seekFolderUri = uri.toString()
        app.settings.seekEnabled = true
        refreshSeek()
    }

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
            when {
                it == com.ghostthief.game.data.Settings.DEFAULT_JAIL_MINUTES -> getString(R.string.min_default_fmt, it)
                it == 60 -> getString(R.string.hour_fmt)
                it > 60 -> getString(R.string.hours_fmt, it / 60)
                else -> getString(R.string.min_fmt, it)
            }
        }
        col.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, durLabels)
            val idx = jailOptions.indexOf(s.jailMinutes).let { if (it >= 0) it else jailOptions.indexOf(com.ghostthief.game.data.Settings.DEFAULT_JAIL_MINUTES) }
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
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { if (pos != s.jailCorner || s.jailPosX >= 0) { s.jailPosX = -1; s.jailPosY = -1; s.jailCorner = pos } }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })

        col.addView(TextView(this).apply { text = getString(R.string.s_jail_hint); textSize = 13f; setTextColor(0xFF546E7A.toInt()) })

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

        // وضع البحث في مجلدات الهاتف (اختياري، بمنتقي الملفات الرسمي)
        seekSwitch = toggle(R.string.s_seek, s.seekEnabled) { on ->
            if (on && s.seekFolderUri.isNullOrBlank()) askPickFolder()
            else { s.seekEnabled = on; refreshSeek() }
        }
        seekLabel = TextView(this).apply { textSize = 14f; setTextColor(0xFF546E7A.toInt()) }
        col.addView(seekLabel)
        col.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.s_seek_pick); setOnClickListener { askPickFolder() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * d).toInt()))
        col.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.s_seek_clear); setOnClickListener { clearFolder() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * d).toInt()))
        refreshSeek()

        // السرقة التمثيلية (اختيارية)
        heistSwitch = toggle(R.string.h_heist, s.heistEnabled) { s.heistEnabled = it }
        col.addView(TextView(this).apply { text = getString(R.string.h_heist_desc); textSize = 13f; setTextColor(0xFF546E7A.toInt()) })
        col.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.h_pick_apps); setOnClickListener { askPickApps() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * d).toInt()))
        col.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.h_scan); setOnClickListener { scanFolder() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * d).toInt()))

        label(R.string.s_float_size)
        val sizes = listOf(R.string.float_small, R.string.float_medium, R.string.float_large).map { getString(it) }
        col.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, sizes)
            setSelection(s.floatSize)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { s.floatSize = pos }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })
        col.addView(TextView(this).apply { text = getString(R.string.s_float_hint); textSize = 13f; setTextColor(0xFF546E7A.toInt()) })

        val adsAvail = app.ads.available
        toggle(R.string.s_ads, s.adsEnabled && adsAvail, adsAvail) { s.adsEnabled = it }
        if (!adsAvail) col.addView(TextView(this).apply { text = getString(R.string.s_ads_unavailable); textSize = 13f })

        col.addView(MaterialButton(this).apply {
            text = getString(R.string.s_reset); setBackgroundColor(0xFFC62828.toInt())
            setOnClickListener { confirmReset() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (56 * d).toInt()).apply { topMargin = (24 * d).toInt() })

        updateCloud()
    }

    private fun askPickApps() {
        AlertDialog.Builder(this)
            .setTitle(R.string.h_dialog_title)
            .setMessage(R.string.h_dialog_msg)
            .setPositiveButton(R.string.continue_btn) { _, _ -> showAppList() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAppList() {
        Thread {
            val pm = packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            val apps = try {
                pm.queryIntentActivities(intent, 0)
                    .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                    .filter { it.first != packageName }
                    .distinctBy { it.first }
                    .sortedBy { it.second.lowercase() }
            } catch (t: Throwable) { emptyList() }
            runOnUiThread {
                val chosen = app.settings.heistApps.split(',').filter { it.isNotBlank() }.toSet()
                val labels = apps.map { it.second }.toTypedArray()
                val checked = BooleanArray(apps.size) { apps[it].first in chosen }
                AlertDialog.Builder(this)
                    .setTitle(R.string.h_pick_title)
                    .setMultiChoiceItems(labels, checked) { _, i, c -> checked[i] = c }
                    .setPositiveButton(R.string.save) { _, _ ->
                        val sel = apps.indices.filter { checked[it] }.take(8).map { apps[it].first }
                        app.settings.heistApps = sel.joinToString(",")
                        if (sel.isNotEmpty()) heistSwitch.isChecked = true
                        Toast.makeText(this, getString(R.string.h_saved, sel.size), Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }.start()
    }

    /** زر اختياري: يفحص المجلد المختار ويعرض ما وُجد فيه من ملفات (اسم + حجم + أسلوب السرقة). */
    private fun scanFolder() {
        val uri = app.settings.seekFolderUri
        Thread {
            val res = try { com.ghostthief.game.game.LootLoader.scan(this, uri) } catch (t: Throwable) { com.ghostthief.game.game.LootLoader.ScanResult(emptyList(), 0) }
            val files = res.files.sortedByDescending { it.sizeBytes }
            runOnUiThread {
                if (files.isEmpty()) {
                    val msg = if (uri.isNullOrBlank()) getString(R.string.h_scan_empty) else getString(R.string.h_scan_none_fmt, res.folders)
                    AlertDialog.Builder(this).setMessage(msg).setPositiveButton(android.R.string.ok, null).show()
                } else {
                    val lines = files.take(40).map {
                        val how = when (it.kind) {
                            com.ghostthief.game.logic.LootKind.SMALL -> getString(R.string.h_kind_small)
                            com.ghostthief.game.logic.LootKind.MEDIUM -> getString(R.string.h_kind_medium)
                            com.ghostthief.game.logic.LootKind.LARGE -> getString(R.string.h_kind_large)
                        }
                        "${it.name} — ${com.ghostthief.game.logic.LootRules.formatSize(it.sizeBytes)} — $how"
                    }
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.h_scan_title, files.size))
                        .setItems(lines.toTypedArray(), null)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }.start()
    }

    private fun askPickFolder() {
        AlertDialog.Builder(this)
            .setTitle(R.string.s_seek_dialog_title)
            .setMessage(R.string.s_seek_dialog_msg)
            .setPositiveButton(R.string.continue_btn) { _, _ -> folderPicker.launch(null) }
            .setNegativeButton(R.string.cancel) { _, _ -> app.settings.seekEnabled = false; refreshSeek() }
            .setOnCancelListener { app.settings.seekEnabled = false; refreshSeek() }
            .show()
    }

    private fun clearFolder() {
        val s = app.settings
        s.seekFolderUri?.let { try { contentResolver.releasePersistableUriPermission(android.net.Uri.parse(it), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {} }
        s.seekFolderUri = null; s.seekEnabled = false
        refreshSeek()
    }

    private fun refreshSeek() {
        val s = app.settings
        seekSwitch.setOnCheckedChangeListener(null)
        seekSwitch.isChecked = s.seekEnabled
        seekSwitch.setOnCheckedChangeListener { _, on ->
            if (on && s.seekFolderUri.isNullOrBlank()) askPickFolder() else { s.seekEnabled = on; refreshSeek() }
        }
        val uri = s.seekFolderUri
        seekLabel.text = if (uri.isNullOrBlank()) getString(R.string.s_seek_none)
        else getString(R.string.s_seek_folder, com.ghostthief.game.game.HideSpots.folderLabel(uri))
    }

    private fun updateCloud() {
        val st = when (app.sync.currentStatus()) {
            SyncStatus.DISABLED -> getString(R.string.s_cloud_disabled)
            SyncStatus.NOT_CONFIGURED -> getString(R.string.s_cloud_not_configured)
            SyncStatus.SYNCING -> getString(R.string.s_cloud_syncing)
            SyncStatus.SYNCED -> getString(R.string.s_cloud_synced)
            SyncStatus.OFFLINE_PENDING -> getString(R.string.s_cloud_pending)
        }
        val reason = app.sync.lastError
        cloudStatus.text = getString(R.string.s_cloud_status, st) +
            if (reason != null && app.sync.currentStatus() == SyncStatus.OFFLINE_PENDING) {
                val hint = when {
                    reason.contains("administrators only", true) || reason.contains("ADMIN_ONLY", true) ||
                        reason.contains("OPERATION_NOT_ALLOWED", true) -> "\n" + getString(R.string.s_cloud_hint_anon)
                    reason.contains("PERMISSION_DENIED", true) || reason.contains("permission", true) -> "\n" + getString(R.string.s_cloud_hint_rules)
                    else -> ""
                }
                "\n" + getString(R.string.s_cloud_reason, reason) + hint
            } else ""
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
