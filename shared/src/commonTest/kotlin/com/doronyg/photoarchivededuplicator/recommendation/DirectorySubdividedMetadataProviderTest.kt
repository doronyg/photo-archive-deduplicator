package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.classification.DirectoryClassification
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationRecord
import com.doronyg.photoarchivededuplicator.classification.OllamaSettings
import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.retention.RetentionRank
import com.doronyg.photoarchivededuplicator.retention.RetentionScorer
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectorySubdividedMetadataProviderTest {
    private val provider = DirectorySubdividedMetadataProvider()

    private fun files(directory: String, stem: String = "IMG") =
        listOf("$stem.jpg", "$stem-001.jpg").map { name ->
            FileRecord("$directory/$name".toPath(), directory.toPath(), name, "jpg", 120_000, 1, 2)
        }

    private fun snapshot(
        files: List<FileRecord>,
        metadata: (FileRecord) -> ExifMetadata = {
            ExifMetadata(capturedAt = "2023:02:18 12:44:25", make = "Canon", model = "Camera",
                width = 4000, height = 3000)
        },
        protectedPaths: Set<Path> = emptySet(),
    ): DeletionAnalysisSnapshot {
        val exif = files.associate { file ->
            file.path to ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED, metadata(file)), 3)
        }
        val scorer = RetentionScorer()
        val scores = files.associate { file ->
            file.path to RetentionPresentation(scorer.score(file, 4, exif[file.path]), RetentionRank.CLOSE)
        }
        return DeletionAnalysisSnapshot(files, DuplicateRecommendationEngine().recommend(files, exif, scores,
            protectedPaths = protectedPaths), exif, scores, emptyMap())
    }

    @Test
    fun recomputesEachDirectoryWithoutRequiringCrossDirectoryMatches() {
        val input = snapshot(files("/Photos/Family", "A") + files("/Photos/Holidays", "B"), metadata = {
            ExifMetadata(capturedAt = "2023:02:18 12:44:25", make = "Canon",
                model = if (it.directoryPath.name == "Family") "Camera A" else "Camera B",
                width = 4000, height = 3000)
        })
        assertEquals(RecommendationAction.REVIEW, input.recommendation.action)
        assertNull(SameDirectoryMetadataProvider().recommend(input))
        val group = assertNotNull(provider.recommend(input))
        assertEquals(RecommendationAction.KEEP_MULTIPLE, group.recommendation.action)
        assertEquals(2, group.survivors.size)
        assertEquals(2, group.candidates.size)
        assertEquals(100, group.recommendation.duplicateConfidence.score)
        assertTrue(!group.recommendation.duplicateConfidence.hasContradictions)
        assertCompleteLocalPlan(input, group)
        assertTrue(group.recommendation.duplicateConfidence.comparisons.values.all {
            it.file.directoryPath == it.reference.directoryPath
        })
    }

    @Test
    fun keepsEveryDirectoryEvenWhenTheOriginalEngineWouldEmptyABackupDirectory() {
        val input = snapshot(files("/Photos/2023-02-18") + files("/Photos/Backup"))
        assertEquals(RecommendationAction.KEEP_ONE, input.recommendation.action)
        val group = assertNotNull(provider.recommend(input))
        assertEquals(2, group.survivors.size)
        assertEquals(2, group.candidates.size)
        assertCompleteLocalPlan(input, group)
    }

    @Test
    fun picasaOriginalsOnlyReducesCopiesInsideItsOwnDirectory() {
        val input = snapshot(files("/Photos/Family") + files("/Photos/Family/.picasaoriginals"))
        val group = assertNotNull(provider.recommend(input))
        assertEquals(2, group.survivors.size)
        assertEquals(2, group.candidates.size)
        assertCompleteLocalPlan(input, group)
    }

    @Test
    fun combinedConfidenceUsesTheWeakestQualifyingLocalAssessment() {
        val input = snapshot(files("/Photos/Strong") + files("/Photos/Weaker"), metadata = {
            ExifMetadata(capturedAt = "2023:02:18 12:44:25",
                make = if (it.directoryPath.name == "Strong") "Canon" else null,
                model = if (it.directoryPath.name == "Strong") "Camera" else null,
                width = 4000, height = 3000)
        })
        val group = assertNotNull(provider.recommend(input))
        assertEquals(90, group.recommendation.duplicateConfidence.score)
        assertNull(group.recommendation.duplicateConfidence.referenceFile)
        assertTrue("within-folder" in group.recommendation.duplicateConfidence.assessment.explanation)
        assertCompleteLocalPlan(input, group)
    }

    @Test
    fun preservesAllMembersOfSingletonConflictingAndStaleDirectories() {
        val good = files("/Photos/Good")
        val conflicting = files("/Photos/Conflicting")
        val singleton = files("/Photos/Singleton").take(1)
        val stale = files("/Photos/Stale")
        val input = snapshot(good + conflicting + singleton + stale, metadata = {
            ExifMetadata(capturedAt = "2023:02:18 12:44:25", make = "Canon",
                model = if (it == conflicting.last()) "Other camera" else "Camera",
                width = 4000, height = 3000)
        })
        val staleFile = stale.last()
        val record = input.exif.getValue(staleFile.path)
        val changed = input.copy(exif = input.exif + (staleFile.path to record.copy(readerVersion = "old")))
        val group = assertNotNull(provider.recommend(changed))
        assertEquals(listOf(good.last().path), group.candidates.map { it.advice.file.path })
        assertEquals((good.take(1) + conflicting + singleton + stale).map { it.path }.toSet(),
            group.survivors.map { it.advice.file.path }.toSet())
        assertCompleteLocalPlan(changed, group)
    }

    @Test
    fun leavesTheOriginalCategoryUnchangedAndRejectsGroupsWithoutLocalCandidates() {
        val local = snapshot(files("/Photos/Family"))
        assertNotNull(SameDirectoryMetadataProvider().recommend(local))
        assertNull(provider.recommend(local))
        assertNull(provider.recommend(snapshot(files("/Photos/Family").take(1) + files("/Photos/Other").take(1))))
        val input = snapshot(files("/Photos/Family") + files("/Photos/Other"))
        assertNull(provider.recommend(input.copy(exif = emptyMap())))
        assertNull(provider.recommend(input.copy(retention = emptyMap())))
        assertNull(provider.recommend(input.copy(files = input.files + input.files.first())))
    }

    @Test
    fun protectionOnAnyOriginalMemberStillVetoesTheWholeGroup() = runTest {
        val files = files("/Photos/Family") + files("/Photos/Other")
        assertNull(provider.recommend(snapshot(files, protectedPaths = setOf(files.last().path))))
        val input = snapshot(files)
        val directory = files.last().directoryPath
        val classified = input.copy(directoryClassifications = mapOf(directory to DirectoryClassificationRecord(
            directory, directory.toString(), DirectoryClassification.PROTECTED,
            OllamaSettings("http://ollama.test", "model").context(), 5)))
        assertNull(provider.recommend(classified))
        assertTrue(buildDeletionRecommendationReport(listOf(classified), listOf(provider))
            .categories.single().groups.isEmpty())
    }

    @Test
    fun producesOneStableCompleteGroupWithoutDuplicatingMembers() = runTest {
        val input = snapshot(files("/Photos/Family") + files("/Photos/Other"))
        val reversed = input.copy(files = input.files.reversed())
        assertEquals(provider.recommend(input), provider.recommend(reversed))
        val results = provider.recommendations(listOf(input, reversed))
        assertEquals(1, results.size)
        assertCompleteLocalPlan(input, results.single())
    }

    private fun assertCompleteLocalPlan(input: DeletionAnalysisSnapshot, group: DeletionRecommendationGroup) {
        val members = group.survivors + group.candidates
        assertEquals(input.files.map { it.path }.toSet(), members.map { it.advice.file.path }.toSet())
        assertEquals(input.files.size, members.size)
        assertEquals(input.files.map { it.directoryPath }.toSet(),
            group.survivors.map { it.advice.file.directoryPath }.toSet())
        val kept = group.survivors.associateBy { it.advice.file.path }
        for (candidate in group.candidates) {
            val replacement = assertNotNull(kept[candidate.advice.retainedReplacementPath])
            assertEquals(candidate.advice.file.directoryPath, replacement.advice.file.directoryPath)
            assertTrue(candidate.advice.retentionDecisionConfidence.score >= 75)
        }
        assertEquals(group.recommendation,
            enforceRetentionPlanSafety(group.recommendation, input.files, input.files.map { it.directoryPath }.toSet()))
    }
}
