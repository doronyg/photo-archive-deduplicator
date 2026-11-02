package com.doronyg.photoarchivededuplicator

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

internal actual class IoThread actual constructor(name: String) {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, name).apply { isDaemon = true }
    }.asCoroutineDispatcher()
    actual val dispatcher: CoroutineDispatcher get() = executor
    actual fun close() = executor.close()
}
