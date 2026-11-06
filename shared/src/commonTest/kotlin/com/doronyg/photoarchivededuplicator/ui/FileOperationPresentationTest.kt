package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.operations.FILE_BIN_NAME
import com.doronyg.photoarchivededuplicator.operations.FileOperationFinding
import com.doronyg.photoarchivededuplicator.operations.FileOperationGroup
import com.doronyg.photoarchivededuplicator.operations.FileOperationMember
import com.doronyg.photoarchivededuplicator.operations.FileOperationProposal
import com.doronyg.photoarchivededuplicator.operations.FileOperationIssueCode
import com.doronyg.photoarchivededuplicator.operations.FileOperationState
import com.doronyg.photoarchivededuplicator.operations.FileOperationValidationStatus
import com.doronyg.photoarchivededuplicator.operations.ProposedFileMove
import com.doronyg.photoarchivededuplicator.operations.SelectedDeletionGroup
import com.doronyg.photoarchivededuplicator.recommendation.DeletionCategoryResults
import com.doronyg.photoarchivededuplicator.recommendation.DeletionRecommendationCategory
import com.doronyg.photoarchivededuplicator.recommendation.DeletionRecommendationGroup
import com.doronyg.photoarchivededuplicator.recommendation.DeletionRecommendationReport
import com.doronyg.photoarchivededuplicator.recommendation.DeletionRecommendationSummary
import com.doronyg.photoarchivededuplicator.recommendation.DeletionReviewFile
import com.doronyg.photoarchivededuplicator.recommendation.DuplicateRecommendationEngine
import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendationRole
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import okio.Path.Companion.toPath
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileOperationPresentationTest {
    private val strings = AppStrings.operations

    @Test
    fun binSuggestionUsesConfigurationThenScanThenCommonMemberDirectory() {
        val configured = "/saved/custom-bin".toPath()
        val sources = listOf("/archive/photos/a/a.jpg", "/archive/photos/b/b.jpg").map { it.toPath() }
        assertEquals(configured.toString(), fileOperationBinSuggestion(configured, "/scan", sources))
        assertEquals("/storage/$FILE_BIN_NAME", fileOperationBinSuggestion(null, "/storage/photos", sources))
        assertEquals("/storage/$FILE_BIN_NAME", fileOperationBinSuggestion(null, "/storage/not-created-yet", emptyList()))
        assertEquals("/archive/$FILE_BIN_NAME", fileOperationBinSuggestion(null, "relative/photos", sources))
        assertEquals("", fileOperationBinSuggestion(null, "", emptyList()))
        assertEquals("", fileOperationBinSuggestion(null, "relative/photos", emptyList()))
        assertEquals("", fileOperationBinSuggestion(null, "/", listOf("/a.jpg".toPath(), "/other/b.jpg".toPath())))
    }

    @Test
    fun binSuggestionNeverProposesAnAncestorOfASource() {
        val sources = listOf("/storage/$FILE_BIN_NAME/photos/a.jpg".toPath())
        assertEquals("/storage/$FILE_BIN_NAME/$FILE_BIN_NAME",
            fileOperationBinSuggestion(null, "/storage/photos", sources))
        assertEquals("", fileOperationBinSuggestion(null, "", listOf("/$FILE_BIN_NAME/a.jpg".toPath())))
    }

    @Test
    fun validationIsNotApprovalAndAiIsAlwaysNotReviewed() {
        assertEquals("Dry-run metadata checks: Passed", strings.status(FileOperationValidationStatus.READY))
        assertEquals("Dry-run metadata checks: Needs attention", strings.status(FileOperationValidationStatus.NEEDS_ATTENTION))
        assertEquals("AI review: Not reviewed", strings.aiStatus)
        assertTrue(strings.availability.contains("unavailable"))
        assertEquals("Candidate", strings.role(FileRecommendationRole.REDUNDANT_CANDIDATE))
        assertEquals("Retained member", strings.role(FileRecommendationRole.PREFERRED))
        assertEquals("Preserved member", strings.role(FileRecommendationRole.PRESERVE))
        assertEquals("Member requiring review", strings.role(FileRecommendationRole.REVIEW))
    }

    @Test
    fun presentationUsesExactSavedPathsWithoutAddingIdentifiersOrComputingMappings() {
        val move = ProposedFileMove(
            "/photos/2024/雪.jpg".toPath(), "2024/雪.jpg".toPath(),
            "/storage/$FILE_BIN_NAME/2024/雪.jpg".toPath(), "/photos/retained/雪.jpg".toPath(), 123, emptyList(),
        )
        val fields = proposedMoveFields(move).toMap()
        assertEquals("2024/雪.jpg", fields[strings.relativePath])
        assertEquals("/storage/DeduplicatorGarbageBin/2024/雪.jpg", fields[strings.destination])
        assertEquals("/photos/retained/雪.jpg", fields[strings.survivor])
        val unmapped = proposedMoveFields(move.copy(relativePath = null, destinationPath = null)).toMap()
        assertEquals(strings.unmapped, unmapped[strings.destination])
        assertEquals(strings.unmapped, unmapped[strings.relativePath])
        assertTrue(strings.binPlaceholder.isNotBlank())
        assertFalse(strings.binPlaceholder.startsWith("/archive"))
    }

    @Test
    fun allFindingsHaveHumanCopyAndKeepDiagnosticContext() {
        FileOperationIssueCode.entries.forEach { code ->
            val text = strings.reason(code)
            assertTrue(text.isNotBlank())
            assertFalse(text == code.name)
        }
        val finding = FileOperationFinding(FileOperationIssueCode.SOURCE_MISSING, "/photos/missing.jpg".toPath(), "detail")
        assertEquals("The source file is missing.\n/photos/missing.jpg\ndetail", strings.finding(finding))
    }

    @Test
    fun writesAreDisabledForAnyBusyScanOrExifWork() {
        for (busy in listOf(false, true)) for (scanning in listOf(false, true)) for (exif in listOf(false, true)) {
            assertEquals(!busy && !scanning && !exif,
                fileOperationWritesEnabled(FileOperationState(busy = busy), scanning, exif))
        }
    }

    @Test
    fun memberDispositionUsesExactProposalMoveSetNotOriginalEngineRole() {
        val selected = "/photos/mismatching-folder/copy.jpg".toPath()
        val omittedCandidate = "/photos/matching-folder/copy.jpg".toPath()
        val retained = "/photos/original.jpg".toPath()
        val moves = listOf(ProposedFileMove(selected, "saved/copy.jpg".toPath(), "/old-bin/saved/copy.jpg".toPath(),
            retained, 123, emptyList()))
        assertEquals("File moves to /old-bin/saved/copy.jpg", operationMemberDisposition(selected, moves))
        assertEquals("File stays", operationMemberDisposition(omittedCandidate, moves))
        assertEquals("File stays", operationMemberDisposition(retained, moves))
        assertEquals(strings.missingDestination,
            operationMemberDisposition(selected, listOf(moves.single().copy(destinationPath = null))))
        assertEquals("Candidate", strings.role(FileRecommendationRole.REDUNDANT_CANDIDATE))
        assertEquals("Recent proposals", strings.history)
    }

    private fun savedMember(path: String, role: FileRecommendationRole) = FileOperationMember(
        FileRecord(path.toPath(), "/photos".toPath(), path.substringAfterLast('/'), "jpg", 123, 10, 20),
        role, "Saved explanation", 88, null, "2024:01:02", null,
        buildJsonObject { put("privateRawEvidence", "JSON detail") },
    )

    @Test
    fun evidenceDetailsAreHiddenByDefaultAndIncludeSavedEvidenceOnlyWhenExpanded() {
        val member = savedMember("/photos/a.jpg", FileRecommendationRole.PREFERRED)
        assertTrue(operationMemberDetails(member).isEmpty())
        val fields = operationMemberDetails(member, expanded = true).toMap()
        assertEquals(member.explanation, fields[strings.explanation])
        assertEquals(member.evidence.toString(), fields[strings.evidence])
        assertTrue(fields.values.any { it.contains("88/100") })
        assertFalse(operationMemberDetails(member, expanded = true, includeRawEvidence = false)
            .any { it.first == strings.evidence })
    }

    @Test
    fun exportIncludesAllOutcomesExactHistoricalDestinationsFindingsAndTimestampsWithoutRawJson() = runTest {
        val keep = savedMember("/photos/keep.jpg", FileRecommendationRole.REDUNDANT_CANDIDATE)
        val move = savedMember("/photos/雪\n\"copy.jpg", FileRecommendationRole.PREFERRED)
        val missing = savedMember("/photos/missing.jpg", FileRecommendationRole.REVIEW)
        val destination = "/historical/bin/雪\n\"saved.jpg".toPath()
        val finding = FileOperationFinding(FileOperationIssueCode.SOURCE_MISSING, missing.file.path, "saved\nreason")
        val proposal = FileOperationProposal("saved-id", 1234, 5678, "/old/archive".toPath(),
            "/historical/bin".toPath(),
            listOf(FileOperationGroup("group", "category", "Saved category", "Saved group reason", 99,
                listOf(keep, move, missing))),
            listOf(
                ProposedFileMove(move.file.path, "historical/relative.jpg".toPath(), destination, keep.file.path, 123, emptyList()),
                ProposedFileMove(missing.file.path, null, null, null, 123, listOf(finding)),
            ), emptyList())
        val text = formatFileOperationResults(proposal)
        assertEquals(text, formatFileOperationResults(proposal))
        listOf(keep, move, missing).forEach { assertTrue(text.contains(quoteResultText(it.file.path.toString()))) }
        assertTrue(text.contains("File stays"))
        assertTrue(text.contains(quoteResultText(strings.fileMoves(destination.toString()))))
        assertTrue(text.contains(quoteResultText(destination.toString())))
        assertTrue(text.contains(strings.missingDestination))
        assertTrue(text.contains("SOURCE_MISSING"))
        assertTrue(text.contains(quoteResultText("saved\nreason")))
        assertTrue(text.contains(strings.status(FileOperationValidationStatus.NEEDS_ATTENTION)))
        assertTrue(text.contains("1234"))
        assertTrue(text.contains("5678"))
        assertTrue(text.contains(strings.fileCreatedAt))
        assertTrue(text.contains(strings.modifiedAt))
        assertFalse(text.contains("privateRawEvidence"))
        assertFalse(text.contains("JSON detail"))
        assertFalse(text.contains("/old/archive"))
    }

    @Test
    fun exportKeepsSavedWindowsMappingAndUnmappedOrphanFindingsWithNoArchiveRoot() = runTest {
        val keep = savedMember("/photos/keep.jpg", FileRecommendationRole.PREFERRED)
        val source = "C:\\Photos\\a.jpg".toPath()
        val destination = "/saved-bin/C/Photos/a.jpg".toPath()
        val orphan = "/missing/orphan.jpg".toPath()
        val error = FileOperationFinding(FileOperationIssueCode.UNSAFE_PATH, orphan, "Cannot map saved path")
        val proposal = FileOperationProposal("modern", 1, 2, null, "/saved-bin".toPath(),
            listOf(FileOperationGroup("group", "category", "Category", "Group", 90, listOf(keep))),
            listOf(
                ProposedFileMove(source, "C/Photos/a.jpg".toPath(), destination, keep.file.path, 123, emptyList()),
                ProposedFileMove(orphan, null, null, null, 123, listOf(error)),
            ), listOf(FileOperationFinding(FileOperationIssueCode.PROTECTED_GROUP, detail = "Group finding")))
        val text = formatFileOperationResults(proposal)
        assertTrue(text.contains(quoteResultText(source.toString())))
        assertTrue(text.contains(quoteResultText(destination.toString())))
        assertTrue(text.contains(quoteResultText(keep.file.path.toString())))
        assertTrue(text.contains("File stays"))
        assertTrue(text.contains(strings.missingDestination))
        assertTrue(text.contains(quoteResultText(orphan.toString())))
        assertTrue(text.contains("Cannot map saved path"))
        assertTrue(text.contains("Group finding"))
    }

    @Test
    fun selectionIsEmptyByDefaultAndKeepsWholeGroupsDistinctAcrossCategories() {
        val files = listOf("a.jpg", "b.jpg").map {
            FileRecord("/photos/$it".toPath(), "/photos".toPath(), it, "jpg", 123, 1, 2)
        }
        val recommendation = DuplicateRecommendationEngine().recommend(files, emptyMap(), emptyMap())
        val members = recommendation.files.values.map { DeletionReviewFile(it, null, null, null) }
        val group = DeletionRecommendationGroup("shared-group", "Original evidence", members.take(1), members.drop(1), recommendation)
        val categories = listOf(
            DeletionRecommendationCategory("first", "First", "First category"),
            DeletionRecommendationCategory("second", "Second", "Second category"),
        )
        val report = DeletionRecommendationReport(categories.map {
            DeletionCategoryResults(it, listOf(group), DeletionRecommendationSummary())
        }, DeletionRecommendationSummary())
        val first = SelectedDeletionGroup(categories[0], group)
        val second = SelectedDeletionGroup(categories[1], group)
        assertTrue(selectedDeletionGroups(report, emptySet()).isEmpty())
        val keys = toggleDeletionGroup(toggleDeletionGroup(emptySet(), first.key, true), second.key, true)
        val selected = selectedDeletionGroups(report, keys)
        assertEquals(listOf(first, second), selected)
        selected.forEach {
            assertSame(group, it.group)
            assertSame(recommendation, it.group.recommendation)
            assertEquals(members.drop(1), it.group.candidates)
        }
        assertEquals(listOf(second), selectedDeletionGroups(report, toggleDeletionGroup(keys, first.key, false)))
        assertEquals(keys, toggleDeletionGroup(keys, first.key, true))
        assertTrue(selectedDeletionGroups(report, setOf("stale-key")).isEmpty())
    }
}
