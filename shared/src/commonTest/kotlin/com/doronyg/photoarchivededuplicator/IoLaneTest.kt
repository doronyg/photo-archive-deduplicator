package com.doronyg.photoarchivededuplicator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class IoLaneTest {
    @Test
    fun exceptionReleasesLaneBeforeQueuedOperationExecutes() = runTest(timeout = 10.seconds) {
        val lane = IoLane("test-db", StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val owner = async {
            assertFailsWith<IllegalStateException> {
                lane.run("failing owner") {
                    events += "begin"
                    entered.complete(Unit)
                    release.await()
                    try {
                        error("write failed")
                    } finally {
                        events += "rollback"
                    }
                }
            }
        }
        try {
            entered.await()
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                lane.run("queued read") {
                    events += "read"
                    42
                }
            }
            runCurrent()
            assertFalse(queued.isCompleted)
            assertEquals(listOf("begin"), events)
            release.complete(Unit)
            assertEquals("write failed", owner.await().message)
            assertEquals(42, queued.await())
            assertEquals(listOf("begin", "rollback", "read"), events)
        } finally {
            release.complete(Unit)
            owner.cancelAndJoin()
            lane.close {}
        }
    }

    @Test
    fun cancelledWaiterNeverExecutesAndDoesNotBlockNextWaiter() = runTest(timeout = 10.seconds) {
        val lane = IoLane("test-db", StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val owner = launch {
            lane.run("owner") {
                entered.complete(Unit)
                release.await()
                events += "owner"
            }
        }
        try {
            entered.await()
            val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
                lane.run("cancelled waiter") { events += "cancelled" }
            }
            val following = async(start = CoroutineStart.UNDISPATCHED) {
                lane.run("following waiter") { events += "following" }
            }
            runCurrent()
            assertFalse(following.isCompleted)
            cancelled.cancelAndJoin()
            release.complete(Unit)
            owner.join()
            following.await()
            assertEquals(listOf("owner", "following"), events)
            assertTrue(cancelled.isCancelled)
        } finally {
            release.complete(Unit)
            owner.cancelAndJoin()
            lane.close {}
        }
    }

    @Test
    fun cancelledOwnerCompletesCleanupBeforeQueuedOperation() = runTest(timeout = 10.seconds) {
        val lane = IoLane("test-db", StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val owner = launch {
            lane.run("owner") {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    events += "cleanup"
                }
            }
        }
        try {
            entered.await()
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                lane.run("queued") { events += "queued" }
            }
            runCurrent()
            assertFalse(queued.isCompleted)
            owner.cancelAndJoin()
            queued.await()
            assertEquals(listOf("cleanup", "queued"), events)
            assertTrue(owner.isCancelled)
        } finally {
            owner.cancelAndJoin()
            lane.close {}
        }
    }

    @Test
    fun cancelledCloseWaitsForOwnerAndClosesResourceExactlyOnce() = runTest(timeout = 10.seconds) {
        var executorCloses = 0
        val lane = IoLane("test-db", StandardTestDispatcher(testScheduler)) { executorCloses++ }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val owner = launch {
            lane.run("owner") {
                entered.complete(Unit)
                release.await()
                events += "owner"
            }
        }
        try {
            entered.await()
            val closing = launch(start = CoroutineStart.UNDISPATCHED) {
                lane.close { events += "close" }
            }
            closing.cancel()
            runCurrent()
            assertEquals(emptyList(), events)
            assertFalse(closing.isCompleted)
            release.complete(Unit)
            owner.join()
            closing.join()
            assertEquals(listOf("owner", "close"), events)
            assertEquals(1, executorCloses)
            lane.close { error("must not close twice") }
            assertFailsWith<IllegalStateException> { lane.run("after close") {} }
        } finally {
            release.complete(Unit)
            owner.cancelAndJoin()
            lane.close {}
        }
    }

    @Test
    fun closeFailureStillReleasesMutexAndClosesExecutorOnce() = runTest(timeout = 10.seconds) {
        var executorCloses = 0
        val lane = IoLane("test-db", StandardTestDispatcher(testScheduler)) { executorCloses++ }
        assertFailsWith<IllegalStateException> {
            lane.close { error("resource close failed") }
        }
        lane.close { error("must not close twice") }
        assertEquals(1, executorCloses)
        assertFailsWith<IllegalStateException> { lane.run("after failed close") {} }
    }

    @Test
    fun forbiddenNestedAcquisitionTimesOutButReleasesOuterOwnership() = runTest(timeout = 10.seconds) {
        val lane = IoLane("test-db", StandardTestDispatcher(testScheduler))
        try {
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(100) {
                    lane.run("outer") { lane.run("unsupported nested call") {} }
                }
            }
            assertEquals(42, lane.run("after misuse") { 42 })
        } finally {
            lane.close {}
        }
    }

    @Test
    fun suspensionKeepsExclusiveOwnershipAndOtherLanesAndUiCanRun() = runTest(timeout = 10.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val database = IoLane("test-db", dispatcher)
        val filesystem = IoLane("test-filesystem", dispatcher)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val transaction = launch {
            database.run("transaction") {
                events += "begin"
                entered.complete(Unit)
                release.await()
                events += "commit"
            }
        }
        entered.await()
        val checkpoint = launch { database.run("checkpoint") { events += "checkpoint" } }
        val ui = async { "responsive" }
        assertEquals("responsive", ui.await())
        assertEquals("filesystem", filesystem.run("listing") { "filesystem" })
        runCurrent()
        assertEquals(listOf("begin"), events)
        release.complete(Unit)
        transaction.join()
        checkpoint.join()
        assertEquals(listOf("begin", "commit", "checkpoint"), events)
        database.close {}
        filesystem.close {}
    }

    @Test
    fun blockedFilesystemDoesNotHoldDatabaseAndShutdownWaitsForActiveOperation() = runTest(timeout = 10.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val filesystem = IoLane("test-filesystem", dispatcher)
        val database = IoLane("test-db", dispatcher)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var closed = false
        val listing = launch {
            filesystem.run("listing") {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        assertEquals(42, database.run("query") { 42 })
        val closing = launch { filesystem.close { closed = true } }
        runCurrent()
        assertFalse(closed)
        release.complete(Unit)
        listing.join()
        closing.join()
        assertTrue(closed)
        assertFailsWith<IllegalStateException> { filesystem.run("too late") {} }
        filesystem.close { error("must close once") }
        database.close {}
    }
}
