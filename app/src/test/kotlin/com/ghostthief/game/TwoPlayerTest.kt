package com.ghostthief.game

import com.ghostthief.game.game.GameEngine
import com.ghostthief.game.logic.CaptureSource
import com.ghostthief.game.logic.LootKind
import com.ghostthief.game.logic.ThiefBrain
import com.ghostthief.game.logic.ThiefState
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** وضع لاعبين: لاعب يحرّك الشرطي بالأسهم، وآخر يدير الحرامي بلوحة أسهم وزر اختباء. */
class TwoPlayerTest {
    private fun brain() = ThiefBrain(Random(7)).also { it.setArena(800f, 600f); it.x = 300f; it.y = 300f }
    private fun chase(e: GameEngine) { e.squad.moveTo(e.squad.ox + 250f, e.squad.oy) }

    @Test fun manualThief_staysStillWithoutInput_noAutonomousBehaviour() {
        val b = brain(); b.setManual(true)
        var hid = false
        repeat(400) { b.update(0.05f); if (b.state == ThiefState.FADE_OUT || b.state == ThiefState.HIDDEN) hid = true }
        assertEquals(300f, b.x, 0.01f); assertEquals(300f, b.y, 0.01f)
        assertFalse(hid); assertEquals(ThiefState.STAND, b.state)
    }

    @Test fun input_movesTheThief_atManualSpeed() {
        val b = brain(); b.setManual(true); b.setInput(1f, 0f)
        advance(b, 1f)
        assertTrue(b.x > 450f); assertEquals(ThiefState.RUN, b.state); assertEquals(1, b.facing)
        b.setInput(0f, 0f); advance(b, 0.1f); assertEquals(ThiefState.STAND, b.state)
    }

    @Test fun diagonalInput_isNotFasterThanStraight() {
        val a = brain().also { it.setManual(true); it.setInput(1f, 0f) }; advance(a, 0.5f)
        val d = brain().also { it.setManual(true); it.setInput(1f, 1f) }; advance(d, 0.5f)
        val da = Math.hypot((a.x - 300f).toDouble(), (a.y - 300f).toDouble())
        val dd = Math.hypot((d.x - 300f).toDouble(), (d.y - 300f).toDouble())
        assertEquals(da, dd, 1.0)
    }

    @Test fun hideButton_hidesThenHasACooldown() {
        val b = brain(); b.setManual(true)
        assertTrue(b.hideReady); assertTrue(b.requestHide())
        assertEquals(ThiefState.FADE_OUT, b.state)
        assertFalse(b.hideReady); assertFalse(b.requestHide())
        advance(b, 1f); assertEquals(ThiefState.HIDDEN, b.state); assertFalse(b.isCatchable())
        assertTrue(b.hideCooldownSeconds > 0f)
        advance(b, 9f)
        assertTrue(b.state == ThiefState.RUN || b.state == ThiefState.STAND)
        assertTrue(b.hideReady)
    }

    @Test fun twoPlayer_activatesPolice_andThiefObeysPlayerTwo() {
        val env = Env(); val e = env.engine(); e.setTwoPlayer(true)
        assertTrue(e.twoPlayer); assertTrue(e.policeActive); assertTrue(e.brain.manual)
        val x0 = e.brain.x; e.thiefDirection(1f, 0f); advanceEngine(e, 1f)
        assertTrue(e.brain.x > x0 + 100f)
    }

    @Test fun thiefInputIsIgnored_outsideTwoPlayerMode() {
        val env = Env(); val e = env.engine()
        e.thiefDirection(1f, 0f); assertFalse(e.thiefHide()); assertEquals(0f, e.brain.inputX, 0f)
    }

    @Test fun catch_inTwoPlayer_scoresForPolice_andUsesAShortJail() {
        val env = Env(); val e = env.engine(); e.setTwoPlayer(true)
        e.brain.x = 300f; e.brain.y = 300f; e.squad.place(300f, 300f); chase(e)
        advanceEngine(e, 0.1f); assertEquals(ThiefState.ARRESTED, e.brain.state)
        advanceEngine(e, 20f)
        assertEquals(1, e.scorePolice)
        assertEquals(0L, env.repo.jailEndAt)                                  // لا يُحفظ وقت السجن الطويل
        assertTrue(e.brain.state == ThiefState.RUN || e.brain.state == ThiefState.STAND) // هرب بعد 10 ثوانٍ فقط
    }

    @Test fun thiefSurvivalScore_oneForEveryTenSeconds() {
        val env = Env(); val e = env.engine(); e.setTwoPlayer(true)
        e.squad.place(700f, 550f)                                            // الشرطي بعيد وواقف
        e.brain.x = 100f; e.brain.y = 100f
        advanceEngine(e, 25f)
        assertEquals(2, e.scoreThief)
    }

    @Test fun twoPlayer_turnsOffHeistsAndSeek() {
        val env = Env(); env.settings.heistEnabled = true
        val e = env.engine(); e.loots = listOf(com.ghostthief.game.logic.Loot("A", 5L * 1024 * 1024, true))
        e.setTwoPlayer(true); advanceEngine(e, 40f)
        assertNull(e.targetIcon)
    }

    @Test fun turningItOff_returnsTheThiefToTheAI() {
        val env = Env(); val e = env.engine(); e.setTwoPlayer(true); e.setTwoPlayer(false)
        assertFalse(e.brain.manual); assertFalse(e.twoPlayer)
    }

    @Test fun enteringWhileTheThiefIsInRealJail_releasesHim() {
        val env = Env(); val e = env.engine()
        e.beginCapture(CaptureSource.CIRCLE); advanceEngine(e, 1.2f)
        assertEquals(ThiefState.JAILED, e.brain.state)
        e.setTwoPlayer(true); advanceEngine(e, 1.3f)
        assertTrue(e.brain.state != ThiefState.JAILED); assertEquals(0L, env.repo.jailEndAt)
    }

    @Test fun showControlsSetting_defaultsOn_andPersists() {
        val env = Env(); assertTrue(env.settings.showControls)
        env.settings.showControls = false
        assertFalse(com.ghostthief.game.data.Settings(env.kv).showControls)
    }
}
