package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.exif.ExifQueuePhase
import com.doronyg.photoarchivededuplicator.exif.ExifQueueState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuspectedScoringPolicyTest {
    @Test
    fun passiveTerminalRefreshNeverReassessesPartialOrAlreadyFinishedQueues() {
        for (phase in listOf(ExifQueuePhase.COMPLETED, ExifQueuePhase.CANCELLED, ExifQueuePhase.FAILED)) {
            assertFalse(shouldAssessSuspectedScores(ExifQueueState(phase), false, terminalRefresh = true, force = false))
        }
        for (phase in listOf(ExifQueuePhase.CANCELLED, ExifQueuePhase.FAILED)) {
            assertFalse(shouldAssessSuspectedScores(ExifQueueState(phase), false, terminalRefresh = false, force = false))
            assertTrue(shouldAssessSuspectedScores(ExifQueueState(phase), false, terminalRefresh = false, force = true))
        }
    }

    @Test
    fun activeScanOrExifAlwaysBlocksAssessmentEvenForForcedRequests() {
        assertFalse(shouldAssessSuspectedScores(ExifQueueState(), true, false, true))
        for (phase in listOf(ExifQueuePhase.SCORING, ExifQueuePhase.EXTRACTING, ExifQueuePhase.REASSESSING)) {
            assertFalse(shouldAssessSuspectedScores(ExifQueueState(phase), false, false, true))
        }
        assertTrue(shouldAssessSuspectedScores(ExifQueueState(), false, false, false))
    }
}
