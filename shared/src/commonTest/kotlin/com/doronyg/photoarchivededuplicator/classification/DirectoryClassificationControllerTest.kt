package com.doronyg.photoarchivededuplicator.classification

import com.doronyg.photoarchivededuplicator.scan.FileRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DirectoryClassificationControllerTest {
    private val context = OllamaSettings("http://ollama.test", "configured-model").context()

    private fun group(vararg directories: String) = directories.mapIndexed { index, directory ->
        val folder = directory.toPath()
        FileRecord(folder / "image-$index.jpg", folder, "image-$index.jpg", "jpg", 120_000, 1, 2)
    }

    @Test
    fun onlyDistinctDirectoriesFromEligibleSelectedGroupsAreSubmittedAndEachSuccessPersists() = runTest {
        val calls = mutableListOf<Path>()
        val saved = mutableListOf<DirectoryClassificationRecord>()
        val source = listOf(group("/Photos/A", "/Photos/B"), group("/Photos/A", "/Photos/A"),
            group("/not-a-group"), emptyList())
        val controller = DirectoryClassificationController(backgroundScope,
            DirectoryClassifier { path, _ ->
                calls += path
                assertEquals(calls.size - 1, saved.size)
                DirectoryClassification.UNKNOWN
            }, load = { paths, _ ->
                assertEquals(setOf("/Photos/A".toPath(), "/Photos/B".toPath()), paths.toSet())
                emptyList()
            }, save = { saved += it }, dispatcher = StandardTestDispatcher(testScheduler), clock = { 42 })
        assertTrue(controller.start(source, context))
        runCurrent()
        assertEquals(listOf("/Photos/A".toPath(), "/Photos/B".toPath()), calls)
        assertEquals(2, saved.size)
        assertTrue(saved.all { it.classification == DirectoryClassification.UNKNOWN && it.classifiedAt == 42L })
        assertEquals(2, controller.state.value.completed)
        assertEquals(0, controller.state.value.remaining)
        assertEquals(2L, controller.state.value.revision)
        controller.shutdown()
    }

    @Test
    fun cacheReuseAndExplicitRefreshNeverConvertFailuresIntoUnknownRows() = runTest {
        val groups = listOf(group("/Photos/A", "/Photos/B"))
        val cached = DirectoryClassificationRecord("/Photos/A".toPath(), "/Photos/A",
            DirectoryClassification.PROTECTED, context, 1)
        val saved = mutableListOf<DirectoryClassificationRecord>()
        var fail = false
        val calls = mutableListOf<Path>()
        val controller = DirectoryClassificationController(backgroundScope, DirectoryClassifier { path, _ ->
            calls += path
            if (fail) throw DirectoryClassificationException("Server unavailable")
            DirectoryClassification.BACKUP
        }, load = { _, _ -> listOf(cached) }, save = { saved += it },
            dispatcher = StandardTestDispatcher(testScheduler))
        assertTrue(controller.start(groups, context))
        runCurrent()
        assertEquals(listOf("/Photos/B".toPath()), calls)
        assertEquals(1, controller.state.value.skipped)
        fail = true
        assertTrue(controller.start(groups, context, forceRefresh = true))
        runCurrent()
        assertEquals(2, controller.state.value.failed)
        assertEquals(1, saved.size)
        fail = false
        assertTrue(controller.retryFailed(groups, context))
        runCurrent()
        assertEquals(2, controller.state.value.completed)
        assertEquals(0, controller.state.value.failed)
        assertEquals(3, saved.size)
        controller.shutdown()
    }

    @Test
    fun retryFailuresRespectsTheCurrentlySelectedDataset() = runTest {
        val calls = mutableListOf<Path>()
        var fail = true
        val controller = DirectoryClassificationController(backgroundScope, DirectoryClassifier { path, _ ->
            calls += path
            if (fail) throw DirectoryClassificationException("Timeout")
            DirectoryClassification.UNKNOWN
        }, load = { _, _ -> emptyList() }, save = {},
            dispatcher = StandardTestDispatcher(testScheduler))
        assertTrue(controller.start(listOf(group("/Photos/A", "/Photos/B")), context))
        runCurrent()
        fail = false
        assertTrue(controller.retryFailed(listOf(group("/Photos/B", "/Photos/C")), context))
        runCurrent()
        assertEquals(listOf("/Photos/A", "/Photos/B", "/Photos/B").map { it.toPath() }, calls)
        assertEquals(1, controller.state.value.total)
        controller.shutdown()
    }

    @Test
    fun successfulUnknownIsCachedButDifferentProfilesAreNotReused() = runTest {
        val groups = listOf(group("/Photos/A", "/Photos/A"))
        var stored = DirectoryClassificationRecord("/Photos/A".toPath(), "/Photos/A",
            DirectoryClassification.UNKNOWN, context, 1)
        var calls = 0
        val controller = DirectoryClassificationController(backgroundScope,
            DirectoryClassifier { _, _ -> calls++; DirectoryClassification.PROTECTED },
            load = { _, _ -> listOf(stored) }, save = { stored = it },
            dispatcher = StandardTestDispatcher(testScheduler))
        assertTrue(controller.start(groups, context))
        runCurrent()
        assertEquals(0, calls)
        assertEquals(1, controller.state.value.skipped)
        val changed = context.copy(model = "another-model")
        assertTrue(controller.start(groups, changed))
        runCurrent()
        assertEquals(1, calls)
        assertEquals(changed, stored.context)
        controller.shutdown()
    }

    @Test
    fun cancellationPreservesCompletedResultsAndNextInvocationOnlyRequestsMissingDirectories() = runTest {
        val groups = listOf(group("/Photos/A", "/Photos/B"))
        val saved = mutableListOf<DirectoryClassificationRecord>()
        var pause = true
        val calls = mutableListOf<Path>()
        val controller = DirectoryClassificationController(backgroundScope, DirectoryClassifier { path, _ ->
            calls += path
            if (pause && path.name == "B") awaitCancellation()
            DirectoryClassification.UNKNOWN
        }, load = { _, _ -> saved.toList() }, save = { saved += it },
            dispatcher = StandardTestDispatcher(testScheduler))
        assertTrue(controller.start(groups, context))
        runCurrent()
        controller.cancel()
        assertEquals(1, saved.size)
        assertEquals(1, controller.state.value.completed)
        assertEquals(1L, controller.state.value.revision)
        assertEquals(1, controller.state.value.remaining)
        pause = false
        assertTrue(controller.start(groups, context))
        runCurrent()
        assertEquals(2, saved.size)
        assertEquals(1, controller.state.value.skipped)
        assertEquals(listOf("/Photos/A", "/Photos/B", "/Photos/B").map { it.toPath() }, calls)
        controller.shutdown()
    }

    @Test
    fun cancellationStopsTheInFlightRequestAndDoesNotPersistAResult() = runTest {
        val saved = mutableListOf<DirectoryClassificationRecord>()
        val controller = DirectoryClassificationController(backgroundScope,
            DirectoryClassifier { _, _ -> awaitCancellation() },
            load = { _, _ -> emptyList() }, save = { saved += it },
            dispatcher = StandardTestDispatcher(testScheduler))
        assertTrue(controller.start(listOf(group("/Photos/A", "/Photos/B")), context))
        runCurrent()
        assertFalse(controller.start(listOf(group("/Photos/A", "/Photos/B")), context))
        controller.cancel()
        assertEquals(DirectoryClassificationPhase.CANCELLED, controller.state.value.phase)
        assertTrue(saved.isEmpty())
        assertEquals(2, controller.state.value.remaining)
        controller.shutdown()
        assertFalse(controller.start(listOf(group("/Photos/A", "/Photos/B")), context))
    }

    @Test
    fun unicodePreferredDirectoriesPersistWithoutLocationRestrictionsAndAreReused() = runTest {
        val groups = listOf(group("/Photos/ישראל קיץ 2018 - תמונות", "/Outside/雪"))
        val saved = mutableListOf<DirectoryClassificationRecord>()
        val controller = DirectoryClassificationController(backgroundScope,
            DirectoryClassifier { _, _ -> DirectoryClassification.PREFERRED },
            load = { _, _ -> saved.toList() }, save = { saved += it },
            dispatcher = StandardTestDispatcher(testScheduler))
        assertTrue(controller.start(groups, context))
        runCurrent()
        assertEquals(setOf("/Photos/ישראל קיץ 2018 - תמונות".toPath(), "/Outside/雪".toPath()),
            saved.map { it.directoryPath }.toSet())
        assertEquals(0, controller.state.value.failed)
        assertTrue(controller.start(groups, context))
        runCurrent()
        assertEquals(2, controller.state.value.skipped)
        assertEquals(2, saved.size)
        controller.shutdown()
    }

    @Test
    fun persistenceFailureIsVisibleAndAppScopeCancellationClosesTheJob() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val controller = DirectoryClassificationController(scope,
            DirectoryClassifier { _, _ -> DirectoryClassification.UNKNOWN },
            load = { _, _ -> emptyList() }, save = { error("Database unavailable") },
            dispatcher = StandardTestDispatcher(testScheduler))
        assertTrue(controller.start(listOf(group("/Photos/A", "/Photos/B")), context))
        runCurrent()
        assertEquals(DirectoryClassificationPhase.FAILED, controller.state.value.phase)
        assertTrue(controller.state.value.error.orEmpty().contains("Database unavailable"))
        assertEquals(0, controller.state.value.completed)
        scope.cancel()
        controller.shutdown()
    }
}
