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

    @Test fun heist_runsToIcon_stops_looksAround_thenGrabsIt() {
        val b = heistBrain()
        assertTrue(b.beginHeist(300f, 300f, com.ghostthief.game.logic.LootKind.SMALL))
        assertEquals(ThiefState.HEIST_GO, b.state)
        assertTrue(advanceUntil(b, ThiefState.SNEAK, 5f))                // يتوقف عند الأيقونة
        var flips = 0; var last = b.facing; var t = 0f
        while (b.state == ThiefState.SNEAK && t < 3f) { b.update(0.05f); t += 0.05f; if (b.facing != last) { flips++; last = b.facing } }
        assertTrue(flips >= 2)                                           // يتلفّت يميناً ويساراً
        assertEquals(ThiefState.GRAB, b.state)
        assertEquals(com.ghostthief.game.logic.LootEvent.NONE, b.takeLootEvent()) // لم تُسرق بعد
        advance(b, 0.7f)
        assertEquals(com.ghostthief.game.logic.LootEvent.STOLEN, b.takeLootEvent())
    }

    private fun toGrabEnd(b: ThiefBrain, kind: com.ghostthief.game.logic.LootKind) {
        b.beginHeist(300f, 300f, kind)
        assertTrue(advanceUntil(b, ThiefState.GRAB, 8f))
        advance(b, 0.7f)
    }

    @Test fun smallLoot_isCarriedAwayThenThiefRunsOn() {
        val b = heistBrain(); toGrabEnd(b, com.ghostthief.game.logic.LootKind.SMALL)
        assertEquals(ThiefState.CARRY, b.state)
        assertEquals(com.ghostthief.game.logic.LootEvent.STOLEN, b.takeLootEvent())
        advance(b, 4.3f); assertEquals(ThiefState.RUN, b.state)
    }

    @Test fun mediumLoot_thiefHidesWithIt() {
        val b = heistBrain(); toGrabEnd(b, com.ghostthief.game.logic.LootKind.MEDIUM)
        assertEquals(com.ghostthief.game.logic.LootEvent.STOLEN, b.takeLootEvent())
        assertTrue(b.state == ThiefState.FADE_OUT || b.state == ThiefState.HIDDEN)
    }

    @Test fun largeLoot_isDraggedWithRopeThenStolenAtTheEdge() {
        val b = heistBrain(); toGrabEnd(b, com.ghostthief.game.logic.LootKind.LARGE)
        assertEquals(ThiefState.DRAG, b.state); assertNotNull(b.lootKind)
        val x0 = b.x; advance(b, 1f); assertTrue(Math.abs(b.x - x0) < 80f)   // بطيء بسبب الثقل
        advance(b, 18f)
        assertEquals(com.ghostthief.game.logic.LootEvent.STOLEN, b.takeLootEvent()); assertNull(b.lootKind)
    }

    @Test fun poke_whileSneaking_scaresHimOff_iconStays() {
        val b = heistBrain(); b.beginHeist(300f, 300f, com.ghostthief.game.logic.LootKind.SMALL)
        assertTrue(advanceUntil(b, ThiefState.SNEAK, 5f))
        assertTrue(b.poke())
        assertEquals(com.ghostthief.game.logic.LootEvent.ABORTED, b.takeLootEvent())
        advance(b, 1f); assertEquals(com.ghostthief.game.logic.LootEvent.NONE, b.takeLootEvent())
    }

    @Test fun poke_whileDragging_dropsTheIconBack() {
        val b = heistBrain(); toGrabEnd(b, com.ghostthief.game.logic.LootKind.LARGE)
        assertTrue(b.poke()); assertEquals(com.ghostthief.game.logic.LootEvent.ABORTED, b.takeLootEvent())
    }

    @Test fun capture_whileSneaking_abortsTheTheft() {
        val b = heistBrain(); b.beginHeist(300f, 300f, com.ghostthief.game.logic.LootKind.SMALL)
        advanceUntil(b, ThiefState.SNEAK, 5f)
        assertTrue(b.capture(700f, 100f)); assertEquals(com.ghostthief.game.logic.LootEvent.ABORTED, b.takeLootEvent())
    }

    @Test fun poke_afterTheTheft_doesNotReturnTheIcon() {
        val b = heistBrain(); toGrabEnd(b, com.ghostthief.game.logic.LootKind.SMALL)
        b.takeLootEvent()                                                // STOLEN
        assertTrue(b.poke())
        assertEquals(com.ghostthief.game.logic.LootEvent.NONE, b.takeLootEvent()) // ما دخل الكيس يبقى معه
    }
}
