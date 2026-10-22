package com.ghostthief.game

import com.ghostthief.game.game.GameListener
import com.ghostthief.game.game.GameMessage
import com.ghostthief.game.game.LoopSound
import com.ghostthief.game.game.SoundEffect
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

    @Test fun officerSlowsDownNearTarget_soWalkReplacesRun() {
        val sq = squad(); sq.place(100f, 300f); sq.moveTo(400f, 300f)
        sq.update(0.05f); assertEquals(1f, sq.speedRatio, 0.01f)       // بعيد: سرعة كاملة (ركض)
        repeat(60) { sq.update(0.05f); if (sq.ox > 360f) return@repeat }
        sq.place(380f, 300f); sq.moveTo(400f, 300f); sq.update(0.05f)
        assertTrue(sq.speedRatio < 0.5f)                               // قريب: بطيء (مشي)
    }

    @Test fun officerCannotWalkIntoTheJail() {
        val sq = squad(); sq.place(200f, 300f); sq.setObstacle(300f, 200f, 420f, 400f)
        sq.moveTo(360f, 300f)
        repeat(60) { sq.update(0.05f) }
        val inside = sq.ox in 300f..420f && sq.oy in 200f..400f
        assertFalse(inside)
    }

    private class Loops : GameListener {
        val events = mutableListOf<Pair<LoopSound, Boolean>>()
        override fun onMessage(msg: GameMessage, points: Int) {}
        override fun onSound(effect: SoundEffect) {}
        override fun onLoop(sound: LoopSound, on: Boolean, rate: Float) { events += sound to on }
    }

    @Test fun sirenAndEngine_startOnce_andStopWhenChaseEnds() {
        val env = Env(); val e = env.engine(); val l = Loops(); e.listener = l
        e.setPoliceMode(true)
        e.brain.x = 300f; e.brain.y = 300f; e.squad.place(300f, 300f)
        advanceEngine(e, 20f)
        val siren = l.events.filter { it.first == LoopSound.SIREN }.map { it.second }
        assertEquals(listOf(true, false), siren)                 // مرة واحدة فقط، لا تكرار كل إطار
        val engine = l.events.filter { it.first == LoopSound.ENGINE }
        assertTrue(engine.first().second); assertFalse(engine.last().second)
        assertTrue(engine.size < 40)                             // طبقة المحرك لا تُرسل كل إطار
    }

    @Test fun pausingStopsTheLoops_andResumeRestartsThem() {
        val env = Env(); val e = env.engine(); val l = Loops(); e.listener = l
        e.setPoliceMode(true)
        e.brain.x = 300f; e.brain.y = 300f; e.squad.place(300f, 300f)
        advanceEngine(e, 2.5f)                                    // أثناء مجيء السيارة
        assertTrue(l.events.any { it.first == LoopSound.SIREN && it.second })
        e.stopAudio()
        assertFalse(l.events.last { it.first == LoopSound.SIREN }.second)
        advanceEngine(e, 0.1f)                                    // عند الاستئناف تُستعاد إن بقيت المطاردة
        assertTrue(l.events.last { it.first == LoopSound.SIREN }.second)
    }
}
