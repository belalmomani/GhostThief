package com.ghostthief.game

import com.ghostthief.game.game.GameEngine
import com.ghostthief.game.logic.CaptureSource
import com.ghostthief.game.logic.RewardPolicy
import com.ghostthief.game.logic.ThiefState
import org.junit.Assert.*
import org.junit.Test

/** مسلّية السجن «حرّاس السجن»: يحاول الحرامي فتح القفل، والطفل ينقر السجن ليمنعه. */
class JailGuardTest {
    private fun jailed(env: Env): GameEngine {
        val e = env.engine()
        // نوقف نقاط اللعب اليومية حتى نقيس نقاط الحرّاس وحدها
        env.settings.playPointsDay = RewardPolicy.dayOf(env.clock.nowMillis()); env.settings.playPointsToday = 60
        e.beginCapture(CaptureSource.CIRCLE); advanceEngine(e, 1.2f)
        assertEquals(ThiefState.JAILED, e.brain.state)
        return e
    }

    private fun waitForAttempt(e: GameEngine, maxSeconds: Float = 15f): Boolean {
        var t = 0f
        while (!e.jailPickActive && t < maxSeconds) { e.update(0.05f); t += 0.05f }
        return e.jailPickActive
    }

    @Test fun thiefAttemptsToPickTheLock_whileJailed() {
        val env = Env(); val e = jailed(env)
        assertTrue(waitForAttempt(e))
    }

    @Test fun tappingTheJailDuringAnAttempt_stopsItAndEarnsAPoint() {
        val env = Env(); val e = jailed(env); waitForAttempt(e)
        val before = env.repo.load().points
        e.onJailTapped()
        assertFalse(e.jailPickActive); assertEquals(before + 1, env.repo.load().points)
    }

    @Test fun tappingWithoutAnAttempt_doesNothing() {
        val env = Env(); val e = jailed(env)
        val before = env.repo.load().points
        e.onJailTapped()
        assertEquals(before, env.repo.load().points)
    }

    @Test fun ignoringTheAttempt_hasNoPenalty_thiefStaysJailed() {
        val env = Env(); val e = jailed(env); waitForAttempt(e)
        val before = env.repo.load().points
        advanceEngine(e, GameEngine.GUARD_PICK_SECONDS + 0.3f)
        assertFalse(e.jailPickActive)
        assertEquals(ThiefState.JAILED, e.brain.state); assertEquals(before, env.repo.load().points)
        assertTrue(e.jail.isJailed)                                         // المدة لم تتأثر
    }

    @Test fun guardPoints_haveASessionCap() {
        val env = Env(); val e = jailed(env)
        val base = env.repo.load().points
        repeat(30) { if (waitForAttempt(e, 20f)) e.onJailTapped() }
        assertEquals(base + GameEngine.GUARD_POINTS_MAX, env.repo.load().points)
    }

    @Test fun tapOnJailViaTouch_countsAsGuarding() {
        val env = Env(); val e = jailed(env); waitForAttempt(e)
        val x = e.jailCenterX(); val y = e.jailCenterY()
        val before = env.repo.load().points
        e.touchDown(x, y); e.touchUp(x, y)                                  // نقرة بلا سحب
        assertEquals(before + 1, env.repo.load().points)
    }

    @Test fun draggingTheJail_isNotATap() {
        val env = Env(); val e = jailed(env); waitForAttempt(e)
        val x = e.jailCenterX(); val y = e.jailCenterY()
        val before = env.repo.load().points
        e.touchDown(x, y); e.touchMove(x - 120f, y + 60f); e.touchUp(x - 120f, y + 60f)
        assertEquals(before, env.repo.load().points); assertTrue(e.jailPickActive)
    }
}
