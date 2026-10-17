package com.ghostthief.game.data

/** إعدادات اللعبة (محلية بالكامل). القيم الافتراضية آمنة. */
class Settings(private val kv: KeyValueStore) {
    /** سرعة الحركة بالمئة: 50..200. */
    var speedPercent: Int
        get() = kv.getInt("speed_percent", 100).coerceIn(50, 200)
        set(v) = kv.put("speed_percent", v.coerceIn(50, 200))

    /** مدة السجن بالدقائق. الافتراضي ساعة. */
    var jailMinutes: Int
        get() = kv.getInt("jail_minutes", DEFAULT_JAIL_MINUTES).coerceIn(1, 24 * 60)
        set(v) = kv.put("jail_minutes", v.coerceIn(1, 24 * 60))

    var volumePercent: Int
        get() = kv.getInt("volume_percent", 70).coerceIn(0, 100)
        set(v) = kv.put("volume_percent", v.coerceIn(0, 100))

    var soundEnabled: Boolean
        get() = kv.getBoolean("sound_enabled", true)
        set(v) = kv.put("sound_enabled", v)

    var effectsEnabled: Boolean
        get() = kv.getBoolean("effects_enabled", true)
        set(v) = kv.put("effects_enabled", v)

    var jailVisible: Boolean
        get() = kv.getBoolean("jail_visible", true)
        set(v) = kv.put("jail_visible", v)

    var showJailTimer: Boolean
        get() = kv.getBoolean("show_jail_timer", true)
        set(v) = kv.put("show_jail_timer", v)

    /** 0 أعلى اليمين، 1 أعلى اليسار، 2 أسفل اليمين، 3 أسفل اليسار. */
    var jailCorner: Int
        get() = kv.getInt("jail_corner", 0).coerceIn(0, 3)
        set(v) = kv.put("jail_corner", v.coerceIn(0, 3))

    var floatingEnabled: Boolean
        get() = kv.getBoolean("floating_enabled", false)
        set(v) = kv.put("floating_enabled", v)

    var cloudSyncEnabled: Boolean
        get() = kv.getBoolean("cloud_sync_enabled", false)
        set(v) = kv.put("cloud_sync_enabled", v)

    /** الإعلانات مطفأة افتراضياً (لعبة أطفال). */
    var adsEnabled: Boolean
        get() = kv.getBoolean("ads_enabled", false)
        set(v) = kv.put("ads_enabled", v)

    fun resetSettings() {
        for (k in listOf(
            "speed_percent", "jail_minutes", "volume_percent", "sound_enabled", "effects_enabled",
            "jail_visible", "show_jail_timer", "jail_corner", "cloud_sync_enabled", "ads_enabled"
        )) kv.put(k, null as String?)
    }

    companion object { const val DEFAULT_JAIL_MINUTES = 60 }
}
