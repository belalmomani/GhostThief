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
import androidx.appcompat.app.AlertDialog
import com.ghostthief.game.GhostThiefApp
import com.ghostthief.game.R
import com.ghostthief.game.game.VehicleSpec
import com.ghostthief.game.game.Vehicles
import com.ghostthief.game.logic.BuyCheck
import com.ghostthief.game.logic.Clock
import com.ghostthief.game.logic.PlayPoints
import com.ghostthief.game.logic.Progress
import com.ghostthief.game.logic.RewardCheck
import com.ghostthief.game.logic.RewardPolicy
import com.ghostthief.game.logic.VehicleRules
import com.google.android.material.button.MaterialButton

/**
 * المرآب: تُشترى المركبات **بالترتيب** وبأسعار تصاعدية (لا تُشترى مركبة قبل السابقة).
 * النقاط تأتي من اللعب (نقطة كل 30 ثانية، حد يومي) ومن القبض والبحث والغنائم، ومن مشاهدة إعلان مكافأة اختياري (+50).
 * في نسخة noads لا توجد إعلانات.
 */
class GarageActivity : BaseActivity() {
    private lateinit var app: GhostThiefApp
    private lateinit var header: TextView
    private lateinit var adSlot: LinearLayout
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
        header = TextView(this).apply { textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF37474F.toInt()); setPadding(0, (4 * d).toInt(), 0, (2 * d).toInt()) }
        root.addView(header)
        root.addView(TextView(this).apply {
            text = getString(R.string.garage_how); textSize = 12.5f; setTextColor(0xFF607D8B.toInt()); setPadding(0, 0, 0, (8 * d).toInt())
        })
        adSlot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(adSlot)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(0xFFE3F2FD.toInt()) })

        app.ads.init(this) // آمن: لا يفعل شيئاً في noads
        render()
    }

    override fun onResume() { super.onResume(); render() }

    private fun playToday(): Int {
        val s = app.settings
        return if (s.playPointsDay == RewardPolicy.dayOf(clock.nowMillis())) s.playPointsToday else 0
    }

    private fun render() {
        val d = resources.displayMetrics.density
        val p = app.progress.load()
        val s = app.settings
        val now = clock.nowMillis()
        val adsOn = app.ads.rewardedSupported && s.adsEnabled
        header.text = getString(R.string.garage_header, p.balance, playToday(), PlayPoints.MAX_PER_DAY)

        adSlot.removeAllViews()
        if (adsOn) {
            val left = RewardPolicy.remainingToday(now, s.adRewardDay, s.adRewardCount)
            adSlot.addView(MaterialButton(this).apply {
                text = getString(R.string.garage_watch_ad_points, RewardPolicy.AD_POINTS, left)
                textSize = 15f
                setOnClickListener { watchAd() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (56 * d).toInt()).apply { bottomMargin = (10 * d).toInt() })
        }

        list.removeAllViews()
        val equipped = Vehicles.effective(s.vehicleId, p).id
        for (spec in Vehicles.all) list.addView(card(spec, p, equipped, d))
    }

    private fun card(spec: VehicleSpec, p: Progress, equipped: String, d: Float): View {
        val owned = VehicleRules.isUnlocked(spec.id, p)
        val check = VehicleRules.canBuy(spec.id, p)
        val price = VehicleRules.price(spec.id)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
        }
        row.addView(ImageView(this).apply {
            setImageResource(spec.drawable); scaleType = ImageView.ScaleType.FIT_CENTER
            if (!owned) alpha = if (check == BuyCheck.NEED_PREVIOUS) 0.3f else 0.55f
        }, LinearLayout.LayoutParams((120 * d).toInt(), (72 * d).toInt()))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((10 * d).toInt(), 0, (10 * d).toInt(), 0) }
        col.addView(TextView(this).apply { text = getString(spec.nameRes); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF263238.toInt()) })
        col.addView(TextView(this).apply {
            textSize = 12.5f; setTextColor(0xFF607D8B.toInt())
            text = when {
                spec.id == equipped -> getString(R.string.garage_equipped)
                owned -> getString(R.string.garage_owned)
                check == BuyCheck.NEED_PREVIOUS -> getString(R.string.garage_need_previous, getString(Vehicles.byId(VehicleRules.previousId(spec.id) ?: "default").nameRes))
                check == BuyCheck.NOT_ENOUGH -> getString(R.string.garage_missing, price - p.balance)
                else -> getString(R.string.garage_price, price)
            }
        })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val btn = MaterialButton(this).apply { textSize = 13f }
        when {
            spec.id == equipped -> { btn.text = getString(R.string.garage_equipped_btn); btn.isEnabled = false }
            owned -> { btn.text = getString(R.string.garage_equip); btn.setOnClickListener { equip(spec) } }
            check == BuyCheck.OK -> { btn.text = getString(R.string.garage_buy, price); btn.setOnClickListener { confirmBuy(spec, price) } }
            check == BuyCheck.NOT_ENOUGH -> { btn.text = getString(R.string.garage_buy, price); btn.isEnabled = false }
            else -> { btn.text = getString(R.string.garage_locked); btn.isEnabled = false }
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

    private fun confirmBuy(spec: VehicleSpec, price: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.garage_buy_title)
            .setMessage(getString(R.string.garage_buy_msg, getString(spec.nameRes), price))
            .setPositiveButton(R.string.garage_buy_yes) { _, _ ->
                if (app.progress.buyVehicle(spec.id) == BuyCheck.OK) {
                    app.settings.vehicleId = spec.id
                    app.sync.syncNow()
                    Toast.makeText(this, getString(R.string.garage_bought_toast, getString(spec.nameRes)), Toast.LENGTH_LONG).show()
                } else Toast.makeText(this, R.string.garage_buy_failed, Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** مشاهدة إعلان مكافأة (اختيارية) مقابل نقاط: حد يومي وانتظار بين إعلانين، ورمز الأهل إن كان مفعّلاً. */
    private fun watchAd() {
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
                    s.adRewardCount = RewardPolicy.countToday(t, s.adRewardDay, s.adRewardCount) + 1
                    s.adRewardDay = RewardPolicy.dayOf(t)
                    s.adRewardLast = t
                    app.progress.awardPoints(RewardPolicy.AD_POINTS)
                    app.sync.syncNow()
                    Toast.makeText(this, getString(R.string.garage_ad_reward_toast, RewardPolicy.AD_POINTS), Toast.LENGTH_LONG).show()
                    render()
                },
                onFail = { Toast.makeText(this, R.string.garage_ad_failed, Toast.LENGTH_LONG).show() }
            )
        }
    }
}
