package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendationRole
import com.doronyg.photoarchivededuplicator.recommendation.DeletionRecommendationSource
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
    fun recentProposalsKeepLatestFiftyAndDisplayNewestFirst() = concurrencyTest { f ->
        val repository = FileOperationRepository(createFilesDatabase(f.driver), f.lane)
        for (index in 1..52) repository.save(FileOperationProposal("proposal-$index",
            index.toLong(), index.toLong(), null, "/Bin".toPath(), emptyList(), emptyList(), emptyList()))
        assertEquals((52L downTo 3L).toList(), repository.recent().map { it.createdAt })
    }

    @Test
    fun duplicateProvenanceRoundTripsBothModesAndOriginalEngineEvidence() = concurrencyTest { f ->
        val repository = FileOperationRepository(createFilesDatabase(f.driver), f.lane)
        val files = listOf("雪.jpg", "雪-001.jpg").map {
            FileRecord("/Archive/$it".toPath(), "/Archive".toPath(), it, "jpg", 42, 1, 2, 3)
        }
        val evidence = buildJsonObject {
            put("role", "REVIEW")
            put("confidence", 15)
            put("futureEvidence", JsonArray(listOf(JsonNull, JsonPrimitive("exact\n雪"))))
        }
        for ((index, paths) in listOf(null, setOf(files.first().path)).withIndex()) {
            val provenance = DuplicateDeletionProvenance(DuplicateDeletionSelection(
                DeletionRecommendationSource.REGULAR, files, paths), evidence)
            val proposal = FileOperationProposal("provenance-$index", 100, 200, null, "/Bin".toPath(),
                listOf(FileOperationGroup("group", "category", "Category", "Saved evidence", 15,
                    files.map { FileOperationMember(it, FileRecommendationRole.REVIEW, "Review", 15,
                        null, null, null, evidence) }, duplicateProvenance = provenance)),
                emptyList(), emptyList())
            repository.save(proposal)
            assertEquals(proposal, repository.load(proposal.id))
        }
    }

    @Test
    fun historicalMixedProposalLoadsWithPartialEligibilityWithoutChangingSavedEvidence() = concurrencyTest { f ->
        val database = createFilesDatabase(f.driver)
        val files = listOf("bad", "good").map { name ->
            FileRecord("/Archive/$name.jpg".toPath(), "/Archive".toPath(), "$name.jpg", "jpg", 42, null, 123, null)
        }
        val proposal = FileOperationProposal("old-mixed", 100, 200, null, "/Bin".toPath(),
            files.map { file ->
                FileOperationGroup(file.name, "category", "Category", "Saved evidence", 100,
                    listOf(FileOperationMember(file, FileRecommendationRole.REDUNDANT_CANDIDATE,
                        "Saved reason", 95, "/Archive/keep.jpg".toPath(), null, null, buildJsonObject {})))
            },
            files.mapIndexed { index, file ->
                ProposedFileMove(file.path, "Archive/${file.name}".toPath(), "/Bin/Archive/${file.name}".toPath(),
                    "/Archive/keep.jpg".toPath(), 42, if (index == 0)
                        listOf(FileOperationFinding(FileOperationIssueCode.SOURCE_MISSING, file.path)) else emptyList())
            }, emptyList())
        val encoded = Json.parseToJsonElement(encodeProposalSnapshot(proposal)).jsonObject
        val oldGroups = JsonArray(encoded.getValue("groups").jsonArray.map { group ->
            JsonObject(group.jsonObject.filterKeys { it != "findings" })
        })
        f.lane.run("insert historical mixed proposal") {
            database.fileOperationProposalQueries.insertProposal(
                id = proposal.id, createdAt = proposal.createdAt, validatedAt = proposal.validatedAt, archiveRoot = null,
                binRoot = proposal.binRoot.toString(), validationStatus = FileOperationValidationStatus.NEEDS_ATTENTION.name,
                groupCount = 2, candidateCount = 2, candidateBytes = 84,
                snapshotJson = JsonObject(encoded + ("groups" to oldGroups)).toString())
        }
        val loaded = FileOperationRepository(database, f.lane).load(proposal.id)
        assertEquals(proposal, loaded)
        assertEquals(FileOperationValidationStatus.PARTIALLY_READY, loaded.validationStatus)
        assertEquals(listOf(files.last().path), loaded.executionEligibility.moves.map { it.sourcePath })
    }

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
        val legacy = fullPathProposal.copy(id = "legacy")
        val encoded = Json.parseToJsonElement(encodeProposalSnapshot(legacy)).jsonObject
        val oldGroups = JsonArray(encoded.getValue("groups").jsonArray.map { group ->
            JsonObject(group.jsonObject.filterKeys { it != "findings" })
        })
        val payload = JsonObject(encoded + ("groups" to oldGroups)).toString()
        f.lane.run("insert historical proposal") {
            createFilesDatabase(f.driver).fileOperationProposalQueries.insertProposal(
                id = legacy.id, createdAt = legacy.createdAt, validatedAt = legacy.validatedAt,
                archiveRoot = null, binRoot = legacy.binRoot.toString(), validationStatus = legacy.validationStatus.name,
                groupCount = legacy.groups.size.toLong(), candidateCount = legacy.moves.size.toLong(),
                candidateBytes = legacy.candidateBytes, snapshotJson = payload)
        }
        assertEquals(legacy, repository.load(legacy.id), "Old snapshots without group findings remain readable")
    }
}
