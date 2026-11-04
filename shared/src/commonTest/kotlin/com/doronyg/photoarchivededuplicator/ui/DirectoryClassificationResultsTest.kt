package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.classification.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.*

class DirectoryClassificationResultsTest {
    private val context = OllamaSettings("http://ollama.test", "selected-model").context()
    private val current = DirectoryClassificationRecord("/Photos/טיול".toPath(), "/Photos/טיול",
        DirectoryClassification.PROTECTED, context, 123)

    @Test
    fun currentSettingsExcludeOtherServersModelsAndPromptVersionsButAllResultsRemainViewable() {
        val records = listOf(current,
            current.copy(context = context.copy(baseUrl = "http://other.test")),
            current.copy(context = context.copy(model = "older-model")),
            current.copy(context = context.copy(promptVersion = "older-prompt")))
        assertEquals(listOf(current), filterDirectoryClassificationResults(records, context, true, ""))
        assertEquals(records, filterDirectoryClassificationResults(records, context, false, ""))
        assertEquals(emptyList(), filterDirectoryClassificationResults(records, null, true, ""))
        assertEquals(records, filterDirectoryClassificationResults(records, null, false, ""))
    }

    @Test
    fun searchMatchesUnicodePathsClassificationAndModelWhilePreservingOrder() {
        val backup = current.copy(directoryPath = "/Photos/Backup".toPath(), classification = DirectoryClassification.BACKUP)
        val records = listOf(backup, current)
        assertEquals(listOf(current), filterDirectoryClassificationResults(records, context, false, " טיול "))
        assertEquals(listOf(backup), filterDirectoryClassificationResults(records, context, true, "backup"))
        assertEquals(records, filterDirectoryClassificationResults(records, context, true, "SELECTED-MODEL"))
        assertTrue(filterDirectoryClassificationResults(records, context, false, "missing").isEmpty())
    }

    @Test
    fun loadingPreservesSeparateProfileRecordsAndSurfacesDatabaseFailures() = runTest {
        val records = listOf(current, current.copy(context = context.copy(model = "other")))
        val loaded = loadDirectoryClassificationResults { records }
        assertEquals(records, loaded.records)
        assertFalse(loaded.loading)
        assertNull(loaded.error)
        val failed = loadDirectoryClassificationResults { error("Database unavailable") }
        assertFalse(failed.loading)
        assertTrue("Database unavailable" in failed.error.orEmpty())
        assertTrue(failed.records.isEmpty())
    }

    @Test
    fun loadingCancellationPropagates() = runTest {
        assertFailsWith<CancellationException> {
            loadDirectoryClassificationResults { throw CancellationException("screen closed") }
        }
    }

    @Test
    fun exportIncludesEveryFilteredRowInDisplayOrderWithProfileAndTimestampDetails() = runTest {
        val records = (0 until 150).map { index ->
            current.copy(directoryPath = "/Photos/trip-$index".toPath(), originalPath = "/Photos/trip-$index")
        }
        val other = current.copy(context = context.copy(model = "other-model"))
        val visible = filterDirectoryClassificationResults(records + other, context, true, "trip")
        val text = formatDirectoryClassificationResults(visible, context, true, "trip")
        assertTrue("Scope: Current saved settings" in text)
        assertTrue("Filter: \"trip\"" in text)
        assertTrue("Results: 150" in text)
        assertEquals(150, text.lineSequence().count { it.startsWith("Directory: ") })
        assertTrue(text.indexOf("\"/Photos/trip-0\"") < text.indexOf("\"/Photos/trip-149\""))
        assertTrue("Classification: PROTECTED" in text)
        assertTrue("Classified at: 1970-01-01 00:00:00 UTC" in text)
        assertTrue("Model: \"selected-model\"" in text)
        assertTrue("Server: \"http://ollama.test\"" in text)
        assertTrue("Prompt version: \"${context.promptVersion}\"" in text)
        assertFalse("other-model" in text)
    }

    @Test
    fun allProfilesExportKeepsSeparateResultsForTheSameDirectory() = runTest {
        val historical = current.copy(context = context.copy(promptVersion = "old"))
        val text = formatDirectoryClassificationResults(listOf(current, historical), context, false, "")
        assertTrue("Scope: All saved results" in text)
        assertTrue("Results: 2" in text)
        assertTrue("Matches current saved settings: true" in text)
        assertTrue("Matches current saved settings: false" in text)
        assertTrue("Prompt version: \"old\"" in text)
    }

    @Test
    fun exportEscapesUnsafeTextAndPreservesUnicode() = runTest {
        val unsafe = current.copy(directoryPath = "/Photos/טיול\nClassification: BACKUP".toPath(),
            originalPath = "/Photos/original\npath", context = context.copy(model = "model\nline"))
        val text = formatDirectoryClassificationResults(listOf(unsafe), null, false, "filter\nline")
        assertTrue("טיול\\nClassification: BACKUP" in text)
        assertTrue("Original path: \"/Photos/original\\npath\"" in text)
        assertTrue("Model: \"model\\nline\"" in text)
        assertTrue("Filter: \"filter\\nline\"" in text)
        assertEquals(1, text.lineSequence().count { it.startsWith("Classification: ") })
        assertTrue("not content verification" in text)
    }
}
