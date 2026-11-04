package com.doronyg.photoarchivededuplicator

import com.doronyg.photoarchivededuplicator.scan.DuplicateSortCriterion
import com.doronyg.photoarchivededuplicator.scan.DuplicateSortRule
import com.doronyg.photoarchivededuplicator.scan.ScanTestEnvironment
import com.doronyg.photoarchivededuplicator.scan.SortDirection
import com.doronyg.photoarchivededuplicator.scan.defaultDuplicateSortRules
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DuplicateSortPreferencesTest {
    @Test
    fun existingPreferencesWithoutSortKeysUseDefaultOrder() {
        val old = encodeUiChoices(UiChoices(scanRoot = "/keep", logFilter = "keep"))
            .filterKeys { !it.startsWith("duplicateSort.") && !it.startsWith("suspectedDuplicateSort.") }
        assertEquals(UiChoices(scanRoot = "/keep", logFilter = "keep"), decodeUiChoices(old))
        assertEquals(defaultDuplicateSortRules(), decodeUiChoices(emptyMap()).duplicateSort)
        assertEquals(defaultDuplicateSortRules(), decodeUiChoices(emptyMap()).suspectedDuplicateSort)
    }

    @Test
    fun missingSuspectedSortKeysUseDefaultsWithoutChangingSavedDuplicateSort() {
        val entries = encodeUiChoices(allUiChoices)
            .filterKeys { !it.startsWith("suspectedDuplicateSort.") }
        assertEquals(allUiChoices.copy(suspectedDuplicateSort = defaultDuplicateSortRules()),
            decodeUiChoices(entries))
    }

    @Test
    fun suspectedSortUsesIndependentPersistedKeyNamespace() {
        val entries = encodeUiChoices(allUiChoices)
        assertEquals(allUiChoices.suspectedDuplicateSort.joinToString(",") { it.criterion.name },
            entries["suspectedDuplicateSort.order"])
        for (rule in allUiChoices.suspectedDuplicateSort) {
            assertEquals(rule.enabled.toString(), entries["suspectedDuplicateSort.${rule.criterion.name}.enabled"])
            assertEquals(rule.direction.name, entries["suspectedDuplicateSort.${rule.criterion.name}.direction"])
        }
        assertEquals(allUiChoices, decodeUiChoices(entries))
    }

    @Test
    fun priorityEnabledFlagsAndDirectionsRoundTripThroughRealDatabase() {
        val env = ScanTestEnvironment()
        try {
            env.test {
                val rules = defaultDuplicateSortRules().reversed().mapIndexed { index, rule ->
                    rule.copy(enabled = index % 2 == 0, direction = SortDirection.DESCENDING)
                }
                val choices = UiChoices(duplicateSort = rules, suspectedDuplicateSort = allUiChoices.suspectedDuplicateSort)
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
            DuplicateSortCriterion.FILE_SIZE, DuplicateSortCriterion.DUPLICATE_COUNT,
            DuplicateSortCriterion.RECOMMENDATION),
            decoded.duplicateSort.map { it.criterion })
        assertEquals(DuplicateSortCriterion.entries.toSet(), decoded.duplicateSort.map { it.criterion }.toSet())
        assertTrue(warnings.any { "FUTURE_SORT" in it })
        assertTrue(warnings.any { "NAME" in it && "repeat" in it.lowercase() })
        assertTrue(warnings.any { "FUTURE_DIRECTION" in it })
    }

    @Test
    fun legacyFourCriteriaKeepTheirOrderAndAppendDisabledRecommendationInBothTabs() {
        val order = "EARLIEST_CREATED,DUPLICATE_COUNT,NAME,FILE_SIZE"
        val entries = mapOf(
            "duplicateSort.order" to order,
            "duplicateSort.NAME.direction" to "DESCENDING",
            "suspectedDuplicateSort.order" to order,
            "suspectedDuplicateSort.FILE_SIZE.enabled" to "false",
        )
        val warnings = mutableListOf<String>()
        val decoded = decodeUiChoices(entries, warnings::add)
        for (rules in listOf(decoded.duplicateSort, decoded.suspectedDuplicateSort)) {
            assertEquals(order + ",RECOMMENDATION", rules.joinToString(",") { it.criterion.name })
            assertFalse(rules.last().enabled)
        }
        assertEquals(SortDirection.DESCENDING,
            decoded.duplicateSort.first { it.criterion == DuplicateSortCriterion.NAME }.direction)
        assertFalse(decoded.suspectedDuplicateSort.first { it.criterion == DuplicateSortCriterion.FILE_SIZE }.enabled)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun suspectedSortUnknownFieldsWarnAndPreserveValidValuesWithoutChangingDuplicates() {
        val warnings = mutableListOf<String>()
        val entries = encodeUiChoices(allUiChoices).toMutableMap().apply {
            this["suspectedDuplicateSort.order"] = "EARLIEST_CREATED,FUTURE_SORT,NAME,NAME"
            this["suspectedDuplicateSort.NAME.direction"] = "FUTURE_DIRECTION"
            this["suspectedDuplicateSort.EARLIEST_CREATED.direction"] = "DESCENDING"
            this["suspectedDuplicateSort.EARLIEST_CREATED.enabled"] = "false"
        }
        val decoded = decodeUiChoices(entries, warnings::add)
        assertEquals(allUiChoices.duplicateSort, decoded.duplicateSort)
        assertEquals(listOf(DuplicateSortCriterion.EARLIEST_CREATED, DuplicateSortCriterion.NAME,
            DuplicateSortCriterion.FILE_SIZE, DuplicateSortCriterion.DUPLICATE_COUNT,
            DuplicateSortCriterion.RECOMMENDATION), decoded.suspectedDuplicateSort.map { it.criterion })
        assertEquals(DuplicateSortRule(DuplicateSortCriterion.EARLIEST_CREATED, false, SortDirection.DESCENDING),
            decoded.suspectedDuplicateSort.first())
        assertEquals(defaultDuplicateSortRules().first { it.criterion == DuplicateSortCriterion.NAME }.direction,
            decoded.suspectedDuplicateSort[1].direction)
        assertEquals(3, warnings.size)
        assertTrue(warnings.any { "FUTURE_SORT" in it })
        assertTrue(warnings.any { "repeat" in it.lowercase() })
        assertTrue(warnings.any { "suspectedDuplicateSort.NAME.direction" in it && "FUTURE_DIRECTION" in it })
    }

    @Test
    fun malformedSuspectedSortBooleansFailRatherThanSilentlyDefaulting() {
        for (value in listOf("TRUE", "yes", "", "1")) {
            val error = assertFailsWith<IllegalArgumentException> {
                decodeUiChoices(mapOf("suspectedDuplicateSort.NAME.enabled" to value))
            }
            assertTrue("suspectedDuplicateSort.NAME.enabled" in error.message.orEmpty())
        }
    }

    @Test
    fun tabSortUpdatesAreIndependentAndPersistTogether() = runTest(timeout = 10.seconds) {
        var saved: UiChoices? = null
        val preferences = UiPreferences(UiChoices(), this) { saved = it }
        try {
            preferences.update { it.copy(duplicateSort = allUiChoices.duplicateSort) }
            assertEquals(defaultDuplicateSortRules(), preferences.state.value.suspectedDuplicateSort)
            preferences.update { it.copy(suspectedDuplicateSort = allUiChoices.suspectedDuplicateSort) }
            assertEquals(allUiChoices.duplicateSort, preferences.state.value.duplicateSort)
            preferences.update { it.copy(duplicateSort = defaultDuplicateSortRules()) }
            assertEquals(allUiChoices.suspectedDuplicateSort, preferences.state.value.suspectedDuplicateSort)
            preferences.shutdown()
            val expected = UiChoices(suspectedDuplicateSort = allUiChoices.suspectedDuplicateSort)
            assertEquals(expected, saved)
            assertEquals(expected, decodeUiChoices(encodeUiChoices(expected)))
        } finally {
            preferences.shutdown()
        }
    }

    @Test
    fun preferencesSnapshotSortRulesInsteadOfRetainingMutableCallerList() = runTest(timeout = 10.seconds) {
        val initialRules = defaultDuplicateSortRules().toMutableList()
        val initialSuspectedRules = allUiChoices.suspectedDuplicateSort.toMutableList()
        val preferences = UiPreferences(UiChoices(
            duplicateSort = initialRules, suspectedDuplicateSort = initialSuspectedRules), this) {}
        try {
            initialRules.clear()
            initialSuspectedRules.clear()
            assertEquals(defaultDuplicateSortRules(), preferences.state.value.duplicateSort)
            assertEquals(allUiChoices.suspectedDuplicateSort, preferences.state.value.suspectedDuplicateSort)
            val editedRules = listOf(
                DuplicateSortRule(DuplicateSortCriterion.NAME, enabled = false),
            ).toMutableList()
            preferences.update { it.copy(duplicateSort = editedRules, suspectedDuplicateSort = editedRules) }
            editedRules.clear()
            assertEquals(listOf(DuplicateSortRule(DuplicateSortCriterion.NAME, enabled = false)),
                preferences.state.value.duplicateSort)
            assertEquals(listOf(DuplicateSortRule(DuplicateSortCriterion.NAME, enabled = false)),
                preferences.state.value.suspectedDuplicateSort)
        } finally {
            preferences.shutdown()
        }
    }
}
