package com.ghostthief.game.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.ghostthief.game.R
import com.ghostthief.game.data.Settings

/**
 * المؤثرات القصيرة + أصوات متكررة (صفارة ومحرك) بـ SoundPool.
 * الحلقات: تبدأ مرة واحدة عند تغيّر الحالة، وتُضبط طبقتها بـ setRate، وتُوقف وتُحرَّر عند الإغلاق.
 */
class SoundFx(context: Context, private val settings: Settings) {
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()

    private val loaded = HashSet<Int>()
    private val streams = HashMap<LoopSound, Int>()
    private val wantedRate = HashMap<LoopSound, Float>()

    private val ids = mapOf(
        SoundEffect.POP to pool.load(context, R.raw.sfx_pop, 1),
        SoundEffect.CATCH to pool.load(context, R.raw.sfx_catch, 1),
        SoundEffect.FAIL to pool.load(context, R.raw.sfx_fail, 1),
        SoundEffect.ESCAPE to pool.load(context, R.raw.sfx_escape, 1),
        SoundEffect.OUCH to pool.load(context, R.raw.sfx_ouch, 1),
        SoundEffect.CLANG to pool.load(context, R.raw.sfx_clang, 1),
        SoundEffect.DING to pool.load(context, R.raw.sfx_ding, 1)
    )
    private val loopIds = mapOf(
        LoopSound.SIREN to pool.load(context, R.raw.sfx_siren_loop, 1),
        LoopSound.ENGINE to pool.load(context, R.raw.sfx_engine_loop, 1)
    )

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                loaded.add(sampleId)
                // إن طُلبت حلقة قبل اكتمال التحميل نبدأها الآن
                for ((sound, id) in loopIds) if (id == sampleId) wantedRate[sound]?.let { startLoop(sound, it) }
            }
        }
    }

    private fun master() = settings.volumePercent / 100f

    fun play(e: SoundEffect) {
        if (!settings.soundEnabled) return
        val v = master()
        if (v <= 0f) return
        ids[e]?.let { pool.play(it, v, v, 1, 0, 1f) }
    }

    private fun loopVolume(s: LoopSound) = master() * (if (s == LoopSound.SIREN) 0.55f else 0.45f)

    private fun startLoop(s: LoopSound, rate: Float) {
        if (!settings.soundEnabled || streams.containsKey(s)) return
        val id = loopIds[s] ?: return
        if (id !in loaded) return
        val v = loopVolume(s)
        if (v <= 0f) return
        val sid = pool.play(id, v, v, 1, -1, rate.coerceIn(0.5f, 2f))
        if (sid != 0) streams[s] = sid
    }

    /** يشغّل/يوقف الحلقة؛ إن كانت تعمل يغيّر طبقتها فقط (لا إعادة تشغيل). */
    fun loop(s: LoopSound, on: Boolean, rate: Float = 1f) {
        if (!on) {
            wantedRate.remove(s)
            streams.remove(s)?.let { pool.stop(it) }
            return
        }
        wantedRate[s] = rate
        val sid = streams[s]
        if (sid != null) pool.setRate(sid, rate.coerceIn(0.5f, 2f)) else startLoop(s, rate)
    }

    fun stopLoops() {
        for (sid in streams.values) pool.stop(sid)
        streams.clear(); wantedRate.clear()
    }

    fun release() {
        stopLoops()
        pool.release()
    }
}
