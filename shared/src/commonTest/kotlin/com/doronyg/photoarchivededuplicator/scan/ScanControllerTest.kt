package com.doronyg.photoarchivededuplicator.scan

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Virtual time (testScheduler.currentTime) is still marked experimental in kotlinx-coroutines-test
@OptIn(ExperimentalCoroutinesApi::class)
class ScanControllerTest {
    private val env = ScanTestEnvironment()
    private val root = env.root

    @AfterTest
    fun tearDown() = env.close()

    private fun TestScope.controller(config: ScanConfig = ScanConfig()) = ScanController(
        scanner = with(env) { scanner(config) },
        // Not backgroundScope: advanceUntilIdle() doesn't wait for background work
        scope = this,
        clock = { testScheduler.currentTime },
    )

    private fun writeTree(): Int {
        val files = listOf(root / "a" / "1.jpg", root / "a" / "2.jpg", root / "b" / "3.jpg")
        files.forEach { env.writeFile(it) }
        return files.size
    }

    @Test
    fun initialStateIsIdle() = env.test {
        assertIs<ScanState.Idle>(controller().state.value)
    }

    @Test
    fun startRunsScanToCompletionAndReportsTotals() = env.test {
        val fileCount = writeTree()
        val controller = controller()

        assertTrue(controller.start(root))
        advanceUntilIdle()

        val progress = assertIs<ScanState.Completed>(controller.state.value).progress
        assertEquals(3L, progress.scannedDirectories)
        assertEquals(fileCount.toLong(), progress.filesFound)
        assertEquals(0L, progress.pendingDirectories)
        assertEquals(0L, progress.failedDirectories)
        assertNull(progress.currentDirectory)
    }

    @Test
    fun reportsProgressWhileRunning() = env.test {
        writeTree()
        val controller = controller()
        var observed: ScanState? = null
        // Directories are processed in path order: root, a, b
        env.fs.onList = { if (it == root / "b") observed = controller.state.value }

        controller.start(root)
        advanceUntilIdle()

        val progress = assertIs<ScanState.Running>(observed).progress
        assertEquals(root / "b", progress.currentDirectory)
        assertEquals(2L, progress.scannedDirectories)
        assertEquals(1L, progress.pendingDirectories)
    }

    @Test
    fun startIsRejectedWhileRunning() = env.test {
        writeTree()
        val controller = controller()
        var secondStartAccepted: Boolean? = null
        env.fs.onList = { if (secondStartAccepted == null) secondStartAccepted = controller.start("/other".toPath()) }

        controller.start(root)
        advanceUntilIdle()

        assertEquals(false, secondStartAccepted)
        assertIs<ScanState.Completed>(controller.state.value)
        assertTrue(env.fs.listCalls.none { it == "/other".toPath() })
    }

    @Test
    fun pauseStopsScanAndResumeFinishesIt() = env.test {
        writeTree()
        val controller = controller()
        env.fs.onList = { if (it == root / "a") controller.pause() }

        controller.start(root)
        advanceUntilIdle()

        assertIs<ScanState.Paused>(controller.state.value)
        assertTrue(env.repository.allDirectories().any { it.status != DirectoryStatus.COMPLETED })

        env.fs.onList = {}
        assertTrue(controller.resume())
        advanceUntilIdle()

        assertIs<ScanState.Completed>(controller.state.value)
        assertTrue(env.repository.allDirectories().all { it.status == DirectoryStatus.COMPLETED })
    }

    @Test
    fun resumeContinuesWorkLeftByPreviousSession() = env.test {
        writeTree()
        env.repository.seedRoot(root)
        val controller = controller()

        controller.resume()
        advanceUntilIdle()

        assertEquals(3L, assertIs<ScanState.Completed>(controller.state.value).progress.scannedDirectories)
    }

    @Test
    fun failedDirectoriesAreCountedAndElapsedTimeIncludesBackoff() = env.test {
        writeTree()
        val config = ScanConfig()
        env.fs.failListing(root / "a")
        val controller = controller(config)

        controller.start(root)
        advanceUntilIdle()

        val progress = assertIs<ScanState.Completed>(controller.state.value).progress
        assertEquals(1L, progress.failedDirectories)
        assertEquals(2L, progress.scannedDirectories)
        assertEquals(0L, progress.pendingDirectories)
        // Backoff doubles after every failed attempt except the last
        val expectedBackoff = (0 until config.maxAttempts - 1).sumOf { config.initialBackoff.inWholeMilliseconds shl it }
        assertEquals(expectedBackoff, progress.elapsedMillis)
    }

    @Test
    fun unexpectedErrorMovesToFailedState() = env.test {
        writeTree()
        val message = "disk exploded"
        env.fs.onList = { error(message) }
        val controller = controller()

        controller.start(root)
        advanceUntilIdle()

        assertEquals(message, assertIs<ScanState.Failed>(controller.state.value).message)
        assertFalse(controller.state.value is ScanState.Running)
    }
}
