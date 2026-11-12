package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.exif.ExifQueuePhase
import com.doronyg.photoarchivededuplicator.exif.ExifQueuePurpose
import com.doronyg.photoarchivededuplicator.exif.ExifQueueState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExifQueueStatusTest {
    @Test
    fun suspectedAnalysisHasDifferentEligibilityAndDoesNotClaimAmbiguityFiltering() {
        val state = ExifQueueState(purpose = ExifQueuePurpose.SUSPECTED_DUPLICATES)
        val preparing = presentExifQueue(state.copy(phase = ExifQueuePhase.SCORING)).orEmpty()
        assertTrue("matching image sizes" in preparing)
        val empty = presentExifQueue(state.copy(phase = ExifQueuePhase.COMPLETED)).orEmpty()
        assertTrue("different filenames" in empty)
        assertFalse("ambiguous" in empty)
        assertFalse("folder date" in empty)
    }

    private fun status(phase: ExifQueuePhase, groups: Int = 2, error: String? = null) =
        presentExifQueue(ExifQueueState(
            phase = phase, groupsTotal = groups, groupsCompleted = 1,
            filesTotal = 6, filesCompleted = 3, error = error,
        )).orEmpty()

    @Test
    fun terminalRefreshDoesNotRequireSuccessRevisionOrReactToIndividualFileProgress() {
        val extracting = ExifQueueState(phase = ExifQueuePhase.EXTRACTING, revision = 4)
        val key = exifQueueRefreshKey(extracting)
        assertEquals(key, exifQueueRefreshKey(extracting.copy(filesCompleted = 1, groupsCompleted = 1)))
        assertEquals(key, exifQueueRefreshKey(extracting.copy(phase = ExifQueuePhase.REASSESSING)))
        listOf(ExifQueuePhase.COMPLETED, ExifQueuePhase.CANCELLED, ExifQueuePhase.FAILED).forEach { phase ->
            assertNotEquals(key, exifQueueRefreshKey(extracting.copy(phase = phase)))
        }
        assertNotEquals(key, exifQueueRefreshKey(extracting.copy(revision = 5)))
    }

    @Test
    fun idleAndPreparationPhasesAreExplicit() {
        assertNull(presentExifQueue(ExifQueueState()))
        assertEquals("EXIF: checking eligible groups...", status(ExifQueuePhase.SCORING))
        assertTrue("updating scores" in status(ExifQueuePhase.REASSESSING))
    }

    @Test
    fun progressCountsVisitsIncludingCacheRatherThanReads() {
        val text = status(ExifQueuePhase.EXTRACTING)
        assertTrue("1/2 groups" in text)
        assertTrue("3/6 files" in text)
        assertFalse("includes cached results" in text)
    }

    @Test
    fun completionDistinguishesNoEligibleGroups() {
        assertTrue("complete" in status(ExifQueuePhase.COMPLETED))
        assertTrue("No eligible groups" in status(ExifQueuePhase.COMPLETED, groups = 0))
        assertFalse("No eligible" in status(ExifQueuePhase.COMPLETED))
    }

    @Test
    fun cancellationPreservesCacheWithoutClaimingCompletedScoring() {
        val text = status(ExifQueuePhase.CANCELLED)
        assertTrue("cancelled" in text)
        assertFalse("Stored EXIF is preserved" in text)
        assertFalse("completed" in text)
    }

    @Test
    fun failureIncludesErrorAndPartialProgress() {
        val text = status(ExifQueuePhase.FAILED, error = "Database unavailable")
        assertTrue("Database unavailable" in text)
        assertTrue("1/2 groups" in text)
        assertTrue("Unknown" in status(ExifQueuePhase.FAILED))
    }
}
