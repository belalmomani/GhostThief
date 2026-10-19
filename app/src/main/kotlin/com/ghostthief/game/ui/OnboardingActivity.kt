package com.ghostthief.game.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ghostthief.game.GhostThiefApp
import com.ghostthief.game.R
import com.google.android.material.button.MaterialButton

/** دليل قصير (3 شاشات) يظهر عند أول تشغيل ويمكن عرضه من الإعدادات. */
class OnboardingActivity : BaseActivity() {
    private data class Page(val image: Int, val title: Int, val text: Int)
    private val pages = listOf(
        Page(R.drawable.thief_caught, R.string.ob_title1, R.string.ob_text1),
        Page(R.drawable.cage_locked, R.string.ob_title2, R.string.ob_text2),
        Page(R.drawable.folder_yellow, R.string.ob_title3, R.string.ob_text3)
    )
    private var index = 0
    private lateinit var img: ImageView
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var dots: TextView
    private lateinit var next: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding((24 * d).toInt(), (32 * d).toInt(), (24 * d).toInt(), (24 * d).toInt())
        }
        img = ImageView(this)
        root.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (220 * d).toInt()))
        title = TextView(this).apply { textSize = 24f; setTextColor(0xFF0D47A1.toInt()); gravity = Gravity.CENTER; setPadding(0, (16 * d).toInt(), 0, (8 * d).toInt()) }
        root.addView(title)
        body = TextView(this).apply { textSize = 18f; setTextColor(0xFF263238.toInt()); gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dots = TextView(this).apply { textSize = 22f; gravity = Gravity.CENTER; setTextColor(0xFF1565C0.toInt()) }
        root.addView(dots)
        next = MaterialButton(this).apply { textSize = 18f; setOnClickListener { advance() } }
        root.addView(next, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (60 * d).toInt()).apply { topMargin = (12 * d).toInt() })
        val skip = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.ob_skip); setOnClickListener { finishGuide() }
        }
        root.addView(skip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * d).toInt()))
        setContentView(root)
        render()
    }

    private fun render() {
        val pg = pages[index]
        img.setImageResource(pg.image)
        title.setText(pg.title)
        body.setText(pg.text)
        dots.text = pages.indices.joinToString("  ") { if (it == index) "●" else "○" }
        next.setText(if (index == pages.lastIndex) R.string.ob_start else R.string.ob_next)
    }

    private fun advance() { if (index < pages.lastIndex) { index++; render() } else finishGuide() }

    private fun finishGuide() {
        GhostThiefApp.of(this).settings.onboardingDone = true
        finish()
    }
}
