package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendationRole
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.scan.concurrencyTest
import com.doronyg.photoarchivededuplicator.scan.createFilesDatabase
import kotlinx.serialization.json.*
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileOperationRepositoryTest {
    @Test
    fun snapshotsRoundTripNullMetadataUnicodePathsAndUnknownEvidenceFields() = concurrencyTest { f ->
        val repository = FileOperationRepository(createFilesDatabase(f.driver), f.lane)
        val file = FileRecord("/Archive/雪_%'.jpg".toPath(), "/Archive".toPath(), "雪_%'.jpg",
            "jpg", 42, null, 123, null)
        val evidence = buildJsonObject {
            put("futureEvidence", buildJsonArray { add(JsonNull); add("retain exactly\n'雪'") })
        }
        val issue = FileOperationFinding(FileOperationIssueCode.SOURCE_OUTSIDE_ROOT, file.path, "old root")
        val proposal = FileOperationProposal("saved", 100, 200, "/Other".toPath(),
            "/DeduplicatorGarbageBin".toPath(),
            listOf(FileOperationGroup("group", "category", "Saved category", "Saved evidence", 100,
                listOf(FileOperationMember(file, FileRecommendationRole.PRESERVE, "Original explanation",
                    95, null, null, null, evidence)))),
            listOf(ProposedFileMove(file.path, null, null, null, 42, listOf(issue))),
            listOf(FileOperationFinding(FileOperationIssueCode.STALE_RECOMMENDATION)),
        )
        repository.save(proposal)
        assertEquals(proposal, repository.load("saved"))
        assertEquals(FileOperationSummary("saved", 100, proposal.archiveRoot, proposal.binRoot,
            FileOperationValidationStatus.NEEDS_ATTENTION, 1, 1, 42), repository.recent().single())
        assertFailsWith<Exception> { repository.save(proposal) }
        assertEquals(proposal, repository.load("saved"), "Saving the same ID cannot overwrite a snapshot")
        val fullPathProposal = proposal.copy(id = "full-path", archiveRoot = null)
        repository.save(fullPathProposal)
        assertEquals(fullPathProposal, repository.load("full-path"))
        assertEquals(null, repository.recent().single { it.id == "full-path" }.archiveRoot)
    }
}
