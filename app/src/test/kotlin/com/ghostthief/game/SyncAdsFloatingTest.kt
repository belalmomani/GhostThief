package com.ghostthief.game

import android.app.Activity
import android.view.ViewGroup
import com.ghostthief.game.ads.AdPolicy
import com.ghostthief.game.ads.AdsController
import com.ghostthief.game.ads.SafeAdsController
import com.ghostthief.game.cloud.SyncManager
import com.ghostthief.game.cloud.SyncStatus
import com.ghostthief.game.floating.EnableResult
import com.ghostthief.game.floating.FloatingModeController
import com.ghostthief.game.floating.OverlayEnvironment
import com.ghostthief.game.logic.Progress
import org.junit.Assert.*
import org.junit.Test

class SyncTest {
    @Test fun notEnabled_staysLocal() {
        val env = Env(); val sm = SyncManager(env.repo, env.settings, FakeBackend())
        sm.syncNow(); assertEquals(SyncStatus.DISABLED, sm.status)
    }
    @Test fun notConfigured_isReportedClearly() {
        val env = Env(); env.settings.cloudSyncEnabled = true
        val sm = SyncManager(env.repo, env.settings, FakeBackend(isConfigured = false))
        sm.syncNow(); assertEquals(SyncStatus.NOT_CONFIGURED, sm.status)
    }
    @Test fun offline_keepsLocalData_thenSyncsLater() {
        val env = Env(); env.settings.cloudSyncEnabled = true; env.repo.recordCapture(true)
        val be = FakeBackend(online = false); val sm = SyncManager(env.repo, env.settings, be)
        sm.syncNow(); assertEquals(SyncStatus.OFFLINE_PENDING, sm.status); assertTrue(env.repo.dirty); assertEquals(1, env.repo.load().captures)
        be.online = true; sm.syncNow()
        assertEquals(SyncStatus.SYNCED, sm.status); assertFalse(env.repo.dirty); assertEquals(1, be.remote?.captures)
    }
    @Test fun backendThrowing_doesNotCrash() {
        val env = Env(); env.settings.cloudSyncEnabled = true; env.repo.recordCapture(false)
        val sm = SyncManager(env.repo, env.settings, FakeBackend(throwOnFetch = true))
        sm.syncNow(); assertEquals(SyncStatus.OFFLINE_PENDING, sm.status); assertEquals(1, env.repo.load().captures)
    }
    @Test fun repeatedSync_doesNotDuplicatePoints() {
        val env = Env(); env.settings.cloudSyncEnabled = true; env.repo.recordCapture(false)
        val be = FakeBackend(); val sm = SyncManager(env.repo, env.settings, be)
        repeat(3) { sm.syncNow() }
        assertEquals(10, env.repo.load().points); assertEquals(10, be.remote?.points)
    }
    @Test fun merge_takesMaxAndUnionsAchievements() {
        val m = Progress.merge(Progress(captures = 3, points = 30, achievements = setOf("a")), Progress(captures = 5, points = 20, achievements = setOf("b")))
        assertEquals(5, m.captures); assertEquals(30, m.points); assertEquals(setOf("a", "b"), m.achievements)
    }
    @Test fun resetEpoch_winsOverStaleCloudData() {
        val m = Progress.merge(Progress(resetEpoch = 1), Progress(captures = 99, points = 999, resetEpoch = 0))
        assertEquals(0, m.captures)
    }
}

class AdsTest {
    private class Failing : AdsController {
        override val available = true
        override fun init(activity: Activity) { throw RuntimeException("sdk init failed") }
        override fun attachBanner(container: ViewGroup) { throw RuntimeException("banner failed") }
        override fun detachBanner() { throw RuntimeException("x") }
        override fun maybeShowInterstitial(activity: Activity, gameBusy: Boolean, onDone: () -> Unit) { throw RuntimeException("load failed") }
    }

    @Test fun adFailure_neverBreaksGame_andCallbackStillRuns() {
        val safe = SafeAdsController(Failing()); var done = 0
        safe.maybeShowInterstitial(Activity(), false) { done++ }
        assertEquals(1, done); assertEquals(1, safe.failures)
    }
    @Test fun afterRepeatedFailures_adsAreDisabled() {
        val safe = SafeAdsController(Failing(), maxFailures = 2); var done = 0
        repeat(5) { safe.maybeShowInterstitial(Activity(), false) { done++ } }
        assertEquals(5, done); assertFalse(safe.available)
    }
    @Test fun interstitial_notWhenBusyOrDisabledOrTooSoon() {
        val clock = FakeClock(); val p = AdPolicy(clock, 180_000L)
        assertFalse(p.canShowInterstitial(adsEnabled = false, adReady = true, gameBusy = false))
        assertFalse(p.canShowInterstitial(true, true, gameBusy = true))
        assertTrue(p.canShowInterstitial(true, true, false))
        p.markShown(); clock.now += 60_000L
        assertFalse(p.canShowInterstitial(true, true, false))
        clock.now += 130_000L
        assertTrue(p.canShowInterstitial(true, true, false))
    }
}

class FloatingTest {
    private class Env2(var allowed: Boolean, var failStart: Boolean = false) : OverlayEnvironment {
        var started = 0; var stopped = 0
        override fun canDrawOverlays() = allowed
        override fun startOverlay() { if (failStart) throw IllegalStateException("denied"); started++ }
        override fun stopOverlay() { stopped++ }
    }

    @Test fun permissionDenied_doesNotCrash_andDoesNotStart() {
        val env = Env(); val o = Env2(false); val c = FloatingModeController(o, env.settings)
        assertEquals(EnableResult.NEEDS_PERMISSION, c.enable()); assertEquals(0, o.started); assertFalse(env.settings.floatingEnabled)
    }
    @Test fun permissionGranted_starts_andDisableStops() {
        val env = Env(); val o = Env2(true); val c = FloatingModeController(o, env.settings)
        assertEquals(EnableResult.ENABLED, c.enable()); assertTrue(env.settings.floatingEnabled)
        c.disable(); assertEquals(1, o.stopped); assertFalse(env.settings.floatingEnabled)
    }
    @Test fun permissionRevokedLater_disablesQuietly() {
        val env = Env(); val o = Env2(true); val c = FloatingModeController(o, env.settings)
        c.enable(); o.allowed = false; c.onResumeCheck()
        assertFalse(env.settings.floatingEnabled); assertEquals(1, o.stopped)
    }
    @Test fun startFailure_isHandled() {
        val env = Env(); val c = FloatingModeController(Env2(true, failStart = true), env.settings)
        assertEquals(EnableResult.FAILED, c.enable()); assertFalse(env.settings.floatingEnabled)
    }
}
