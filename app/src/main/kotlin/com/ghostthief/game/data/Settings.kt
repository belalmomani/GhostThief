package com.ghostthief.game.data

import com.ghostthief.game.BuildConfig

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
        get() = kv.getBoolean("cloud_sync_enabled", BuildConfig.FIREBASE_CONFIGURED)
        set(v) = kv.put("cloud_sync_enabled", v)

    /** مفعّلة افتراضياً في نكهة ads فقط (نكهة noads لا تحتوي إعلانات). */
    var adsEnabled: Boolean
        get() = kv.getBoolean("ads_enabled", BuildConfig.ADS_ENABLED)
        set(v) = kv.put("ads_enabled", v)

    /** موضع السجن المخصص (من 0 إلى 1000 نسبةً لمساحة الساحة). -1 = استخدم الزاوية المختارة. */
    var jailPosX: Int
        get() = kv.getInt("jail_px", -1)
        set(v) = kv.put("jail_px", v)
    var jailPosY: Int
        get() = kv.getInt("jail_py", -1)
        set(v) = kv.put("jail_py", v)

    /** وضع البحث: يختبئ الحرامي داخل أسماء عناصر مجلد اختاره المستخدم بمنتقي الملفات. */
    var seekEnabled: Boolean
        get() = kv.getBoolean("seek_enabled", false)
        set(v) = kv.put("seek_enabled", v)
    var seekFolderUri: String?
        get() = kv.getString("seek_folder_uri", null)
        set(v) = kv.put("seek_folder_uri", v)

    /** السرقة التمثيلية: يسرق الحرامي «غنائم» (أسماء/أحجام ملفات أو تطبيقات اختارها المستخدم). تمثيل فقط، لا يتغير شيء فعلياً. */
    var heistEnabled: Boolean
        get() = kv.getBoolean("heist_enabled", false)
        set(v) = kv.put("heist_enabled", v)
    /** حزم التطبيقات التي اختارها المستخدم بنفسه (مفصولة بفاصلة). */
    var heistApps: String
        get() = kv.getString("heist_apps", "") ?: ""
        set(v) = kv.put("heist_apps", v)

    /** حجم النافذة العائمة: 0 صغير، 1 متوسط، 2 كبير. */
    var floatSize: Int
        get() = kv.getInt("float_size", 0).coerceIn(0, 2)
        set(v) = kv.put("float_size", v.coerceIn(0, 2))

    /** وضع الشرطي: الطفل يحرّك شرطياً للقبض على الحرامي وتأخذهما سيارة الشرطة إلى السجن. */
    var policeMode: Boolean
        get() = kv.getBoolean("police_mode", false)
        set(v) = kv.put("police_mode", v)

    /** هل شاهد المستخدم الدليل التعريفي؟ */
    var onboardingDone: Boolean
        get() = kv.getBoolean("onboarding_done", false)
        set(v) = kv.put("onboarding_done", v)

    fun resetSettings() {
        for (k in listOf(
            "speed_percent", "jail_minutes", "volume_percent", "sound_enabled", "effects_enabled",
            "jail_visible", "show_jail_timer", "jail_corner", "cloud_sync_enabled", "ads_enabled", "jail_px", "jail_py"
        )) kv.put(k, null as String?)
    }

    companion object { const val DEFAULT_JAIL_MINUTES = 15 }
}
