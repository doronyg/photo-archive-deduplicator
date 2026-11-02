package com.doronyg.photoarchivededuplicator

import com.doronyg.photoarchivededuplicator.scan.DuplicateSortCriterion
import com.doronyg.photoarchivededuplicator.scan.DuplicateSortRule
import com.doronyg.photoarchivededuplicator.scan.ScanTestEnvironment
import com.doronyg.photoarchivededuplicator.scan.SortDirection
import com.doronyg.photoarchivededuplicator.scan.defaultDuplicateSortRules
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DuplicateSortPreferencesTest {
    @Test
    fun existingPreferencesWithoutSortKeysUseDefaultOrder() {
        val old = encodeUiChoices(UiChoices(scanRoot = "/keep", logFilter = "keep"))
            .filterKeys { !it.startsWith("duplicateSort.") }
        assertEquals(UiChoices(scanRoot = "/keep", logFilter = "keep"), decodeUiChoices(old))
        assertEquals(defaultDuplicateSortRules(), decodeUiChoices(emptyMap()).duplicateSort)
    }

    @Test
    fun priorityEnabledFlagsAndDirectionsRoundTripThroughRealDatabase() {
        val env = ScanTestEnvironment()
        try {
            env.test {
                val rules = defaultDuplicateSortRules().reversed().mapIndexed { index, rule ->
                    rule.copy(enabled = index % 2 == 0, direction = SortDirection.DESCENDING)
                }
                val choices = UiChoices(duplicateSort = rules)
                env.repository.saveUiChoices(choices)
                assertEquals(choices, env.repository.loadUiChoices())
            }
        } finally {
            env.close()
        }
    }

    @Test
    fun unknownOrRepeatedCriteriaWarnAndRetainKnownPriorityWithoutDuplicates() {
        val warnings = mutableListOf<String>()
        val entries = encodeUiChoices(UiChoices()).toMutableMap().apply {
            this["duplicateSort.order"] = "EARLIEST_CREATED,FUTURE_SORT,NAME,NAME"
            this["duplicateSort.NAME.direction"] = "FUTURE_DIRECTION"
        }
        val decoded = decodeUiChoices(entries, warnings::add)
        assertEquals(listOf(DuplicateSortCriterion.EARLIEST_CREATED, DuplicateSortCriterion.NAME,
            DuplicateSortCriterion.FILE_SIZE, DuplicateSortCriterion.DUPLICATE_COUNT),
            decoded.duplicateSort.map { it.criterion })
        assertEquals(DuplicateSortCriterion.entries.toSet(), decoded.duplicateSort.map { it.criterion }.toSet())
        assertTrue(warnings.any { "FUTURE_SORT" in it })
        assertTrue(warnings.any { "NAME" in it && "repeat" in it.lowercase() })
        assertTrue(warnings.any { "FUTURE_DIRECTION" in it })
    }

    @Test
    fun preferencesSnapshotSortRulesInsteadOfRetainingMutableCallerList() = runTest(timeout = 10.seconds) {
        val initialRules = defaultDuplicateSortRules().toMutableList()
        val preferences = UiPreferences(UiChoices(duplicateSort = initialRules), this) {}
        try {
            initialRules.clear()
            assertEquals(defaultDuplicateSortRules(), preferences.state.value.duplicateSort)
            val editedRules = listOf(
                DuplicateSortRule(DuplicateSortCriterion.NAME, enabled = false),
            ).toMutableList()
            preferences.update { it.copy(duplicateSort = editedRules) }
            editedRules.clear()
            assertEquals(listOf(DuplicateSortRule(DuplicateSortCriterion.NAME, enabled = false)),
                preferences.state.value.duplicateSort)
        } finally {
            preferences.shutdown()
        }
    }
}
