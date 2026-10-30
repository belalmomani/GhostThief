package com.ghostthief.game

import android.app.Activity
import android.view.ViewGroup
import com.ghostthief.game.ads.AdsController
import com.ghostthief.game.ads.SafeAdsController
import com.ghostthief.game.game.Vehicles
import com.ghostthief.game.logic.BuyCheck
import com.ghostthief.game.logic.PlayPoints
import com.ghostthief.game.logic.Progress
import com.ghostthief.game.logic.RewardCheck
import com.ghostthief.game.logic.RewardPolicy
import com.ghostthief.game.logic.VehicleRules
import org.junit.Assert.*
import org.junit.Test

class GarageTest {
    @Test fun defaultVehicle_isAlwaysOwned() = assertTrue(VehicleRules.isUnlocked("default", Progress()))

    @Test fun pricesGrowWithEachVehicle() {
        val prices = VehicleRules.ALL.map { it.price }
        assertEquals(0, prices.first())
        for (i in 1 until prices.size) assertTrue(prices[i] > prices[i - 1])
    }

    @Test fun cannotBuyWithoutEnoughBalance() {
        assertEquals(BuyCheck.NOT_ENOUGH, VehicleRules.canBuy("sedan", Progress(points = 199)))
        assertEquals(BuyCheck.OK, VehicleRules.canBuy("sedan", Progress(points = 200)))
    }

    @Test fun cannotBuyASecondVehicleBeforeTheFirst_evenWithManyPoints() {
        val rich = Progress(points = 100_000)
        assertEquals(BuyCheck.NEED_PREVIOUS, VehicleRules.canBuy("suv", rich))
        assertEquals(BuyCheck.NEED_PREVIOUS, VehicleRules.canBuy("heli", rich))
        assertEquals(BuyCheck.OK, VehicleRules.canBuy("sedan", rich))
    }

    @Test fun buyingDeductsFromBalance_andUnlocksTheNextOne() {
        var p = Progress(points = 1000)
        p = VehicleRules.buy("sedan", p)!!
        assertEquals(800, p.balance); assertTrue(VehicleRules.isUnlocked("sedan", p))
        assertEquals(BuyCheck.OK, VehicleRules.canBuy("suv", p))          // بعد الأولى تصبح الثانية قابلة للشراء
        p = VehicleRules.buy("suv", p)!!
        assertEquals(300, p.balance)
        assertNull(VehicleRules.buy("suv", p))                              // لا شراء مرتين
        assertEquals(BuyCheck.ALREADY_OWNED, VehicleRules.canBuy("suv", p))
    }

    @Test fun repositoryBuy_persistsAndMarksDirty() {
        val env = Env(); env.repo.awardPoints(500)
        assertEquals(BuyCheck.NEED_PREVIOUS, env.repo.buyVehicle("suv"))
        assertEquals(BuyCheck.OK, env.repo.buyVehicle("sedan"))
        val p = env.repo.load()
        assertEquals(300, p.balance); assertEquals(200, p.spent); assertTrue(env.repo.dirty)
        assertEquals("sedan", Vehicles.effective("sedan", p).id)
    }

    @Test fun mergeKeepsTheLargerSpent_soAPurchaseIsNeverUndone() {
        val m = Progress.merge(Progress(points = 500, spent = 200, achievements = setOf("veh_sedan")), Progress(points = 500, spent = 0))
        assertEquals(200, m.spent); assertTrue("veh_sedan" in m.achievements)
    }

    @Test fun resetClearsPurchases() {
        val env = Env(); env.repo.awardPoints(500); env.repo.buyVehicle("sedan"); env.repo.resetProgress()
        val p = env.repo.load()
        assertEquals(0, p.spent); assertFalse(VehicleRules.isUnlocked("sedan", p))
        assertEquals("default", Vehicles.effective("sedan", p).id)
    }

    @Test fun everyVehicleInTheRulesHasADrawSpec() {
        for (v in VehicleRules.ALL) assertEquals(v.id, Vehicles.byId(v.id).id)
    }

    @Test fun rewardPolicy_dailyLimitAndCooldown() {
        val now = 10_000_000_000L; val day0 = RewardPolicy.dayOf(now)
        assertEquals(RewardCheck.OK, RewardPolicy.check(now, 0, 0, 0))
        assertEquals(RewardCheck.COOLDOWN, RewardPolicy.check(now + 60_000L, day0, 1, now))
        assertEquals(RewardCheck.OK, RewardPolicy.check(now + RewardPolicy.COOLDOWN_MS + 1, day0, 1, now))
        assertEquals(RewardCheck.DAILY_LIMIT, RewardPolicy.check(now + 3_600_000L, day0, 3, now))
        assertEquals(RewardCheck.OK, RewardPolicy.check(now + 90_000_000L, day0, 3, now)) // يوم جديد
        assertEquals(3, RewardPolicy.remainingToday(now, 0, 0))
    }

    @Test fun playingEarnsAPointEvery30Seconds() {
        val env = Env(); val e = env.engine()
        advanceEngine(e, PlayPoints.SECONDS_PER_POINT + 1f)
        assertEquals(1, env.repo.load().points)
    }

    @Test fun playPoints_haveADailyCap() {
        val env = Env(); val e = env.engine()
        env.settings.playPointsDay = RewardPolicy.dayOf(env.clock.nowMillis()); env.settings.playPointsToday = PlayPoints.MAX_PER_DAY
        advanceEngine(e, 70f)
        assertEquals(0, env.repo.load().points)
    }

    @Test fun engine_usesTheEquippedVehicle_andItsWidth() {
        val env = Env(); env.settings.vehicleId = "heli"
        assertEquals("default", env.engine().vehicle.id)            // غير مملوكة
        env.repo.unlock(VehicleRules.unlockKey("heli"))
        val e = env.engine()
        assertEquals("heli", e.vehicle.id)
        assertTrue(e.squad.carWidth > e.thiefRadius * 4.1f)           // المروحية أعرض
        env.settings.vehicleId = "moto"; env.repo.unlock(VehicleRules.unlockKey("moto"))
        val mo = env.engine(); assertTrue(mo.squad.carWidth < mo.thiefRadius * 4.1f) // الدراجة أصغر
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
