package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.classification.*
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath
import kotlin.test.*

class AiRecommendationStatisticsTest {
    private val context = OllamaSettings("http://ollama.test", "model").context()
    private fun advice(path: String, role: FileRecommendationRole, replacement: String? = null): FileRecommendation {
        val p = path.toPath()
        return FileRecommendation(FileRecord(p, p.parent!!, p.name, "jpg", 100, 1, 2),
            role, p.name, emptySet(), "Fixture", ConfidenceAssessment(90, emptySet(), "Fixture"),
            replacement?.toPath())
    }
    private fun group(action: RecommendationAction, vararg files: FileRecommendation) =
        DuplicateRecommendationEngine().recommend(emptyList(), emptyMap(), emptyMap()).copy(
            action = action, files = files.associateBy { it.file.path })

    private fun record(path: String, value: DirectoryClassification) =
        DirectoryClassificationRecord(path.toPath(), path, value, context, 1)

    @Test
    fun coverageDeduplicatesDirectoriesAcrossGroupsAndIncludesUnknownAndMissingSeparately() = runTest {
        val groups = listOf(
            group(RecommendationAction.REVIEW, advice("/a/1.jpg", FileRecommendationRole.REVIEW),
                advice("/b/1.jpg", FileRecommendationRole.REVIEW)),
            group(RecommendationAction.REVIEW, advice("/a/2.jpg", FileRecommendationRole.REVIEW),
                advice("/c/2.jpg", FileRecommendationRole.REVIEW)),
            group(RecommendationAction.REVIEW, advice("/d/3.jpg", FileRecommendationRole.REVIEW),
                advice("/e/3.jpg", FileRecommendationRole.REVIEW)))
        val records = listOf(record("/a", DirectoryClassification.PREFERRED), record("/b", DirectoryClassification.UNKNOWN),
            record("/outside", DirectoryClassification.BACKUP)).associateBy { it.directoryPath }
        val result = aggregateAiRecommendationStatistics(groups, groups, records)
        assertEquals(5L, result.directoryCount)
        assertEquals(2L, result.classifiedDirectories)
        assertEquals(FileStatistics(3, 300), result.classifiedFiles)
        assertEquals(FileStatistics(3, 300), result.missing.files)
        assertEquals(3L, result.missing.directoryCount)
        assertEquals(1L, result.byClassification.getValue(DirectoryClassification.PREFERRED).directoryCount)
        assertEquals(FileStatistics(2, 200), result.byClassification.getValue(DirectoryClassification.PREFERRED).files)
        assertEquals(FileStatistics(1, 100), result.byClassification.getValue(DirectoryClassification.UNKNOWN).files)
        assertEquals(AiClassificationStatistics(), result.byClassification.getValue(DirectoryClassification.BACKUP))
        assertEquals(1L, result.fullyClassifiedGroups)
        assertEquals(1L, result.partiallyClassifiedGroups)
        assertEquals(1L, result.unclassifiedGroups)
        assertEquals(0L, result.changedGroups)
    }

    @Test
    fun impactCountsAddedAndWithdrawnRedundancyAndGroupTransitions() = runTest {
        val a = advice("/a/1.jpg", FileRecommendationRole.PRESERVE)
        val b = advice("/b/1.jpg", FileRecommendationRole.PRESERVE)
        val c = advice("/c/2.jpg", FileRecommendationRole.PREFERRED)
        val d = advice("/d/2.jpg", FileRecommendationRole.REDUNDANT_CANDIDATE, "/c/2.jpg")
        val baseline = listOf(group(RecommendationAction.KEEP_MULTIPLE, a, b), group(RecommendationAction.KEEP_ONE, c, d))
        val actual = listOf(
            group(RecommendationAction.KEEP_ONE, a.copy(role = FileRecommendationRole.PREFERRED),
                b.copy(role = FileRecommendationRole.REDUNDANT_CANDIDATE, retainedReplacementPath = a.file.path)),
            group(RecommendationAction.KEEP_MULTIPLE, c.copy(role = FileRecommendationRole.PRESERVE),
                d.copy(role = FileRecommendationRole.PRESERVE, retainedReplacementPath = null)))
        val result = aggregateAiRecommendationStatistics(baseline, actual, emptyMap())
        assertEquals(2L, result.changedGroups)
        assertEquals(2L, result.changedActionGroups)
        assertEquals(FileStatistics(4, 400), result.changedFiles)
        assertEquals(FileStatistics(1, 100), result.addedRedundantCandidates)
        assertEquals(FileStatistics(1, 100), result.withdrawnRedundantCandidates)
        assertEquals(FileStatistics(1, 100), result.baselineRedundantCandidates)
        assertEquals(FileStatistics(1, 100), result.currentRedundantCandidates)
        assertEquals(1L, result.actionTransitions.getValue(RecommendationAction.KEEP_MULTIPLE to RecommendationAction.KEEP_ONE))
        assertEquals(1L, result.roleTransitions.getValue(FileRecommendationRole.REDUNDANT_CANDIDATE to FileRecommendationRole.PRESERVE))
    }

