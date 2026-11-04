package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.recommendation.DuplicateRecommendationEngine
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationReason
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.retention.RetentionRank
import com.doronyg.photoarchivededuplicator.retention.RetentionScorer
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateFilter
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DuplicateRecommendationPresentationTest {
    private val directory = "/Pictures/Zoo".toPath()
    private val original = FileRecord(directory / "IMG_1234.jpg", directory, "IMG_1234.jpg", "jpg", 120_000, null, 1)
    private val copy = original.copy(path = directory / "IMG_1234-001.jpg", name = "IMG_1234-001.jpg")
    private val files = listOf(copy, original)
    private val exif = files.associate { it.path to ExifRecord(ExifInput.from(it),
        ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(capturedAt = "2023:02:18 12:44:25")), 2) }
    private val retention = files.associate { it.path to RetentionPresentation(RetentionScorer().score(it, 3, exif[it.path]), RetentionRank.CLOSE) }
    private val recommendation = DuplicateRecommendationEngine().recommend(files, exif, retention)

    @Test
    fun summaryAndPerFileLabelsDistinguishRecommendationsFromConfirmedDuplicates() {
        assertTrue(groupRecommendationText(recommendation).contains("Keep one"))
        assertTrue(groupRecommendationText(recommendation).contains("metadata"))
        assertTrue(fileRecommendationText(recommendation.files.getValue(original.path)).contains("Preferred copy"))
        assertTrue(fileRecommendationText(recommendation.files.getValue(copy.path)).contains("Likely redundant candidate"))
        assertEquals("Recommendation: Loading stored evidence...", groupRecommendationText(null))
        assertEquals("Recommendation: Loading stored evidence...", fileRecommendationText(null))
    }

    @Test
    fun copiedResultsIncludeGroupAndPerFileReasonsWithoutChangingDisplayedOrder() = runTest {
        val group = DuplicateCandidateGroup(original.name, original.sizeBytes, files)
        val text = formatDuplicateResults("/Pictures", DuplicateCandidateFilter(), emptyList(), listOf(group),
            StandardTestDispatcher(testScheduler), retention, exif,
            recommendations = files.associate { it.path to recommendation })
        assertTrue("Group recommendation: KEEP_ONE" in text)
        assertTrue("SAME_DIRECTORY_COPY_VARIANTS" in text)
        assertTrue("File recommendation: REDUNDANT_CANDIDATE" in text)
        assertTrue("File recommendation: PREFERRED" in text)
        assertTrue("Normalized filename: \"IMG_1234.jpg\"" in text)
        assertTrue(text.indexOf(quoteResultText(copy.path.toString())) < text.indexOf("  Path: ${quoteResultText(original.path.toString())}"))
        assertTrue(RecommendationReason.NUMBERED_COPY_SUFFIX.name in text)
        assertTrue("not content-verified" in text)
        assertTrue("No files have been changed" in text)
    }
}
