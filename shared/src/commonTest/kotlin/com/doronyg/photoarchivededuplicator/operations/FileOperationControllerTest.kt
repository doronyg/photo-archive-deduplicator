package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.scan.concurrencyTest
import com.doronyg.photoarchivededuplicator.scan.createFilesDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import okio.Path.Companion.toPath
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class FileOperationControllerTest {
    @Test
    fun configurationRefreshAndValidationFailuresAreVisibleInAppOwnedState() = concurrencyTest { f ->
        val proposals = FileOperationRepository(createFilesDatabase(f.driver), f.lane)
        val service = FileOperationService(f.repository, proposals,
            RetentionScoreService(f.repository, dispatcher = f.dispatcher), f.lane,
            f.fakeFs, f.dispatcher)
        val controller = FileOperationController(this, f.repository, proposals, service)
        controller.refresh()
        advanceUntilIdle()
        assertNull(controller.state.value.binRoot)
        assertTrue(controller.state.value.history.isEmpty())
        controller.configureBin("/DeduplicatorGarbageBin")
        advanceUntilIdle()
        assertEquals("/DeduplicatorGarbageBin".toPath(), controller.state.value.binRoot)
        assertFalse(f.fakeFs.exists("/DeduplicatorGarbageBin".toPath()))
        controller.createDryRun()
        advanceUntilIdle()
        assertEquals(FileOperationIssueCode.EMPTY_SELECTION, controller.state.value.error?.code)
        assertFalse(controller.state.value.busy)
        controller.configureBin("relative/path")
        advanceUntilIdle()
        assertEquals(FileOperationIssueCode.CONFIGURATION_ERROR, controller.state.value.error?.code)
        assertEquals("/DeduplicatorGarbageBin".toPath(), f.repository.binRoot())
    }

    @Test
    fun activeWorkBlocksConfigurationAndDryRunButNotHistoryReads() = concurrencyTest { f ->
        val proposals = FileOperationRepository(createFilesDatabase(f.driver), f.lane)
        val service = FileOperationService(f.repository, proposals,
            RetentionScoreService(f.repository, dispatcher = f.dispatcher), f.lane,
            f.fakeFs, f.dispatcher)
        val controller = FileOperationController(this, f.repository, proposals, service, admissionBlocked = { true })
        controller.configureBin("/DeduplicatorGarbageBin")
        advanceUntilIdle()
        assertEquals(FileOperationIssueCode.BUSY, controller.state.value.error?.code)
        assertNull(f.repository.binRoot())
        controller.refresh()
        advanceUntilIdle()
        assertNull(controller.state.value.error)
        assertTrue(controller.state.value.history.isEmpty())
    }
}
