package com.doronyg.photoarchivededuplicator.scan

import com.doronyg.photoarchivededuplicator.IoLane
import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import okio.FileMetadata
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BinMarkerExclusionsTest {
    private val env = ScanTestEnvironment()
    private val root = env.root
    private val markerName = ".deduplicator-bin-directory.json"

    @AfterTest
    fun close() = env.close()

    @Test
    fun scanningDiscoversNewBinAndHidesContentWithoutDeletingItOrChangingActiveBin() = env.test {
        val bin = root / "old-bin"
        val kept = root / "kept" / "same.jpg"
        val hidden = bin / "same.jpg"
        env.writeFile(kept)
        env.writeFile(hidden)
        with(env) { scanner().startScan(root) }
        val groups = env.repository.duplicateCandidates()
        assertEquals(1, groups.size)
        val scoring = RetentionScoreService(env.repository, dispatcher = env.io)
        scoring.assess(groups)
        val statements = mutableListOf<String>()
        env.beforeStatement = { statements += it }
        env.writeFile(bin / markerName, "malformed")
        assertEquals(1, env.repository.duplicateCandidates().size, "External markers take effect at the next scan")
        with(env) { scanner().startScan(root) }
        assertTrue(env.repository.duplicateCandidates().isEmpty())
        assertNull(env.repository.file(hidden))
        assertEquals(listOf(kept), env.repository.allFiles().map { it.path })
        assertEquals(1L, env.repository.summary().fileCount)
        assertTrue(env.repository.exclusions().single().isSystem)
        assertNull(env.repository.binRoot())
        assertFalse(hidden in scoring.assess(groups, force = true).files)
        assertFalse(hidden in scoring.stored(groups).files)
        assertEquals(listOf(bin), env.repository.registeredBinRoots())
        with(env) { scanner().startScan(root) }
        assertTrue(statements.none { it.contains("DELETE FROM scanFile", ignoreCase = true) ||
            it.contains("DELETE FROM scanDirectory", ignoreCase = true) })
        env.fakeFs.delete(bin / markerName)
        assertNull(env.newRepository().file(hidden), "Discovered exclusions survive marker removal and repository recreation")
    }

    @Test
    fun scanInsideMarkedAncestorNeverListsOrInventoriesBin() = env.test {
        val nested = root / "bin" / "nested"
        env.writeFile(root / "bin" / markerName)
        env.writeFile(nested / "photo.jpg")
        with(env) { scanner().startScan(nested) }
        assertTrue(env.fs.listCalls.isEmpty())
        assertTrue(env.repository.allFiles().isEmpty())
        assertTrue(env.repository.allDirectories().isEmpty())
    }

    @Test
    fun symlinkMarkerIsExcludedWithoutOpeningOrFollowingItsTarget() = env.test {
        val bin = root / "bin"
        env.fakeFs.createDirectories(bin)
        env.fakeFs.createSymlink(bin / markerName, root / "missing-target")
        env.writeFile(bin / "photo.jpg")
        env.writeFile(root / "$markerName.staging", "partial")
        with(env) { scanner().startScan(root) }
        assertTrue(env.repository.allFiles().isEmpty())
        assertFalse(bin in env.fs.listCalls)
    }

    @Test
    fun reservedStagingDirectoryCannotBeScannedAsAnIndependentRoot() = env.test {
        val staging = root / "$markerName.staging"
        env.writeFile(staging / "photo.jpg")
        env.repository.beginScanSession(staging)
        with(env) { scanner().startScan(staging) }
        assertTrue(env.repository.allFiles().isEmpty())
        assertTrue(env.repository.allDirectories().isEmpty())
    }

    @Test
    fun markerObservationsAreSharedWithinPassButNotNegativelyCachedAcrossPasses() = env.test {
        val observed = mutableListOf<Path>()
        val counting = object : ForwardingFileSystem(env.fs) {
            override fun metadataOrNull(path: Path): FileMetadata? {
                observed += path
                return super.metadataOrNull(path)
            }
        }
        val helper = BinMarkerExclusions(counting, IoLane("marker-test", env.io))
        val directory = root / "bin" / "nested"
        env.fakeFs.createDirectories(directory)
        assertTrue(helper.discover(listOf(directory, directory)).isEmpty())
        assertEquals(observed.distinct(), observed, "Each ancestor is observed once per pass")
        env.writeFile(root / "bin" / markerName)
        assertEquals(setOf(root / "bin"), helper.discover(listOf(directory)))
    }

    @Test
    fun suspectedCandidatesAndStaleSuspectedScoresExcludeNewMarkers() = env.test {
        val bin = root / "bin"
        env.writeFile(root / "keep" / "original.jpg", "x".repeat(100_001))
        env.writeFile(bin / "renamed.jpg", "x".repeat(100_001))
        with(env) { scanner().startScan(root) }
        for (file in env.repository.allFiles()) {
            assertTrue(env.repository.saveFileExif(ExifRecord(
                ExifInput.from(file),
                ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(capturedAt = "2024:02:29 12:34:56")), 1,
            )))
        }
        assertEquals(1, env.repository.suspectedExifCandidates().size)
        val groups = env.repository.suspectedDuplicates()
        assertEquals(1, groups.size)
        val scoring = RetentionScoreService(env.repository, dispatcher = env.io)
        scoring.assessSuspected(groups)
        env.writeFile(bin / markerName, "{}")
        with(env) { scanner().startScan(root) }
        assertTrue(env.repository.suspectedExifCandidates().isEmpty())
        assertTrue(env.repository.suspectedDuplicates().isEmpty())
        val hidden = bin / "renamed.jpg"
        assertFalse(hidden in scoring.assessSuspected(groups, force = true).files)
        assertFalse(hidden in scoring.storedSuspected(groups).files)
    }

    @Test
    fun mediaIndexProbesMarkersAbsentFromIndexAndTheirAncestors() = env.test {
        val bin = root / "bin"
        env.writeFile(bin / markerName)
        val source = MediaSource { listOf(
            MediaItem(bin / "nested" / "photo.jpg", 1, null),
            MediaItem(root / "kept.jpg", 1, null),
            MediaItem(root / "$markerName.staging", 1, null),
        ) }
        val scanner = MediaIndexScanner(source, env.repository, env.io)
        scanner.startScan(root)
        assertEquals(listOf(root / "kept.jpg"), env.repository.allFiles().map { it.path })
        scanner.startScan(bin / "nested")
        assertEquals(listOf(root), env.repository.allDirectories().map { it.path })
    }

    @Test
    fun markerMetadataErrorsBlockDiscoveryButDoNotBlockStoredInventoryReads() = env.test {
        env.writeFile(root / "photo.jpg")
        with(env) { scanner().startScan(root) }
        val failing = object : ForwardingFileSystem(env.fs) {
            override fun metadataOrNull(path: Path): FileMetadata? {
                if (path.name == markerName) throw IOException("Marker metadata is inaccessible")
                return super.metadataOrNull(path)
            }
        }
        val helper = BinMarkerExclusions(failing, IoLane("marker-test", env.io))
        val inaccessible = env.newRepository(helper)
        assertEquals(1L, inaccessible.summary().fileCount)
        assertTrue(inaccessible.duplicateCandidates().isEmpty())
        assertFailsWith<IOException> { inaccessible.discoverBinMarkers(listOf(root)) }
    }

    @Test
    fun inventoryDuplicateAndScoringReadsNeverProbeTheFilesystem() = env.test {
        val kept = root / "kept" / "photo.jpg"
        env.writeFile(kept)
        repeat(20) { env.writeFile(root / "unrelated-$it" / "other.jpg") }
        with(env) { scanner().startScan(root) }
        val observed = mutableListOf<Path>()
        val counting = object : ForwardingFileSystem(env.fs) {
            override fun metadataOrNull(path: Path): FileMetadata? {
                observed += path
                return super.metadataOrNull(path)
            }
        }
        val repository = env.newRepository(BinMarkerExclusions(counting, IoLane("scoped-markers", env.io)))
        repository.summary()
        repository.childOverviews(root)
        repository.allDirectories()
        repository.allFiles()
        val groups = repository.duplicateCandidates()
        repository.suspectedExifCandidates()
        repository.suspectedDuplicates()
        repository.eligibleFileCountsByExtension()
        assertEquals(kept, repository.file(kept)?.path)
        assertEquals(listOf(kept), repository.filesIn(kept.parent!!).map { it.path })
        assertEquals(kept.parent, repository.directory(kept.parent!!)?.path)
        val scoring = RetentionScoreService(repository, dispatcher = env.io)
        scoring.assess(groups)
        scoring.stored(groups)
        assertTrue(observed.isEmpty(), "Reading stored inventory and scores must not discover new Bin markers")
    }

    @Test
    fun reservedMarkerMetadataIsExcludedThroughAncestorsAndCaseVariants() {
        assertTrue((root / "${markerName.uppercase()}.staging/nested/photo.jpg").isBinMarkerMetadata())
        assertTrue((root / markerName.uppercase()).isBinMarkerMetadata())
        assertFalse((root / "ordinary/photo.jpg").isBinMarkerMetadata())
    }

    @Test
    fun nestedDirectoryMarkersRegisterOnlyTheOutermostBinExclusion() = env.test {
        val bin = root / "bin"
        val first = bin / "first"
        val second = bin / "second"
        for (directory in listOf(bin, first, second)) env.writeFile(directory / markerName, "{}")
        val helper = BinMarkerExclusions(env.fs, IoLane("minimal-markers", env.io))
        assertEquals(setOf(bin), helper.discover(listOf(first, second)))
        env.repository.discoverBinMarkers(listOf(first, second))
        assertEquals(listOf(bin), env.repository.registeredBinRoots())
        env.repository.discoverBinMarkers(listOf(first, second))
        assertEquals(listOf(bin), env.repository.registeredBinRoots())
    }

    @Test
    fun markedAncestorStopsAllMarkerProbesBelowTheBinRoot() = env.test {
        val bin = root / "bin"
        env.writeFile(bin / markerName, "{}")
        val observed = mutableListOf<Path>()
        val counting = object : ForwardingFileSystem(env.fs) {
            override fun metadataOrNull(path: Path): FileMetadata? {
                observed += path
                return super.metadataOrNull(path)
            }
        }
        val helper = BinMarkerExclusions(counting, IoLane("shallow-markers", env.io))
        val pass = helper.newPass()
        assertEquals(setOf(bin), pass.discover(listOf(bin / "first/nested", bin / "second/nested")))
        assertTrue(observed.none { it.isInTree(bin / "first") || it.isInTree(bin / "second") },
            "A Bin marker excludes all descendants without inspecting them")
    }

    @Test
    fun registeredBinTreesAreNotListedOrProbedOnSubsequentScans() = env.test {
        val bin = root / "bin"
        env.writeFile(bin / markerName, "{}")
        env.writeFile(bin / "deep/nested/photo.jpg")
        with(env) { scanner().startScan(root) }
        val metadata = mutableListOf<Path>()
        val counting = object : ForwardingFileSystem(env.fs) {
            override fun metadataOrNull(path: Path): FileMetadata? {
                metadata += path
                return super.metadataOrNull(path)
            }
        }
        val scanner = DirectoryScanner(counting, env.repository, ioDispatcher = env.io)
        scanner.startScan(bin / "deep") {}
        assertTrue(metadata.none { it.isInTree(bin) })
        assertTrue(env.fs.listCalls.none { it.isInTree(bin) })
    }
}
