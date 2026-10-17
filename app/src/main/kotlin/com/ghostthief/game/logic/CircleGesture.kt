package com.ghostthief.game.logic

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

data class PathPoint(val x: Float, val y: Float)

data class CircleResult(
    val success: Boolean,
    /** مجموع الزاوية المقطوعة بالدرجات (بالقيمة المطلقة، في اتجاه واحد غالب). */
    val sweepDegrees: Float,
    /** نسبة الاقتراب من الإكمال 0..1 (لتأثير الخيط الضوئي). */
    val progress: Float
)

/**
 * يتحقق من أن مسار الإصبع لفّ حول مركز معيّن.
 * متسامح عمداً (للأطفال): يكفي ~300° مع هامش واسع في نصف القطر والاتجاه.
 */
object CircleGesture {
    const val REQUIRED_SWEEP_DEGREES = 300f

    fun evaluate(
        points: List<PathPoint>,
        centerX: Float,
        centerY: Float,
        targetRadius: Float,
        requiredSweep: Float = REQUIRED_SWEEP_DEGREES
    ): CircleResult {
        if (points.size < 8 || targetRadius <= 0f) return CircleResult(false, 0f, 0f)

        val minR = targetRadius * 0.35f
        val maxR = targetRadius * 3.0f

        var signedSum = 0.0
        var posSteps = 0
        var negSteps = 0
        var prevAngle = Double.NaN
        var inBand = 0

        for (p in points) {
            val dx = (p.x - centerX).toDouble()
            val dy = (p.y - centerY).toDouble()
            val r = hypot(dx, dy)
            if (r < minR || r > maxR) { prevAngle = Double.NaN; continue }
            inBand++
            val a = atan2(dy, dx)
            if (!prevAngle.isNaN()) {
                var d = a - prevAngle
                while (d > PI) d -= 2 * PI
                while (d < -PI) d += 2 * PI
                signedSum += d
                if (d > 0) posSteps++ else if (d < 0) negSteps++
            }
            prevAngle = a
        }

        if (inBand < points.size * 0.6) {
            val p = (abs(Math.toDegrees(signedSum)) / requiredSweep).toFloat().coerceIn(0f, 1f)
            return CircleResult(false, abs(Math.toDegrees(signedSum)).toFloat(), p * 0.5f)
        }

        val sweep = abs(Math.toDegrees(signedSum)).toFloat()
        val steps = posSteps + negSteps
        val dominant = if (steps == 0) 0f else maxOf(posSteps, negSteps).toFloat() / steps
        val consistent = dominant >= 0.75f
        val progress = (sweep / requiredSweep).coerceIn(0f, 1f)
        return CircleResult(consistent && sweep >= requiredSweep, sweep, if (consistent) progress else progress * 0.5f)
    }
}
