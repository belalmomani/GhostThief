package com.ghostthief.game.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.ghostthief.game.R
import com.ghostthief.game.data.Settings

class SoundFx(context: Context, private val settings: Settings) {
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()
    private val ids = mapOf(
        SoundEffect.POP to pool.load(context, R.raw.sfx_pop, 1),
        SoundEffect.CATCH to pool.load(context, R.raw.sfx_catch, 1),
        SoundEffect.FAIL to pool.load(context, R.raw.sfx_fail, 1),
        SoundEffect.ESCAPE to pool.load(context, R.raw.sfx_escape, 1),
        SoundEffect.OUCH to pool.load(context, R.raw.sfx_ouch, 1),
        SoundEffect.CLANG to pool.load(context, R.raw.sfx_clang, 1),
        SoundEffect.DING to pool.load(context, R.raw.sfx_ding, 1)
    )

    fun play(e: SoundEffect) {
        if (!settings.soundEnabled) return
        val v = settings.volumePercent / 100f
        if (v <= 0f) return
        ids[e]?.let { pool.play(it, v, v, 1, 0, 1f) }
    }

    fun release() = pool.release()
}
