package com.ghostthief.game.ui

import androidx.appcompat.app.AppCompatActivity

/** يعرف الوضع العائم إن كانت اللعبة نفسها ظاهرة على الشاشة (فيخفي نافذتيه حتى لا تغطيا واجهة اللعبة). */
object AppVisibility {
    @Volatile var started = 0
}

abstract class BaseActivity : AppCompatActivity() {
    override fun onStart() {
        super.onStart()
        AppVisibility.started = AppVisibility.started + 1
    }

    override fun onStop() {
        AppVisibility.started = (AppVisibility.started - 1).coerceAtLeast(0)
        super.onStop()
    }
}