    @Test
    fun changedReplacementCountsEvenWithUnchangedActionAndRolesButWordingDoesNot() = runTest {
        val a = advice("/a/1.jpg", FileRecommendationRole.PRESERVE)
        val b = advice("/b/1.jpg", FileRecommendationRole.PRESERVE)
        val c = advice("/c/1.jpg", FileRecommendationRole.REDUNDANT_CANDIDATE, "/a/1.jpg")
        val baseline = group(RecommendationAction.KEEP_MULTIPLE, a, b, c)
        val actual = baseline.copy(files = baseline.files + (c.file.path to c.copy(retainedReplacementPath = b.file.path)))
        val result = aggregateAiRecommendationStatistics(listOf(baseline), listOf(actual), emptyMap())
        assertEquals(1L, result.changedGroups)
        assertEquals(0L, result.changedActionGroups)
        assertEquals(FileStatistics(1, 100), result.changedFiles)
        assertEquals(FileStatistics(1, 100), result.changedReplacements)
        assertTrue(result.roleTransitions.isEmpty())
        val wordingOnly = baseline.copy(explanation = "AI evidence", files = baseline.files.mapValues {
            it.value.copy(explanation = "AI annotation", retentionDecisionConfidence = ConfidenceAssessment(95, emptySet(), "New wording"))
        })
        assertEquals(0L, aggregateAiRecommendationStatistics(listOf(baseline), listOf(wordingOnly), emptyMap()).changedGroups)
    }

    @Test
    fun emptyCoverageHasExplicitZeroBucketsAndMismatchedComparisonsAreRejected() = runTest {
        val empty = aggregateAiRecommendationStatistics(emptyList(), emptyList(), emptyMap())
        assertEquals(DirectoryClassification.entries.toSet(), empty.byClassification.keys)
        assertEquals(0L, empty.directoryCount)
        assertEquals(FileStatistics(), empty.changedFiles)
        val original = group(RecommendationAction.REVIEW, advice("/a/1.jpg", FileRecommendationRole.REVIEW))
        assertFailsWith<IllegalArgumentException> {
            aggregateAiRecommendationStatistics(listOf(original), emptyList(), emptyMap())
        }
        assertFailsWith<IllegalArgumentException> {
            aggregateAiRecommendationStatistics(listOf(original), listOf(original.copy(files = emptyMap())), emptyMap())
        }
    }

    @Test
    fun coverageOverflowAndCancellationDoNotPublishPartialResults() = runTest {
        val first = advice("/a/1.jpg", FileRecommendationRole.REVIEW)
        val large = first.copy(file = first.file.copy(sizeBytes = Long.MAX_VALUE))
        val second = advice("/a/2.jpg", FileRecommendationRole.REVIEW)
        val input = listOf(group(RecommendationAction.REVIEW, large, second))
        assertFailsWith<ArithmeticException> { aggregateAiRecommendationStatistics(input, input, emptyMap()) }
        var published = false
        val job = launch {
            currentCoroutineContext().cancel()
            aggregateAiRecommendationStatistics(emptyList(), emptyList(), emptyMap())
            published = true
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(published)
    }
}
