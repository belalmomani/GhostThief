package com.ghostthief.game

import android.app.Activity
import android.view.ViewGroup
import com.ghostthief.game.ads.AdsController
import com.ghostthief.game.ads.SafeAdsController
import com.ghostthief.game.game.Vehicles
import com.ghostthief.game.logic.Progress
import com.ghostthief.game.logic.RewardCheck
import com.ghostthief.game.logic.RewardPolicy
import com.ghostthief.game.logic.VehicleRules
import org.junit.Assert.*
import org.junit.Test

class GarageTest {
    @Test fun defaultVehicle_isAlwaysUnlocked() = assertTrue(VehicleRules.isUnlocked("default", Progress()))

    @Test fun vehicleUnlocksByPointsThreshold_withoutSpendingPoints() {
        assertFalse(VehicleRules.isUnlocked("sedan", Progress(points = 99)))
        assertTrue(VehicleRules.isUnlocked("sedan", Progress(points = 100)))
    }

    @Test fun vehicleUnlocksByRewardedAd_evenWithFewPoints() {
        val p = Progress(points = 0, achievements = setOf(VehicleRules.unlockKey("heli")))
        assertTrue(VehicleRules.isUnlocked("heli", p)); assertFalse(VehicleRules.isUnlocked("sport", p))
    }

    @Test fun unknownVehicle_isLocked() = assertFalse(VehicleRules.isUnlocked("tank", Progress(points = 99999)))

    @Test fun equippedVehicleFallsBackToDefault_whenLocked() {
        assertEquals("default", Vehicles.effective("heli", Progress()).id)
        assertEquals("heli", Vehicles.effective("heli", Progress(points = 2000)).id)
    }

    @Test fun everyVehicleInTheRulesHasADrawSpec() {
        for (v in VehicleRules.ALL) assertEquals(v.id, Vehicles.byId(v.id).id)
    }

    @Test fun rewardPolicy_dailyLimitAndCooldown() {
        val day0 = RewardPolicy.dayOf(10_000_000_000L); val now = 10_000_000_000L
        assertEquals(RewardCheck.OK, RewardPolicy.check(now, 0, 0, 0))
        assertEquals(RewardCheck.COOLDOWN, RewardPolicy.check(now + 60_000L, day0, 1, now))
        assertEquals(RewardCheck.OK, RewardPolicy.check(now + RewardPolicy.COOLDOWN_MS + 1, day0, 1, now))
        assertEquals(RewardCheck.DAILY_LIMIT, RewardPolicy.check(now + 3_600_000L, day0, 3, now))
        // يوم جديد: يبدأ العدّاد من الصفر
        assertEquals(RewardCheck.OK, RewardPolicy.check(now + 90_000_000L, day0, 3, now))
        assertEquals(3, RewardPolicy.remainingToday(now, 0, 0))
    }

    @Test fun engine_usesTheEquippedVehicle_andItsWidth() {
        val env = Env(); env.settings.vehicleId = "heli"
        assertEquals("default", env.engine().vehicle.id)            // مقفلة
        env.repo.unlock(VehicleRules.unlockKey("heli"))
        val e = env.engine()
        assertEquals("heli", e.vehicle.id)
        assertTrue(e.squad.carWidth > e.thiefRadius * 4.1f)           // المروحية أعرض
        env.settings.vehicleId = "moto"; env.repo.unlock(VehicleRules.unlockKey("moto"))
        val m = env.engine(); assertTrue(m.squad.carWidth < m.thiefRadius * 4.1f) // الدراجة أصغر
    }

    private class Failing : AdsController {
        override val available = true
        override val rewardedSupported = true
        override fun init(activity: Activity) {}
        override fun attachBanner(container: ViewGroup) {}
        override fun detachBanner() {}
        override fun maybeShowInterstitial(activity: Activity, gameBusy: Boolean, onDone: () -> Unit) { onDone() }
        override fun showRewarded(activity: Activity, onReward: () -> Unit, onFail: () -> Unit) { throw RuntimeException("rewarded failed") }
    }

    @Test fun rewardedAdFailure_neverGrantsAndNeverCrashes() {
        val safe = SafeAdsController(Failing()); var rewards = 0; var fails = 0
        safe.showRewarded(Activity(), { rewards++ }, { fails++ })
        assertEquals(0, rewards); assertEquals(1, fails); assertEquals(1, safe.failures)
    }

    @Test fun noAdsVersion_rewardedIsUnavailable() {
        val noAds = object : AdsController {
            override val available = false
            override fun init(activity: Activity) {}
            override fun attachBanner(container: ViewGroup) {}
            override fun detachBanner() {}
            override fun maybeShowInterstitial(activity: Activity, gameBusy: Boolean, onDone: () -> Unit) { onDone() }
        }
        var failed = false
        noAds.showRewarded(Activity(), { fail("must not reward") }, { failed = true })
        assertTrue(failed); assertFalse(noAds.rewardedSupported)
    }
}
