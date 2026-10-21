package com.ghostthief.game

import com.ghostthief.game.logic.PoliceSquad
import com.ghostthief.game.logic.PoliceStage
import com.ghostthief.game.logic.ThiefBrain
import com.ghostthief.game.logic.ThiefState
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class PoliceTest {
    private fun squad() = PoliceSquad(Random(1)).also { it.setArena(800f, 600f, 0f); it.carWidth = 200f }

    @Test fun officerWalksTowardTheFinger() {
        val sq = squad(); sq.place(100f, 300f); sq.moveTo(500f, 300f)
        repeat(20) { sq.update(0.05f) }
        assertTrue(sq.ox > 300f)
    }

    @Test fun fullSequence_carTakesThiefToJailThenLeaves() {
        val sq = squad(); sq.place(200f, 300f)
        assertTrue(sq.arrest(220f, 300f, 700f, 150f))
        val stages = mutableSetOf<PoliceStage>()
        var dropped = false; var t = 0f
        while (t < 15f && !dropped) { sq.update(0.05f); stages += sq.stage; if (sq.takeDropped()) dropped = true; t += 0.05f }
        assertTrue(dropped)
        assertTrue(PoliceStage.CAR_COMING in stages && PoliceStage.BOARDING in stages && PoliceStage.DRIVING in stages)
        assertTrue(sq.officerVisible) // الشرطي ينزل من السيارة عند السجن
        repeat(80) { sq.update(0.05f) }
        assertEquals(PoliceStage.PATROL, sq.stage); assertFalse(sq.carVisible)
    }

    @Test fun cannotArrestWhileBusy() {
        val sq = squad(); sq.place(200f, 300f)
        assertTrue(sq.arrest(220f, 300f, 700f, 150f)); assertFalse(sq.arrest(220f, 300f, 700f, 150f))
    }

    @Test fun threatenedThief_fleesAwayFromTheOfficer() {
        val b = ThiefBrain(Random(7)).also { it.setArena(800f, 600f); it.x = 400f; it.y = 300f }
        b.setThreat(300f, 300f)
        val x0 = b.x; advance(b, 0.5f)
        assertTrue(b.x > x0 + 40f)
    }

    @Test fun arrestedThief_isDeliveredToJail() {
        val b = ThiefBrain(Random(7)).also { it.setArena(800f, 600f) }
        assertTrue(b.arrest()); assertEquals(ThiefState.ARRESTED, b.state)
        assertFalse(b.isCatchable())
        assertTrue(b.deliverToJail(700f, 100f)); assertEquals(ThiefState.CAUGHT, b.state)
        advance(b, 1.1f); assertEquals(ThiefState.JAILED, b.state)
    }

    @Test fun policeMode_officerCatches_carDeliversThief_scoreCountedOnce() {
        val env = Env(); val e = env.engine(); e.setPoliceMode(true)
        e.brain.x = 300f; e.brain.y = 300f; e.squad.place(300f, 300f)
        advanceEngine(e, 0.1f)
        assertEquals(ThiefState.ARRESTED, e.brain.state)
        advanceEngine(e, 15f)
        assertTrue(e.brain.state == ThiefState.JAILED || e.brain.state == ThiefState.CAUGHT)
        assertEquals(1, env.repo.load().captures)
        assertEquals(10, env.repo.load().points)
    }

    @Test fun turningPoliceModeOff_cancelsOperation() {
        val env = Env(); val e = env.engine(); e.setPoliceMode(true)
        e.brain.x = 300f; e.brain.y = 300f; e.squad.place(300f, 300f)
        advanceEngine(e, 0.1f); assertEquals(ThiefState.ARRESTED, e.brain.state)
        e.setPoliceMode(false)
        assertEquals(ThiefState.RUN, e.brain.state); assertFalse(e.squad.carVisible)
    }
}
