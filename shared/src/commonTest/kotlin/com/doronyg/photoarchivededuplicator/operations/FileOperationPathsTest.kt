package com.doronyg.photoarchivededuplicator.operations

import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotEquals

class FileOperationPathsTest {
    @Test
    fun destinationPreservesFullOriginalPathWithoutAnArchiveRoot() {
        assertEquals("/NAS/DeduplicatorGarbageBin/NAS/Archive/2018/Trip/雪.JPG".toPath(),
            operationDestinationPath("/NAS/Archive/2018/Trip/雪.JPG".toPath(),
                "/NAS/DeduplicatorGarbageBin".toPath()))
        assertEquals("/Archive/DeduplicatorGarbageBin/Archive/Family/photo.jpg".toPath(),
            operationDestinationPath("/Archive/Family/photo.jpg".toPath(),
                "/Archive/DeduplicatorGarbageBin".toPath()))
        assertEquals("/Bin/other/photo.jpg".toPath(),
            operationDestinationPath("/other/photo.jpg".toPath(), "/Bin".toPath()))
        assertEquals("/Bin/Photos/a\\b.jpg".toPath(),
            operationDestinationPath("/Photos/a\\b.jpg".toPath(), "/Bin".toPath()))
    }

    @Test
    fun neighboringRootsTraversalAndSourcesAlreadyInTheBinCannotBeMapped() {
        val bin = "/Archive/DeduplicatorGarbageBin".toPath()
        for (source in listOf("/Archive/../other/photo.jpg",
            "/Archive/DeduplicatorGarbageBin/photo.jpg", "/", "Family/photo.jpg")) {
            assertNull(operationDestinationPath(source.toPath(), bin), source)
        }
        assertNull(operationDestinationPath("/Archive/photo.jpg".toPath(), "/".toPath()))
        assertEquals("/Bin/Archive-extra/photo.jpg".toPath(),
            operationDestinationPath("/Archive-extra/photo.jpg".toPath(), "/Bin".toPath()))
    }

    @Test
    fun windowsDrivesAndNetworkSharesRemainDistinct() {
        assertEquals("D:\\Bin\\C\\Photos\\a.jpg".toPath(),
            operationDestinationPath("C:\\Photos\\a.jpg".toPath(), "D:\\Bin".toPath()))
        assertEquals("D:\\Bin\\server\\share\\Photos\\a.jpg".toPath(),
            operationDestinationPath("\\\\server\\share\\Photos\\a.jpg".toPath(), "D:\\Bin".toPath()))
        assertNotEquals(operationDestinationPath("C:\\Photos\\a.jpg".toPath(), "D:\\Bin".toPath()),
            operationDestinationPath("E:\\Photos\\a.jpg".toPath(), "D:\\Bin".toPath()))
    }

    @Test
    fun identicalRelativePathsFromDifferentLocationsNeverCollapseIntoOneDestination() {
        val bin = "/Bin".toPath()
        assertNotEquals(operationDestinationPath("/photos/Family/a.jpg".toPath(), bin),
            operationDestinationPath("/backup/Family/a.jpg".toPath(), bin))
        assertEquals("/Bin/Bin-neighbor/a.jpg".toPath(),
            operationDestinationPath("/Bin-neighbor/a.jpg".toPath(), bin))
        assertNull(operationDestinationPath("/photos/a.jpg".toPath(), "relative/Bin".toPath()))
        assertNull(operationDestinationPath("/photos/a.jpg".toPath(), "/Bin/../other".toPath()))
    }
}
