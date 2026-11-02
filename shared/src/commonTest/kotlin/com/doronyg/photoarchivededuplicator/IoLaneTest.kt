package com.doronyg.photoarchivededuplicator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class IoLaneTest {
    @Test
    fun suspensionKeepsExclusiveOwnershipAndOtherLanesAndUiCanRun() = runTest {
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
    fun blockedFilesystemDoesNotHoldDatabaseAndShutdownWaitsForActiveOperation() = runTest {
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
