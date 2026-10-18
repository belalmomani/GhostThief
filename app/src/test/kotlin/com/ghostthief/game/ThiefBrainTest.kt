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

    @Test fun poke_hurtsThenFleesThenHides() {
        val b = brain()
        assertTrue(b.poke()); assertEquals(ThiefState.SURPRISED, b.state)
        advance(b, 0.45f); assertEquals(ThiefState.FLEE, b.state)
        advance(b, 1.3f); assertTrue(b.state == ThiefState.FADE_OUT || b.state == ThiefState.HIDDEN)
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

    private fun heistBrain() = brain().also { it.x = 100f; it.y = 300f }

    @Test fun smallLoot_carriedThenEscapesWithIt() {
        val b = heistBrain()
        assertTrue(b.beginHeist(300f, 300f, com.ghostthief.game.logic.LootKind.SMALL))
        assertTrue(advanceUntil(b, ThiefState.CARRY, 5f)); assertTrue(b.carrying)
        advance(b, 7.3f)
        assertFalse(b.carrying); assertNull(b.lootKind)
        assertEquals(com.ghostthief.game.logic.LootEvent.ESCAPED_WITH, b.takeLootEvent())
    }

    @Test fun mediumLoot_isStashedAfterHiding() {
        val b = heistBrain()
        b.beginHeist(300f, 300f, com.ghostthief.game.logic.LootKind.MEDIUM)
        assertTrue(advanceUntil(b, ThiefState.CARRY, 5f))
        advance(b, 1.4f); assertTrue(b.state == ThiefState.FADE_OUT || b.state == ThiefState.HIDDEN)
        advance(b, 4.2f)
        assertEquals(com.ghostthief.game.logic.LootEvent.STASHED, b.takeLootEvent()); assertFalse(b.carrying)
    }

    @Test fun largeLoot_isDraggedWithRopeSlowlyToTheEdge() {
        val b = heistBrain()
        b.beginHeist(300f, 300f, com.ghostthief.game.logic.LootKind.LARGE)
        assertTrue(advanceUntil(b, ThiefState.DRAG, 5f)); assertTrue(b.carrying)
        val x0 = b.x; advance(b, 1f)
        assertTrue(Math.abs(b.x - x0) < 80f) // بطيء بسبب الثقل
        advance(b, 16f)
        assertEquals(com.ghostthief.game.logic.LootEvent.ESCAPED_WITH, b.takeLootEvent()); assertFalse(b.carrying)
    }

    @Test fun poke_whileCarrying_dropsLoot() {
        val b = heistBrain()
        b.beginHeist(300f, 300f, com.ghostthief.game.logic.LootKind.SMALL)
        advanceUntil(b, ThiefState.CARRY, 5f)
        assertTrue(b.poke()); assertFalse(b.carrying)
        assertEquals(com.ghostthief.game.logic.LootEvent.DROPPED, b.takeLootEvent())
    }

    @Test fun capture_whileCarrying_rescuesLoot() {
        val b = heistBrain()
        b.beginHeist(300f, 300f, com.ghostthief.game.logic.LootKind.LARGE)
        advanceUntil(b, ThiefState.DRAG, 5f)
        assertTrue(b.capture(700f, 100f))
        assertEquals(com.ghostthief.game.logic.LootKind.LARGE, b.takeRescued())
    }
}
