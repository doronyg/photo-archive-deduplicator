package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.UiChoices
import com.doronyg.photoarchivededuplicator.UiPreferences
import com.doronyg.photoarchivededuplicator.operations.history.OperationHistoryStore
import com.doronyg.photoarchivededuplicator.scan.concurrencyTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OperationHistoryControllerTest {
    @Test
    fun explicitRegistrationPersistsMappingAndRefreshDoesNotCreateOperations() = concurrencyTest { f ->
        f.fakeFs.createDirectory(f.root)
        val bin = "/Bin".toPath()
        f.repository.configureBinRoot(bin, 1)
        val preferences = UiPreferences(UiChoices(), this, f.repository::saveUiChoices)
        val controller = OperationHistoryController(this, f.repository, preferences,
            OperationHistoryStore(f.lane, f.fakeFs, f.dispatcher))
        controller.register(f.root.toString())
        advanceUntilIdle()
        assertNull(controller.state.value.error)
        assertNotNull(controller.state.value.binding)
        assertEquals(f.root.toString(), preferences.state.value.operationHistoryArchiveRoot)
        assertEquals(controller.state.value.binding!!.identity.archiveId, preferences.state.value.operationHistoryArchiveId)
        assertTrue(controller.state.value.snapshot!!.operations.isEmpty())
        assertEquals(0L, controller.state.value.snapshot!!.statistics.completedMoves)
        assertFalse(controller.state.value.busy)
        preferences.shutdown()
    }

    @Test
    fun wrongMappingKeepsHistoryBrowseableAndNeverBecomesVerified() = concurrencyTest { f ->
        f.fakeFs.createDirectory(f.root)
        f.fakeFs.createDirectory("/Wrong".toPath())
        val bin = "/Bin".toPath()
        f.repository.configureBinRoot(bin, 1)
        val store = OperationHistoryStore(f.lane, f.fakeFs, f.dispatcher)
        store.register(f.root, bin)
        val preferences = UiPreferences(UiChoices(operationHistoryArchiveRoot = "/Wrong"), this, f.repository::saveUiChoices)
        val controller = OperationHistoryController(this, f.repository, preferences, store)
        controller.refresh()
        advanceUntilIdle()
        assertNotNull(controller.state.value.snapshot)
        assertNull(controller.state.value.binding)
        assertNotNull(controller.state.value.error)
        assertFalse(f.fakeFs.exists("/Wrong/.photo-archive-id.json".toPath()))
        preferences.shutdown()
    }

    @Test
    fun busyWorkBlocksMetadataRegistration() = concurrencyTest { f ->
        val preferences = UiPreferences(UiChoices(), this, f.repository::saveUiChoices)
        val controller = OperationHistoryController(this, f.repository, preferences,
            OperationHistoryStore(f.lane, f.fakeFs, f.dispatcher), admissionBlocked = { true })
        controller.register(f.root.toString())
        advanceUntilIdle()
        assertNotNull(controller.state.value.error)
        assertNull(controller.state.value.binding)
        assertFalse(f.fakeFs.exists(f.root))
        preferences.shutdown()
    }

    @Test
    fun changedPinnedIdentityRequiresExplicitConnectionButDoesNotHideHistory() = concurrencyTest { f ->
        f.fakeFs.createDirectory(f.root)
        val bin = "/Bin".toPath()
        f.repository.configureBinRoot(bin, 1)
        val store = OperationHistoryStore(f.lane, f.fakeFs, f.dispatcher)
        store.register(f.root, bin)
        val preferences = UiPreferences(UiChoices(operationHistoryArchiveRoot = f.root.toString(),
            operationHistoryArchiveId = "different", operationHistoryRegistrationToken = "different"),
            this, f.repository::saveUiChoices)
        val controller = OperationHistoryController(this, f.repository, preferences, store)
        controller.refresh()
        advanceUntilIdle()
        assertNull(controller.state.value.binding)
        assertNotNull(controller.state.value.error)
        assertNotNull(controller.state.value.snapshot)
        controller.connect(f.root.toString())
        advanceUntilIdle()
        assertNull(controller.state.value.error)
        assertNotNull(controller.state.value.binding)
        assertEquals(controller.state.value.binding!!.identity.archiveId, preferences.state.value.operationHistoryArchiveId)
        preferences.shutdown()
    }

    @Test
    fun unavailableStorageKeepsTheLastSnapshotAndMarksTheRefreshFailure() = concurrencyTest { f ->
        f.fakeFs.createDirectory(f.root)
        val bin = "/Bin".toPath()
        f.repository.configureBinRoot(bin, 1)
        val preferences = UiPreferences(UiChoices(), this, f.repository::saveUiChoices)
        val controller = OperationHistoryController(this, f.repository, preferences,
            OperationHistoryStore(f.lane, f.fakeFs, f.dispatcher))
        controller.register(f.root.toString())
        advanceUntilIdle()
        val before = assertNotNull(controller.state.value.snapshot)
        val refreshed = controller.state.value.refreshedAt
        f.fakeFs.delete(bin / ".deduplicator-history/bin.json")
        controller.refresh()
        advanceUntilIdle()
        assertSame(before, controller.state.value.snapshot)
        assertEquals(refreshed, controller.state.value.refreshedAt)
        assertNotNull(controller.state.value.error)
        assertNull(controller.state.value.binding)
        preferences.shutdown()
    }
}
