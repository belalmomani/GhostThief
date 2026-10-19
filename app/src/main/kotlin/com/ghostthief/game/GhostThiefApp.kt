package com.ghostthief.game

import android.app.Application
import android.content.Context
import com.ghostthief.game.ads.AdsController
import com.ghostthief.game.ads.AdsFactory
import com.ghostthief.game.cloud.FirebaseBackend
import com.ghostthief.game.cloud.SyncManager
import com.ghostthief.game.data.PrefsKeyValueStore
import com.ghostthief.game.data.ProgressRepository
import com.ghostthief.game.data.Settings
import com.ghostthief.game.logic.ParentLock

class GhostThiefApp : Application() {
    lateinit var settings: Settings; private set
    lateinit var progress: ProgressRepository; private set
    lateinit var sync: SyncManager; private set
    lateinit var parentLock: ParentLock; private set
    val ads: AdsController by lazy { AdsFactory.create(this, settings) }

    override fun onCreate() {
        super.onCreate()
        val kv = PrefsKeyValueStore(this)
        settings = Settings(kv)
        progress = ProgressRepository(kv)
        parentLock = ParentLock(kv)
        sync = SyncManager(progress, settings, FirebaseBackend(this))
    }

    companion object {
        fun of(context: Context) = context.applicationContext as GhostThiefApp
    }
}
