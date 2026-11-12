package com.doronyg.photoarchivededuplicator.scan

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

class ExclusionsAndDuplicatesTest {
    private val env = ScanTestEnvironment()
    private val repository = env.repository
    private val root = env.root

    @AfterTest
    fun close() = env.close()

    @Test
    fun unknownExclusionsArePersistentNormalizedAndBoundarySafe() = env.test {
        repository.addExclusion(root / "private" / ".." / "private", "User reason")
        repository.addExclusion(root / "a%_")
        assertEquals("User reason", repository.exclusions().first { it.path == root / "private" }.reason)
        assertTrue(repository.isExcluded(root / "private" / "deep" / "file"))
        assertFalse(repository.isExcluded(root / "private-other" / "file"))
        assertFalse(repository.isExcluded(root / "abc" / "file"))
        assertTrue(repository.isExcluded(root / "a%_" / "file"))
        assertTrue(repository.allDirectories().isEmpty())
        repository.removeExclusion(root / "private")
        assertFalse(repository.isExcluded(root / "private"))
    }

    @Test
    fun scannerSkipsMetadataAndResumeForExcludedTrees() = env.test {
        val hidden = root / "hidden"
        env.writeFile(hidden / "file.jpg")
        env.writeFile(root / "hidden-other" / "file.jpg")
        repository.seedRoot(hidden)
        repository.addExclusion(hidden)
        val metadataPaths = mutableListOf<String>()
        env.fs.onBlockingCall = {
            env.io.assertOnIo(it)
            metadataPaths += it
        }
        with(env) { scanner() }.startScan(root)
        with(env) { scanner() }.resume()
        assertFalse(hidden in env.fs.listCalls)
        assertFalse("metadataOrNull($hidden)" in metadataPaths)
        assertNotNull(repository.file(root / "hidden-other" / "file.jpg"))
        assertNull(repository.file(hidden / "file.jpg"))
    }

    @Test
    fun reconciliationPreservesExcludedDescendantsEvenWhenAncestorDisappears() = env.test {
        val protected = root / "old" / "protected"
        env.writeFile(protected / "keep.jpg")
        env.writeFile(root / "old" / "gone.jpg")
        with(env) { scanner() }.startScan(root)
        val saved = repository.file(protected / "keep.jpg")
        repository.addExclusion(protected)
        env.fakeFs.deleteRecursively(root / "old")
        with(env) { scanner() }.startScan(root)
        assertEquals(saved, repository.file(protected / "keep.jpg"))
        assertNotNull(repository.directory(root / "old"))
        assertEquals(0, repository.directory(root / "old")?.fileCount)
        assertNull(repository.file(root / "old" / "gone.jpg"))
        repository.removeExclusion(protected)
        with(env) { scanner() }.startScan(root)
        assertNull(repository.file(protected / "keep.jpg"))
    }

    @Test
    fun candidatesUseExactCaseSensitiveNameAndSizeWithinRootAndIgnoreExclusions() = env.test {
        for (folder in listOf("a", "b", "protected")) env.writeFile(root / folder / "same.jpg", "12")
        env.writeFile(root / "c" / "same.jpg", "123")
        env.writeFile(root / "d" / "Same.jpg", "12")
        env.writeFile("/archive-other/same.jpg".toPath(), "12")
        with(env) { scanner() }.startScan(root)
        with(env) { scanner() }.startScan("/archive-other".toPath())
        repository.addExclusion(root / "protected")
        val group = repository.duplicateCandidates(root).single()
        assertEquals("same.jpg", group.name)
        assertEquals(2L, group.sizeBytes)
        assertEquals(listOf(root / "a" / "same.jpg", root / "b" / "same.jpg"), group.files.map { it.path })
        assertTrue(repository.duplicateCandidates(root / "a").isEmpty())
        repository.addExclusion(root / "a")
        assertTrue(repository.duplicateCandidates(root).isEmpty())
    }

