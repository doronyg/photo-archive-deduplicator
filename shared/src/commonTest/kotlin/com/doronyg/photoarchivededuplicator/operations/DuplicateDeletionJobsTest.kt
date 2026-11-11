package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.classification.*
import com.doronyg.photoarchivededuplicator.exif.*
import com.doronyg.photoarchivededuplicator.recommendation.*
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.scan.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DuplicateDeletionJobsTest {
    private data class Fixture(
        val files: List<FileRecord>,
        val retention: RetentionScoreService,
        val proposals: FileOperationRepository,
    ) {
        fun selection(paths: Set<Path>? = null) =
            DuplicateDeletionSelection(DeletionRecommendationSource.SUSPECTED, files, paths)
    }

    private suspend fun seed(f: ConcurrencyFixture, evidence: Boolean = false): Fixture {
        val folder = f.root / "Family"
        val names = listOf("IMG.JPG", "IMG-001.JPG", "IMG-002.JPG")
        names.forEach { f.write(folder / it, "a".repeat(120_001)) }
        f.repository.seedRoot(f.root)
        f.repository.recordListing(f.root, DirectoryListing(
            listOf(DirectoryListing.Entry(folder, 0, null)), emptyList()), 1)
        f.repository.recordListing(folder, DirectoryListing(emptyList(), names.map {
            val path = folder / it
            val meta = f.fakeFs.metadata(path)
            DirectoryListing.Entry(path, meta.size!!, meta.lastModifiedAtMillis, meta.createdAtMillis)
        }), 2)
        val files = f.repository.filesIn(folder)
        val retention = RetentionScoreService(f.repository, dispatcher = f.dispatcher)
        files.forEach { f.repository.saveFileExif(ExifRecord(ExifInput.from(it),
            ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(capturedAt = "2023:02:18 12:44:25",
                make = "Canon", model = "Camera", width = 4000, height = 3000)), 3)) }
        if (evidence) {
            retention.assessSuspected(f.repository.suspectedDuplicates())
        }
        f.repository.configureBinRoot("/Bin".toPath(), 4)
        return Fixture(files, retention, FileOperationRepository(createFilesDatabase(f.driver), f.lane))
    }

    private fun service(f: ConcurrencyFixture, fixture: Fixture, fs: FileSystem = f.fakeFs,
        context: DirectoryClassificationContext? = null) =
        FileOperationService(f.repository, fixture.proposals, fixture.retention, f.lane, fs, f.dispatcher,
            classificationContext = { context }, clock = { 100 }, nextId = { "duplicate-job" })

    @Test
    fun keepAllRecommendationCanBeAddedAndDryRunPreservesEveryMember() = concurrencyTest { f ->
        val fixture = seed(f)
        val service = service(f, fixture)
        val controller = FileOperationController(this, f.repository, fixture.proposals, service)
        controller.addDuplicateSelections(listOf(fixture.selection()))
        advanceUntilIdle()
        assertNull(controller.state.value.error)
        assertEquals("/Bin".toPath(), controller.state.value.binRoot)
        val selected = controller.state.value.selectedGroups.single()
        assertTrue(selected.group.candidates.isEmpty())
        assertEquals(fixture.files.size, selected.group.survivors.size)
        controller.createDryRun()
        advanceUntilIdle()
        assertNull(controller.state.value.error)
        val proposal = assertNotNull(controller.state.value.proposal)
        assertTrue(proposal.moves.isEmpty())
        assertFalse(proposal.canExecute)
        assertTrue(proposal.groups.single().explanation.contains(
            com.doronyg.photoarchivededuplicator.strings.AppStrings.operations.groupKeepsAll))
        assertEquals(proposal, fixture.proposals.load(proposal.id))
        fixture.files.forEach { assertTrue(f.fakeFs.exists(it.path)) }
    }

    @Test
    fun manualReviewOverrideIsMetadataOnlyAndSavedProposalRevalidatesWithoutProviderCategories() = concurrencyTest { f ->
        val fixture = seed(f)
        assertTrue(DeletionRecommendationService(f.repository, fixture.retention, f.dispatcher).load()
            .categories.all { it.groups.isEmpty() })
        val sources = setOf(fixture.files.first().path)
        val inspected = mutableListOf<Path>()
        val metadataOnly = object : ForwardingFileSystem(f.fakeFs) {
            override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path {
                check(functionName == "metadataOrNull") { "Unexpected filesystem operation $functionName" }
                inspected += path
                return path
            }
        }
        val service = service(f, fixture, metadataOnly)
        val queries = f.driver.statements.size
        val selected = service.resolveDuplicateSelections(listOf(fixture.selection(sources)))
        assertTrue(inspected.isEmpty(), "Adding choices must use stored evidence only")
        val proposal = service.createProposal(selected)
        assertTrue(proposal.canExecute, proposal.toString())
        assertEquals(sources, proposal.moves.map { it.sourcePath }.toSet())
        assertFalse(inspected.any { it in sources }, "Never precheck candidate source files")
        assertTrue(proposal.groups.single().members.filter { it.file.path !in sources }
            .all { it.role == FileRecommendationRole.REVIEW })
        val restored = fixture.proposals.load(proposal.id)
        assertEquals(proposal, restored)
        assertNotNull(restored.groups.single().duplicateProvenance)
        assertTrue(service.revalidateForExecution(restored).groupFindings.isEmpty())
        assertEquals(1, fixture.proposals.recent().size)
        val statements = f.driver.statements.drop(queries)
        assertFalse(statements.any { it.contains("INSERT INTO fileExif", true) ||
            it.contains("INSERT INTO fileScore", true) }, "Must not extract or rescore")
        assertFalse(f.fakeFs.exists("/Bin".toPath()))
    }

    @Test
    fun groupRecommendationAndManualKeeperOverrideBothUseCurrentEngineAndOriginalEvidence() = concurrencyTest { f ->
        val fixture = seed(f, evidence = true)
        val service = service(f, fixture)
        val enginePlan = service.resolveDuplicateSelections(listOf(fixture.selection())).single()
        assertEquals(2, enginePlan.group.candidates.size)
        val keeper = enginePlan.group.survivors.single().advice.file.path
        val manual = service.resolveDuplicateSelections(listOf(fixture.selection(setOf(keeper)))).single()
        assertEquals(enginePlan.key, manual.key)
        assertEquals(setOf(keeper), manual.group.candidates.map { it.advice.file.path }.toSet())
        val proposal = service.createProposal(listOf(manual))
        assertTrue(proposal.canExecute)
        assertTrue(service.revalidateForExecution(proposal).groupFindings.isEmpty())
        val retained = proposal.moves.single().survivorPath!!
        val exif = f.repository.fileExif(listOf(retained)).single()
        f.repository.saveFileExif(exif.copy(extractedAt = 99))
        assertTrue(service.revalidateForExecution(proposal).groupFindings.getValue(manual.key)
            .any { it.code == FileOperationIssueCode.STALE_RECOMMENDATION })
    }

    @Test
    fun protectedAndStaleGroupInputsFailBeforeAnyFilesystemAccess() = concurrencyTest { f ->
        val fixture = seed(f)
        val context = OllamaSettings("http://test", "model").context()
        val folder = fixture.files.first().directoryPath
        f.repository.saveDirectoryClassification(DirectoryClassificationRecord(folder, folder.toString(),
            DirectoryClassification.PROTECTED, context, 6))
        val noFs = object : ForwardingFileSystem(f.fakeFs) {
            override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path =
                error("No filesystem prechecks permitted")
        }
        val service = service(f, fixture, noFs, context)
        val selection = fixture.selection(setOf(fixture.files.first().path))
        assertEquals(FileOperationIssueCode.PROTECTED_GROUP, assertFailsWith<FileOperationException> {
            service.resolveDuplicateSelections(listOf(selection))
        }.finding.code)
        assertEquals(FileOperationIssueCode.STALE_RECOMMENDATION, assertFailsWith<FileOperationException> {
            service.resolveDuplicateSelections(listOf(selection.copy(files = fixture.files.drop(1))))
        }.finding.code)
    }

    @Test
    fun savedManualProposalRejectsChangedKeeperMetadataAndNewProtection() = concurrencyTest { f ->
        val fixture = seed(f)
        val context = OllamaSettings("http://test", "model").context()
        val service = service(f, fixture, context = context)
        val selected = service.resolveDuplicateSelections(listOf(fixture.selection(setOf(fixture.files.first().path))))
        val proposal = service.createProposal(selected)
        assertTrue(proposal.canExecute)
        f.fakeFs.write(proposal.moves.single().survivorPath!!) { writeUtf8("changed") }
        assertTrue(service.revalidateForExecution(proposal).groupFindings.getValue(selected.single().key)
            .any { it.code == FileOperationIssueCode.SURVIVOR_CHANGED })
        val folder = fixture.files.first().directoryPath
        f.repository.saveDirectoryClassification(DirectoryClassificationRecord(folder, folder.toString(),
            DirectoryClassification.PROTECTED, context, 6))
        assertTrue(service.revalidateForExecution(proposal).groupFindings.getValue(selected.single().key)
            .any { it.code == FileOperationIssueCode.PROTECTED_GROUP })
    }

    @Test
    fun regularGroupSelectionUsesRegularInventoryAndRejectsPartialGroup() = concurrencyTest { f ->
        val folders = listOf(f.root / "Family", f.root / "Trips")
        folders.forEach { f.write(it / "IMG.JPG", "a".repeat(120_001)) }
        f.repository.seedRoot(f.root)
        f.repository.recordListing(f.root, DirectoryListing(
            folders.map { DirectoryListing.Entry(it, 0, null) }, emptyList()), 1)
        folders.forEach { folder ->
            val path = folder / "IMG.JPG"
            val meta = f.fakeFs.metadata(path)
            f.repository.recordListing(folder, DirectoryListing(emptyList(),
                listOf(DirectoryListing.Entry(path, meta.size!!, meta.lastModifiedAtMillis, meta.createdAtMillis))), 2)
        }
        val files = f.repository.duplicateCandidates().single().files
        val fixture = Fixture(files, RetentionScoreService(f.repository, dispatcher = f.dispatcher),
            FileOperationRepository(createFilesDatabase(f.driver), f.lane))
        f.repository.configureBinRoot("/Bin".toPath(), 4)
        val service = service(f, fixture)
        val input = DuplicateDeletionSelection(DeletionRecommendationSource.REGULAR, files, setOf(files.first().path))
        val selected = service.resolveDuplicateSelections(listOf(input))
        val proposal = service.createProposal(selected)
        assertTrue(proposal.canExecute)
        assertEquals(DeletionRecommendationSource.REGULAR, proposal.groups.single().duplicateProvenance?.selection?.source)
        assertTrue(service.revalidateForExecution(fixture.proposals.load(proposal.id)).groupFindings.isEmpty())
        assertEquals(FileOperationIssueCode.STALE_RECOMMENDATION, assertFailsWith<FileOperationException> {
            service.resolveDuplicateSelections(listOf(input.copy(files = files.take(1))))
        }.finding.code)
    }

    @Test
    fun manualChoiceCannotDeleteAnotherSelectedGroupsRetainedCopy() = concurrencyTest { f ->
        val fixture = seed(f, evidence = true)
        val service = service(f, fixture)
        val report = DeletionRecommendationService(f.repository, fixture.retention, f.dispatcher).load()
        val category = report.categories.first { it.groups.isNotEmpty() }
        val existing = SelectedDeletionGroup(category.category, category.groups.single())
        val keeper = existing.group.survivors.single().advice.file.path
        val manual = service.resolveDuplicateSelections(listOf(fixture.selection(setOf(keeper)))).single()
        val proposal = service.createProposal(listOf(existing, manual))
        assertFalse(proposal.canExecute)
        assertTrue(proposal.moves.all { move ->
            move.findings.any { it.code == FileOperationIssueCode.SURVIVOR_SELECTED }
        })
    }

    @Test
    fun recommendationPoolReplacementPreservesSelectedAndDeselectedDuplicateOriginJobs() = concurrencyTest { f ->
        val fixture = seed(f, evidence = true)
        val service = service(f, fixture)
        val controller = FileOperationController(this, f.repository, fixture.proposals, service)
        val duplicates = service.resolveDuplicateSelections(listOf(fixture.selection())).single()
        controller.addDuplicateSelections(listOf(fixture.selection()))
        advanceUntilIdle()
        val report = DeletionRecommendationService(f.repository, fixture.retention, f.dispatcher).load()
        val category = report.categories.first { it.groups.isNotEmpty() }
        val regular = SelectedDeletionGroup(category.category, category.groups.single())
        controller.selectGroups(listOf(regular))
        assertEquals(listOf(duplicates, regular), controller.state.value.selectedGroups)
        assertEquals(listOf(duplicates, regular), controller.state.value.availableGroups)
        controller.createDryRun()
        advanceUntilIdle()
        val proposal = assertNotNull(controller.state.value.proposal)
        controller.selectGroups(listOf(regular))
        assertEquals(proposal, controller.state.value.proposal, "Identical recommendation additions preserve the display")
        controller.setGroupSelected(duplicates.key, false)
        controller.selectGroups(emptyList())
        assertTrue(controller.state.value.selectedGroups.isEmpty())
        assertEquals(listOf(duplicates), controller.state.value.availableGroups)
        controller.selectAllGroups()
        assertEquals(listOf(duplicates), controller.state.value.selectedGroups)
    }

    @Test
    fun controllerAppendsMergesPreservesUnrelatedAndOnlyInvalidatesChangedSelection() = concurrencyTest { f ->
        val fixture = seed(f, evidence = true)
        val service = service(f, fixture)
        val report = DeletionRecommendationService(f.repository, fixture.retention, f.dispatcher).load()
        val category = report.categories.first { it.groups.isNotEmpty() }
        val unrelated = SelectedDeletionGroup(category.category, category.groups.single())
        val controller = FileOperationController(this, f.repository, fixture.proposals, service)
        controller.selectGroups(listOf(unrelated))
        controller.addDuplicateSelections(listOf(fixture.selection()))
        advanceUntilIdle()
        assertNull(controller.state.value.error)
        assertEquals("/Bin".toPath(), controller.state.value.binRoot,
            "First entry into File Operations must have configuration loaded after adding selections")
        assertEquals(2, controller.state.value.selectedGroups.size)
        assertEquals(unrelated, controller.state.value.selectedGroups.first())
        controller.createDryRun()
        advanceUntilIdle()
        val groupProposal = assertNotNull(controller.state.value.proposal)
        controller.addDuplicateSelections(listOf(fixture.selection()))
        advanceUntilIdle()
        assertEquals(groupProposal, controller.state.value.proposal, "Identical addition retains approval display")
        controller.selectGroups(listOf(unrelated))
        assertEquals(groupProposal, controller.state.value.proposal, "Refreshing the same recommendation pool preserves job order")
        controller.setGroupSelected(unrelated.key, false)
        val first = fixture.files.first().path
        controller.addDuplicateSelections(listOf(fixture.selection(setOf(first))))
        advanceUntilIdle()
        assertNull(controller.state.value.proposal)
        assertEquals(1, controller.state.value.selectedGroups.size)
        assertEquals(2, controller.state.value.availableGroups.size)
        assertEquals(setOf(first), controller.state.value.selectedGroups.single().duplicateProvenance?.selection?.selectedPaths)
        val second = fixture.files[1].path
        controller.addDuplicateSelections(listOf(fixture.selection(setOf(second))))
        advanceUntilIdle()
        assertEquals(setOf(first, second),
            controller.state.value.selectedGroups.single().duplicateProvenance?.selection?.selectedPaths)
        val state = controller.state.value
        controller.addDuplicateSelections(listOf(fixture.selection(setOf(fixture.files[2].path))))
        advanceUntilIdle()
        assertEquals(FileOperationIssueCode.SURVIVOR_SELECTED, controller.state.value.error?.code)
        assertEquals(state.selectedGroups, controller.state.value.selectedGroups)
        assertEquals(state.availableGroups, controller.state.value.availableGroups)
    }
}
