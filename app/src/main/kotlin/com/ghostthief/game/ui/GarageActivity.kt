package com.ghostthief.game.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ghostthief.game.GhostThiefApp
import com.ghostthief.game.R
import com.ghostthief.game.game.VehicleSpec
import com.ghostthief.game.game.Vehicles
import com.ghostthief.game.logic.Clock
import com.ghostthief.game.logic.Progress
import com.ghostthief.game.logic.RewardCheck
import com.ghostthief.game.logic.RewardPolicy
import com.ghostthief.game.logic.VehicleRules
import com.google.android.material.button.MaterialButton

/**
 * المرآب: مركبات الشرطة. تُفتح بالنقاط (عتبة) أو بمشاهدة إعلان مكافأة اختياري (حد يومي، ورمز الأهل إن وُجد).
 * في نسخة noads لا توجد إعلانات: تُفتح بالنقاط فقط.
 */
class GarageActivity : BaseActivity() {
    private lateinit var app: GhostThiefApp
    private lateinit var header: TextView
    private lateinit var list: LinearLayout
    private val clock = Clock.System

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = GhostThiefApp.of(this)
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.garage_title); textSize = 24f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF0D47A1.toInt())
        })
        header = TextView(this).apply { textSize = 15f; setTextColor(0xFF37474F.toInt()); setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt()) }
        root.addView(header)
        root.addView(TextView(this).apply {
            text = getString(R.string.garage_ad_note); textSize = 12.5f; setTextColor(0xFF607D8B.toInt()); setPadding(0, 0, 0, (8 * d).toInt())
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(0xFFE3F2FD.toInt()) })

        app.ads.init(this) // آمن: لا يفعل شيئاً في noads
        render()
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val d = resources.displayMetrics.density
        val p = app.progress.load()
        val now = clock.nowMillis()
        val s = app.settings
        val remaining = RewardPolicy.remainingToday(now, s.adRewardDay, s.adRewardCount)
        val adsOn = app.ads.rewardedSupported && s.adsEnabled
        header.text = if (adsOn) getString(R.string.garage_header_ads, p.points, remaining) else getString(R.string.garage_header, p.points)
        list.removeAllViews()
        val equipped = Vehicles.effective(s.vehicleId, p).id
        for (spec in Vehicles.all) list.addView(card(spec, p, equipped, adsOn, d))
    }

    private fun card(spec: VehicleSpec, p: Progress, equipped: String, adsOn: Boolean, d: Float): View {
        val unlocked = VehicleRules.isUnlocked(spec.id, p)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
        }
        val img = ImageView(this).apply {
            setImageResource(spec.drawable); scaleType = ImageView.ScaleType.FIT_CENTER
            if (!unlocked) alpha = 0.45f
        }
        row.addView(img, LinearLayout.LayoutParams((120 * d).toInt(), (72 * d).toInt()))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((10 * d).toInt(), 0, (10 * d).toInt(), 0) }
        col.addView(TextView(this).apply { text = getString(spec.nameRes); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF263238.toInt()) })
        col.addView(TextView(this).apply {
            textSize = 12.5f; setTextColor(0xFF607D8B.toInt())
            text = when {
                spec.id == equipped -> getString(R.string.garage_equipped)
                unlocked -> getString(R.string.garage_unlocked)
                adsOn -> getString(R.string.garage_locked_ads, VehicleRules.pointsRequired(spec.id))
                else -> getString(R.string.garage_locked_points, VehicleRules.pointsRequired(spec.id))
            }
        })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val btn = MaterialButton(this).apply { textSize = 13f }
        when {
            spec.id == equipped -> { btn.text = getString(R.string.garage_equipped_btn); btn.isEnabled = false }
            unlocked -> { btn.text = getString(R.string.garage_equip); btn.setOnClickListener { equip(spec) } }
            adsOn -> { btn.text = getString(R.string.garage_watch_ad); btn.setOnClickListener { watchAd(spec) } }
            else -> { btn.text = getString(R.string.garage_points_only); btn.isEnabled = false }
        }
        row.addView(btn, LinearLayout.LayoutParams((104 * d).toInt(), (52 * d).toInt()))
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, (8 * d).toInt()) }
        wrap.addView(row)
        return wrap
    }

    private fun equip(spec: VehicleSpec) {
        app.settings.vehicleId = spec.id
        render()
    }

    /** مشاهدة إعلان مكافأة (اختيارية): حد يومي وانتظار بين إعلانين، ورمز الأهل إن كان مفعّلاً. */
    private fun watchAd(spec: VehicleSpec) {
        val s = app.settings
        val now = clock.nowMillis()
        when (RewardPolicy.check(now, s.adRewardDay, s.adRewardCount, s.adRewardLast)) {
            RewardCheck.DAILY_LIMIT -> { Toast.makeText(this, R.string.garage_limit, Toast.LENGTH_LONG).show(); return }
            RewardCheck.COOLDOWN -> { Toast.makeText(this, R.string.garage_cooldown, Toast.LENGTH_LONG).show(); return }
            RewardCheck.OK -> {}
        }
        PinDialogs.ask(this, app.parentLock) {
            app.ads.showRewarded(this,
                onReward = {
                    val t = clock.nowMillis()
                    val day = RewardPolicy.dayOf(t)
                    s.adRewardCount = RewardPolicy.countToday(t, s.adRewardDay, s.adRewardCount) + 1
                    s.adRewardDay = day
                    s.adRewardLast = t
                    app.progress.unlock(VehicleRules.unlockKey(spec.id)) // يُحفظ ويُزامَن كإنجاز
                    s.vehicleId = spec.id
                    app.sync.syncNow()
                    Toast.makeText(this, getString(R.string.garage_unlocked_toast, getString(spec.nameRes)), Toast.LENGTH_LONG).show()
                    render()
                },
                onFail = { Toast.makeText(this, R.string.garage_ad_failed, Toast.LENGTH_LONG).show() }
            )
        }
    }
}
