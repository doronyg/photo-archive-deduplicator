package com.doronyg.photoarchivededuplicator

import com.doronyg.photoarchivededuplicator.classification.OllamaSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OllamaSettingsPreferencesTest {
    @Test
    fun literalSettingsRoundTripWithUnicodeAndBlankDefaults() {
        assertEquals(OllamaSettings(), decodeUiChoices(emptyMap()).ollama)
        val settings = OllamaSettings("http://test.invalid:11434/\n雪", "model\n雪")
        val choices = UiChoices(ollama = settings)
        val encoded = encodeUiChoices(choices)
        assertEquals(settings.baseUrl, encoded["ollama.baseUrl"])
        assertEquals(settings.model, encoded["ollama.model"])
        assertFalse(encoded.keys.any { it.startsWith("ollama.preferredRoots") })
        assertEquals(choices, decodeUiChoices(encoded))
        assertEquals(OllamaSettings(model = "partial"),
            decodeUiChoices(mapOf("ollama.model" to "partial")).ollama)
    }

    @Test
    fun removedRootSettingsAreIgnoredWhenLoadingExistingPreferences() {
        for (count in listOf("-1", "invalid", "2147483648", "2")) {
            val settings = OllamaSettings("http://test.invalid", "model")
            val decoded = decodeUiChoices(mapOf(
                "ollama.baseUrl" to settings.baseUrl, "ollama.model" to settings.model,
                "ollama.preferredRoots.count" to count, "ollama.preferredRoots.0" to "/old/root",
            ))
            assertEquals(settings, decoded.ollama)
            assertFalse(encodeUiChoices(decoded).keys.any { it.startsWith("ollama.preferredRoots") })
        }
    }

    @Test
    fun updatedServerAndModelArePersisted() = runTest {
        val initial = OllamaSettings("http://initial.invalid", "initial-model")
        val saved = mutableListOf<UiChoices>()
        val preferences = UiPreferences(UiChoices(ollama = initial), this) {
            saved.add(it)
        }
        assertEquals(initial, preferences.state.value.ollama)
        val updated = OllamaSettings("http://updated.invalid", "updated-model")
        preferences.update { it.copy(ollama = updated) }
        assertEquals(updated, preferences.state.value.ollama)
        preferences.shutdown()
        assertEquals(updated, saved.single().ollama)
    }
}
