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
    fun approvedDryRunMovesOnceRecordsHistoryAndUpdatesInventoryWithoutARescan() = runTest(timeout = 15.seconds) {
        val fs = FakeFileSystem()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val root = "/archive".toPath()
        val directory = root / "Family"
        val bin = "/Bin".toPath()
        fs.createDirectories(directory)
        var moved = 0
        val io = object : FileMoveIo {
            override fun sameFileSystem(source: Path, destinationDirectory: Path) = true
            override fun moveNoReplace(source: Path, destination: Path) {
                check(!fs.exists(destination))
                fs.atomicMove(source, destination)
                moved++
            }
            override fun flushFile(path: Path) = Unit
        }
        val container = AppContainer(createInMemoryDriver(), IoLane("test-db", dispatcher),
            IoLane("test-fs", dispatcher), dispatcher,
            createScanner = { repository, lane -> DirectoryScanner(fs, repository, filesystemLane = lane) },
            createExifSource = { ExifSource { error("Real runs must not extract photo contents") } },
            fileSystem = fs, moveIo = io)
        try {
            for (name in listOf("IMG.JPG", "IMG-001.JPG"))
                fs.write(directory / name) { writeUtf8("a".repeat(120_001)) }
            container.repository.seedRoot(root)
            container.repository.recordListing(root,
                DirectoryListing(listOf(DirectoryListing.Entry(directory, 0, null)), emptyList()), 1)
            container.repository.recordListing(directory, DirectoryListing(emptyList(), listOf("IMG.JPG", "IMG-001.JPG").map {
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
            container.fileOperations.createDryRun()
            container.fileOperations.state.first { !it.busy }
            assertNull(container.fileOperations.state.value.error)
            val proposal = assertNotNull(container.fileOperations.state.value.proposal)
            assertEquals(FileOperationValidationStatus.READY, proposal.validationStatus)
            val move = proposal.moves.single()
            assertEquals(0, moved)
            assertTrue(fs.exists(move.sourcePath))
            assertFalse(fs.exists(move.destinationPath!!))

            container.fileOperations.runRealOperation(proposal.id)
            container.fileOperations.state.first { !it.busy }
            container.operationHistory.state.first { !it.busy }
            assertNull(container.fileOperations.state.value.error)
            assertEquals(1, moved)
            val result = assertNotNull(container.fileOperations.state.value.executionResult)
            assertEquals(FileMoveExecutionStatus.COMPLETED, result.items.single().status)
            assertNull(result.items.single().postProcessingError)
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
            assertEquals(1L, container.repository.summary().fileCount)
            assertEquals(1, container.repository.directory(directory)?.fileCount)
            assertTrue(container.repository.suspectedDuplicates().isEmpty())
            val history = assertNotNull(container.operationHistory.state.value.snapshot)
            assertEquals(1L, history.statistics.completedMoves)
            assertEquals(120_001L, history.statistics.logicalBytesMoved)
            assertEquals(1L, history.statistics.filesInBin)
            assertEquals(OperationHistoryMoveStatus.COMPLETED, history.operations.single().items.single().moveStatus)
            assertNotNull(history.operations.single().items.single().movedAt)

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
