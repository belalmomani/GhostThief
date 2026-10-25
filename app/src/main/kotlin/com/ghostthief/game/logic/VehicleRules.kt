package com.ghostthief.game.logic

/** مركبة في المرآب: تُفتح بالنقاط (عتبة، دون خصم) أو بمشاهدة إعلان مكافأة. */
data class VehicleInfo(val id: String, val pointsRequired: Int)

object VehicleRules {
    const val DEFAULT_ID = "default"

    val ALL = listOf(
        VehicleInfo(DEFAULT_ID, 0),
        VehicleInfo("sedan", 100), VehicleInfo("suv", 250), VehicleInfo("classic", 400),
        VehicleInfo("pickup", 550), VehicleInfo("van", 700), VehicleInfo("moto", 850),
        VehicleInfo("atv", 1000), VehicleInfo("sport", 1300), VehicleInfo("heli", 1600)
    )

    fun unlockKey(id: String) = "veh_$id"
    fun isVehicleKey(key: String) = key.startsWith("veh_")

    /** مفتوحة إن كانت مجانية، أو بلغت النقاط العتبة، أو فُتحت بإعلان (محفوظة كإنجاز مجموع مع السحابة). */
    fun isUnlocked(id: String, p: Progress): Boolean {
        val v = ALL.find { it.id == id } ?: return false
        return v.pointsRequired == 0 || p.points >= v.pointsRequired || unlockKey(id) in p.achievements
    }

    fun pointsRequired(id: String) = ALL.find { it.id == id }?.pointsRequired ?: 0
}

enum class RewardCheck { OK, DAILY_LIMIT, COOLDOWN }

/** حدود الإعلانات المكافِئة للأطفال: 3 يومياً، وانتظار 10 دقائق بين إعلانين. */
object RewardPolicy {
    const val MAX_PER_DAY = 3
    const val COOLDOWN_MS = 10 * 60_000L

    fun dayOf(nowMillis: Long): Long = nowMillis / 86_400_000L

    fun countToday(now: Long, savedDay: Long, savedCount: Int): Int = if (savedDay == dayOf(now)) savedCount else 0

    fun remainingToday(now: Long, savedDay: Long, savedCount: Int): Int =
        (MAX_PER_DAY - countToday(now, savedDay, savedCount)).coerceAtLeast(0)

    fun check(now: Long, savedDay: Long, savedCount: Int, lastAt: Long): RewardCheck = when {
        countToday(now, savedDay, savedCount) >= MAX_PER_DAY -> RewardCheck.DAILY_LIMIT
        lastAt > 0L && now - lastAt < COOLDOWN_MS -> RewardCheck.COOLDOWN
        else -> RewardCheck.OK
    }
}
