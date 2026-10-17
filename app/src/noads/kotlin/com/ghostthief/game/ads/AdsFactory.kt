package com.ghostthief.game.ads

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import com.ghostthief.game.data.Settings

/** نكهة noads: لا يوجد أي SDK إعلاني في التطبيق إطلاقاً. */
object AdsFactory {
    fun create(context: Context, settings: Settings): AdsController = NoAds
}

private object NoAds : AdsController {
    override val available = false
    override fun init(activity: Activity) {}
    override fun attachBanner(container: ViewGroup) { container.visibility = android.view.View.GONE }
    override fun detachBanner() {}
    override fun maybeShowInterstitial(activity: Activity, gameBusy: Boolean, onDone: () -> Unit) = onDone()
}
