package com.doronyg.photoarchivededuplicator.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class InventoryFilePathsTextTest {
    @Test
    fun outputContainsOnlyUnquotedFullPathsOnePerLine() = runTest {
        val paths = listOf("/Photos/Family album/a.jpg", "/Photos/sub/\"b\".png", "C:\\Photos\\c.jpg")
        assertEquals(paths.joinToString("\n"), formatInventoryFilePaths(paths, StandardTestDispatcher(testScheduler)))
    }

    @Test
    fun emptyInventoryProducesEmptyTextWithoutAHeader() = runTest {
        assertEquals("", formatInventoryFilePaths(emptyList(), StandardTestDispatcher(testScheduler)))
    }

    @Test
    fun lineBreaksInFileNamesAreReportedInsteadOfProducingMisleadingPaths() = runTest {
        for (path in listOf("/Photos/a\nb.jpg", "/Photos/a\rb.jpg")) {
            val error = assertFailsWith<IllegalArgumentException> {
                formatInventoryFilePaths(listOf(path), StandardTestDispatcher(testScheduler))
            }
            assertEquals(
                "A stored file path contains a line break and cannot be exported as one path per line.",
                error.message,
            )
        }
    }
}