    @Test
    fun creationMetadataIsOptionalAndUpdated() = env.test {
        repository.seedRoot(root)
        val path = root / "photo.jpg"
        suspend fun record(created: Long?) = repository.recordListing(
            root, DirectoryListing(emptyList(), listOf(DirectoryListing.Entry(path, 3, 20, created))), 30,
        )
        record(10)
        assertEquals(10L, repository.file(path)?.createdAt)
        record(null)
        assertNull(repository.file(path)?.createdAt)
        assertEquals(20L, repository.file(path)?.modifiedAt)
    }

    @Test
    fun mediaIndexIgnoresExcludedItemsAndKeepsUnknownBirthTime() = env.test {
        val hidden = root / "private"
        val source = MediaSource {
            listOf(MediaItem(hidden / "photo.jpg", 1, 2), MediaItem(root / "photo.jpg", 1, 2))
        }
        repository.addExclusion(hidden)
        MediaIndexScanner(source, repository, env.io).startScan(root)
        assertNull(repository.directory(hidden))
        assertNull(repository.file(root / "photo.jpg")?.createdAt)
        assertNotNull(repository.file(root / "photo.jpg"))
    }

    @Test
    fun excludedInterruptedDirectoryIsNotAlteredByResume() = env.test {
        val hidden = root / "hidden"
        repository.seedRoot(hidden)
        repository.markInProgress(hidden)
        val saved = repository.directory(hidden)
        repository.addExclusion(hidden)
        with(env) { scanner() }.resume()
        assertEquals(saved, repository.directory(hidden))
        assertEquals(0L, repository.pendingScanCount())
    }

    @Test
    fun scanLockRejectsEditsAndCancellationReleasesIt() = env.test {
        val started = CompletableDeferred<Unit>()
        val scanner = MediaIndexScanner(MediaSource {
            started.complete(Unit)
            awaitCancellation()
        }, repository, env.io)
        val job = launch { scanner.startScan(root) }
        started.await()
        assertFailsWith<IllegalStateException> { repository.addExclusion(root / "private") }
        job.cancelAndJoin()
        repository.addExclusion(root / "private")
        assertTrue(repository.isExcluded(root / "private"))
    }

    @Test
    fun sqlBoundariesHandleWildcardsRootAndWindowsSeparatorsLiterally() = env.test {
        val special = "/a%_".toPath()
        suspend fun record(folder: okio.Path) {
            repository.seedRoot(folder)
            repository.recordListing(folder, DirectoryListing(
                emptyList(), listOf(DirectoryListing.Entry(folder / "same.jpg", 0, null)),
            ), 1)
        }
        for (folder in listOf(special / "a", special / "b", "/abc/c".toPath())) record(folder)
        assertEquals(2, repository.duplicateCandidates(special).single().files.size)
        assertEquals(3, repository.duplicateCandidates("/".toPath()).single().files.size)
        repository.addExclusion(special)
        assertTrue(repository.duplicateCandidates("/".toPath()).isEmpty())
        val windows = "C:\\archive".toPath()
        for (folder in listOf(windows / "a", windows / "b", "C:\\archive-other\\c".toPath())) record(folder)
        assertEquals(2, repository.duplicateCandidates(windows).single().files.size)
        repository.addExclusion(windows / "a")
        assertTrue(repository.duplicateCandidates(windows).isEmpty())
    }

    @Test
    fun scannerReadsOkioCreationTimeAndExcludedInventorySurvivesNormalRescan() = env.test {
        val hidden = root / "hidden"
        val file = hidden / "photo.jpg"
        env.writeFile(file)
        with(env) { scanner() }.startScan(root)
        val saved = repository.file(file)
        assertNotNull(saved)
        assertEquals(env.fakeFs.metadata(file).createdAtMillis, saved.createdAt)
        repository.addExclusion(hidden)
        env.writeFile(file, "changed")
        with(env) { scanner() }.startScan(root)
        assertEquals(saved, repository.file(file))
        repository.removeExclusion(hidden)
        with(env) { scanner() }.startScan(root)
        assertEquals(7L, repository.file(file)?.sizeBytes)
    }
}
