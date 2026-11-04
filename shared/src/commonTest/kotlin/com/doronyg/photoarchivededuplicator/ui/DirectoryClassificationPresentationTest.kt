package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.classification.*
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

class DirectoryClassificationPresentationTest {
    private val context = OllamaSettings("https://example.test", "chosen-model").context()
    private fun file(path: String) = FileRecord(path.toPath(), path.toPath().parent!!,
        path.toPath().name, "jpg", 10, null, 1)

    @Test
    fun blankSettingsCanBeClearedButPartialAndInvalidSettingsExplainErrors() {
        assertNull(ollamaSettingsValidationError(OllamaSettings()))
        assertTrue(ollamaSettingsValidationError(OllamaSettings("https://example.test", ""))!!.isNotBlank())
        assertTrue(ollamaSettingsValidationError(OllamaSettings("file:///private", "model"))!!.isNotBlank())
        assertNull(ollamaSettingsValidationError(OllamaSettings("https://example.test", "model")))
        assertFalse("preferred roots" in DIRECTORY_CLASSIFICATION_PRIVACY)
    }

    @Test
    fun selectedGroupsIncludeAllMembersAndOffscreenGroupsButNeverOtherGroups() {
        val selected = listOf(
            listOf(file("/visible/a.jpg"), file("/outside/b.jpg")),
            listOf(file("/visible/c.jpg"), file("/offscreen/d.jpg")),
        )
        val other = listOf(file("/excluded/e.jpg"), file("/excluded/f.jpg"))
        val counts = directoryClassificationCounts(selected, emptyMap())
        assertEquals(3, counts.total)
        assertEquals(3, counts.missing)
        assertEquals(4, directoryClassificationCounts(selected + listOf(other), emptyMap()).total)
        val stored = DirectoryClassificationRecord("/outside".toPath(), "/outside", DirectoryClassification.UNKNOWN, context, 2)
        assertEquals(2, directoryClassificationCounts(selected, mapOf(stored.directoryPath to stored)).missing)
    }

    @Test
    fun folderSelectionPreservesEveryMemberOfMatchingGroups() {
        val matching = DuplicateCandidateGroup("a.jpg", 10,
            listOf(file("/visible/a.jpg"), file("/outside/a.jpg")))
        val outside = DuplicateCandidateGroup("b.jpg", 10,
            listOf(file("/visible-other/b.jpg"), file("/outside/b.jpg")))
        assertEquals(listOf(matching), completeDuplicateGroupsUnderRoot(listOf(matching, outside), "/visible".toPath()))
        assertEquals(2, completeDuplicateGroupsUnderRoot(listOf(matching, outside), "/visible".toPath()).single().files.size)
    }

    @Test
    fun progressIncludesFailuresRemainingAndCancellation() {
        val state = DirectoryClassificationJobState(phase = DirectoryClassificationPhase.CANCELLED,
            total = 5, completed = 1, skipped = 1,
            failures = listOf(DirectoryClassificationFailure("/failed".toPath(), "Offline")))
        val text = directoryClassificationProgressText(state)
        listOf("Cancelled", "1 completed", "1 skipped", "2 remaining", "1 failed").forEach {
            assertTrue(it in text)
        }
        assertTrue("Completed with failures" in directoryClassificationProgressText(
            state.copy(phase = DirectoryClassificationPhase.COMPLETED)))
    }

    @Test
    fun cachedClassificationPresentationIsExplicitlyAdvisoryAndProfileScoped() {
        val record = DirectoryClassificationRecord("/写真".toPath(), "/写真", DirectoryClassification.PROTECTED, context, 2)
        val text = directoryClassificationRecordText(record)
        assertTrue("PROTECTED" in text)
        assertTrue("cached" in text)
        assertTrue("chosen-model" in text)
        assertTrue("not content verification" in text)
    }

    @Test
    fun cachedEvidenceOnlyAcceptsRequestedDirectoriesAndCurrentProfile() = runTest {
        val wanted = DirectoryClassificationRecord("/visible".toPath(), "/visible",
            DirectoryClassification.PROTECTED, context, 2)
        val outside = wanted.copy(directoryPath = "/outside".toPath())
        val stale = wanted.copy(context = context.copy(model = "old-model"))
        var calls = 0
        val result = loadCachedDirectoryClassifications(listOf("/visible".toPath()), context) { paths, profile ->
            calls++
            assertEquals(listOf("/visible".toPath()), paths)
            assertEquals(context, profile)
            listOf(wanted, outside, stale)
        }
        assertEquals(1, calls)
        assertTrue(result.ready)
        assertEquals(mapOf(wanted.directoryPath to wanted), result.records)
        assertNull(result.error)
    }

    @Test
    fun cachedReadFailuresAreNotSuccessfulEmptyEvidenceAndUnconfiguredNeverReads() = runTest {
        val failed = loadCachedDirectoryClassifications(listOf("/visible".toPath()), context) { _, _ ->
            error("Database unavailable")
        }
        assertFalse(failed.ready)
        assertTrue(failed.records.isEmpty())
        assertTrue("Database unavailable" in failed.error.orEmpty())
        val unconfigured = loadCachedDirectoryClassifications(listOf("/visible".toPath()), null) { _, _ ->
            error("Must not query when unconfigured")
        }
        assertTrue(unconfigured.ready)
        assertTrue(unconfigured.records.isEmpty())
        assertNull(unconfigured.error)
    }

    @Test
    fun pendingReadsRetainMatchingProtectionButNeverClaimReadyOrLeakAnotherProfile() {
        val protected = DirectoryClassificationRecord("/visible".toPath(), "/visible",
            DirectoryClassification.PROTECTED, context, 2)
        val outside = protected.copy(directoryPath = "/outside".toPath())
        val previous = CachedDirectoryClassifications(
            mapOf(protected.directoryPath to protected, outside.directoryPath to outside), ready = true)
        val pending = pendingCachedDirectoryClassifications(previous, listOf(protected.directoryPath), context)
        assertFalse(pending.ready)
        assertEquals(mapOf(protected.directoryPath to protected), pending.records)
        assertTrue(pendingCachedDirectoryClassifications(previous, listOf(protected.directoryPath),
            context.copy(model = "other-model")).records.isEmpty())
    }
}
