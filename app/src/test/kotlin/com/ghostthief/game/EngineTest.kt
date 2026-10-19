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
        assertEquals(5 + 15, env.repo.load().points) // أساس 5 + مكافأة سرعة (أقل من 5 ثوان)
        assertTrue("sharp_eyes" in env.repo.load().achievements)
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

    @Test fun tapOnThief_hurts_playsOuch_thenHides() {
        val env = Env(); val e = env.engine()
        val sounds = mutableListOf<SoundEffect>()
        e.listener = object : GameListener {
            override fun onMessage(msg: GameMessage, points: Int) {}
            override fun onSound(effect: SoundEffect) { sounds += effect }
        }
        e.brain.x = 300f; e.brain.y = 300f
        e.touchDown(300f, 300f); e.touchUp(300f, 300f)
        assertTrue(SoundEffect.OUCH in sounds)
        advanceEngine(e, 1.8f)
        assertTrue(e.brain.state == ThiefState.FADE_OUT || e.brain.state == ThiefState.HIDDEN)
    }

    @Test fun heist_rescue_giveBonusOnCapture() {
        val env = Env(); env.settings.heistEnabled = true
        val e = env.engine(); e.loots = listOf(com.ghostthief.game.logic.Loot("WhatsApp", 200L * 1024 * 1024, true))
        e.triggerHeist(); assertNotNull(e.currentLoot)
        e.brain.x = e.brain.lootX - 20f; e.brain.y = e.brain.lootY // بجانب الغنيمة
        advanceEngine(e, 0.3f)
        assertEquals(ThiefState.DRAG, e.brain.state)
        assertTrue(e.beginCapture(CaptureSource.CIRCLE))
        assertEquals(15 + 40, env.repo.load().points) // قبض دائري + مكافأة غنيمة كبيرة
        assertNull(e.currentLoot)
    }

    @Test fun heist_disabled_neverStarts() {
        val env = Env(); val e = env.engine(); e.loots = listOf(com.ghostthief.game.logic.Loot("A", 1L, false))
        advanceEngine(e, 40f)
        assertNull(e.currentLoot)
    }

    @Test fun compactMode_smallerThief_andLootAppearsNearIt() {
        val env = Env(); env.settings.heistEnabled = true
        val e = env.engine(); e.compact = true; e.sizeFactor = 0.65f; e.setArena(1080f, 2000f, 2f)
        assertTrue(e.thiefRadius < 48f * 2f * 0.7f)
        e.loots = listOf(com.ghostthief.game.logic.Loot("A", 1L, false))
        e.brain.x = 500f; e.brain.y = 1000f
        e.triggerHeist()
        val d = Math.hypot((e.brain.lootX - 500f).toDouble(), (e.brain.lootY - 1000f).toDouble())
        assertTrue(d <= e.thiefRadius * 5.1f)
    }

    @Test fun jailWindowDrag_isSavedAndRestored() {
        val env = Env(); val e = env.engine()
        e.dragJailTo(200f, 300f); e.commitJailPosition()
        assertTrue(env.settings.jailPosX >= 0)
        assertTrue(Math.abs(env.engine().jailLeft() - 200f) < 2f)
    }

    @Test fun insets_keepJailAndThiefBelowStatusBar() {
        val env = Env(); val e = env.engine()
        e.topInset = 100f; e.bottomInset = 150f; e.setArena(800f, 600f, 1f)
        e.dragJailTo(0f, 0f); assertTrue(e.jailTop() >= 100f)
        e.dragJailTo(0f, 9999f); assertTrue(e.jailTop() + e.jailH <= 600f - 150f + 0.5f)
        advanceEngine(e, 20f)
        assertTrue(e.brain.y >= 100f)
    }

    @Test fun slowFind_givesNoSpeedBonus_andNoSharpEyes() {
        val env = Env(); env.settings.seekEnabled = true
        val e = env.engine(); e.seekNames = listOf("A", "B", "C")
        e.brain.forceHide(); advanceEngine(e, 1.0f)
        advanceEngine(e, 25f)
        val real = e.seekSpots[e.seekRealIndex]
        e.touchDown(real.x, real.y)
        assertEquals(5, env.repo.load().points)
        assertFalse("sharp_eyes" in env.repo.load().achievements)
    }

    @Test fun rescuingLargeLoot_unlocksRescueAchievements() {
        val env = Env(); env.settings.heistEnabled = true
        val e = env.engine(); e.loots = listOf(com.ghostthief.game.logic.Loot("Big", 500L * 1024 * 1024, true))
        e.triggerHeist(); e.brain.x = e.brain.lootX - 20f; e.brain.y = e.brain.lootY
        advanceEngine(e, 0.3f); e.beginCapture(CaptureSource.CIRCLE)
        val ach = env.repo.load().achievements
        assertTrue("first_rescue" in ach && "large_rescue" in ach)
    }
}
