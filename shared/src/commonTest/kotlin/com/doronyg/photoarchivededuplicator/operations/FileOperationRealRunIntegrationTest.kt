package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.AppContainer
import com.doronyg.photoarchivededuplicator.IoLane
import com.doronyg.photoarchivededuplicator.exif.*
import com.doronyg.photoarchivededuplicator.operations.history.OperationHistoryMoveStatus
import com.doronyg.photoarchivededuplicator.operations.history.historyObject
import com.doronyg.photoarchivededuplicator.operations.history.historyLong
import com.doronyg.photoarchivededuplicator.recommendation.DeletionRecommendationService
import com.doronyg.photoarchivededuplicator.scan.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class FileOperationRealRunIntegrationTest {
    @Test
    fun missingCandidateDoesNotBlockDryRunOrIndependentMoves() = runTest(timeout = 15.seconds) {
        assertMixedGroupExecution(missingSource = true)
    }

    @Test
    fun invalidRetainedCopyRejectsOnlyItsGroupAndKeepsFailureInReport() = runTest(timeout = 15.seconds) {
        assertMixedGroupExecution(missingSource = false)
    }

    private suspend fun TestScope.assertMixedGroupExecution(missingSource: Boolean) {
        val fs = FakeFileSystem()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val root = "/archive".toPath()
        val badDirectory = root / "Bad"
        val goodDirectory = root / "Good"
        val bin = "/Bin".toPath()
        var moved = 0
        val io = object : FileMoveIo {
            override fun sameFileSystem(source: Path, destinationDirectory: Path) = true
            override fun moveNoReplace(source: Path, destination: Path) {
                if (!fs.exists(source)) throw FileMoveRejectedException("Source is missing: $source")
                check(!fs.exists(destination))
                fs.atomicMove(source, destination)
                moved++
            }
            override fun flushFile(path: Path) = Unit
        }
        val container = AppContainer(createInMemoryDriver(), IoLane("test-db", dispatcher),
            IoLane("test-fs", dispatcher), dispatcher,
            createScanner = { repository, lane -> DirectoryScanner(fs, repository, filesystemLane = lane) },
            createExifSource = { ExifSource { error("Execution must not read photo contents") } },
            fileSystem = fs, moveIo = io)
        try {
            fs.createDirectories(badDirectory)
            fs.createDirectories(goodDirectory)
            container.repository.seedRoot(root)
            container.repository.recordListing(root,
                DirectoryListing(listOf(badDirectory, goodDirectory).map { DirectoryListing.Entry(it, 0, null) },
                    emptyList()), 1)
            for ((index, directory) in listOf(badDirectory, goodDirectory).withIndex()) {
                val entries = listOf("IMG.JPG", "IMG-001.JPG").map { name ->
                    val path = directory / name
                    fs.write(path) { writeUtf8("a".repeat(120_001)) }
                    val metadata = fs.metadata(path)
                    DirectoryListing.Entry(path, metadata.size!!, metadata.lastModifiedAtMillis, metadata.createdAtMillis)
                }
                container.repository.recordListing(directory, DirectoryListing(emptyList(), entries), 2)
                for (file in container.repository.filesIn(directory)) {
                    container.repository.saveFileExif(ExifRecord(ExifInput.from(file), ExifReadResult(
                        ExifStatus.EXTRACTED, ExifMetadata(capturedAt = "2023:02:${18 + index} 12:44:25",
                            make = "Canon", model = "Camera", width = 4000, height = 3000)), 3))
                }
            }
            container.retentionScores.assessSuspected(container.repository.suspectedDuplicates())
            container.fileOperations.configureBin(bin.toString())
            advanceUntilIdle()
            container.operationHistory.register(root.toString())
            advanceUntilIdle()
            assertNull(container.operationHistory.state.value.error)
            val category = DeletionRecommendationService(container.repository, container.retentionScores, dispatcher).load()
                .categories.single { it.category.id == "same-directory-metadata" }
            assertEquals(2, category.groups.size)
            container.fileOperations.selectGroups(category.groups.map { SelectedDeletionGroup(category.category, it) })
            container.fileOperations.createDryRun()
            container.fileOperations.state.first { !it.busy }
            val original = assertNotNull(container.fileOperations.state.value.proposal)
            val bad = original.moves.single { it.sourcePath.parent == badDirectory }
            val good = original.moves.single { it.sourcePath.parent == goodDirectory }
            fs.delete(if (missingSource) bad.sourcePath else assertNotNull(bad.survivorPath))

            container.fileOperations.createDryRun()
            container.fileOperations.state.first { !it.busy }
            assertNull(container.fileOperations.state.value.error)
            val proposal = assertNotNull(container.fileOperations.state.value.proposal)
            assertEquals(if (missingSource) FileOperationValidationStatus.READY else
                FileOperationValidationStatus.PARTIALLY_READY, proposal.validationStatus)
            container.fileOperations.runRealOperation(proposal.id)
            container.fileOperations.state.first { !it.busy }
            container.operationHistory.state.first { !it.busy }

            assertNull(container.fileOperations.state.value.error)
            val result = assertNotNull(container.fileOperations.state.value.executionResult)
            assertEquals(2, result.items.size)
            assertEquals(FileMoveExecutionStatus.FAILED, result.items.single { it.move.sourcePath == bad.sourcePath }.status)
            assertNotNull(result.items.single { it.move.sourcePath == bad.sourcePath }.error)
            assertEquals(FileMoveExecutionStatus.COMPLETED, result.items.single { it.move.sourcePath == good.sourcePath }.status)
            assertEquals(1, moved)
            assertEquals(120_001L, container.fileOperations.state.value.executionProgress?.logicalBytesMoved)
            assertEquals(!missingSource, fs.exists(bad.sourcePath))
            assertFalse(fs.exists(bad.destinationPath!!))
            assertFalse(fs.exists(good.sourcePath))
            assertTrue(fs.exists(good.destinationPath!!))
            assertNotNull(container.repository.file(bad.sourcePath))
            assertTrue(container.repository.fileExif(listOf(bad.sourcePath)).isNotEmpty())
            assertTrue(container.repository.fileScores(listOf(bad.sourcePath)).isNotEmpty())
            assertNull(container.repository.file(good.sourcePath))
            assertTrue(container.repository.fileExif(listOf(good.sourcePath)).isEmpty())
            assertTrue(container.repository.fileScores(listOf(good.sourcePath)).isEmpty())
            assertEquals(3L, container.repository.summary().fileCount)
            val history = assertNotNull(container.operationHistory.state.value.snapshot)
            assertEquals(1L, history.statistics.completedMoves)
            assertEquals(120_001L, history.statistics.logicalBytesMoved)
            assertEquals(1L, history.statistics.filesInBin)
            val checkpoints = history.operations.map { operation ->
                historyObject(fs.read(bin / ".deduplicator-history/operations/${operation.operationId}/statistics.json") {
                    readUtf8()
                })
            }
            assertEquals(1L, checkpoints.sumOf { it.historyLong("completedMoves") })
            assertEquals(120_001L, checkpoints.sumOf { it.historyLong("logicalBytesMoved") })
        } finally {
            container.shutdown()
        }
        fs.checkNoOpenFiles()
    }

    @Test
    fun approvedDryRunMovesOnceRecordsHistoryAndUpdatesInventoryWithoutARescan() = runTest(timeout = 15.seconds) {
        assertApprovedExecution(cancelAfterFirst = false)
    }

    @Test
    fun cancellingFromTheControllerFinishesCurrentMoveAndClosesUnusedIntents() = runTest(timeout = 15.seconds) {
        assertApprovedExecution(cancelAfterFirst = true)
    }

    private suspend fun TestScope.assertApprovedExecution(cancelAfterFirst: Boolean) {
        val fs = FakeFileSystem()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val root = "/archive".toPath()
        val directory = root / "Family"
        val bin = "/Bin".toPath()
        fs.createDirectories(directory)
        var moved = 0
        var afterMove: () -> Unit = {}
        val io = object : FileMoveIo {
            override fun sameFileSystem(source: Path, destinationDirectory: Path) = true
            override fun moveNoReplace(source: Path, destination: Path) {
                check(!fs.exists(destination))
                fs.atomicMove(source, destination)
                moved++
                if (cancelAfterFirst && moved == 1) afterMove()
            }
            override fun flushFile(path: Path) = Unit
        }
        val container = AppContainer(createInMemoryDriver(), IoLane("test-db", dispatcher),
            IoLane("test-fs", dispatcher), dispatcher,
            createScanner = { repository, lane -> DirectoryScanner(fs, repository, filesystemLane = lane) },
            createExifSource = { ExifSource { error("Real runs must not extract photo contents") } },
            fileSystem = fs, moveIo = io)
        afterMove = container.fileOperations::cancelOperation
        try {
            val names = if (cancelAfterFirst) listOf("IMG.JPG", "IMG-001.JPG", "IMG-002.JPG")
                else listOf("IMG.JPG", "IMG-001.JPG")
            for (name in names)
                fs.write(directory / name) { writeUtf8("a".repeat(120_001)) }
            container.repository.seedRoot(root)
            container.repository.recordListing(root,
                DirectoryListing(listOf(DirectoryListing.Entry(directory, 0, null)), emptyList()), 1)
            container.repository.recordListing(directory, DirectoryListing(emptyList(), names.map {
                val path = directory / it
                val metadata = fs.metadata(path)
                DirectoryListing.Entry(path, metadata.size!!, metadata.lastModifiedAtMillis, metadata.createdAtMillis)
            }), 2)
            for (file in container.repository.filesIn(directory)) {
                container.repository.saveFileExif(ExifRecord(ExifInput.from(file), ExifReadResult(
                    ExifStatus.EXTRACTED, ExifMetadata(capturedAt = "2023:02:18 12:44:25",
                        make = "Canon", model = "Camera", width = 4000, height = 3000)), 3))
            }
            container.retentionScores.assessSuspected(container.repository.suspectedDuplicates())
            container.fileOperations.configureBin(bin.toString())
            advanceUntilIdle()
            container.operationHistory.register(root.toString())
            advanceUntilIdle()
            assertNull(container.operationHistory.state.value.error)
            val category = DeletionRecommendationService(container.repository, container.retentionScores, dispatcher).load()
                .categories.single { it.category.id == "same-directory-metadata" }
            container.fileOperations.selectGroups(listOf(SelectedDeletionGroup(category.category, category.groups.single())))
            container.fileOperations.clearGroupSelection()
            assertTrue(container.fileOperations.state.value.selectedGroups.isEmpty())
            container.fileOperations.selectAllGroups()
            container.fileOperations.createDryRun()
            container.fileOperations.state.first { !it.busy }
            assertNull(container.fileOperations.state.value.error)
            val proposal = assertNotNull(container.fileOperations.state.value.proposal)
            assertEquals(FileOperationValidationStatus.READY, proposal.validationStatus)
            val move = proposal.moves.first()
            assertEquals(0, moved)
            assertTrue(fs.exists(move.sourcePath))
            assertFalse(fs.exists(move.destinationPath!!))

            container.fileOperations.runRealOperation(proposal.id)
            container.fileOperations.state.first { !it.busy }
            container.operationHistory.state.first { !it.busy }
            assertNull(container.fileOperations.state.value.error)
            assertEquals(1, moved)
            assertTrue(container.fileOperations.state.value.availableGroups.isEmpty())
            val result = assertNotNull(container.fileOperations.state.value.executionResult)
            assertEquals(cancelAfterFirst, result.cancelled)
            val completed = result.items.single { it.status == FileMoveExecutionStatus.COMPLETED }
            assertNull(completed.postProcessingError)
            if (cancelAfterFirst) {
                val untouched = result.items.single { it.status == FileMoveExecutionStatus.NOT_STARTED }.move
                assertTrue(fs.exists(untouched.sourcePath))
                assertFalse(fs.exists(untouched.destinationPath!!))
                assertNotNull(container.repository.file(untouched.sourcePath))
                assertTrue(container.repository.fileExif(listOf(untouched.sourcePath)).isNotEmpty())
                assertEquals(FileExecutionPhase.CANCELLED, container.fileOperations.state.value.executionProgress?.phase)
                assertFalse(container.fileOperations.state.value.cancellationRequested)
            }
            assertNull(result.statisticsError)
            assertEquals(120_001L, container.fileOperations.state.value.executionProgress?.logicalBytesMoved)
            val savedStatistics = historyObject(fs.read(
                bin / ".deduplicator-history/operations/${proposal.id}/statistics.json") { readUtf8() })
            assertEquals(1L, savedStatistics.historyLong("completedMoves"))
            assertEquals(120_001L, savedStatistics.historyLong("logicalBytesMoved"))
            assertFalse(fs.exists(move.sourcePath))
            assertEquals("a".repeat(120_001), fs.read(move.destinationPath!!) { readUtf8() })
            assertNull(container.repository.file(move.sourcePath))
            assertTrue(container.repository.fileExif(listOf(move.sourcePath)).isEmpty())
            assertTrue(container.repository.fileScores(listOf(move.sourcePath)).isEmpty())
            assertEquals((names.size - 1).toLong(), container.repository.summary().fileCount)
            assertEquals(names.size - 1, container.repository.directory(directory)?.fileCount)
            assertEquals(!cancelAfterFirst, container.repository.suspectedDuplicates().isEmpty())
            val history = assertNotNull(container.operationHistory.state.value.snapshot)
            assertEquals(1L, history.statistics.completedMoves)
            assertEquals(120_001L, history.statistics.logicalBytesMoved)
            assertEquals(1L, history.statistics.filesInBin)
            val completedHistory = history.operations.single().items.single {
                it.moveStatus == OperationHistoryMoveStatus.COMPLETED
            }
            assertNotNull(completedHistory.movedAt)
            assertTrue(history.operations.single().items.none { it.moveStatus == OperationHistoryMoveStatus.PENDING })
            assertFalse(history.operations.single().incomplete)

            container.fileOperations.openProposal(proposal.id)
            advanceUntilIdle()
            container.fileOperations.runRealOperation(proposal.id)
            container.fileOperations.state.first { !it.busy }
            assertNotNull(container.fileOperations.state.value.error)
            assertEquals(1, moved, "Reopening an old dry run must not repeat its moves")
        } finally {
            container.shutdown()
        }
        fs.checkNoOpenFiles()
    }
}
