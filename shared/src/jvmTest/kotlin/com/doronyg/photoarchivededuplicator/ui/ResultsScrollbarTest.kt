package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.ui.unit.Constraints
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResultsScrollbarTest {
    private val minimumThumbHeight = defaultScrollbarStyle().minimalHeight.value

    @Test
    fun zeroAndShortTracksCannotCreateAnInteractiveScrollbar() {
        for (height in 0..minimumThumbHeight.toInt()) {
            assertFalse(resultsScrollbarHasDragSpace(Constraints(maxHeight = height), minimumThumbHeight))
        }
    }

    @Test
    fun ordinaryTracksRetainTheScrollbarAndRespectPixelDensity() {
        assertTrue(resultsScrollbarHasDragSpace(Constraints(maxHeight = 200), minimumThumbHeight))
        val scaledMinimum = minimumThumbHeight * 1.3f
        assertFalse(resultsScrollbarHasDragSpace(Constraints(maxHeight = 20), scaledMinimum))
        assertTrue(resultsScrollbarHasDragSpace(Constraints(maxHeight = 21), scaledMinimum))
    }

    @Test
    fun unboundedOrShrinkingTracksNeverRetainAnUnsafeDragTarget() {
        assertFalse(resultsScrollbarHasDragSpace(Constraints(), minimumThumbHeight))
        for (height in listOf(200, 8, 0, 100)) {
            assertTrue(
                resultsScrollbarHasDragSpace(Constraints(maxHeight = height), minimumThumbHeight) ==
                    (height > minimumThumbHeight),
            )
        }
    }
}
