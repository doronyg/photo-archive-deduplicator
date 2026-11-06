package com.doronyg.photoarchivededuplicator.scan

import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.retention.FileScore
import com.doronyg.photoarchivededuplicator.retention.FileScoreInput
import com.doronyg.photoarchivededuplicator.retention.ScoreIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BinExclusionTest {
    private val env = ScanTestEnvironment()
    private val repository = env.repository
    private val root = env.root
    private val bin = root / "DeduplicatorGarbageBin"

    @AfterTest
    fun close() = env.close()

    @Test
    fun configurationIsPersistentNormalizedMandatoryAndDoesNotTouchFilesystem() = env.test {
        env.fs.onBlockingCall = { error("Bin configuration must not access the filesystem: $it") }
        assertNull(repository.binRoot())
        repository.seedRoot(root)
        repository.addExclusion(bin, "ordinary exclusion")
        repository.configureBinRoot(bin / "unused" / "..", 10)
        assertEquals(bin, repository.binRoot())
        assertEquals(listOf(bin), repository.registeredBinRoots())
        assertEquals(DirectoryExclusion(bin, "DeduplicatorGarbageBin", isSystem = true),
            repository.exclusions().single())
        assertFailsWith<IllegalArgumentException> { repository.removeExclusion(bin) }
        assertFailsWith<IllegalArgumentException> { repository.addExclusion(bin, "replace") }
        val next = root / "next-bin"
        repository.configureBinRoot(next, 20)
        assertEquals(next, env.newRepository().binRoot())
        assertEquals(setOf(bin, next), env.newRepository().registeredBinRoots().toSet())
        assertTrue(repository.isExcluded(bin / "old-proposal.jpg"))
        assertTrue(repository.exclusions().all { it.isSystem })
    }

    @Test
    fun rejectsFilesystemRootsRelativePathsAndAncestorsOfKnownScanRoots() = env.test {
        repository.beginScanSession(root)
        for (invalid in listOf("/".toPath(), "relative".toPath(), root, root.parent!!)) {
            assertFailsWith<IllegalArgumentException> { repository.configureBinRoot(invalid, 1) }
        }
        repository.configureBinRoot(bin, 2)
        assertEquals(bin, repository.binRoot())
        repository.seedRoot("/another/archive".toPath())
        assertFailsWith<IllegalArgumentException> {
            repository.configureBinRoot("/another".toPath(), 3)
        }
        assertEquals(bin, repository.binRoot())
    }

    @Test
    fun existingInventoryIsHiddenImmediatelyWithoutErasingEvidenceOrOrdinaryExclusions() = env.test {
        val ordinary = root / "ordinary"
        val neighbor = root / "DeduplicatorGarbageBin-extra"
        for (folder in listOf(bin, ordinary, neighbor)) {
            env.writeFile(folder / "same.jpg", "12")
        }
        with(env) { scanner() }.startScan(root)
        val hidden = assertNotNull(repository.file(bin / "same.jpg"))
        val evidence = ExifRecord(
            ExifInput.from(hidden),
            ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(capturedAt = "2024:01:02 03:04:05")),
            extractedAt = 3,
        )
        assertTrue(repository.saveFileExif(evidence))
        val score = FileScore(FileScoreInput.from(hidden),
            ScoreIdentity("retention", "bin-test", "1", ""), 50.0, emptyList(), 3)
        assertTrue(repository.saveFileScore(score))
        repository.addExclusion(ordinary)
        repository.configureBinRoot(bin, 4)
        assertNull(repository.file(hidden.path))
        assertNull(repository.directory(bin))
        assertEquals(setOf(ordinary / "same.jpg", neighbor / "same.jpg"),
            repository.allFiles().map { it.path }.toSet())
        assertTrue(repository.filesIn(bin).isEmpty())
        assertEquals(setOf(ordinary / "same.jpg", neighbor / "same.jpg"),
            repository.filePathsUnder(root).map { it.toPath() }.toSet())
        assertEquals(2L, repository.summary().fileCount)
        assertEquals(4L, repository.summary().totalBytes)
        assertEquals(3L, repository.summary().directoryCount)
        assertEquals(setOf(ordinary, neighbor), repository.childOverviews(root).map { it.directory.path }.toSet())
        assertFalse(repository.allDirectories().any { it.path.isInTree(bin) })
        assertTrue(repository.duplicateCandidates().isEmpty())
        assertEquals(1L, repository.eligibleFileCountsByExtension()["jpg"])
        assertEquals(listOf(evidence), repository.fileExif(listOf(hidden.path), currentOnly = false))
        assertTrue(repository.fileExif(listOf(hidden.path), currentOnly = true).isEmpty())
        assertTrue(repository.fileScores(listOf(hidden.path), currentOnly = true).isEmpty())
        assertEquals(listOf(score), repository.fileScores(listOf(hidden.path), currentOnly = false))
        assertTrue(repository.saveFileExif(evidence))
        val statements = mutableListOf<String>()
        env.beforeStatement = { statements += it }
        repository.configureBinRoot(root / "new-bin", 5)
        assertFalse(statements.any { it.contains("DELETE", ignoreCase = true) })
        assertTrue(repository.isExcluded(hidden.path))
        assertNull(repository.file(hidden.path))
    }

    @Test
    fun binFileEntriesAreRemovedFromVisibleDirectoryCountsAndBytes() = env.test {
        repository.seedRoot(root)
        repository.recordListing(root, DirectoryListing(emptyList(), listOf(
            DirectoryListing.Entry(bin, 50, null),
            DirectoryListing.Entry(root / "visible.jpg", 2, null),
        )), 1)
        repository.configureBinRoot(bin, 2)
        assertEquals(1, repository.directory(root)?.fileCount)
        assertEquals(1, repository.allDirectories().single().fileCount)
        assertEquals(1, repository.directoriesWithStatus(DirectoryStatus.COMPLETED).single().fileCount)
        val overview = repository.childOverviews(null).single()
        assertEquals(1, overview.directory.fileCount)
        assertEquals(2L, overview.fileBytes)
        assertEquals(2L, repository.summary().totalBytes)
    }

    @Test
    fun literalWildcardUnicodeAndSeparatorBoundariesAreRespected() = env.test {
        val special = root / "垃圾%_"
        val folders = listOf(special, root / "垃圾XY", root / "垃圾%_-extra")
        repository.seedRoot(root)
        repository.recordListing(root, DirectoryListing(
            folders.map { DirectoryListing.Entry(it, 0, null) }, emptyList(),
        ), 1)
        for (folder in folders) {
            repository.recordListing(folder, DirectoryListing(emptyList(),
                listOf(DirectoryListing.Entry(folder / "same.jpg", 2, null))), 1)
        }
        repository.configureBinRoot(special, 2)
        assertEquals(folders.drop(1).map { it / "same.jpg" }.toSet(),
            repository.allFiles().map { it.path }.toSet())
        assertEquals(2, repository.duplicateCandidates(root).single().files.size)
        assertEquals(2L, repository.summary().fileCount)
        assertEquals(folders.drop(1).toSet(), repository.childOverviews(root).map { it.directory.path }.toSet())
        assertFalse(repository.isExcluded(root / "垃圾XY" / "same.jpg"))
    }

    @Test
    fun storedBinImagesAreExcludedFromSuspectedExifSelections() = env.test {
        val visible = root / "visible.jpg"
        val hidden = bin / "renamed.jpg"
        repository.seedRoot(root)
        repository.recordListing(root, DirectoryListing(
            listOf(DirectoryListing.Entry(bin, 0, null)),
            listOf(DirectoryListing.Entry(visible, 2_000_000, null)),
        ), 1)
        repository.recordListing(bin, DirectoryListing(emptyList(),
            listOf(DirectoryListing.Entry(hidden, 2_000_000, null))), 1)
        for (path in listOf(visible, hidden)) {
            repository.saveFileExif(ExifRecord(
                ExifInput.from(assertNotNull(repository.file(path))),
                ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(capturedAt = "2024:01:02 03:04:05")),
                extractedAt = 2,
            ))
        }
        assertEquals(1, repository.suspectedExifCandidates(root).size)
        assertEquals(1, repository.suspectedDuplicates(root).size)
        repository.configureBinRoot(bin, 3)
        assertTrue(repository.suspectedExifCandidates(root).isEmpty())
        assertTrue(repository.suspectedDuplicates(root).isEmpty())
        assertEquals(1, repository.fileExif(listOf(visible, hidden)).size)
        assertEquals(2, repository.fileExif(listOf(visible, hidden), currentOnly = false).size)
    }

    @Test
    fun desktopAndMediaScannersSkipBinsIncludingASelectedRootInsideBin() = env.test {
        repository.configureBinRoot(bin, 1)
        env.writeFile(bin / "hidden" / "same.jpg")
        env.writeFile(root / "DeduplicatorGarbageBin-extra" / "same.jpg")
        with(env) { scanner() }.startScan(root)
        assertFalse(bin in env.fs.listCalls)
        assertNotNull(repository.file(root / "DeduplicatorGarbageBin-extra" / "same.jpg"))
        with(env) { scanner() }.startScan(bin / "hidden")
        assertFalse(bin / "hidden" in env.fs.listCalls)
        var sourceCalls = 0
        val scanner = MediaIndexScanner(MediaSource {
            sourceCalls++
            listOf(MediaItem(bin / "hidden.jpg", 1, 2), MediaItem(root / "visible.jpg", 1, 2))
        }, repository, env.io)
        scanner.startScan(root)
        assertNotNull(repository.file(root / "visible.jpg"))
        assertNull(repository.file(bin / "hidden.jpg"))
        scanner.startScan(bin / "hidden")
        assertEquals(1, sourceCalls)
        assertTrue(repository.allFiles().none { it.path.isInTree(bin) })
    }

    @Test
    fun configuringDuringScanIsRejectedAndCancellationReleasesMutex() = env.test {
        val started = CompletableDeferred<Unit>()
        val scanner = MediaIndexScanner(MediaSource {
            started.complete(Unit)
            awaitCancellation()
        }, repository, env.io)
        val job = launch { scanner.startScan(root) }
        started.await()
        assertFailsWith<IllegalStateException> { repository.configureBinRoot(bin, 1) }
        assertNull(repository.binRoot())
        job.cancelAndJoin()
        repository.configureBinRoot(bin, 2)
        assertEquals(bin, repository.binRoot())
    }
}
