package com.ghostthief.game.logic

/** مركبة في المرآب لها سعر بالنقاط. */
data class VehicleInfo(val id: String, val price: Int)

enum class BuyCheck { OK, ALREADY_OWNED, NEED_PREVIOUS, NOT_ENOUGH, UNKNOWN }

/**
 * المركبات تُشترى **بالترتيب** وبأسعار تصاعدية: لا تُشترى مركبة قبل السابقة لها.
 * الشراء يخصم من الرصيد (spent)، والملكية تُحفظ كإنجاز `veh_<id>` فتُزامَن مع السحابة.
 */
object VehicleRules {
    const val DEFAULT_ID = "default"

    /** عدّل الأسعار هنا. */
    val ALL = listOf(
        VehicleInfo(DEFAULT_ID, 0),
        VehicleInfo("sedan", 200), VehicleInfo("suv", 500), VehicleInfo("classic", 900),
        VehicleInfo("pickup", 1400), VehicleInfo("van", 2000), VehicleInfo("moto", 2700),
        VehicleInfo("atv", 3500), VehicleInfo("sport", 4500), VehicleInfo("heli", 6000)
    )

    fun unlockKey(id: String) = "veh_$id"
    fun isVehicleKey(key: String) = key.startsWith("veh_")

    fun price(id: String) = ALL.find { it.id == id }?.price ?: 0

    /** مملوكة: الأساسية مجانية، والباقي بعد الشراء. */
    fun isUnlocked(id: String, p: Progress): Boolean {
        val v = ALL.find { it.id == id } ?: return false
        return v.price == 0 || unlockKey(id) in p.achievements
    }

    fun previousId(id: String): String? {
        val i = ALL.indexOfFirst { it.id == id }
        return if (i > 0) ALL[i - 1].id else null
    }

    fun canBuy(id: String, p: Progress): BuyCheck {
        val i = ALL.indexOfFirst { it.id == id }
        if (i < 0) return BuyCheck.UNKNOWN
        if (isUnlocked(id, p)) return BuyCheck.ALREADY_OWNED
        if (i > 0 && !isUnlocked(ALL[i - 1].id, p)) return BuyCheck.NEED_PREVIOUS
        return if (p.balance >= ALL[i].price) BuyCheck.OK else BuyCheck.NOT_ENOUGH
    }

    /** يُنفّذ الشراء إن أمكن ويرجع الناتج الجديد، وإلا null. */
    fun buy(id: String, p: Progress): Progress? {
        if (canBuy(id, p) != BuyCheck.OK) return null
        return p.copy(spent = p.spent + price(id), achievements = p.achievements + unlockKey(id))
    }
}

enum class RewardCheck { OK, DAILY_LIMIT, COOLDOWN }

/** حدود الإعلانات المكافِئة للأطفال: 3 يومياً، وانتظار 10 دقائق بين إعلانين، ونقاط ثابتة لكل إعلان. */
object RewardPolicy {
    const val MAX_PER_DAY = 3
    const val COOLDOWN_MS = 10 * 60_000L
    /** نقاط تُمنح بعد إكمال إعلان مكافأة. */
    const val AD_POINTS = 50

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

/** نقاط اللعب: نقطة لكل 30 ثانية لعب فعلي، بحد أقصى 60 نقطة يومياً (حتى لا تُجمع بترك اللعبة مفتوحة). */
object PlayPoints {
    const val SECONDS_PER_POINT = 30f
    const val MAX_PER_DAY = 60
}
