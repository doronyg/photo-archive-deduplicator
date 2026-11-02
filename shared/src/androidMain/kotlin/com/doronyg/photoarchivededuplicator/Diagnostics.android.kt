package com.doronyg.photoarchivededuplicator

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

internal actual class LogCounter {
    private val value = AtomicLong()
    actual fun increment(): Long = value.incrementAndGet()
    actual fun get(): Long = value.get()
}
internal actual fun diagnosticThread(): String = "Android/${Thread.currentThread().name}"
actual fun diagnosticLogDirectory(): String? = null
internal actual fun platformLogFile(): DiagnosticFileSink? = null
internal actual fun platformLog(entry: LogEntry) {
    val priority = when (entry.level) {
        LogLevel.D -> Log.DEBUG
        LogLevel.I -> Log.INFO
        LogLevel.W -> Log.WARN
        LogLevel.E -> Log.ERROR
    }
    Log.println(priority, entry.tag.take(23), formatLog(entry))
}
