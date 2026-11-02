package com.doronyg.photoarchivededuplicator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.doronyg.photoarchivededuplicator.scan.DirectoryListing
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.scan.ScanSessionRecord
import com.doronyg.photoarchivededuplicator.scan.SuspendingQueryDriver
import com.doronyg.photoarchivededuplicator.scan.createFilesDatabase
import com.doronyg.photoarchivededuplicator.scan.createInMemoryDriver
import okio.Path.Companion.toPath
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class IoLaneThreadTest {
    private fun threadedTest(block: suspend CoroutineScope.() -> Unit) =
        runBlocking { withTimeout(10_000, block) }

    @Test
    fun jdbcTransactionRemainsOnItsOpeningThreadAndCheckpointWaits() = threadedTest {
        val executor = IoThread("inventory-db-test")
        val lane = IoLane("inventory-db-test", executor.dispatcher, executor::close)
        // SQLite :memory: is connection-local: open/schema/query/close on the same lane.
        val driver = lane.run("open") { SuspendingQueryDriver(createInMemoryDriver()) }
        val repository = DirectoryRepository(createFilesDatabase(driver), databaseLane = lane)
        val root = "/archive".toPath()
        try {
            repository.seedRoot(root)
            repository.beginScanSession(root)
            driver.suspendNextChildQuery = true
            val listing = async { repository.recordListing(root, DirectoryListing(emptyList(), emptyList()), 1) }
            withTimeout(3000) { driver.entered.await() }
            val blockedStatementCount = withContext(executor.dispatcher) { driver.statements }
            val checkpoint = async(start = CoroutineStart.UNDISPATCHED) {
                repository.checkpointScanSession(ScanSessionRecord(root, 10))
            }
            // Drain dispatched work while the transaction is still suspended.
            withContext(executor.dispatcher) {
                assertEquals(blockedStatementCount, driver.statements)
            }
            assertFalse(checkpoint.isCompleted)
            driver.release.complete(Unit)
            withTimeout(3000) { listing.await(); checkpoint.await() }
            assertEquals(10L, repository.currentScanSession()!!.activeMillis)
        } finally {
            driver.release.complete(Unit)
            lane.close { driver.close() }
        }
    }

    @Test
    fun databaseThreadIsPinnedAcrossSuspension() = threadedTest {
        val executor = IoThread("inventory-db-test")
        val lane = IoLane("inventory-db-test", executor.dispatcher, executor::close)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val thread = try {
            val transaction = async {
                lane.run("transaction") {
                    val before = Thread.currentThread()
                    entered.complete(Unit)
                    release.await()
                    assertEquals(before, Thread.currentThread())
                    assertTrue(before.name.contains("inventory-db-test"))
                    before
                }
            }
            entered.await()
            // This queued task can run only after the transaction suspends on the same thread.
            withContext(executor.dispatcher) {}
            assertFalse(transaction.isCompleted)
            release.complete(Unit)
            transaction.await()
        } finally {
            release.complete(Unit)
            lane.close {}
        }
        withContext(Dispatchers.IO) {
            thread.join(3000)
            assertFalse(thread.isAlive)
        }
    }

    @Test
    fun cancellationOfBlockingFilesystemCallCannotCloseLaneEarly() = threadedTest {
        val lane = IoLane.dedicated("inventory-filesystem-test")
        val database = IoLane.dedicated("inventory-db-test")
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val listing = async(Dispatchers.Default) {
            lane.run("blocking listing") {
                entered.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        try {
            entered.await()
            assertEquals(42, withTimeout(3000) { database.run("query") { 42 } })
            assertEquals("responsive", async(Dispatchers.Default) { "responsive" }.await())
            listing.cancel()
            val close = async(start = CoroutineStart.UNDISPATCHED) { lane.close {} }
            assertFalse(close.isCompleted)
            release.countDown()
            close.await()
        } finally {
            release.countDown()
            withContext(Dispatchers.Default) { listing.join() }
            lane.close {}
            database.close {}
        }
    }

    @Test
    fun blockingDatabaseDoesNotBlockFilesystemOrUi() = threadedTest {
        val database = IoLane.dedicated("inventory-db-test")
        val filesystem = IoLane.dedicated("inventory-filesystem-test")
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val query = async {
            database.run("blocking query") {
                entered.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        try {
            entered.await()
            assertEquals("listing", withTimeout(3000) { filesystem.run("listing") { "listing" } })
            assertEquals("responsive", async { "responsive" }.await())
        } finally {
            release.countDown()
            query.await()
            database.close {}
            filesystem.close {}
        }
    }
}
