package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.classification.DirectoryClassification
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationRecord
import com.doronyg.photoarchivededuplicator.classification.OllamaSettings
import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.recommendation.DeletionRecommendationPolicy
import com.doronyg.photoarchivededuplicator.recommendation.DeletionRecommendationService
import com.doronyg.photoarchivededuplicator.recommendation.DeletionRecommendationSource
import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendationRole
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.scan.ConcurrencyFixture
import com.doronyg.photoarchivededuplicator.scan.DirectoryListing
import com.doronyg.photoarchivededuplicator.scan.concurrencyTest
import com.doronyg.photoarchivededuplicator.scan.createFilesDatabase
import com.doronyg.photoarchivededuplicator.scan.isInTree
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileOperationServiceTest {
    private data class Fixture(
        val retention: RetentionScoreService,
        val proposals: FileOperationRepository,
        val selected: SelectedDeletionGroup,
        val bin: Path,
    )

    private suspend fun seed(f: ConcurrencyFixture, folderName: String = "Family", copies: Int = 2): Fixture {
        val folder = f.root / folderName
        val bin = "/DeduplicatorGarbageBin".toPath()
        val prefix = if (folderName == "Family") "IMG" else folderName.uppercase()
        val names = listOf("$prefix.JPG") + (1 until copies).map { "$prefix-${it.toString().padStart(3, '0')}.JPG" }
        for (name in names) f.write(folder / name, "a".repeat(120_001))
        f.repository.seedRoot(f.root)
        f.repository.recordListing(f.root, DirectoryListing(
            f.fakeFs.list(f.root).filter { f.fakeFs.metadata(it).isDirectory }
                .map { DirectoryListing.Entry(it, 0, null) }, emptyList()), 1)
        f.repository.recordListing(folder, DirectoryListing(emptyList(), names.map { name ->
            val path = folder / name
            val metadata = f.fakeFs.metadata(path)
            DirectoryListing.Entry(path, metadata.size!!, metadata.lastModifiedAtMillis, metadata.createdAtMillis)
        }), 2)
        f.repository.filesIn(folder).forEach { file ->
            f.repository.saveFileExif(ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED,
                ExifMetadata(capturedAt = if (folderName == "Family") "2023:02:18 12:44:25" else "2024:02:18 12:44:25",
                    make = "Canon", model = "Camera",
                    width = 4000, height = 3000)), 3))
        }
        val retention = RetentionScoreService(f.repository, dispatcher = f.dispatcher)
        retention.assessSuspected(f.repository.suspectedDuplicates())
        val category = DeletionRecommendationService(f.repository, retention, f.dispatcher).load()
            .categories.single { it.category.id == "same-directory-metadata" }
        val selected = SelectedDeletionGroup(category.category, category.groups.single {
            it.recommendation.files.values.any { advice -> advice.file.directoryPath == folder }
        })
        f.repository.configureBinRoot(bin, 4)
        return Fixture(retention, FileOperationRepository(createFilesDatabase(f.driver), f.lane), selected, bin)
    }

    private fun service(f: ConcurrencyFixture, fixture: Fixture, fileSystem: FileSystem = f.fakeFs) =
        FileOperationService(f.repository, fixture.proposals, fixture.retention, f.lane,
            fileSystem, f.dispatcher, clock = { 100 }, nextId = { "proposal-1" })

    @Test
    fun protectedCategoryUsesFreshProviderPolicyWhileOrdinaryManualAndForgedPlansRemainBlocked() = concurrencyTest { f ->
        val fixture = seed(f)
        val context = OllamaSettings("http://ollama.test", "model").context()
        val directory = f.root / "Family"
        val classification = DirectoryClassificationRecord(directory, directory.toString(),
            DirectoryClassification.PROTECTED, context, 5)
        f.repository.saveDirectoryClassification(classification)
        val category = DeletionRecommendationService(f.repository, fixture.retention, f.dispatcher, context).load()
            .categories.single { it.category.id == "single-protected-directory" }
        val selected = SelectedDeletionGroup(category.category, category.groups.single())
        var id = 0
        val service = FileOperationService(f.repository, fixture.proposals, fixture.retention, f.lane,
            f.fakeFs, f.dispatcher, classificationContext = { context },
            clock = { 100 }, nextId = { "protected-${++id}" })
        val proposal = service.createProposal(listOf(selected))
        assertTrue(proposal.canExecute)
        assertTrue(proposal.groups.single().allowsSingleProtectedDirectory())
        assertTrue(proposal.groups.single().members.all { it.classification == DirectoryClassification.PROTECTED })
        assertEquals(proposal, fixture.proposals.load(proposal.id))
        val validation = service.revalidateForExecution(proposal)
        assertTrue(validation.groupFindings.isEmpty())
        service.executionMoveValidator(proposal, validation)(proposal.moves.single())
        assertFalse(service.createProposal(listOf(fixture.selected)).canExecute)
        assertFalse(service.createProposal(listOf(selected.copy(group = selected.group.copy(
            policy = DeletionRecommendationPolicy.ORDINARY)))).canExecute)
        assertFalse(service.createProposal(listOf(selected.copy(category = selected.category.copy(id = "forged")))).canExecute)
        for (paths in listOf(null, setOf(selected.group.candidates.single().advice.file.path))) {
            val failure = assertFailsWith<FileOperationException> {
                service.resolveDuplicateSelections(listOf(DuplicateDeletionSelection(
                    DeletionRecommendationSource.SUSPECTED, selected.group.recommendation.files.values.map { it.file }, paths)))
            }
            assertEquals(FileOperationIssueCode.PROTECTED_GROUP, failure.finding.code)
        }
        f.repository.saveDirectoryClassification(classification.copy(classification = DirectoryClassification.PREFERRED,
            classifiedAt = 6))
        assertFalse(service.createProposal(listOf(selected)).canExecute)
        assertTrue(service.revalidateForExecution(proposal).groupFindings.isNotEmpty())
        assertFailsWith<FileOperationException> {
            service.executionMoveValidator(proposal, validation)(proposal.moves.single())
        }
        assertFalse(f.fakeFs.exists(fixture.bin))
    }

    @Test
    fun protectedBeforeMoveRejectsChangedStoredEvidence() = concurrencyTest { f ->
        val fixture = seed(f)
        val context = OllamaSettings("http://ollama.test", "model").context()
        val directory = f.root / "Family"
        f.repository.saveDirectoryClassification(DirectoryClassificationRecord(directory, directory.toString(),
            DirectoryClassification.PROTECTED, context, 5))
        val category = DeletionRecommendationService(f.repository, fixture.retention, f.dispatcher, context).load()
            .categories.single { it.category.id == "single-protected-directory" }
        val service = FileOperationService(f.repository, fixture.proposals, fixture.retention, f.lane,
            f.fakeFs, f.dispatcher, classificationContext = { context }, nextId = { "protected-evidence" })
        val proposal = service.createProposal(listOf(SelectedDeletionGroup(category.category, category.groups.single())))
        val validation = service.revalidateForExecution(proposal)
        val file = category.groups.single().survivors.single().advice.file
        val record = f.repository.fileExif(listOf(file.path)).single()
        f.repository.saveFileExif(record.copy(result = record.result.copy(
            metadata = record.result.metadata.copy(model = "Other camera")), extractedAt = 6))
        assertEquals(FileOperationIssueCode.STALE_RECOMMENDATION, assertFailsWith<FileOperationException> {
            service.executionMoveValidator(proposal, validation)(proposal.moves.single())
        }.finding.code)
        assertTrue(service.revalidateForExecution(proposal).groupFindings.isNotEmpty())
    }

    @Test
    fun protectedCategoryExecutesThroughPreparationAndJournalWindowsLeavingOneCopy() = concurrencyTest { f ->
        val fixture = seed(f, copies = 52)
        val context = OllamaSettings("http://ollama.test", "model").context()
        val directory = f.root / "Family"
        f.repository.saveDirectoryClassification(DirectoryClassificationRecord(directory, directory.toString(),
            DirectoryClassification.PROTECTED, context, 5))
        val category = DeletionRecommendationService(f.repository, fixture.retention, f.dispatcher, context).load()
            .categories.single { it.category.id == "single-protected-directory" }
        val selected = SelectedDeletionGroup(category.category, category.groups.single())
        val binding = com.doronyg.photoarchivededuplicator.operations.history.OperationHistoryStore(
            f.lane, f.fakeFs, f.dispatcher).register(f.root, fixture.bin)
        val service = FileOperationService(f.repository, fixture.proposals, fixture.retention, f.lane,
            f.fakeFs, f.dispatcher, classificationContext = { context }, nextId = { "protected-run" })
        val proposal = service.createProposal(listOf(selected))
        val validation = service.revalidateForExecution(proposal)
        val preparer = BinDirectoryPreparer(f.lane, f.fakeFs, f.dispatcher)
        for (invalidGroup in listOf(
            proposal.groups.single().copy(policy = DeletionRecommendationPolicy.ORDINARY),
            proposal.groups.single().copy(members = proposal.groups.single().members.map {
                it.copy(classification = DirectoryClassification.PREFERRED)
            }),
        )) {
            val rejected = preparer.prepareEligibleGroups(proposal.copy(groups = listOf(invalidGroup)), binding)
            assertEquals(setOf(invalidGroup.key), rejected.rejected.keys)
            assertTrue(rejected.preparedProposal.moves.isEmpty())
        }
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
            validate = { assertTrue(service.revalidateForExecution(proposal).groupFindings.isEmpty()) },
            beforeMove = service.executionMoveValidator(proposal, validation),
            recordMove = { f.repository.recordCompletedMove(it) })
        assertEquals(51, moved)
        assertTrue(result.items.all { it.status == FileMoveExecutionStatus.COMPLETED })
        assertEquals(listOf(selected.group.survivors.single().advice.file.path), f.repository.filesIn(directory).map { it.path })
        assertTrue(f.fakeFs.exists(selected.group.survivors.single().advice.file.path))
        assertTrue(proposal.moves.all { f.fakeFs.exists(it.destinationPath!!) && !f.fakeFs.exists(it.sourcePath) })
        assertTrue(proposal.groups.single().members.all { it.classification == DirectoryClassification.PROTECTED })
    }

    @Test
    fun executionRevalidationChecksTheSavedPlanWithoutCreatingAnotherProposal() = concurrencyTest { f ->
        val fixture = seed(f)
        val service = service(f, fixture)
        val proposal = service.createProposal(listOf(fixture.selected))
        assertNull(service.revalidateForExecution(proposal).classificationContext)
        var groupReads = 0
        val countedGroups = object : AbstractList<FileOperationGroup>() {
            override val size = proposal.groups.size
            override fun get(index: Int): FileOperationGroup {
                groupReads++
                return proposal.groups[index]
            }
        }
        val beforeMove = service.executionMoveValidator(proposal.copy(groups = countedGroups),
            FileOperationExecutionValidation(null))
        val initialReads = groupReads
        repeat(3) { beforeMove(proposal.moves.single()) }
        assertEquals(initialReads, groupReads, "Per-file validation must not rescan all approved groups")
        assertEquals(1, fixture.proposals.recent().size)
        assertEquals(proposal, fixture.proposals.load(proposal.id))
        f.fakeFs.write(proposal.moves.single().sourcePath) { writeUtf8("changed") }
        assertTrue(service.revalidateForExecution(proposal).groupFindings.isEmpty())
        assertEquals(1, fixture.proposals.recent().size)
    }

    @Test
    fun executionRejectsChangedRecommendationsAndNewlyProtectedGroups() = concurrencyTest { f ->
        val fixture = seed(f)
        val context = OllamaSettings("http://ollama.test", "model").context()
        val service = FileOperationService(f.repository, fixture.proposals, fixture.retention, f.lane,
            f.fakeFs, f.dispatcher, classificationContext = { context },
            clock = { 100 }, nextId = { "approved" })
        val proposal = service.createProposal(listOf(fixture.selected))
        val validation = service.revalidateForExecution(proposal)
        assertEquals(context, validation.classificationContext)
        val beforeMove = service.executionMoveValidator(proposal, validation)
        f.repository.saveDirectoryClassification(DirectoryClassificationRecord(
            f.root / "Family", (f.root / "Family").toString(), DirectoryClassification.PROTECTED, context, 5))
        val failure = assertFailsWith<FileOperationException> {
            beforeMove(proposal.moves.single())
        }
        assertEquals(FileOperationIssueCode.PROTECTED_GROUP, failure.finding.code)
        assertFalse(failure.global)
        val stale = service.revalidateForExecution(proposal)
        assertTrue(stale.groupFindings.getValue(proposal.groups.single().key).isNotEmpty())
        assertFalse(f.fakeFs.exists(fixture.bin))
    }

    @Test
    fun confirmedMoveInventoryUpdateRemovesOnlyTheMatchingSourceAndItsEvidence() = concurrencyTest { f ->
        val fixture = seed(f)
        val source = fixture.selected.group.candidates.single().advice.file
        val keeper = fixture.selected.group.survivors.single().advice.file
        val sourceScore = f.repository.fileScores(listOf(source.path)).single()
        assertTrue(f.repository.saveFileScore(sourceScore.copy(
            identity = sourceScore.identity.copy(configurationId = "historical-profile"))))
        val retainedScores = f.repository.fileScores(listOf(keeper.path))
        val retainedExif = f.repository.fileExif(listOf(keeper.path))
        assertEquals(2, f.repository.fileScores(listOf(source.path)).size)
        f.repository.recordCompletedMove(source)
        assertNull(f.repository.file(source.path))
        assertNotNull(f.repository.file(keeper.path))
        assertTrue(f.repository.fileExif(listOf(source.path)).isEmpty())
        assertTrue(f.repository.fileScores(listOf(source.path)).isEmpty())
        assertEquals(retainedScores, f.repository.fileScores(listOf(keeper.path)))
        assertEquals(retainedExif, f.repository.fileExif(listOf(keeper.path)))
        assertEquals(1, f.repository.directory(source.directoryPath)?.fileCount)
        assertEquals(1L, f.repository.summary().fileCount)
        assertTrue(f.repository.suspectedDuplicates().isEmpty())
        f.repository.recordCompletedMove(source)
        assertEquals(1, f.repository.directory(source.directoryPath)?.fileCount)
        val current = assertNotNull(f.repository.file(keeper.path))
        assertFailsWith<IllegalStateException> {
            f.repository.recordCompletedMove(current.copy(sizeBytes = current.sizeBytes + 1))
        }
        assertNotNull(f.repository.file(keeper.path))
    }

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
        assertEquals(5, inspected.size, "Do not stat candidate files or destinations beneath the missing Bin")
        assertFalse(report.moves.single().sourcePath in inspected)
        assertTrue(inspected.none { it != fixture.bin && it.isInTree(fixture.bin) })
        assertTrue(f.driver.statements.drop(start).all {
            it.startsWith("SELECT") || it.startsWith("WITH") || it.startsWith("INSERT INTO fileOperationProposal")
        })
    }

    @Test
    fun destinationCollisionIsSavedButChangedSourceIsNotInspected() = concurrencyTest { f ->
        val fixture = seed(f)
        val candidate = fixture.selected.group.candidates.single().advice.file.path
        val destination = fixture.bin / "archive" / "Family" / candidate.name
        f.write(destination, "existing file")
        f.fakeFs.write(candidate) { writeUtf8("changed") }
        val report = service(f, fixture).createProposal(listOf(fixture.selected))
        assertEquals(FileOperationValidationStatus.NEEDS_ATTENTION, report.validationStatus)
        assertTrue(report.moves.single().findings.any { it.code == FileOperationIssueCode.DESTINATION_EXISTS })
        assertTrue(report.moves.single().findings.none { it.code == FileOperationIssueCode.SOURCE_CHANGED })
        assertEquals("existing file", f.fakeFs.read(destination) { readUtf8() })
        assertEquals("changed", f.fakeFs.read(candidate) { readUtf8() })
        assertEquals(report, fixture.proposals.load(report.id))
    }

    @Test
    fun executionRevalidationDoesNotReuseMissingDestinationObservationsFromTheDryRun() = concurrencyTest { f ->
        val fixture = seed(f)
        val service = service(f, fixture)
        val proposal = service.createProposal(listOf(fixture.selected))
        val destination = assertNotNull(proposal.moves.single().destinationPath)
        f.write(destination, "created after dry run")
        val validation = service.revalidateForExecution(proposal)
        assertTrue(validation.groupFindings.values.flatten().any { it.code == FileOperationIssueCode.DESTINATION_EXISTS })
        val failure = assertFailsWith<FileOperationException> {
            service.executionMoveValidator(proposal, validation)(proposal.moves.single())
        }
        assertFalse(failure.global)
        assertEquals("created after dry run", f.fakeFs.read(destination) { readUtf8() })
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
        assertTrue(report.moves.single().findings.any { it.code == FileOperationIssueCode.SURVIVOR_MISSING })
        assertTrue(report.groups.single().findings.any { it.code == FileOperationIssueCode.STALE_RECOMMENDATION })
        assertTrue(report.findings.isEmpty())
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
        assertTrue(report.groups.single().findings.any { it.code == FileOperationIssueCode.PROTECTED_GROUP })
        assertTrue(report.groups.single().findings.any { it.code == FileOperationIssueCode.STALE_RECOMMENDATION })
        assertTrue(report.findings.isEmpty())
        assertTrue(report.groups.single().members.all { it.classification == DirectoryClassification.PROTECTED })
    }

    @Test
    fun sourceSymlinkIsNotInspectedUntilTheActualMove() = concurrencyTest { f ->
        val fixture = seed(f)
        f.fakeFs.allowSymlinks = true
        val candidate = fixture.selected.group.candidates.single().advice.file.path
        val target = "/elsewhere".toPath()
        f.write(target, "other")
        f.fakeFs.delete(candidate)
        f.fakeFs.createSymlink(candidate, target)
        val report = service(f, fixture).createProposal(listOf(fixture.selected))
        assertEquals(FileOperationValidationStatus.READY, report.validationStatus)
        assertTrue(report.moves.single().findings.isEmpty())
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
    fun missingCandidateIsReadyAndNeverInspectedInEitherValidationPass() = concurrencyTest { f ->
        val fixture = seed(f)
        val candidate = fixture.selected.group.candidates.single().advice.file.path
        f.fakeFs.delete(candidate)
        val guarded = object : ForwardingFileSystem(f.fakeFs) {
            override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path {
                assertNotEquals(candidate, path, "Service must leave candidate metadata to the move primitive")
                return path
            }
        }
        val service = service(f, fixture, guarded)
        val proposal = service.createProposal(listOf(fixture.selected))
        assertEquals(FileOperationValidationStatus.READY, proposal.validationStatus)
        val validation = service.revalidateForExecution(proposal)
        assertTrue(validation.groupFindings.isEmpty())
        service.executionMoveValidator(proposal, validation)(proposal.moves.single())
        assertNotNull(f.repository.file(candidate))
        assertEquals(2L, f.repository.summary().fileCount)
    }

    @Test
    fun sourceOnlyAncestorsAreNotProbedEvenWhenACandidateWouldBeADestinationAncestor() = concurrencyTest { f ->
        val fixture = seed(f)
        val candidate = fixture.selected.group.candidates.single()
        val source = "/unmounted-source/photos/photo.jpg".toPath()
        fun selection(path: Path): SelectedDeletionGroup {
            val advice = candidate.advice.copy(file = candidate.advice.file.copy(
                path = path, directoryPath = requireNotNull(path.parent), name = path.name))
            val group = fixture.selected.group
            return fixture.selected.copy(group = group.copy(
                candidates = listOf(candidate.copy(advice = advice)),
                recommendation = group.recommendation.copy(files =
                    group.recommendation.files - candidate.advice.file.path + (path to advice))))
        }
        val guarded = object : ForwardingFileSystem(f.fakeFs) {
            override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path {
                assertFalse(path.isInTree("/unmounted-source".toPath()), "Source-only tree must not be inspected")
                return path
            }
        }
        var id = 0
        val service = FileOperationService(f.repository, fixture.proposals, fixture.retention, f.lane,
            guarded, f.dispatcher, clock = { 100 }, nextId = { "source-tree-${id++}" })
        service.createProposal(listOf(selection(source)))
        f.repository.configureBinRoot("/unmounted-source/bin".toPath(), 6)
        val report = service.createProposal(listOf(selection("/unmounted-source".toPath())))
        assertTrue(report.findings.any { it.code == FileOperationIssueCode.UNSAFE_PATH })
    }

    @Test
    fun missingKeeperAndStaleSelectionRejectOnlyTheirOwnGroup() = concurrencyTest { f ->
        val bad = seed(f)
        val good = seed(f, "Holiday")
        val keeper = bad.selected.group.survivors.single().advice.file.path
        f.fakeFs.delete(keeper)
        val staleCandidate = bad.selected.group.candidates.single().advice.file.path
        val exif = f.repository.fileExif(listOf(staleCandidate)).single()
        f.repository.saveFileExif(exif.copy(readerVersion = "outdated"))
        val report = service(f, good).createProposal(listOf(bad.selected, good.selected))
        assertEquals(FileOperationValidationStatus.PARTIALLY_READY, report.validationStatus)
        assertTrue(report.findings.isEmpty())
        assertEquals(listOf(good.selected.group.candidates.single().advice.file.path),
            report.executionEligibility.moves.map { it.sourcePath })
        assertTrue(report.groups.single { it.key == bad.selected.key }.findings.any {
            it.code == FileOperationIssueCode.STALE_RECOMMENDATION
        })
        val validation = service(f, good).revalidateForExecution(report)
        assertEquals(setOf(bad.selected.key), validation.groupFindings.keys)
        val beforeMove = service(f, good).executionMoveValidator(report, validation)
        beforeMove(report.executionEligibility.moves.single())
        val failure = assertFailsWith<FileOperationException> {
            beforeMove(report.moves.single { it.sourcePath == staleCandidate })
        }
        assertFalse(failure.global)
    }

    @Test
    fun runtimeStaleGroupDoesNotInvalidateAnIndependentGroupButBinAndSettingsChangesAreGlobal() = concurrencyTest { f ->
        val bad = seed(f)
        val good = seed(f, "Holiday")
        var context = OllamaSettings("http://ollama.test", "model").context()
        val service = FileOperationService(f.repository, good.proposals, good.retention, f.lane,
            f.fakeFs, f.dispatcher, classificationContext = { context },
            clock = { 100 }, nextId = { "runtime" })
        val proposal = service.createProposal(listOf(bad.selected, good.selected))
        val candidate = bad.selected.group.candidates.single().advice.file.path
        val record = f.repository.fileExif(listOf(candidate)).single()
        f.repository.saveFileExif(record.copy(readerVersion = "old"))
        val validation = service.revalidateForExecution(proposal)
        assertEquals(setOf(bad.selected.key), validation.groupFindings.keys)
        val beforeMove = service.executionMoveValidator(proposal, validation)
        val goodMove = proposal.moves.single { it.sourcePath != candidate }
        beforeMove(goodMove)
        assertFalse(assertFailsWith<FileOperationException> {
            beforeMove(proposal.moves.single { it.sourcePath == candidate })
        }.global)
        context = OllamaSettings("http://ollama.test", "another-model").context()
        assertTrue(assertFailsWith<FileOperationException> { beforeMove(goodMove) }.global)
        f.repository.configureBinRoot("/another-bin".toPath(), 200)
        assertTrue(assertFailsWith<FileOperationException> { service.revalidateForExecution(proposal) }.global)
    }

    @Test
    fun protectedGroupLeavesAnUnrelatedGroupEligible() = concurrencyTest { f ->
        val bad = seed(f)
        val good = seed(f, "Holiday")
        val context = OllamaSettings("http://ollama.test", "model").context()
        f.repository.saveDirectoryClassification(DirectoryClassificationRecord(
            f.root / "Family", (f.root / "Family").toString(), DirectoryClassification.PROTECTED, context, 5))
        val service = FileOperationService(f.repository, good.proposals, good.retention, f.lane,
            f.fakeFs, f.dispatcher, classificationContext = { context })
        val proposal = service.createProposal(listOf(bad.selected, good.selected))
        assertEquals(FileOperationValidationStatus.PARTIALLY_READY, proposal.validationStatus)
        assertTrue(proposal.findings.isEmpty())
        assertEquals(listOf(good.selected.group.candidates.single().advice.file.path),
            proposal.executionEligibility.moves.map { it.sourcePath })
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
