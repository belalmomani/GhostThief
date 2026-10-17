package com.ghostthief.game.data

import com.ghostthief.game.logic.Progress
import com.ghostthief.game.logic.ScoreRules
import com.ghostthief.game.logic.CaptureReward

/** حفظ التقدم محلياً (يعمل بلا إنترنت). */
class ProgressRepository(private val kv: KeyValueStore) {

    fun load(): Progress = Progress(
        captures = kv.getInt("p_captures", 0),
        circleCaptures = kv.getInt("p_circle", 0),
        points = kv.getInt("p_points", 0),
        achievements = (kv.getString("p_ach", "") ?: "").split(',').filter { it.isNotBlank() }.toSet(),
        resetEpoch = kv.getInt("p_epoch", 0)
    )

    fun save(p: Progress) {
        kv.put("p_captures", p.captures)
        kv.put("p_circle", p.circleCaptures)
        kv.put("p_points", p.points)
        kv.put("p_ach", p.achievements.sorted().joinToString(","))
        kv.put("p_epoch", p.resetEpoch)
    }

    /** يسجّل قبضاً جديداً ويحفظه فوراً. يجب استدعاؤه مرة واحدة لكل قبض (يضمنها CaptureController). */
    fun recordCapture(viaCircle: Boolean): CaptureReward {
        val (next, reward) = ScoreRules.apply(load(), viaCircle)
        save(next)
        kv.put("dirty", true)
        return reward
    }

    /** نقاط إضافية (مثل مكافأة العثور على الحرامي في وضع البحث). */
    fun awardPoints(n: Int) {
        if (n <= 0) return
        val p = load()
        save(p.copy(points = p.points + n))
        kv.put("dirty", true)
    }

    /** إعادة ضبط التقدم (بعد تأكيد المستخدم). يرفع resetEpoch لتفوز على نسخة السحابة القديمة. */
    fun resetProgress() {
        val epoch = load().resetEpoch + 1
        save(Progress(resetEpoch = epoch))
        kv.put("jail_end_at", 0L)
        kv.put("dirty", true)
    }

    var jailEndAt: Long
        get() = kv.getLong("jail_end_at", 0L)
        set(v) = kv.put("jail_end_at", v)

    /** true إذا كان هناك تغيير محلي لم يُرسل للسحابة بعد. */
    var dirty: Boolean
        get() = kv.getBoolean("dirty", false)
        set(v) = kv.put("dirty", v)
}
