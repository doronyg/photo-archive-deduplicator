package com.doronyg.photoarchivededuplicator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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

class IoLaneThreadTest {
    @Test
    fun jdbcTransactionRemainsOnItsOpeningThreadAndCheckpointWaits() = runBlocking {
        val lane = IoLane.dedicated("inventory-db-test")
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
            val checkpoint = async { repository.checkpointScanSession(ScanSessionRecord(root, 10)) }
            delay(20)
            assertTrue(!checkpoint.isCompleted)
            driver.release.complete(Unit)
            withTimeout(3000) { listing.await(); checkpoint.await() }
            assertEquals(10L, repository.currentScanSession()!!.activeMillis)
        } finally {
            driver.release.complete(Unit)
            lane.close { driver.close() }
        }
    }

    @Test
    fun databaseThreadIsPinnedAcrossSuspension() = runBlocking {
        val lane = IoLane.dedicated("inventory-db-test")
        val thread = try {
            lane.run("transaction") {
                val before = Thread.currentThread()
                delay(10)
                assertEquals(before, Thread.currentThread())
                assertTrue(before.name.contains("inventory-db-test"))
                before
            }
        } finally {
            lane.close {}
        }
        withTimeout(3000) {
            while (thread.isAlive) delay(5)
        }
    }

    @Test
    fun cancellationOfBlockingFilesystemCallCannotCloseLaneEarly() = runBlocking {
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
            val close = async { lane.close {} }
            delay(20)
            assertTrue(!close.isCompleted)
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
    fun blockingDatabaseDoesNotBlockFilesystemOrUi() = runBlocking {
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
