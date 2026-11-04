package com.doronyg.photoarchivededuplicator

import com.doronyg.photoarchivededuplicator.recommendation.RecommendationAction
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SuspectedDuplicatePreferencesTest {
    @Test
    fun missingFilterKeysDefaultToEmptyAndAllWithoutChangingExistingPreferences() {
        val entries = encodeUiChoices(allUiChoices).filterKeys {
            it !in setOf("suspectedDuplicateRoot", "suspectedDuplicateFilenamePattern", "suspectedDuplicateRecommendation")
        }
        val warnings = mutableListOf<String>()
        assertEquals(allUiChoices.copy(
            suspectedDuplicateRoot = "",
            suspectedDuplicateFilenamePattern = "",
            suspectedDuplicateRecommendation = null,
        ), decodeUiChoices(entries, warnings::add))
        val defaults = decodeUiChoices(emptyMap(), warnings::add)
        assertEquals("", defaults.suspectedDuplicateRoot)
        assertEquals("", defaults.suspectedDuplicateFilenamePattern)
        assertNull(defaults.suspectedDuplicateRecommendation)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun filterKeysPreserveLiteralTextAndEveryRecommendationIncludingAllAndProtected() {
        for (action in listOf(null) + RecommendationAction.entries) {
            val choices = allUiChoices.copy(suspectedDuplicateRecommendation = action)
            val encoded = encodeUiChoices(choices)
            assertEquals(choices.suspectedDuplicateRoot, encoded["suspectedDuplicateRoot"])
            assertEquals(choices.suspectedDuplicateFilenamePattern, encoded["suspectedDuplicateFilenamePattern"])
            assertEquals(action?.name ?: "ALL", encoded["suspectedDuplicateRecommendation"])
            val warnings = mutableListOf<String>()
            assertEquals(choices, decodeUiChoices(encoded, warnings::add))
            assertTrue(warnings.isEmpty())
        }
    }

    @Test
    fun unknownRecommendationWarnsAndDefaultsOnlyRecommendationToAll() {
        val entries = encodeUiChoices(allUiChoices) + ("suspectedDuplicateRecommendation" to "FUTURE_ACTION")
        val warnings = mutableListOf<String>()
        assertEquals(allUiChoices.copy(suspectedDuplicateRecommendation = null),
            decodeUiChoices(entries, warnings::add))
        assertEquals(listOf(
            "Unknown UI preference suspectedDuplicateRecommendation 'FUTURE_ACTION'; using ALL",
        ), warnings)
    }

    @Test
    fun suspectedAndNormalDuplicateFiltersUpdateIndependentlyAndPersistTogether() = runTest(timeout = 10.seconds) {
        var saved: UiChoices? = null
        val preferences = UiPreferences(allUiChoices, this) { saved = it }
        try {
            preferences.update { it.copy(
                suspectedDuplicateRoot = "/new\n疑い",
                suspectedDuplicateFilenamePattern = "?*literal[雪].jpg",
                suspectedDuplicateRecommendation = RecommendationAction.PROTECTED,
            ) }
            assertEquals(allUiChoices.duplicateRoot, preferences.state.value.duplicateRoot)
            assertEquals(allUiChoices.duplicateFilenamePattern, preferences.state.value.duplicateFilenamePattern)
            preferences.update { it.copy(duplicateRoot = "/normal", duplicateFilenamePattern = "*.mp4") }
            val expected = allUiChoices.copy(
                duplicateRoot = "/normal",
                duplicateFilenamePattern = "*.mp4",
                suspectedDuplicateRoot = "/new\n疑い",
                suspectedDuplicateFilenamePattern = "?*literal[雪].jpg",
                suspectedDuplicateRecommendation = RecommendationAction.PROTECTED,
            )
            assertEquals(expected, preferences.state.value)
            preferences.shutdown()
            assertEquals(expected, saved)
            assertEquals(expected, decodeUiChoices(encodeUiChoices(expected)))
        } finally {
            preferences.shutdown()
        }
    }
}
