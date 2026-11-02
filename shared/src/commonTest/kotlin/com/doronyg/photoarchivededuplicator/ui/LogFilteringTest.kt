package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.LogEntry
import com.doronyg.photoarchivededuplicator.LogLevel
import com.doronyg.photoarchivededuplicator.scan.TrackingIoDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LogFilteringTest {
    @Test
    fun largeMessagesAreFilteredOffTheCallerThread() = runTest {
        val worker = TrackingIoDispatcher(StandardTestDispatcher(testScheduler))
        val entries = object : AbstractList<LogEntry>() {
            override val size = 2000
            override fun get(index: Int): LogEntry {
                worker.assertOnIo("log filtering")
                return LogEntry(index.toLong(), 0, if (index % 2 == 0) LogLevel.W else LogLevel.D,
                    "Database", "inventory-db", "x".repeat(8192))
            }
        }
        val result = filterLogEntries(entries, "DATABASE", LogLevel.W, worker)
        assertEquals(1000, result.size)
        assertEquals(0L, result.first().id)
        assertEquals(1998L, result.last().id)
    }
}
