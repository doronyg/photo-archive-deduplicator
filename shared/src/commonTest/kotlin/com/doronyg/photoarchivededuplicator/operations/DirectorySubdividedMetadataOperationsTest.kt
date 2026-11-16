package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.operations.history.OperationHistoryStore
import com.doronyg.photoarchivededuplicator.recommendation.DeletionRecommendationService
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.scan.ConcurrencyFixture
import com.doronyg.photoarchivededuplicator.scan.DirectoryListing
import com.doronyg.photoarchivededuplicator.scan.concurrencyTest
import com.doronyg.photoarchivededuplicator.scan.createFilesDatabase
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectorySubdividedMetadataOperationsTest {
    private data class Fixture(
        val selected: SelectedDeletionGroup,
        val proposals: FileOperationRepository,
        val service: FileOperationService,
        val bin: Path,
    )

    private suspend fun seed(f: ConcurrencyFixture): Fixture {
        val directories = listOf(f.root / "2023-02-18", f.root / "Backup", f.root / "Singleton")
        for ((index, directory) in directories.withIndex()) {
            val names = if (index == 2) listOf("IMG.jpg") else listOf("IMG.jpg", "IMG-001.jpg")
            for (name in names) f.write(directory / name, "a".repeat(120_001))
            f.repository.seedRoot(directory)
            f.repository.recordListing(directory, DirectoryListing(emptyList(), names.map { name ->
                val path = directory / name
                val metadata = f.fakeFs.metadata(path)
                DirectoryListing.Entry(path, metadata.size!!, metadata.lastModifiedAtMillis, metadata.createdAtMillis)
            }), 2)
            for (file in f.repository.filesIn(directory)) {
                f.repository.saveFileExif(ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED,
                    ExifMetadata(capturedAt = "2023:02:18 12:44:25", make = "Canon", model = "Camera",
                        width = 4000, height = 3000)), 3))
            }
        }
        val retention = RetentionScoreService(f.repository, dispatcher = f.dispatcher)
        retention.assessSuspected(f.repository.suspectedDuplicates())
        val category = DeletionRecommendationService(f.repository, retention, f.dispatcher).load()
            .categories.single { it.category.id == "same-directory-metadata-by-directory" }
        val selected = SelectedDeletionGroup(category.category, category.groups.single())
        val bin = "/DeduplicatorGarbageBin".toPath()
        f.repository.configureBinRoot(bin, 4)
        val proposals = FileOperationRepository(createFilesDatabase(f.driver), f.lane)
        var id = 0
        val service = FileOperationService(f.repository, proposals, retention, f.lane,
            f.fakeFs, f.dispatcher, clock = { 100 }, nextId = { "directory-subdivided-${++id}" })
        return Fixture(selected, proposals, service, bin)
    }

    @Test
    fun savedCombinedPlanRevalidatesAndChangedLocalEvidenceInvalidatesIt() = concurrencyTest { f ->
        val fixture = seed(f)
        val proposal = fixture.service.createProposal(listOf(fixture.selected))
        assertTrue(proposal.canExecute)
        assertEquals(2, proposal.moves.size)
        assertEquals(5, proposal.groups.single().members.size)
        assertEquals(proposal, fixture.proposals.load(proposal.id))
        val validation = fixture.service.revalidateForExecution(proposal)
        assertTrue(validation.groupFindings.isEmpty())
        for (move in proposal.moves) fixture.service.executionMoveValidator(proposal, validation)(move)
        val candidate = fixture.selected.group.candidates.first().advice.file
        val record = f.repository.fileExif(listOf(candidate.path)).single()
        f.repository.saveFileExif(record.copy(result = record.result.copy(
            metadata = record.result.metadata.copy(model = "Other camera")), extractedAt = 6))
        assertTrue(fixture.service.revalidateForExecution(proposal).groupFindings.isNotEmpty())
        assertFalse(fixture.service.createProposal(listOf(fixture.selected)).canExecute)
        assertTrue(fixture.selected.group.recommendation.files.keys.all { f.fakeFs.exists(it) })
    }

    @Test
    fun executionMovesOnlyLocalCandidatesAndKeepsEveryDirectoryPopulated() = concurrencyTest { f ->
        val fixture = seed(f)
        val proposal = fixture.service.createProposal(listOf(fixture.selected))
        assertTrue(proposal.canExecute)
        val binding = OperationHistoryStore(f.lane, f.fakeFs, f.dispatcher).register(f.root, fixture.bin)
        val validation = fixture.service.revalidateForExecution(proposal)
        var moved = 0
        val io = object : FileMoveIo {
            override fun sameFileSystem(source: Path, destinationDirectory: Path) = true
            override fun moveNoReplace(source: Path, destination: Path) {
                assertFalse(f.fakeFs.exists(destination))
                f.fakeFs.atomicMove(source, destination)
                moved++
            }
            override fun flushFile(path: Path) = Unit
        }
        val result = FileOperationExecutor(f.lane, io, f.fakeFs, f.dispatcher).execute(
            proposal, binding,
            validate = { assertTrue(fixture.service.revalidateForExecution(proposal).groupFindings.isEmpty()) },
            beforeMove = fixture.service.executionMoveValidator(proposal, validation),
            recordMove = { f.repository.recordCompletedMove(it) })
        assertEquals(2, moved)
        assertTrue(result.items.all { it.status == FileMoveExecutionStatus.COMPLETED })
        val kept = fixture.selected.group.survivors.map { it.advice.file }
        assertEquals(3, kept.size)
        for (file in kept) {
            assertTrue(f.fakeFs.exists(file.path))
            assertEquals(listOf(file.path), f.repository.filesIn(file.directoryPath).map { it.path })
        }
        assertTrue(proposal.moves.all { f.fakeFs.exists(it.destinationPath!!) && !f.fakeFs.exists(it.sourcePath) })
    }
}
