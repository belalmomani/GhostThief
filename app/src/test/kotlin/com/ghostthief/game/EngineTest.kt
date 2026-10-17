package com.ghostthief.game

import com.ghostthief.game.game.GameEngine
import com.ghostthief.game.game.GameListener
import com.ghostthief.game.game.GameMessage
import com.ghostthief.game.game.SoundEffect
import com.ghostthief.game.logic.CaptureController
import com.ghostthief.game.logic.CaptureSource
import com.ghostthief.game.logic.ThiefState
import org.junit.Assert.*
import org.junit.Test

class EngineTest {
    private class Rec : GameListener {
        val msgs = mutableListOf<GameMessage>()
        override fun onMessage(msg: GameMessage, points: Int) { msgs += msg }
        override fun onSound(effect: SoundEffect) {}
    }

    @Test fun captureController_beginsOnlyOnce() {
        val c = CaptureController()
        assertTrue(c.tryBegin(CaptureSource.CIRCLE)); assertFalse(c.tryBegin(CaptureSource.LONG_PRESS_DRAG))
        c.finish(); assertTrue(c.tryBegin(CaptureSource.CIRCLE))
    }

    @Test fun overlappingTouchEvents_recordOneCapture() {
        val env = Env(); val e = env.engine()
        assertTrue(e.beginCapture(CaptureSource.CIRCLE))
        assertFalse(e.beginCapture(CaptureSource.CIRCLE))
        assertFalse(e.beginCapture(CaptureSource.LONG_PRESS_DRAG))
        assertEquals(1, env.repo.load().captures)
        assertEquals(15, env.repo.load().points) // 10 أساسية + 5 للحركة الدائرية
    }

    @Test fun jail_releasesAfterConfiguredTime_andThiefRunsAgain() {
        val env = Env(); env.settings.jailMinutes = 60
        val e = env.engine(); val rec = Rec(); e.listener = rec
        e.beginCapture(CaptureSource.CIRCLE)
        advanceEngine(e, 1.2f)
        assertEquals(ThiefState.JAILED, e.brain.state)
        env.clock.now += 59 * 60_000L; advanceEngine(e, 0.1f)
        assertEquals(ThiefState.JAILED, e.brain.state)
        env.clock.now += 2 * 60_000L; advanceEngine(e, 0.1f)
        assertEquals(ThiefState.ESCAPING, e.brain.state)
        assertTrue(rec.msgs.contains(GameMessage.ESCAPED))
        advanceEngine(e, 1.2f)
        assertEquals(ThiefState.RUN, e.brain.state)
        assertEquals(0L, env.repo.jailEndAt)
    }

    @Test fun defaultJailDuration_isOneHour() = assertEquals(60, Env().settings.jailMinutes)

    @Test fun jailSurvivesRestart_whileTimeRemains() {
        val env = Env(); val e1 = env.engine(); e1.beginCapture(CaptureSource.CIRCLE)
        val e2 = env.engine()
        assertEquals(ThiefState.JAILED, e2.brain.state)
        env.clock.now += 61 * 60_000L
        assertEquals(ThiefState.RUN, env.engine().brain.state)
    }

    @Test fun longPressThenDragToJail_captures() {
        val env = Env(); val e = env.engine()
        e.brain.let { it.x = 200f; it.y = 300f }
        e.touchDown(200f, 300f)
        advanceEngine(e, 0.6f)
        assertEquals(ThiefState.HELD, e.brain.state)
        e.touchMove(e.jailCenterX(), e.jailCenterY())
        e.touchUp(e.jailCenterX(), e.jailCenterY())
        assertEquals(ThiefState.CAUGHT, e.brain.state)
        assertEquals(1, env.repo.load().captures)
    }

    @Test fun circularFingerMotion_capturesThief() {
        val env = Env(); val e = env.engine()
        e.brain.x = 300f; e.brain.y = 300f
        e.touchDown(400f, 300f)
        for (deg in 0..380 step 8) {
            val a = Math.toRadians(deg.toDouble())
            e.touchMove(300f + 100f * Math.cos(a).toFloat(), 300f + 100f * Math.sin(a).toFloat())
        }
        assertEquals(1, env.repo.load().captures)
        assertEquals(ThiefState.CAUGHT, e.brain.state)
    }

    @Test fun jailHidden_doesNotCancelCapturedState() {
        val env = Env(); val e = env.engine(); e.beginCapture(CaptureSource.CIRCLE); advanceEngine(e, 1.2f)
        env.settings.jailVisible = false
        assertEquals(ThiefState.JAILED, e.brain.state); assertTrue(e.jail.isJailed)
    }
}
