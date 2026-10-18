package com.ghostthief.game

import com.ghostthief.game.cloud.CloudBackend
import com.ghostthief.game.data.InMemoryKeyValueStore
import com.ghostthief.game.data.ProgressRepository
import com.ghostthief.game.data.Settings
import com.ghostthief.game.game.GameEngine
import com.ghostthief.game.logic.Clock
import com.ghostthief.game.logic.Progress
import com.ghostthief.game.logic.ThiefBrain

class FakeClock(var now: Long = 1_000_000L) : Clock { override fun nowMillis() = now }

fun advance(brain: ThiefBrain, seconds: Float, step: Float = 0.05f) {
    var t = 0f
    while (t < seconds) { brain.update(step); t += step }
}

fun advanceUntil(brain: ThiefBrain, state: com.ghostthief.game.logic.ThiefState, maxSeconds: Float = 10f, step: Float = 0.05f): Boolean {
    var t = 0f
    while (t < maxSeconds) { if (brain.state == state) return true; brain.update(step); t += step }
    return brain.state == state
}

fun advanceEngine(e: GameEngine, seconds: Float, step: Float = 0.05f) {
    var t = 0f
    while (t < seconds) { e.update(step); t += step }
}

class Env(val clock: FakeClock = FakeClock(), val kv: InMemoryKeyValueStore = InMemoryKeyValueStore()) {
    val settings = Settings(kv)
    val repo = ProgressRepository(kv)
    fun engine(seed: Int = 1): GameEngine = GameEngine(settings, repo, clock, kotlin.random.Random(seed)).also { it.setArena(800f, 600f, 1f) }
}

class FakeBackend(
    override var isConfigured: Boolean = true,
    var online: Boolean = true,
    var throwOnFetch: Boolean = false,
    var remote: Progress? = null
) : CloudBackend {
    var pushes = 0
    override fun fetch(callback: (Result<Progress?>) -> Unit) {
        if (throwOnFetch) throw IllegalStateException("boom")
        if (online) callback(Result.success(remote)) else callback(Result.failure(java.io.IOException("offline")))
    }
    override fun push(progress: Progress, callback: (Result<Unit>) -> Unit) {
        if (online) { pushes++; remote = progress; callback(Result.success(Unit)) } else callback(Result.failure(java.io.IOException("offline")))
    }
}
