package com.ghostthief.game.cloud

import com.ghostthief.game.data.ProgressRepository
import com.ghostthief.game.data.Settings
import com.ghostthief.game.logic.Progress

enum class SyncStatus { DISABLED, NOT_CONFIGURED, SYNCING, SYNCED, OFFLINE_PENDING }

/** واجهة الخلفية السحابية. التنفيذ الحقيقي: FirebaseBackend. */
interface CloudBackend {
    val isConfigured: Boolean
    /** يرجع null إذا لا توجد وثيقة بعد. */
    fun fetch(callback: (Result<Progress?>) -> Unit)
    fun push(progress: Progress, callback: (Result<Unit>) -> Unit)
}

/**
 * المزامنة: محلي أولاً دائماً. عند فشل الشبكة تبقى البيانات محلية مع علامة dirty
 * وتُعاد المحاولة لاحقاً. الدمج يأخذ الأكبر (لا جمع) فلا تتكرر النقاط.
 */
class SyncManager(
    private val repo: ProgressRepository,
    private val settings: Settings,
    private val backend: CloudBackend
) {
    @Volatile var status: SyncStatus = SyncStatus.DISABLED
        private set
    @Volatile private var syncing = false
    /** سبب آخر فشل (للتشخيص: مثل PERMISSION_DENIED أو مشكلة Auth). */
    @Volatile var lastError: String? = null
        private set

    fun currentStatus(): SyncStatus = when {
        !settings.cloudSyncEnabled -> SyncStatus.DISABLED
        !backend.isConfigured -> SyncStatus.NOT_CONFIGURED
        else -> status
    }

    fun syncNow(onDone: (SyncStatus) -> Unit = {}) {
        if (!settings.cloudSyncEnabled) { status = SyncStatus.DISABLED; onDone(status); return }
        if (!backend.isConfigured) { status = SyncStatus.NOT_CONFIGURED; onDone(status); return }
        if (syncing) { onDone(status); return }
        syncing = true
        status = SyncStatus.SYNCING
        try {
            backend.fetch { fetched ->
                try {
                    fetched.onFailure { fail(onDone, it) }
                    fetched.onSuccess { remote ->
                        val merged = if (remote == null) repo.load() else Progress.merge(repo.load(), remote)
                        repo.save(merged)
                        backend.push(merged) { pushed ->
                            pushed.onSuccess { repo.dirty = false; lastError = null; finish(SyncStatus.SYNCED, onDone) }
                            pushed.onFailure { fail(onDone, it) }
                        }
                    }
                } catch (t: Throwable) { fail(onDone, t) }
            }
        } catch (t: Throwable) {
            fail(onDone, t)
        }
    }

    private fun fail(onDone: (SyncStatus) -> Unit, t: Throwable? = null) {
        lastError = t?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty().take(140)}" }
        repo.dirty = true
        finish(SyncStatus.OFFLINE_PENDING, onDone)
    }

    private fun finish(s: SyncStatus, onDone: (SyncStatus) -> Unit) {
        status = s
        syncing = false
        onDone(s)
    }
}
