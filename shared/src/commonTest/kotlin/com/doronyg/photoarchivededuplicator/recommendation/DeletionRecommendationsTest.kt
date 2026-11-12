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
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeletionRecommendationsTest {
    private val scorer = RetentionScorer()
    private fun file(folder: String, name: String) = FileRecord(
        "$folder/$name".toPath(), folder.toPath(), name, "jpg", 120_000, 1, 2)
    private fun snapshot(
        files: List<FileRecord>,
        missingExif: Boolean = false,
        staleScore: Boolean = false,
        protected: Boolean = false,
    ): DeletionAnalysisSnapshot {
        val exif = if (missingExif) emptyMap() else files.associate { file ->
            file.path to ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED,
                ExifMetadata(capturedAt = "2023:02:18 12:44:25", make = "Canon", model = "Camera",
                    width = 4000, height = 3000)), 3)
        }
        val retention = files.associate { file ->
            val score = scorer.score(file, 4, exif[file.path])
            file.path to RetentionPresentation(
                if (staleScore) score.copy(identity = score.identity.copy(algorithmVersion = "old")) else score,
                RetentionRank.CLOSE)
        }
        val result = DuplicateRecommendationEngine().recommend(files, exif, retention,
            protectedPaths = if (protected) setOf(files.first().path) else emptySet())
        return DeletionAnalysisSnapshot(files, result, exif, retention, emptyMap())
    }
    private val files = listOf(file("/Photos/Family", "IMG.jpg"), file("/Photos/Family", "IMG-001.jpg"))

    @Test
    fun protectedClassificationRejectsStrongExistingDecisionInDirectAndReportPaths() = runTest {
        val valid = snapshot(files)
        val provider = SameDirectoryMetadataProvider()
        assertNotNull(provider.recommend(valid))
        val directory = files.first().directoryPath
        val record = DirectoryClassificationRecord(directory, directory.toString(),
            DirectoryClassification.PROTECTED, OllamaSettings("http://ollama.test", "model").context(), 5)
        val protected = valid.copy(directoryClassifications = mapOf(directory to record))
        assertNull(provider.recommend(protected))
        val future = object : DeletionRecommendationProvider {
            override val category = DeletionRecommendationCategory("future", "Future", "Future category")
            override suspend fun recommendations(snapshots: List<DeletionAnalysisSnapshot>): List<DeletionRecommendationGroup> {
                assertTrue(snapshots.isEmpty(), "Registry must not pass protected groups to any category")
                return emptyList()
            }
        }
        val report = buildDeletionRecommendationReport(listOf(protected), listOf(provider, future))
        assertTrue(report.categories.all { it.groups.isEmpty() })
        assertEquals(DeletionRecommendationSummary(), report.summary)
    }

    @Test
    fun sameDirectoryProviderPreservesEngineSurvivorDependenciesAndRealExplanations() {
        val input = snapshot(files.reversed())
        val result = assertNotNull(SameDirectoryMetadataProvider().recommend(input))
        assertEquals(files.first(), result.survivors.single().advice.file)
        assertEquals(files.last(), result.candidates.single().advice.file)
        assertEquals(files.first().path, result.candidates.single().advice.retainedReplacementPath)
        assertEquals(input.recommendation.files.getValue(files.first().path).explanation,
            result.survivors.single().advice.explanation)
        assertEquals(input.recommendation.files.getValue(files.last().path).explanation,
            result.candidates.single().advice.explanation)
        assertTrue("equal sizes and matching capture metadata" in result.evidenceSummary)
        assertEquals(input.recommendation, result.recommendation)
        assertNotNull(result.survivors.single().exif)
    }

    @Test
    fun missingStaleProtectedAndCrossDirectoryGroupsNeverQualify() {
        val provider = SameDirectoryMetadataProvider()
        assertNull(provider.recommend(snapshot(files, missingExif = true)))
        assertNull(provider.recommend(snapshot(files, staleScore = true)))
        assertNull(provider.recommend(snapshot(files, protected = true)))
        assertNull(provider.recommend(snapshot(listOf(files.first(), file("/Photos/Other", "IMG-001.jpg")))))
        assertNull(provider.recommend(snapshot(listOf(files.first()))))
        assertNull(provider.recommend(snapshot(files).copy(retention = emptyMap())))
    }

    @Test
    fun sameNamesWeakEvidenceAndOutdatedExifNeverQualify() {
        val provider = SameDirectoryMetadataProvider()
        assertNull(provider.recommend(snapshot(listOf(files.first(), files.last().copy(name = "IMG.jpg")))))
        assertNull(provider.recommend(snapshot(listOf(files.first(), file("/Photos/Family", "unrelated.jpg")))))
        val input = snapshot(files)
        val file = files.last()
        val record = input.exif.getValue(file.path)
        for (invalid in listOf(
            record.copy(readerVersion = "outdated"),
            record.copy(input = record.input.copy(modifiedAt = 99)),
            record.copy(result = record.result.copy(status = ExifStatus.STALE)),
            record.copy(result = record.result.copy(metadata = record.result.metadata.copy(capturedAt = null))),
        )) {
            assertNull(provider.recommend(input.copy(exif = input.exif + (file.path to invalid))))
        }
        assertNull(provider.recommend(input.copy(retention = input.retention + (file.path to
            input.retention.getValue(file.path).copy(rank = RetentionRank.STALE)))))
    }

    @Test
    fun invalidCandidateReplacementAndLowDecisionConfidenceNeverQualify() {
        val input = snapshot(files)
        val candidate = input.recommendation.files.getValue(files.last().path)
        val provider = SameDirectoryMetadataProvider()
        for (invalid in listOf(
            candidate.copy(retainedReplacementPath = candidate.file.path),
            candidate.copy(retainedReplacementPath = "/outside/IMG.jpg".toPath()),
            candidate.copy(retainedReplacementPath = null),
            candidate.copy(retentionDecisionConfidence = candidate.retentionDecisionConfidence.copy(score = 74)),
        )) {
            assertNull(provider.recommend(input.copy(recommendation = input.recommendation.copy(
                files = input.recommendation.files + (candidate.file.path to invalid)))))
        }
    }

    @Test
    fun completeGroupIsRequiredAndCaptureContradictionsCannotBeHiddenByChoosingSubset() {
        val provider = SameDirectoryMetadataProvider()
        val input = snapshot(files + file("/Photos/Other", "IMG-002.jpg"))
        assertNull(provider.recommend(input))
        val valid = snapshot(files)
        assertNull(provider.recommend(valid.copy(files = files + file("/Photos/Family", "IMG-002.jpg"))))
        val badExif = valid.exif.mapValues { (_, record) ->
            if (record.input.path == files.last().path) record.copy(result = record.result.copy(
                metadata = record.result.metadata.copy(capturedAt = "2023:02:19 12:44:25")))
            else record
        }
        val scores = files.associate { it.path to RetentionPresentation(scorer.score(it, 4, badExif[it.path]), RetentionRank.CLOSE) }
        val conflicted = valid.copy(exif = badExif, retention = scores,
            recommendation = DuplicateRecommendationEngine().recommend(files, badExif, scores))
        assertNull(provider.recommend(conflicted))
    }

    @Test
    fun registrySupportsFutureProvidersAndEmptyCategoriesWithoutUiChanges() = runTest {
        val input = snapshot(files)
        val first = SameDirectoryMetadataProvider()
        val future = object : DeletionRecommendationProvider {
            override val category = DeletionRecommendationCategory("future-category", "Future category", "Future rules")
            override suspend fun recommendations(snapshots: List<DeletionAnalysisSnapshot>) = emptyList<DeletionRecommendationGroup>()
        }
        val result = buildDeletionRecommendationReport(listOf(input), listOf(first, future))
        assertEquals(listOf(first.category, future.category), result.categories.map { it.category })
        assertEquals(1L, result.categories.first().summary.groupCount)
        assertEquals(FileStatistics(1, 120_000), result.categories.first().summary.candidates)
        assertEquals(0L, result.categories.last().summary.groupCount)
        assertEquals(FileStatistics(), result.categories.last().summary.candidates)
        assertEquals(1L, result.summary.groupCount)
        assertEquals(FileStatistics(1, 120_000), result.summary.candidates)
        val overlapping = object : DeletionRecommendationProvider {
            override val category = DeletionRecommendationCategory("overlapping", "Overlapping category", "Other rules")
            override suspend fun recommendations(snapshots: List<DeletionAnalysisSnapshot>) = first.recommendations(snapshots)
        }
        val overlap = buildDeletionRecommendationReport(listOf(input), listOf(first, overlapping))
        assertEquals(listOf(1L, 1L), overlap.categories.map { it.summary.groupCount })
        assertEquals(1L, overlap.summary.groupCount)
        assertEquals(FileStatistics(1, 120_000), overlap.summary.candidates)
        assertEquals(DeletionRecommendationSummary(), buildDeletionRecommendationReport(emptyList(), emptyList()).summary)
        assertFailsWith<IllegalArgumentException> {
            buildDeletionRecommendationReport(listOf(input), listOf(first, first))
        }
    }

    @Test
    fun futureProviderCanUseDifferentGroupingWithoutChangingPresentationModel() = runTest {
        val input = snapshot(files + file("/Photos/Family", "IMG-002.jpg"))
        val original = assertNotNull(SameDirectoryMetadataProvider().recommend(input))
        val split = object : DeletionRecommendationProvider {
            override val category = DeletionRecommendationCategory("split-review", "Separate decisions", "Other grouping")
            override suspend fun recommendations(snapshots: List<DeletionAnalysisSnapshot>) =
                original.candidates.map { candidate ->
                    original.copy(id = candidate.advice.file.path.toString(), candidates = listOf(candidate))
                }
        }
        val result = buildDeletionRecommendationReport(listOf(input), listOf(split))
        assertEquals(2L, result.summary.groupCount)
        assertEquals(FileStatistics(2, 240_000), result.summary.candidates)
        assertTrue(result.categories.single().groups.all { it.survivors == original.survivors })
    }

    @Test
    fun overallGroupIdentityUsesMemberPathsRatherThanProviderLocalIds() = runTest {
        val first = assertNotNull(SameDirectoryMetadataProvider().recommend(snapshot(files)))
        val second = assertNotNull(SameDirectoryMetadataProvider().recommend(snapshot(
            listOf(file("/Photos/Other", "IMG.jpg"), file("/Photos/Other", "IMG-001.jpg")))))
        fun provider(id: String, group: DeletionRecommendationGroup) = object : DeletionRecommendationProvider {
            override val category = DeletionRecommendationCategory(id, id, "Other category")
            override suspend fun recommendations(snapshots: List<DeletionAnalysisSnapshot>) = listOf(group)
        }
        val unrelated = buildDeletionRecommendationReport(emptyList(),
            listOf(provider("a", first.copy(id = "local-id")), provider("b", second.copy(id = "local-id"))))
        assertEquals(2L, unrelated.summary.groupCount)
        assertEquals(FileStatistics(2, 240_000), unrelated.summary.candidates)
        val sameMembers = buildDeletionRecommendationReport(emptyList(),
            listOf(provider("a", first.copy(id = "first-id")), provider("b", first.copy(id = "other-id"))))
        assertEquals(1L, sameMembers.summary.groupCount)
        assertEquals(FileStatistics(1, 120_000), sameMembers.summary.candidates)
    }
}
