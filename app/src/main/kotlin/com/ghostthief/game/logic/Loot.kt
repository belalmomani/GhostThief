package com.ghostthief.game.logic

/** حجم الغنيمة يحدد أسلوب السرقة: صغيرة = يحملها ويهرب، متوسطة = يخبّئها، كبيرة = يسحبها بحبل. */
enum class LootKind { SMALL, MEDIUM, LARGE }

/** غنيمة تمثيلية فقط (اسم + حجم). لا يتم تعديل أو حذف أي ملف أو تطبيق فعلياً. */
data class Loot(val name: String, val sizeBytes: Long, val isApp: Boolean) {
    val kind: LootKind get() = LootRules.classify(sizeBytes)
}

object LootRules {
    const val SMALL_MAX = 30L * 1024 * 1024    // أقل من 30MB
    const val MEDIUM_MAX = 150L * 1024 * 1024  // أقل من 150MB

    fun classify(bytes: Long): LootKind = when {
        bytes < SMALL_MAX -> LootKind.SMALL
        bytes < MEDIUM_MAX -> LootKind.MEDIUM
        else -> LootKind.LARGE
    }

    /** مكافأة استرجاع الغنيمة عند القبض على الحرامي وهو يحملها. */
    fun rescueBonus(kind: LootKind): Int = when (kind) {
        LootKind.SMALL -> 10
        LootKind.MEDIUM -> 20
        LootKind.LARGE -> 40
    }

    const val DROP_BONUS = 5

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024L * 1024 -> "%d MB".format(bytes / 1024 / 1024)
        bytes >= 1024L -> "%d KB".format(bytes / 1024)
        else -> "$bytes B"
    }
}
