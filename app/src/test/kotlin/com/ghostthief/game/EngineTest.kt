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

    @Test fun defaultJailDuration_isFifteenMinutes() = assertEquals(15, Env().settings.jailMinutes)

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

    @Test fun jail_canBeDraggedAnywhere_andPositionIsSaved() {
        val env = Env(); val e = env.engine()
        e.brain.x = 100f; e.brain.y = 500f // الحرامي بعيد عن السجن
        val sx = e.jailCenterX(); val sy = e.jailCenterY()
        e.touchDown(sx, sy)
        e.touchMove(300f, 300f)
        e.touchUp(300f, 300f)
        assertTrue(Math.abs(e.jailCenterX() - 300f) < 1f && Math.abs(e.jailCenterY() - 300f) < 1f)
        assertTrue(env.settings.jailPosX in 0..1000 && env.settings.jailPosY in 0..1000)
        val e2 = env.engine() // موضع السجن يُستعاد
        assertTrue(Math.abs(e2.jailCenterX() - 300f) < 2f)
    }

    @Test fun jail_dragClampsInsideScreen() {
        val env = Env(); val e = env.engine(); e.brain.x = 100f; e.brain.y = 500f
        e.touchDown(e.jailCenterX(), e.jailCenterY()); e.touchMove(-500f, 5000f); e.touchUp(0f, 0f)
        assertTrue(e.jailLeft() >= 0f && e.jailTop() + e.jailH <= 600.5f)
    }

    @Test fun jailedThief_followsDraggedJail() {
        val env = Env(); val e = env.engine(); e.beginCapture(CaptureSource.CIRCLE); advanceEngine(e, 1.2f)
        e.touchDown(e.jailCenterX(), e.jailCenterY()); e.touchMove(200f, 200f); e.touchUp(200f, 200f); advanceEngine(e, 0.1f)
        assertEquals(ThiefState.JAILED, e.brain.state)
        assertTrue(Math.abs(e.brain.x - e.jailCenterX()) < 1f)
    }

    @Test fun seekMode_hidesInNamedFolders_wrongTapThenFind() {
        val env = Env(); env.settings.seekEnabled = true
        val e = env.engine(); e.seekNames = listOf("Photos", "Docs", "Music", "Games")
        e.brain.forceHide()
        advanceEngine(e, 1.0f)
        assertTrue(e.seekActive); assertEquals(ThiefState.HIDDEN, e.brain.state)
        val wrong = e.seekSpots.indices.first { it != e.seekRealIndex }
        e.touchDown(e.seekSpots[wrong].x, e.seekSpots[wrong].y)
        assertTrue(e.seekSpots[wrong].wrong); assertTrue(e.seekActive)
        val real = e.seekSpots[e.seekRealIndex]
        e.touchDown(real.x, real.y)
        assertFalse(e.seekActive)
        advanceEngine(e, 0.6f)
        assertEquals(ThiefState.RUN, e.brain.state)
        assertEquals(5, env.repo.load().points)
    }

    @Test fun seekMode_off_usesNormalHiding() {
        val env = Env(); val e = env.engine(); e.seekNames = listOf("A", "B")
        e.brain.forceHide(); advanceEngine(e, 1.0f)
        assertFalse(e.seekActive)
    }

    @Test fun seekMode_timeout_revealsThief() {
        val env = Env(); env.settings.seekEnabled = true
        val e = env.engine(); e.seekNames = listOf("A", "B", "C")
        e.brain.forceHide(); advanceEngine(e, 1.0f); assertTrue(e.seekActive)
        advanceEngine(e, GameEngine.SEEK_TIMEOUT_SEC + 1f)
        assertFalse(e.seekActive)
        assertTrue(e.brain.state != ThiefState.HIDDEN)
    }
}
