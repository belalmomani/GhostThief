package com.ghostthief.game

import com.ghostthief.game.data.ProgressRepository
import com.ghostthief.game.logic.Progress
import com.ghostthief.game.logic.ScoreRules
import org.junit.Assert.*
import org.junit.Test

class PersistenceTest {
    @Test fun settings_saveAndRestore() {
        val env = Env()
        env.settings.speedPercent = 150; env.settings.jailMinutes = 15; env.settings.soundEnabled = false; env.settings.jailCorner = 3
        val again = com.ghostthief.game.data.Settings(env.kv)
        assertEquals(150, again.speedPercent); assertEquals(15, again.jailMinutes); assertFalse(again.soundEnabled); assertEquals(3, again.jailCorner)
    }

    @Test fun progress_saveAndRestore_withAchievements() {
        val env = Env()
        env.repo.recordCapture(true)
        val reloaded = ProgressRepository(env.kv).load()
        assertEquals(1, reloaded.captures); assertTrue("first_catch" in reloaded.achievements)
    }

    @Test fun tieredRewards_atFiveCaptures() {
        var p = Progress()
        var last = 0
        repeat(5) { val (n, r) = ScoreRules.apply(p, false); p = n; last = r.pointsGained }
        assertEquals(10 + 50, last)
    }

    @Test fun reset_clearsProgress_andBumpsEpoch() {
        val env = Env(); env.repo.recordCapture(false); env.repo.resetProgress()
        val p = env.repo.load()
        assertEquals(0, p.captures); assertEquals(0, p.points); assertEquals(1, p.resetEpoch)
    }

    @Test fun defaults_floatingAndSeekAreOff_jailIsFifteenMinutes() {
        val env = Env(); assertFalse(env.settings.floatingEnabled); assertFalse(env.settings.seekEnabled); assertEquals(15, env.settings.jailMinutes)
    }
}
