package com.doronyg.photoarchivededuplicator.scan

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.doronyg.photoarchivededuplicator.IoLane
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

/** Force a real suspension inside SQLDelight's otherwise synchronous JDBC transaction. */
internal class SuspendingQueryDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var suspendNextChildQuery = false
    var statements = 0

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        statements++
        if (suspendNextChildQuery && sql.startsWith("SELECT path FROM scanDirectory WHERE parentPath")) {
            suspendNextChildQuery = false
            return QueryResult.AsyncValue {
                val transaction = delegate.currentTransaction()
                check(transaction != null)
                entered.complete(Unit)
                release.await()
                check(delegate.currentTransaction() === transaction) { "Transaction changed across suspension" }
                delegate.executeQuery(identifier, sql, mapper, parameters, binders).await()
            }
        }
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DatabaseLaneTest {
    @Test
    fun tickerKeepsPublishingWhileCheckpointWaitsForTransaction() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val lane = IoLane("database-test", dispatcher)
        val driver = SuspendingQueryDriver(createInMemoryDriver())
        val repository = DirectoryRepository(createFilesDatabase(driver), databaseLane = lane)
        val root = "/archive".toPath()
        val scanner = object : InventoryScanner {
            override suspend fun startScan(root: okio.Path, onEvent: suspend (ScanEvent) -> Unit) {
                onEvent(ScanEvent.Started(1))
                repository.recordListing(root, DirectoryListing(emptyList(), emptyList()), 1)
            }
            override suspend fun resume(onEvent: suspend (ScanEvent) -> Unit) = Unit
        }
        try {
            repository.seedRoot(root)
            val controller = ScanController(scanner, this, repository, clock = { testScheduler.currentTime })
            runCurrent()
            driver.suspendNextChildQuery = true
            controller.start(root)
            driver.entered.await()
            advanceTimeBy(11_001)
            runCurrent()
            assertEquals(11_000L, assertIs<ScanState.Running>(controller.state.value).progress.elapsedMillis)
            driver.release.complete(Unit)
            advanceUntilIdle()
            assertIs<ScanState.Completed>(controller.state.value)
        } finally {
            driver.release.complete(Unit)
            lane.close { driver.close() }
        }
    }

    @Test
    fun checkpointAndUiQueryCannotEnterSuspendedDirectoryTransaction() = runTest {
        val lane = IoLane("database-test", StandardTestDispatcher(testScheduler))
        val driver = SuspendingQueryDriver(createInMemoryDriver())
        val repository = DirectoryRepository(createFilesDatabase(driver), databaseLane = lane)
        val root = "/archive".toPath()
        try {
            repository.seedRoot(root)
            repository.beginScanSession(root)
            driver.suspendNextChildQuery = true
            val listing = launch { repository.recordListing(root, DirectoryListing(emptyList(), emptyList()), 1) }
            driver.entered.await()
            val count = driver.statements
            val checkpoint = launch { repository.checkpointScanSession(ScanSessionRecord(root, 5000)) }
            val browse = async { repository.summary() }
            runCurrent()
            assertEquals(count, driver.statements, "No unrelated query may inherit a suspended JDBC transaction")
            assertFalse(browse.isCompleted)
            driver.release.complete(Unit)
            listing.join()
            checkpoint.join()
            assertEquals(1L, browse.await().directoryCount)
            assertEquals(5000L, repository.currentScanSession()!!.activeMillis)
            assertNull(driver.currentTransaction())
        } finally {
            driver.release.complete(Unit)
            lane.close { driver.close() }
        }
    }

    @Test
    fun cancellationRollsBackBeforeNextOperationAndNestedHelpersDoNotReenterLane() = runTest {
        val lane = IoLane("database-test", StandardTestDispatcher(testScheduler))
        val driver = SuspendingQueryDriver(createInMemoryDriver())
        val repository = DirectoryRepository(createFilesDatabase(driver), databaseLane = lane)
        val root = "/archive".toPath()
        try {
            repository.seedRoot(root) // exclusions helper
            repository.resumableScanSession(root) // directory + begin-session helpers
            driver.suspendNextChildQuery = true
            val listing = launch { repository.recordListing(root, DirectoryListing(emptyList(), emptyList()), 1) }
            driver.entered.await()
            listing.cancelAndJoin()
            assertNull(driver.currentTransaction())
            repository.checkpointScanSession(ScanSessionRecord(root, 123)) // current-session helper
            assertEquals(DirectoryStatus.PENDING, repository.directory(root)!!.status)
            assertEquals(root, repository.resumableScanSession().root)
            assertTrue(repository.allFiles().isEmpty())
        } finally {
            lane.close { driver.close() }
        }
    }
}
