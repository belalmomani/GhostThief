package com.ghostthief.game.logic

/** حالة التقدم. كل العدّادات تصاعدية فقط؛ هذا يجعل الدمج مع السحابة آمناً بلا تكرار نقاط. */
data class Progress(
    val captures: Int = 0,
    val circleCaptures: Int = 0,
    val points: Int = 0,
    val achievements: Set<String> = emptySet(),
    /** يزيد عند "إعادة الضبط" حتى لا تعيد المزامنة بيانات قديمة. */
    val resetEpoch: Int = 0
) {
    companion object {
        /**
         * دمج آمن: إن اختلف resetEpoch يفوز الأعلى؛ وإلا أخذ الأكبر لكل عدّاد واتحاد الإنجازات.
         * لا يجمع القيم أبداً، فلا تتكرر النقاط عند تكرار المزامنة.
         */
        fun merge(a: Progress, b: Progress): Progress = when {
            a.resetEpoch > b.resetEpoch -> a
            b.resetEpoch > a.resetEpoch -> b
            else -> Progress(
                captures = maxOf(a.captures, b.captures),
                circleCaptures = maxOf(a.circleCaptures, b.circleCaptures),
                points = maxOf(a.points, b.points),
                achievements = a.achievements + b.achievements,
                resetEpoch = a.resetEpoch
            )
        }
    }
}

enum class Achievement(val id: String, val atCaptures: Int = 0) {
    FIRST("first_catch", 1),
    FIVE("five_catches", 5),
    TWENTY_FIVE("twenty_five_catches", 25),
    HUNDRED("hundred_catches", 100),
    CIRCLE_MASTER("circle_master")
}

data class CaptureReward(
    val pointsGained: Int,
    val newAchievements: List<Achievement>,
    val bonusTier: Boolean
)

object ScoreRules {
    const val BASE_POINTS = 10
    const val CIRCLE_BONUS = 5
    const val CIRCLE_MASTER_COUNT = 10

    /** مكافأة متدرجة عند بلوغ عدد معيّن من مرات القبض. */
    fun tierBonus(totalCapturesAfter: Int): Int = when (totalCapturesAfter) {
        5 -> 50
        10 -> 100
        25 -> 250
        50 -> 500
        100 -> 1000
        else -> 0
    }

    fun apply(p: Progress, viaCircle: Boolean): Pair<Progress, CaptureReward> {
        val captures = p.captures + 1
        val circles = p.circleCaptures + if (viaCircle) 1 else 0
        val bonus = tierBonus(captures)
        val gained = BASE_POINTS + (if (viaCircle) CIRCLE_BONUS else 0) + bonus

        val earned = mutableListOf<Achievement>()
        for (a in Achievement.values()) {
            if (a.id in p.achievements) continue
            val ok = if (a == Achievement.CIRCLE_MASTER) circles >= CIRCLE_MASTER_COUNT else captures >= a.atCaptures
            if (ok) earned += a
        }
        val next = p.copy(
            captures = captures,
            circleCaptures = circles,
            points = p.points + gained,
            achievements = p.achievements + earned.map { it.id }
        )
        return next to CaptureReward(gained, earned, bonus > 0)
    }
}
