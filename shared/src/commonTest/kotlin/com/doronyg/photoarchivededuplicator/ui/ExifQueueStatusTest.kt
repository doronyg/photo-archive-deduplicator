package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.exif.ExifQueuePhase
import com.doronyg.photoarchivededuplicator.exif.ExifQueueState
import com.doronyg.photoarchivededuplicator.exif.ExifQueuePurpose
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
        assertTrue("same-size images" in preparing)
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
        assertEquals("EXIF queue: checking scores and eligibility…", status(ExifQueuePhase.SCORING))
        assertTrue("updating final retention scores" in status(ExifQueuePhase.REASSESSING))
    }

    @Test
    fun progressCountsVisitsIncludingCacheRatherThanReads() {
        val text = status(ExifQueuePhase.EXTRACTING)
        assertTrue("1/2 groups" in text)
        assertTrue("3/6 files visited" in text)
        assertTrue("includes cached results, not file reads" in text)
    }

    @Test
    fun completionDistinguishesNoEligibleGroups() {
        assertTrue("completed" in status(ExifQueuePhase.COMPLETED))
        assertTrue("no eligible ambiguous groups with a complete folder date" in status(ExifQueuePhase.COMPLETED, groups = 0))
        assertFalse("no eligible" in status(ExifQueuePhase.COMPLETED))
    }

    @Test
    fun cancellationPreservesCacheWithoutClaimingCompletedScoring() {
        val text = status(ExifQueuePhase.CANCELLED)
        assertTrue("cancelled" in text)
        assertTrue("Stored EXIF is preserved; processing stopped" in text)
        assertFalse("completed" in text)
    }

    @Test
    fun failureIncludesErrorAndPartialProgress() {
        val text = status(ExifQueuePhase.FAILED, error = "Database unavailable")
        assertTrue("Database unavailable" in text)
        assertTrue("1/2 groups" in text)
        assertTrue("Unknown error" in status(ExifQueuePhase.FAILED))
    }
}
