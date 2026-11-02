package com.doronyg.photoarchivededuplicator

import java.util.concurrent.atomic.AtomicLong

internal actual class LogCounter {
    private val value = AtomicLong()
    actual fun increment(): Long = value.incrementAndGet()
    actual fun get(): Long = value.get()
}
internal actual fun diagnosticThread(): String = "JVM/${Thread.currentThread().name}"
actual fun diagnosticLogDirectory(): String? = (desktopDataDirectory() / "logs").toString()
internal actual fun platformLogFile(): DiagnosticFileSink? =
    RotatingLogFile({ java.io.File(requireNotNull(diagnosticLogDirectory())) })
internal actual fun platformLog(entry: LogEntry) {
    if (entry.level >= LogLevel.W) System.err.println(formatLog(entry))
    else System.out.println(formatLog(entry))
}
