package com.ghostthief.game

import com.ghostthief.game.logic.CircleGesture
import com.ghostthief.game.logic.PathPoint
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class CircleGestureTest {
    private fun arc(deg: Int, r: Float = 100f, cx: Float = 300f, cy: Float = 300f, dir: Int = 1) =
        (0..deg step 6).map { val a = dir * it * PI / 180.0; PathPoint(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat()) }

    @Test fun fullCircle_succeeds() = assertTrue(CircleGesture.evaluate(arc(360), 300f, 300f, 60f).success)
    @Test fun counterClockwiseCircle_succeeds() = assertTrue(CircleGesture.evaluate(arc(360, dir = -1), 300f, 300f, 60f).success)
    @Test fun roughlyThreeQuarters_succeedsForKids() = assertTrue(CircleGesture.evaluate(arc(330), 300f, 300f, 60f).success)
    @Test fun halfCircle_fails() = assertFalse(CircleGesture.evaluate(arc(180), 300f, 300f, 60f).success)
    @Test fun straightLine_fails() {
        val pts = (0..40).map { PathPoint(100f + it * 10f, 300f) }
        assertFalse(CircleGesture.evaluate(pts, 300f, 300f, 60f).success)
    }
    @Test fun circleFarAway_fails() = assertFalse(CircleGesture.evaluate(arc(360, r = 100f, cx = 700f, cy = 700f), 300f, 300f, 60f).success)
    @Test fun tooFewPoints_fails() = assertFalse(CircleGesture.evaluate(arc(30), 300f, 300f, 60f).success)
}
