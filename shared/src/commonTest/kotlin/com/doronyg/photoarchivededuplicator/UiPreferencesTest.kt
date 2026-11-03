package com.doronyg.photoarchivededuplicator

import app.cash.sqldelight.db.SqlDriver
import com.doronyg.photoarchivededuplicator.scan.ConcurrencyTestDriver
import com.doronyg.photoarchivededuplicator.scan.DuplicateFileType
import com.doronyg.photoarchivededuplicator.scan.SortDirection
import com.doronyg.photoarchivededuplicator.scan.defaultDuplicateSortRules
import com.doronyg.photoarchivededuplicator.scan.InventoryScanner
import com.doronyg.photoarchivededuplicator.scan.IoGuardedDriver
import com.doronyg.photoarchivededuplicator.scan.ScanEvent
import com.doronyg.photoarchivededuplicator.scan.TrackingIoDispatcher
import com.doronyg.photoarchivededuplicator.scan.createInMemoryDriver
import com.doronyg.photoarchivededuplicator.scan.concurrencyTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

internal val preferencesTestScanner = object : InventoryScanner {
    override suspend fun startScan(root: Path, onEvent: suspend (ScanEvent) -> Unit) = Unit
    override suspend fun resume(onEvent: suspend (ScanEvent) -> Unit) = Unit
}

