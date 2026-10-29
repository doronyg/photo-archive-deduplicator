package com.doronyg.photoarchivededuplicator.scan

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import okio.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Virtual time (testScheduler.currentTime) is still marked experimental in kotlinx-coroutines-test
@OptIn(ExperimentalCoroutinesApi::class)
class MediaIndexScannerTest {
    private val env = ScanTestEnvironment()
    private val root = env.root
    private var items = listOf<MediaItem>()
    private val source = MediaSource {
        env.io.assertOnIo("MediaSource.items")
        items
    }

    @AfterTest
    fun tearDown() = env.close()

    private fun TestScope.scanner() = MediaIndexScanner(source, env.repository, env.io, clock = { testScheduler.currentTime })

    private fun item(path: Path, size: Long = 1) = MediaItem(path, size, modifiedAt = 7)

    @Test
    fun recordsFilesInTheirFoldersIncludingIntermediateFolders() = env.test {
        items = listOf(item(root / "DCIM" / "Camera" / "1.jpg", 10), item(root / "Pictures" / "2.png", 20))

        scanner().startScan(root)

        val directories = env.repository.allDirectories().associateBy { it.path }
        assertEquals(
            setOf(root, root / "DCIM", root / "DCIM" / "Camera", root / "Pictures"),
            directories.keys,
        )
        assertTrue(directories.values.all { it.status == DirectoryStatus.COMPLETED })
        assertEquals(root / "DCIM", directories.getValue(root / "DCIM" / "Camera").parentPath)
        val photo = env.repository.file(root / "DCIM" / "Camera" / "1.jpg")!!
        assertEquals(10L, photo.sizeBytes)
        assertEquals(7L, photo.modifiedAt)
        assertEquals("jpg", photo.extension)
    }

    @Test
    fun ignoresItemsOutsideRoot() = env.test {
        items = listOf(item(root / "a.jpg"), item(root.parent!! / "elsewhere" / "b.jpg"))

        scanner().startScan(root)

        assertEquals(listOf(root / "a.jpg"), env.repository.allFiles().map { it.path })
        assertEquals(listOf(root), env.repository.allDirectories().map { it.path })
    }

    @Test
    fun rescanRemovesFilesAndFoldersNoLongerIndexed() = env.test {
        items = listOf(item(root / "keep" / "1.jpg"), item(root / "gone" / "2.jpg"), item(root / "keep" / "3.jpg"))
        scanner().startScan(root)

        items = listOf(item(root / "keep" / "1.jpg", 99))
        scanner().startScan(root)

        assertEquals(listOf(root / "keep" / "1.jpg"), env.repository.allFiles().map { it.path })
        assertEquals(99L, env.repository.file(root / "keep" / "1.jpg")!!.sizeBytes)
        assertNull(env.repository.directory(root / "gone"))
    }

    @Test
    fun resumeRescansPreviouslyScannedRoots() = env.test {
        items = listOf(item(root / "1.jpg"))
        scanner().startScan(root)

        items = listOf(item(root / "1.jpg"), item(root / "new" / "2.jpg"))
        scanner().resume()

        assertEquals(setOf(root / "1.jpg", root / "new" / "2.jpg"), env.repository.allFiles().map { it.path }.toSet())
    }

    @Test
    fun reportsProgressThroughScanController() = env.test {
        items = listOf(item(root / "a" / "1.jpg"), item(root / "a" / "2.jpg"), item(root / "b" / "c" / "3.jpg"))
        val controller = ScanController(scanner(), scope = this, clock = { testScheduler.currentTime })

        controller.start(root)
        advanceUntilIdle()

        val progress = assertIs<ScanState.Completed>(controller.state.value).progress
        // root, a, b, b/c
        assertEquals(4L, progress.scannedDirectories)
        assertEquals(3L, progress.filesFound)
        assertEquals(0L, progress.pendingDirectories)
    }
}
