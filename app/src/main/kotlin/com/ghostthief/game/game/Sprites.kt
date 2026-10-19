package com.ghostthief.game.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ghostthief.game.R

/** يحمّل أصول PNG الشفافة (من drawable-nodpi بدون تحجيم تلقائي). */
class Sprites(context: Context) {
    private val res = context.applicationContext.resources
    private fun load(id: Int): Bitmap {
        val o = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeResource(res, id, o)
    }

    val run = listOf(R.drawable.thief_run1, R.drawable.thief_run2, R.drawable.thief_run3, R.drawable.thief_run4).map { load(it) }
    val stand = load(R.drawable.thief_stand)
    val ghost = load(R.drawable.thief_ghost)
    val hide = load(R.drawable.thief_hide)
    val caught = load(R.drawable.thief_caught)
    val folders = listOf(R.drawable.folder_peek, R.drawable.folder_yellow, R.drawable.folder_blue).map { load(it) }
    val cageClosing = load(R.drawable.cage_closing)
    val cageLocked = load(R.drawable.cage_locked)
    val jailEmpty = load(R.drawable.jail_empty)
    val jailOpen = load(R.drawable.jail_open)
    val poof = load(R.drawable.fx_poof)
    val stars = load(R.drawable.fx_stars)
    val question = load(R.drawable.fx_question)

    /** ارتفاع إطار الركض المرجعي لتوحيد مقياس كل حالات الشخصية. */
    val refHeight: Float get() = run[0].height.toFloat()
}
