package com.doronyg.photoarchivededuplicator

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal expect class IoThread(name: String) {
    val dispatcher: CoroutineDispatcher
    fun close()
}

/**
 * The mutex covers the entire suspending operation, not individual dispatches. This is
 * essential for JDBC's thread-local transactions: no other operation may use the thread
 * while a transaction is suspended. Never call run recursively; use private in-lane helpers.
 * The dispatcher stays private so work cannot bypass the mutex or race driver shutdown.
 */
internal class IoLane(
    private val name: String,
    private val dispatcher: CoroutineDispatcher,
    private val closeDispatcher: () -> Unit = {},
) {
    private val mutex = Mutex()
    private var closed = false

    suspend fun <T> run(operation: String, block: suspend () -> T): T =
        Diagnostics.logger.operation(LogTags.IO, "$name wait/hold $operation") {
            mutex.withLock {
                check(!closed) { "$name is closed" }
                withContext(dispatcher) {
                    Diagnostics.logger.operation(LogTags.IO, "$name executing $operation") { block() }
                }
            }
        }

    suspend fun close(closeResource: () -> Unit) = withContext(NonCancellable) {
        mutex.withLock {
            if (closed) return@withLock
            closed = true
            try {
                withContext(dispatcher) { closeResource() }
            } finally {
                closeDispatcher()
            }
        }
    }

    companion object {
        fun dedicated(name: String): IoLane {
            val thread = IoThread(name)
            return IoLane(name, thread.dispatcher, thread::close)
        }
    }
}