internal val allUiChoices = UiChoices(
    selectedTab = UiTab.DUPLICATES,
    scanRoot = "/写真\nscan",
    browseStack = listOf("/写真", "/写真/a\nb", "/写真/a\nb/\\|;☃"),
    duplicateRoot = "/duplicates\nroot",
    duplicateFileType = DuplicateFileType.VIDEOS,
    duplicateFilenamePattern = "雪*?.mp4",
    duplicateSort = defaultDuplicateSortRules().reversed().mapIndexed { index, rule ->
        rule.copy(enabled = index % 2 == 0, direction = SortDirection.DESCENDING)
    },
    logFilter = "database\nfailure",
    logMinimum = LogLevel.W,
    logFollow = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class UiPreferencesTest {
    @Test
    fun suspectedDuplicatesTabIsAdjacentAndPersistsByNameWithoutChangingExistingSelections() {
        assertEquals(UiTab.SUSPECTED_DUPLICATES, UiTab.entries[UiTab.DUPLICATES.ordinal + 1])
        for (tab in UiTab.entries) {
            val choices = UiChoices(selectedTab = tab)
            assertEquals(choices, decodeUiChoices(encodeUiChoices(choices)))
            assertEquals(tab.name, encodeUiChoices(choices)["selectedTab"])
        }
    }

    @Test
    fun unchangedInstanceAndEquivalentCopyDoNotWriteSettings() = runTest(timeout = 10.seconds) {
        val worker = StandardTestDispatcher(testScheduler)
        val io = TrackingIoDispatcher(worker)
        var writes = 0
        val container = createAppContainer(io, worker, openDriver = {
            IoGuardedDriver(createInMemoryDriver(), io) {
                if (it.startsWith("INSERT INTO uiPreference") || it.startsWith("DELETE FROM uiPreference")) writes++
            }
        }, createScanner = { _, _ -> preferencesTestScanner })
        try {
            container.uiPreferences.update { it }
            container.uiPreferences.update { it.copy() }
            container.uiPreferences.shutdown()
            assertEquals(0, writes)
            assertEquals(UiChoices(), container.repository.loadUiChoices())
        } finally {
            container.shutdown()
        }
    }

    @Test
    fun defaultsAndRapidIndependentUpdatesPersistLatestSnapshotOffCallerLane() = runTest(timeout = 10.seconds) {
        val worker = StandardTestDispatcher(testScheduler)
        val io = TrackingIoDispatcher(worker)
        val caller = TrackingIoDispatcher(worker)
        val container = createAppContainer(io, worker, openDriver = {
            IoGuardedDriver(createInMemoryDriver(), io) {
                assertFalse(caller.isRunningTask, "Settings SQL must not execute on caller dispatcher")
            }
        }, createScanner = { _, _ -> preferencesTestScanner })
        try {
            assertEquals(UiChoices(), container.uiPreferences.state.value)
            assertNull(container.uiPreferences.error.value)
            kotlinx.coroutines.withContext(caller) {
                repeat(100) { index ->
                    container.uiPreferences.update { it.copy(scanRoot = "/scan/$index") }
                    container.uiPreferences.update { it.copy(logFilter = "filter $index") }
                    container.uiPreferences.update { it.copy(selectedTab = UiTab.BROWSE) }
                }
            }
            container.uiPreferences.shutdown()
            val expected = UiChoices(selectedTab = UiTab.BROWSE, scanRoot = "/scan/99", logFilter = "filter 99")
            assertEquals(expected, container.uiPreferences.state.value)
            assertEquals(expected, container.repository.loadUiChoices())
            assertFailsWith<IllegalStateException> {
                container.uiPreferences.update { it.copy(scanRoot = "/too late") }
            }
            assertEquals(expected, container.uiPreferences.state.value)
        } finally {
            container.shutdown()
        }
    }

    @Test
    fun shutdownDrainsInFlightAndConflatedPendingWritesBeforeDriverClose() = runTest(timeout = 10.seconds) {
        val worker = StandardTestDispatcher(testScheduler)
        val io = TrackingIoDispatcher(worker)
        lateinit var driver: ConcurrencyTestDriver
        var closed = false
        var inserts = 0
        val container = createAppContainer(io, worker, openDriver = {
            driver = ConcurrencyTestDriver(IoGuardedDriver(createInMemoryDriver(), io))
            driver.beforeStatement = { if (it.startsWith("INSERT")) inserts++ }
            object : SqlDriver by driver {
                override fun close() {
                    assertNull(driver.currentTransaction())
                    assertEquals(2 * encodeUiChoices(allUiChoices).size, inserts,
                        "The in-flight snapshot and only the latest pending snapshot must commit before close")
                    closed = true
                    driver.close()
                }
            }
        }, createScanner = { _, _ -> preferencesTestScanner })
        try {
            driver.pauseAfter = { it.startsWith("DELETE FROM uiPreference") }
            container.uiPreferences.update { allUiChoices }
            driver.entered.await()
            repeat(100) { index ->
                container.uiPreferences.update { it.copy(scanRoot = "/latest/$index") }
            }
            val shutdown = launch(start = CoroutineStart.UNDISPATCHED) { container.shutdown() }
            runCurrent()
            assertFalse(shutdown.isCompleted)
            assertFalse(closed)
            shutdown.cancel()
            val secondShutdown = async { container.shutdown() }
            runCurrent()
            assertFalse(secondShutdown.isCompleted)
            driver.release.complete(Unit)
            shutdown.join()
            secondShutdown.await()
            assertTrue(closed)
        } finally {
            driver.release.complete(Unit)
            container.shutdown()
        }
    }

    @Test
    fun failedSnapshotRollsBackSurfacesErrorAndLaterEditRecovers() = runTest(timeout = 10.seconds) {
        val worker = StandardTestDispatcher(testScheduler)
        val io = TrackingIoDispatcher(worker)
        var failInsert = false
        val container = createAppContainer(io, worker, openDriver = {
            IoGuardedDriver(createInMemoryDriver(), io) {
                if (failInsert && it.startsWith("INSERT INTO uiPreference")) error("disk full")
            }
        }, createScanner = { _, _ -> preferencesTestScanner })
        try {
            container.uiPreferences.update { allUiChoices }
            runCurrent()
            assertEquals(allUiChoices, container.repository.loadUiChoices())
            failInsert = true
            container.uiPreferences.update { it.copy(scanRoot = "/unsaved") }
            runCurrent()
            assertTrue(container.uiPreferences.error.value!!.contains("disk full"))
            assertEquals("/unsaved", container.uiPreferences.state.value.scanRoot)
            assertEquals(allUiChoices, container.repository.loadUiChoices(), "Delete and inserts must roll back together")
            failInsert = false
            container.uiPreferences.update { it.copy(logFilter = "recovered") }
            container.uiPreferences.shutdown()
            assertNull(container.uiPreferences.error.value)
            assertEquals(allUiChoices.copy(scanRoot = "/unsaved", logFilter = "recovered"),
                container.repository.loadUiChoices())
        } finally {
            container.shutdown()
        }
    }

    @Test
    fun unknownEnumsWarnAndDefaultOnlyAffectedFields() {
        val warnings = mutableListOf<String>()
        val entries = encodeUiChoices(allUiChoices).toMutableMap().apply {
            this["selectedTab"] = "FUTURE_TAB"
            this["duplicateFileType"] = "FUTURE_TYPE"
            this["logMinimum"] = "FUTURE_LEVEL"
        }
        assertEquals(allUiChoices.copy(selectedTab = UiTab.SCAN,
            duplicateFileType = DuplicateFileType.ALL, logMinimum = LogLevel.D),
            decodeUiChoices(entries, warnings::add))
        assertEquals(3, warnings.size)
        assertTrue(warnings.any { "FUTURE_TAB" in it })
        assertTrue(warnings.any { "FUTURE_TYPE" in it })
        assertTrue(warnings.any { "FUTURE_LEVEL" in it })
    }

    @Test
    fun cancelledSnapshotTransactionRollsBackBeforeLaneIsReused() = concurrencyTest { fixture ->
        fixture.repository.saveUiChoices(allUiChoices)
        val job = Job()
        val preferences = UiPreferences(allUiChoices, CoroutineScope(job + fixture.dispatcher),
            fixture.repository::saveUiChoices)
        try {
            fixture.driver.pauseAfter = { it.startsWith("INSERT INTO uiPreference") }
            preferences.update { it.copy(scanRoot = "/interrupted") }
            fixture.driver.entered.await()
            job.cancelAndJoin()
            fixture.lane.run("assert rollback complete") { assertNull(fixture.driver.currentTransaction()) }
            assertEquals(allUiChoices, fixture.repository.loadUiChoices())
            assertNull(preferences.error.value, "Cancellation must propagate rather than masquerade as save failure")
        } finally {
            fixture.driver.release.complete(Unit)
            job.cancelAndJoin()
            preferences.shutdown()
        }
    }

    @Test
    fun failedSettingsReadFailsStartupAndClosesDriver() = runTest(timeout = 10.seconds) {
        val worker = StandardTestDispatcher(testScheduler)
        val io = TrackingIoDispatcher(worker)
        var closed = false
        assertFailsWith<IllegalStateException> {
            createAppContainer(io, worker, openDriver = {
                val guarded = IoGuardedDriver(createInMemoryDriver(), io) {
                    if ("FROM uiPreference" in it) error("settings read failed")
                }
                object : SqlDriver by guarded {
                    override fun close() {
                        closed = true
                        guarded.close()
                    }
                }
            }, createScanner = { _, _ -> preferencesTestScanner })
        }
        assertTrue(closed)
    }
}
