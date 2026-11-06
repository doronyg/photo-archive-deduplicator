package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.UiChoices
import com.doronyg.photoarchivededuplicator.operations.OperationHistoryState
import com.doronyg.photoarchivededuplicator.operations.history.ArchiveBinding
import com.doronyg.photoarchivededuplicator.operations.history.ArchiveIdentity
import com.doronyg.photoarchivededuplicator.operations.history.HistoryProblem
import com.doronyg.photoarchivededuplicator.operations.history.OperationHistoryEntry
import com.doronyg.photoarchivededuplicator.operations.history.OperationHistoryItem
import com.doronyg.photoarchivededuplicator.operations.history.OperationHistoryMoveStatus
import com.doronyg.photoarchivededuplicator.operations.history.OperationHistoryRestoreStatus
import com.doronyg.photoarchivededuplicator.operations.history.OperationHistorySnapshot
import com.doronyg.photoarchivededuplicator.operations.history.OperationHistoryStatistics
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import com.doronyg.photoarchivededuplicator.strings.OperationHistoryStrings
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationHistoryPresentationTest {
    private val strings = OperationHistoryStrings
    private val identity = ArchiveIdentity("shared-archive", "private-registration-token")
    private val archive = "/local/archive".toPath()
    private val bin = "/local/bin".toPath()

    private fun snapshot(
        operations: List<OperationHistoryEntry> = emptyList(),
        problems: List<HistoryProblem> = emptyList(),
        statistics: OperationHistoryStatistics = OperationHistoryStatistics(),
    ) = OperationHistorySnapshot(identity, operations, problems, statistics)

    private fun operation(incomplete: Boolean = false, problems: List<String> = emptyList()) =
        OperationHistoryEntry("operation-1", "2026-11-06T12:00:00Z", identity.archiveId, emptyList(), problems, incomplete)

    private fun item() = OperationHistoryItem(
        itemId = "item-1",
        originalRelativePath = "2024/雪.jpg",
        binRelativePath = "operations/operation-1/雪.jpg",
        sizeBytes = 2048,
        modifiedAtMillis = 123,
        createdAtMillis = null,
        categoryId = "recorded-category",
        explanation = "Recorded evidence, not recomputed",
        retainedRelativePath = "retained/雪.jpg",
    )

    @Test
    fun mappingSuggestionUsesSavedInstallationMountBeforeScanRoot() {
        assertEquals("/saved/archive", operationHistoryArchiveSuggestion(
            UiChoices(operationHistoryArchiveRoot = "/saved/archive", scanRoot = "/scan")))
        assertEquals("/scan", operationHistoryArchiveSuggestion(UiChoices(scanRoot = "/scan")))
        assertEquals("", operationHistoryArchiveSuggestion(UiChoices()))
    }

    @Test
    fun onlyMatchingVerifiedIdentityAndBothCurrentMountsShowVerifiedStatus() {
        val state = OperationHistoryState(
            binRoot = bin,
            snapshot = snapshot(),
            binding = ArchiveBinding(identity, archive, bin),
        )
        assertEquals(strings.verified, operationHistoryMappingStatus(state, archive.toString(), bin))
        assertEquals(strings.verified, operationHistoryMappingStatus(state, "  $archive  ", bin))
        assertTrue("remain disabled" in operationHistoryMappingStatus(state, archive.toString(), bin))
        val unverifiedStates = listOf(
            state.copy(binding = null),
            state.copy(snapshot = null),
            state.copy(snapshot = snapshot().copy(identity = identity.copy(registrationToken = "other-token"))),
            state.copy(binRoot = "/other/bin".toPath()),
            state.copy(binding = state.binding!!.copy(binRoot = "/other/bin".toPath())),
            state.copy(busy = true),
            state.copy(error = "identity mismatch"),
        )
        unverifiedStates.forEach {
            assertEquals(strings.unmapped, operationHistoryMappingStatus(it, archive.toString(), bin))
        }
        assertEquals(strings.unmapped, operationHistoryMappingStatus(state, "/other/archive", bin))
        assertEquals(strings.unmapped, operationHistoryMappingStatus(state, archive.toString(), null))
        assertEquals(strings.unmapped, operationHistoryMappingStatus(state, archive.toString(), "/other/bin".toPath()))
        assertTrue("browsing remains available" in strings.unmapped)
    }

    @Test
    fun allSnapshotAndOperationProblemsMarkStatisticsPartialWithoutHidingDiagnostics() {
        val complete = snapshot(listOf(operation()))
        assertFalse(operationHistoryTotalsPartial(complete))
        assertEquals(strings.statistics, operationHistoryStatisticsTitle(complete))
        val partial = listOf(
            snapshot(problems = listOf(HistoryProblem("journal.jsonl", "Unsupported schema 999"))),
            snapshot(problems = listOf(HistoryProblem("manifest.json", "Malformed JSON"))),
            snapshot(listOf(operation(incomplete = true))),
            snapshot(listOf(operation(problems = listOf("Unknown event type")))),
            snapshot(listOf(operation().copy(journalValid = false))),
            complete.copy(complete = false),
        )
        partial.forEach {
            assertTrue(operationHistoryTotalsPartial(it))
            assertEquals(strings.partialStatistics, operationHistoryStatisticsTitle(it))
        }
        assertTrue("confirmed events only" in strings.incomplete)
    }

    @Test
    fun statisticsUseConfirmedEventsNotUniqueFileCountsAndKeepLogicalBytesDistinct() {
        val stats = OperationHistoryStatistics(
            completedMoves = 7,
            logicalBytesMoved = 4096,
            filesInBin = 2,
            logicalBytesInBin = 1024,
            completedRestores = 3,
            permanentlyDeleted = 1,
        )
        val fields = operationHistoryStatisticsFields(snapshot(statistics = stats)).toMap()
        assertEquals("7", fields[strings.moves])
        assertEquals("2", fields[strings.inBin])
        assertEquals(formatBytes(4096), fields[strings.bytesMoved])
        assertEquals(formatBytes(1024), fields[strings.bytesInBin])
        assertEquals("3", fields[strings.restores])
        assertEquals("1", fields[strings.deleted])
        assertTrue("not unique files" in strings.eventCountNote)
        assertTrue("Unknown" in strings.physicalSpace)
        assertFalse(fields.keys.any { "reclaimed" in it })
        val empty = operationHistoryStatisticsFields(snapshot()).toMap()
        assertEquals("0", empty[strings.moves])
        assertEquals("0", empty[strings.restores])
        assertEquals("0", empty[strings.inBin])
    }

    @Test
    fun recordedPathsAreImmutableRelativeTextNotRebasedNativeActions() {
        val paths = operationHistoryItemPaths(item()).toMap()
        assertEquals("2024/雪.jpg", paths[strings.original])
        assertEquals("operations/operation-1/雪.jpg", paths[strings.binPath])
        assertFalse(paths.values.any { it.startsWith(archive.toString()) || it.startsWith(bin.toString()) })
        val unusual = item().copy(originalRelativePath = "../untrusted", binRelativePath = "/untrusted")
        assertEquals("../untrusted", operationHistoryItemPaths(unusual).toMap()[strings.original])
        assertEquals("/untrusted", operationHistoryItemPaths(unusual).toMap()[strings.binPath])
    }

    @Test
    fun evidenceIsCollapsedByDefaultAndUsesRecordedMetadataAndReconciliationStatus() {
        assertTrue(operationHistoryItemDetails(item()).isEmpty())
        val recorded = item().copy(
            moveStatus = OperationHistoryMoveStatus.PENDING,
            restoreStatus = OperationHistoryRestoreStatus.COMPLETED,
            confirmedInBin = false,
            completedMoves = 2,
            completedRestores = 1,
        )
        val fields = operationHistoryItemDetails(recorded, expanded = true).toMap()
        assertEquals(recorded.explanation, fields[strings.explanation])
        assertEquals(recorded.categoryId, fields[strings.category])
        assertEquals("retained/雪.jpg", fields[strings.retained])
        assertEquals("123", fields[strings.modifiedAt])
        assertEquals(AppStrings.unavailable, fields[strings.fileCreatedAt])
        assertEquals(strings.pending, fields[strings.moveStatus])
        assertEquals(strings.restored, fields[strings.restoreStatus])
        assertEquals(strings.no, fields[strings.recordedInBin])
        assertEquals("2", fields[strings.moves])
        assertEquals("1", fields[strings.restores])
        assertFalse(fields.values.any { identity.registrationToken in it })
        OperationHistoryMoveStatus.entries.forEach { status ->
            assertFalse(operationHistoryItemDetails(recorded.copy(moveStatus = status), true)
                .toMap().getValue(strings.moveStatus).isBlank())
        }
        OperationHistoryRestoreStatus.entries.forEach { status ->
            assertFalse(operationHistoryItemDetails(recorded.copy(restoreStatus = status), true)
                .toMap().getValue(strings.restoreStatus).isBlank())
        }
    }

    @Test
    fun registrationCopySeparatesReadOnlyConnectFromExplicitIdentityWrites() {
        assertTrue("read only" in strings.connect)
        assertTrue("without creating or changing" in strings.connectNote)
        assertTrue("writes identity metadata" in strings.registrationNote)
        assertTrue("does not move files" in strings.registrationNote)
        assertTrue("Create missing" in strings.registrationConfirmation)
        assertTrue("No moves, restoration" in strings.actionsDisabled)
    }
}
