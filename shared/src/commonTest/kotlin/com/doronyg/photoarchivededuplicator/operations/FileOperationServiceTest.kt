package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.classification.*
import com.doronyg.photoarchivededuplicator.exif.*
import com.doronyg.photoarchivededuplicator.recommendation.*
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.scan.*
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.*

class FileOperationServiceTest {
    private data class Fixture(
        val retention: RetentionScoreService,
        val proposals: FileOperationRepository,
        val selected: SelectedDeletionGroup,
        val bin: Path,
    )

    private suspend fun seed(f: ConcurrencyFixture): Fixture {
        val folder = f.root / "Family"
        val bin = "/DeduplicatorGarbageBin".toPath()
        val names = listOf("IMG.JPG", "IMG-001.JPG")
        for (name in names) f.write(folder / name, "a".repeat(120_001))
        f.repository.seedRoot(f.root)
        f.repository.recordListing(f.root, DirectoryListing(
            listOf(DirectoryListing.Entry(folder, 0, null)), emptyList()), 1)
        f.repository.recordListing(folder, DirectoryListing(emptyList(), names.map { name ->
            val path = folder / name
            val metadata = f.fakeFs.metadata(path)
            DirectoryListing.Entry(path, metadata.size!!, metadata.lastModifiedAtMillis, metadata.createdAtMillis)
        }), 2)
        f.repository.filesIn(folder).forEach { file ->
            f.repository.saveFileExif(ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED,
                ExifMetadata(capturedAt = "2023:02:18 12:44:25", make = "Canon", model = "Camera",
                    width = 4000, height = 3000)), 3))
        }
        val retention = RetentionScoreService(f.repository, dispatcher = f.dispatcher)
        retention.assessSuspected(f.repository.suspectedDuplicates())
        val category = DeletionRecommendationService(f.repository, retention, f.dispatcher).load()
            .categories.single { it.category.id == "same-directory-metadata" }
        val selected = SelectedDeletionGroup(category.category, category.groups.single())
        f.repository.configureBinRoot(bin, 4)
        return Fixture(retention, FileOperationRepository(createFilesDatabase(f.driver), f.lane), selected, bin)
    }

    private fun service(f: ConcurrencyFixture, fixture: Fixture, fileSystem: FileSystem = f.fakeFs) =
        FileOperationService(f.repository, fixture.proposals, fixture.retention, f.lane,
            fileSystem, f.dispatcher, clock = { 100 }, nextId = { "proposal-1" })

    @Test
    fun persistsExactCandidatesSurvivorsAndEvidenceWithoutChangingFilesOrReassessing() = concurrencyTest { f ->
        val fixture = seed(f)
        val beforePaths = f.fakeFs.listRecursively(f.root).toList()
        val beforeFiles = beforePaths.filter { f.fakeFs.metadata(it).isRegularFile }
            .associateWith { f.fakeFs.metadata(it).toString() }
        val start = f.driver.statements.size
        val inspected = mutableListOf<Path>()
        val metadataOnly = object : ForwardingFileSystem(f.fakeFs) {
            override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path {
                check(functionName == "metadataOrNull") { "Unexpected filesystem operation: $functionName" }
                inspected += path
                return path
            }
        }
        val report = service(f, fixture, metadataOnly).createProposal(listOf(fixture.selected))
        assertEquals(FileOperationValidationStatus.READY, report.validationStatus)
        assertEquals(fixture.bin / "archive" / "Family" / "IMG-001.JPG", report.moves.single().destinationPath)
        assertEquals("archive/Family/IMG-001.JPG".toPath(), report.moves.single().relativePath)
        assertNull(report.archiveRoot)
        assertEquals(f.root / "Family" / "IMG.JPG", report.moves.single().survivorPath)
        assertEquals(fixture.selected.group.recommendation.files.keys,
            report.groups.single().members.map { it.file.path }.toSet())
        assertEquals(fixture.selected.group.candidates.single().advice.explanation,
            report.groups.single().members.single { it.role == FileRecommendationRole.REDUNDANT_CANDIDATE }.explanation)
        assertTrue(report.groups.single().members.all { it.evidence["retention"] != null && it.evidence["exif"] != null })
        assertEquals(report, fixture.proposals.load(report.id))
        assertEquals(1L, fixture.proposals.recent().single().candidateCount)
        assertEquals(beforePaths, f.fakeFs.listRecursively(f.root).toList())
        assertEquals(beforeFiles, beforeFiles.keys.associateWith { f.fakeFs.metadata(it).toString() })
        assertFalse(f.fakeFs.exists(fixture.bin))
        assertEquals(inspected.distinct().size, inspected.size, "Inspect shared parent paths only once per dry run")
        assertTrue(f.driver.statements.drop(start).all {
            it.startsWith("SELECT") || it.startsWith("WITH") || it.startsWith("INSERT INTO fileOperationProposal")
        })
    }

    @Test
    fun destinationCollisionAndChangedSourceAreSavedAsFindingsNotMoves() = concurrencyTest { f ->
        val fixture = seed(f)
        val candidate = fixture.selected.group.candidates.single().advice.file.path
        val destination = fixture.bin / "archive" / "Family" / candidate.name
        f.write(destination, "existing file")
        f.fakeFs.write(candidate) { writeUtf8("changed") }
        val report = service(f, fixture).createProposal(listOf(fixture.selected))
        assertEquals(FileOperationValidationStatus.NEEDS_ATTENTION, report.validationStatus)
        assertTrue(report.moves.single().findings.any { it.code == FileOperationIssueCode.DESTINATION_EXISTS })
        assertTrue(report.moves.single().findings.any { it.code == FileOperationIssueCode.SOURCE_CHANGED })
        assertEquals("existing file", f.fakeFs.read(destination) { readUtf8() })
        assertEquals("changed", f.fakeFs.read(candidate) { readUtf8() })
        assertEquals(report, fixture.proposals.load(report.id))
    }

    @Test
    fun missingSurvivorAndStaleStoredEvidenceBlockProposalWithoutInventingAKeeper() = concurrencyTest { f ->
        val fixture = seed(f)
        val keeper = fixture.selected.group.survivors.single().advice.file.path
        f.fakeFs.delete(keeper)
        val candidate = fixture.selected.group.candidates.single().advice.file.path
        val record = f.repository.fileExif(listOf(candidate)).single()
        f.repository.saveFileExif(record.copy(readerVersion = "old"))
        val report = service(f, fixture).createProposal(listOf(fixture.selected))
        assertEquals(FileOperationValidationStatus.NEEDS_ATTENTION, report.validationStatus)
        assertTrue(report.findings.any { it.code == FileOperationIssueCode.SURVIVOR_MISSING })
        assertTrue(report.findings.any { it.code == FileOperationIssueCode.STALE_RECOMMENDATION })
        assertEquals(keeper, report.moves.single().survivorPath)
    }

    @Test
    fun missingBinAndParentDirectoriesDoNotBlockDryRunsOrCreateAnything() = concurrencyTest { f ->
        val fixture = seed(f)
        val missingBin = "/not/created/yet/DeduplicatorGarbageBin".toPath()
        f.repository.configureBinRoot(missingBin, 5)
        val report = service(f, fixture).createProposal(listOf(fixture.selected))
        assertEquals(FileOperationValidationStatus.READY, report.validationStatus)
        assertEquals(missingBin / "archive/Family/IMG-001.JPG", report.moves.single().destinationPath)
        assertFalse(f.fakeFs.exists("/not".toPath()))
    }

    @Test
    fun fileInDestinationParentChainBlocksTheMove() = concurrencyTest { f ->
        val fixture = seed(f)
        f.fakeFs.createDirectory(fixture.bin)
        f.write(fixture.bin / "archive", "not a directory")
        val report = service(f, fixture).createProposal(listOf(fixture.selected))
        assertEquals(FileOperationValidationStatus.NEEDS_ATTENTION, report.validationStatus)
        assertTrue(report.moves.single().findings.isNotEmpty())
    }

    @Test
    fun existingBinFileIsStillRejectedEvenThoughMissingBinsAreAllowed() = concurrencyTest { f ->
        val fixture = seed(f)
        f.write(fixture.bin, "not a directory")
        val report = service(f, fixture).createProposal(listOf(fixture.selected))
        assertEquals(FileOperationValidationStatus.NEEDS_ATTENTION, report.validationStatus)
        assertTrue(report.findings.any { it.code == FileOperationIssueCode.BIN_NOT_DIRECTORY })
        assertEquals("not a directory", f.fakeFs.read(fixture.bin) { readUtf8() })
    }

    @Test
    fun newlyProtectedDirectoryBlocksTheEntireSelectedGroup() = concurrencyTest { f ->
        val fixture = seed(f)
        val folder = f.root / "Family"
        val context = OllamaSettings("http://ollama.test", "model").context()
        f.repository.saveDirectoryClassification(DirectoryClassificationRecord(
            folder, folder.toString(), DirectoryClassification.PROTECTED, context, 5))
        val service = FileOperationService(f.repository, fixture.proposals, fixture.retention, f.lane,
            f.fakeFs, f.dispatcher, classificationContext = { context },
            clock = { 100 }, nextId = { "protected" })
        val report = service.createProposal(listOf(fixture.selected))
        assertEquals(FileOperationValidationStatus.NEEDS_ATTENTION, report.validationStatus)
        assertTrue(report.findings.any { it.code == FileOperationIssueCode.PROTECTED_GROUP })
        assertTrue(report.findings.any { it.code == FileOperationIssueCode.STALE_RECOMMENDATION })
        assertTrue(report.groups.single().members.all { it.classification == DirectoryClassification.PROTECTED })
    }

    @Test
    fun sourceSymlinkIsRejected() = concurrencyTest { f ->
        val fixture = seed(f)
        f.fakeFs.allowSymlinks = true
        val candidate = fixture.selected.group.candidates.single().advice.file.path
        val target = "/elsewhere".toPath()
        f.write(target, "other")
        f.fakeFs.delete(candidate)
        f.fakeFs.createSymlink(candidate, target)
        val report = service(f, fixture).createProposal(listOf(fixture.selected))
        assertEquals(FileOperationValidationStatus.NEEDS_ATTENTION, report.validationStatus)
        assertTrue(report.moves.single().findings.any { it.code == FileOperationIssueCode.SOURCE_NOT_REGULAR })
    }

    @Test
    fun destinationSymlinkAncestorIsRejected() = concurrencyTest { f ->
        val fixture = seed(f)
        f.fakeFs.allowSymlinks = true
        f.fakeFs.createDirectory(fixture.bin)
        f.fakeFs.createSymlink(fixture.bin / "archive", f.root)
        val report = service(f, fixture).createProposal(listOf(fixture.selected))
        assertEquals(FileOperationValidationStatus.NEEDS_ATTENTION, report.validationStatus)
        assertTrue(report.moves.single().findings.any {
            it.code == FileOperationIssueCode.UNSAFE_PATH && it.path == fixture.bin / "archive"
        })
    }

    @Test
    fun savedEvidenceAndOldBinMappingRemainStableAfterConfigurationChanges() = concurrencyTest { f ->
        val fixture = seed(f)
        val report = service(f, fixture).createProposal(listOf(fixture.selected, fixture.selected))
        assertEquals(1, report.moves.size)
        assertEquals(1, report.groups.size)
        f.repository.configureBinRoot("/AnotherBin".toPath(), 200)
        assertEquals(report, fixture.proposals.load(report.id))
        assertEquals(fixture.bin, fixture.proposals.load(report.id).binRoot)
        assertTrue(fixture.bin in f.repository.registeredBinRoots())
    }
}
