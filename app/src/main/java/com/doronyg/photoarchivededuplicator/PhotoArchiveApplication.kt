package com.doronyg.photoarchivededuplicator

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** Holds the single [AppContainer]; it lives as long as the process, so it is never closed. */
class PhotoArchiveApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.logger.i(LogTags.LIFECYCLE, "Android process starting")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val container by lazy { scope.async { createAndroidContainer(this@PhotoArchiveApplication) } }

    suspend fun container(): AppContainer = container.await()
}
