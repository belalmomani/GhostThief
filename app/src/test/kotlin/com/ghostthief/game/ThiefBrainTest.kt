package com.ghostthief.game

import com.ghostthief.game.logic.ThiefBrain
import com.ghostthief.game.logic.ThiefState
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ThiefBrainTest {
    private fun brain() = ThiefBrain(Random(7)).also { it.setArena(800f, 600f) }

    @Test fun hideCycle_fadesOut_hides_fadesIn_thenRuns() {
        val b = brain()
        b.forceHide()
        assertEquals(ThiefState.FADE_OUT, b.state)
        advance(b, 0.8f); assertEquals(ThiefState.HIDDEN, b.state); assertEquals(0f, b.alpha, 0.001f)
        advance(b, b.config.hideSeconds + 0.1f); assertEquals(ThiefState.FADE_IN, b.state)
        advance(b, 0.8f); assertEquals(ThiefState.RUN, b.state); assertEquals(1f, b.alpha, 0.001f)
    }

    @Test fun poke_causesSurprisedThenFleeThenRun() {
        val b = brain()
        assertTrue(b.poke()); assertEquals(ThiefState.SURPRISED, b.state)
        advance(b, 0.45f); assertEquals(ThiefState.FLEE, b.state)
        advance(b, 1.3f); assertEquals(ThiefState.RUN, b.state)
    }

    @Test fun capture_goesToJail_thenEscapes() {
        val b = brain()
        assertTrue(b.capture(700f, 100f))
        assertEquals(ThiefState.CAUGHT, b.state)
        var arrived = false
        repeat(40) { b.update(0.05f); if (b.arrivedInJail) arrived = true }
        assertTrue(arrived); assertEquals(ThiefState.JAILED, b.state)
        assertTrue(b.escape(400f, 300f)); assertEquals(ThiefState.ESCAPING, b.state)
        advance(b, 1.1f); assertEquals(ThiefState.RUN, b.state); assertEquals(1f, b.scale, 0.001f)
    }

    @Test fun cannotCapture_whileHidden() {
        val b = brain()
        b.forceHide(); advance(b, 0.9f)
        assertEquals(ThiefState.HIDDEN, b.state)
        assertFalse(b.capture(700f, 100f))
    }

    @Test fun speedConfig_changesMovement() {
        val slow = brain().also { it.config.speedScale = 0.5f }
        val fast = brain().also { it.config.speedScale = 2f }
        val sx = slow.x; val fx = fast.x
        slow.update(0.1f); fast.update(0.1f)
        assertTrue(Math.abs(fast.x - fx) > Math.abs(slow.x - sx))
    }
}
