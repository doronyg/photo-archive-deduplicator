package com.doronyg.photoarchivededuplicator

import android.app.Application

/** Holds the single [AppContainer]; it lives as long as the process, so it is never closed. */
class PhotoArchiveApplication : Application() {
    val container: AppContainer by lazy { createAndroidContainer(this) }
}
