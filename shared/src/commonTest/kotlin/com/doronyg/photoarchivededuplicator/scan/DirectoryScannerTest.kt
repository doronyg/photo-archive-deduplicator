package com.doronyg.photoarchivededuplicator.scan

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// Virtual time (testScheduler.currentTime) is still marked experimental in kotlinx-coroutines-test
@OptIn(ExperimentalCoroutinesApi::class)
class DirectoryScannerTest {
    private val testDispatcher = StandardTestDispatcher()

    // Every SQL statement and file system call below asserts it runs on this dispatcher
    private val io = TrackingIoDispatcher(testDispatcher)

    private val root = "/archive".toPath()
    private val fakeFs = FakeFileSystem().apply { allowSymlinks = true }
    private val fs = guardedFileSystem()
    private val drivers = mutableListOf<SqlDriver>()
    private val repository = newRepository()

    @AfterTest
    fun tearDown() {
        drivers.forEach { it.close() }
        fakeFs.checkNoOpenFiles()
    }

    private fun scanTest(body: suspend TestScope.() -> Unit) = runTest(testDispatcher, testBody = body)

    private fun newRepository() = DirectoryRepository(
        database = createFilesDatabase(IoGuardedDriver(createInMemoryDriver().also { drivers += it }, io)),
        ioDispatcher = io,
    )

    private fun guardedFileSystem() = FlakyFileSystem(fakeFs).apply { onBlockingCall = io::assertOnIo }

    private fun TestScope.scanner(
        fileSystem: FlakyFileSystem = fs,
        repo: DirectoryRepository = repository,
        config: ScanConfig = ScanConfig(),
    ) = DirectoryScanner(
        fileSystem = fileSystem,
        repository = repo,
        config = config,
        ioDispatcher = io,
        clock = { testScheduler.currentTime },
    )

    private fun writeFile(path: Path, content: String = "x") {
        path.parent?.let { fakeFs.createDirectories(it) }
        fakeFs.write(path) { writeUtf8(content) }
    }

    private suspend fun status(path: Path) = repository.directory(path)?.status

    @Test
    fun guardsRejectBlockingCallsOutsideIoDispatcher() = scanTest {
        val unguardedRepositoryDb = createFilesDatabase(IoGuardedDriver(createInMemoryDriver().also { drivers += it }, io))
        assertFailsWith<IllegalStateException> { unguardedRepositoryDb.scanDirectoryQueries.selectAll().awaitAsList() }
        assertFailsWith<IllegalStateException> { fs.list(root) }
    }

    // 1. Root seeding
    @Test
    fun seedingRootInsertsItAsPending() = scanTest {
        repository.seedRoot(root)

        val record = assertNotNull(repository.directory(root))
        assertEquals(DirectoryStatus.PENDING, record.status)
        assertNull(record.parentPath)
    }

    // 2. Directory discovery
    @Test
    fun scanningDirectoryRecordsSubdirectoriesAndFiles() = scanTest {
        val subA = root / "a"
        val subB = root / "b"
        val image = root / "IMG_0001.JPG"
        val imageContent = "12345"
        val text = root / "notes.txt"
        val dotFile = root / ".hidden"
        fakeFs.createDirectories(subA)
        fakeFs.createDirectories(subB)
        writeFile(image, imageContent)
        writeFile(text)
        writeFile(dotFile)

        scanner().startScan(root)

        val rootRecord = assertNotNull(repository.directory(root))
        assertEquals(DirectoryStatus.COMPLETED, rootRecord.status)
        assertEquals(listOf(image, text, dotFile).size, rootRecord.fileCount)
        assertNotNull(rootRecord.lastScanned)
        assertEquals(root, repository.directory(subA)?.parentPath)
        assertEquals(root, repository.directory(subB)?.parentPath)

        val imageRecord = assertNotNull(repository.file(image))
        assertEquals(root, imageRecord.directoryPath)
        assertEquals(image.name, imageRecord.name)
        assertEquals("jpg", imageRecord.extension)
        assertEquals(imageContent.length.toLong(), imageRecord.sizeBytes)
        assertNotNull(imageRecord.modifiedAt)
        assertEquals("txt", repository.file(text)?.extension)
        assertNull(repository.file(dotFile)?.extension)
    }

    // 3. Full run over a nested tree
    @Test
    fun fullRunRecordsWholeTree() = scanTest {
        val files = setOf(root / "2019" / "01" / "a.jpg", root / "2019" / "02" / "b.heic", root / "2020" / "c.png")
        val emptyDir = root / "empty"
        files.forEach { writeFile(it) }
        fakeFs.createDirectories(emptyDir)

        scanner().startScan(root)

        val dirs = repository.allDirectories()
        val expectedDirs = files.flatMap { generateSequence(it.parent) { p -> p.parent.takeIf { p != root } } }
            .toSet() + emptyDir
        assertEquals(expectedDirs, dirs.map { it.path }.toSet())
        assertTrue(dirs.all { it.status == DirectoryStatus.COMPLETED })
        assertEquals(files, repository.allFiles().map { it.path }.toSet())
    }

    // 4. Symlinks and Synology system folders
    @Test
    fun symlinksAndExcludedDirectoriesAreNotRecorded() = scanTest {
        val photos = root / "photos"
        val photo = photos / "a.jpg"
        writeFile(photo)
        for (excluded in ScanConfig.SYNOLOGY_SYSTEM_DIRECTORIES) writeFile(root / excluded / "a.jpg")
        fakeFs.createSymlink(root / "loop", root)
        fakeFs.createSymlink(root / "link.jpg", photo)

        scanner().startScan(root)

        assertEquals(setOf(root, photos), repository.allDirectories().map { it.path }.toSet())
        assertEquals(setOf(photo), repository.allFiles().map { it.path }.toSet())
    }

    // 5. Transient failure is retried
    @Test
    fun transientListingFailureIsRetriedAndCompletes() = scanTest {
        val dir = root / "a"
        val photo = dir / "a.jpg"
        val failures = 1
        writeFile(photo)
        fs.failListing(dir, times = failures)

        scanner().startScan(root)

        val record = assertNotNull(repository.directory(dir))
        assertEquals(DirectoryStatus.COMPLETED, record.status)
        assertEquals(0, record.retryCount)
        assertNotNull(repository.file(photo))
        assertEquals(failures + 1, fs.listCalls.count { it == dir })
    }

    // 6. Persistent failure, exponential backoff, previous data kept
    @Test
    fun persistentFailureMarksFailedAfterMaxAttemptsWithExponentialBackoff() = scanTest {
        val failing = root / "a"
        val failingPhoto = failing / "a.jpg"
        val healthy = root / "b"
        val config = ScanConfig(maxAttempts = 4, initialBackoff = 1.seconds)
        writeFile(failingPhoto)
        writeFile(healthy / "b.jpg")
        scanner().startScan(root)
        val listingsBefore = fs.listCalls.count { it == failing }
        val timeBefore = testScheduler.currentTime

        fs.failListing(failing)
        scanner(config = config).startScan(root)

        val record = assertNotNull(repository.directory(failing))
        assertEquals(DirectoryStatus.FAILED, record.status)
        assertEquals(config.maxAttempts, record.retryCount)
        assertEquals(config.maxAttempts, fs.listCalls.count { it == failing } - listingsBefore)
        // Doubling backoff after each failed attempt except the last
        val expectedBackoff = config.initialBackoff * (1 + 2 + 4)
        assertEquals(expectedBackoff.inWholeMilliseconds, testScheduler.currentTime - timeBefore)
        // Failure must not wipe data that may still exist on the share
        assertNotNull(repository.file(failingPhoto))
        assertEquals(DirectoryStatus.COMPLETED, status(healthy))
    }

    @Test
    fun failedDirectoriesAreNotRetriedOnResume() = scanTest {
        val failing = root / "a"
        fakeFs.createDirectories(failing)
        fs.failListing(failing)
        scanner().startScan(root)
        val callsBefore = fs.listCalls.size

        scanner().resume()

        assertEquals(DirectoryStatus.FAILED, status(failing))
        assertEquals(callsBefore, fs.listCalls.size)
    }

    // 7. Resume skips completed directories
    @Test
    fun resumeSkipsCompletedAndProcessesOnlyPending() = scanTest {
        val done = root / "done"
        val todo = root / "todo"
        writeFile(done / "a.jpg")
        writeFile(todo / "b.jpg")
        scanner().startScan(root)
        // Simulate a partially finished scan: only "todo" still pending
        repository.seedRoot(todo)
        fs.listCalls.clear()

        scanner().resume()

        assertEquals(listOf(todo), fs.listCalls)
        assertEquals(DirectoryStatus.COMPLETED, status(todo))
    }

    // 8. Interrupted directories are reset
    @Test
    fun resumeResetsInterruptedDirectoriesToPending() = scanTest {
        val photo = root / "a" / "a.jpg"
        writeFile(photo)
        repository.seedRoot(root)
        repository.markInProgress(root)
        assertEquals(DirectoryStatus.IN_PROGRESS, status(root))

        repository.resetInterrupted()
        assertEquals(DirectoryStatus.PENDING, status(root))

        repository.markInProgress(root)
        scanner().resume()
        assertEquals(DirectoryStatus.COMPLETED, status(root))
        assertNotNull(repository.file(photo))
    }

    // 9. Pause (cancel) + resume == uninterrupted scan
    @Test
    fun cancelledThenResumedScanMatchesUninterruptedScan() = scanTest {
        val pauseAfterListings = 5
        for (year in 2015..2020) for (month in 1..3) {
            writeFile(root / "$year" / "$month" / "img_$year$month.jpg", "$year$month")
        }

        val reference = newRepository()
        scanner(fileSystem = guardedFileSystem(), repo = reference).startScan(root)

        lateinit var scanJob: Job
        fs.onList = { if (fs.listCalls.size == pauseAfterListings) scanJob.cancel() }
        scanJob = launch { scanner(config = ScanConfig(batchSize = 2)).startScan(root) }
        scanJob.join()
        assertTrue(scanJob.isCancelled)
        assertTrue(repository.allDirectories().any { it.status != DirectoryStatus.COMPLETED })

        fs.onList = {}
        scanner().resume()

        assertEquals(reference.snapshot(), repository.snapshot())
    }

    // 10. Rescan reconciles changes
    @Test
    fun rescanAddsUpdatesAndRemovesChangedEntries() = scanTest {
        val kept = root / "keep.jpg"
        val edited = root / "edit.jpg"
        val editedContent = "edited content"
        val deleted = root / "gone.jpg"
        val added = root / "new.jpg"
        val deletedAlbum = root / "old-album"
        writeFile(kept)
        writeFile(edited, "old")
        writeFile(deleted)
        writeFile(deletedAlbum / "nested" / "deep.jpg")
        writeFile(deletedAlbum / "top.jpg")
        scanner().startScan(root)

        writeFile(added)
        writeFile(edited, editedContent)
        fakeFs.delete(deleted)
        fakeFs.deleteRecursively(deletedAlbum)
        scanner().startScan(root)

        val expectedFiles = setOf(kept, edited, added)
        assertEquals(expectedFiles, repository.allFiles().map { it.path }.toSet())
        assertEquals(editedContent.length.toLong(), repository.file(edited)?.sizeBytes)
        assertEquals(setOf(root), repository.allDirectories().map { it.path }.toSet())
        assertEquals(expectedFiles.size, repository.directory(root)?.fileCount)
    }

    @Test
    fun rescanAfterTransientFailureStillReconciles() = scanTest {
        val dir = root / "a"
        val photo = dir / "a.jpg"
        writeFile(photo)
        scanner().startScan(root)
        fakeFs.delete(photo)
        fs.failListing(dir, times = 1)

        scanner().startScan(root)

        assertEquals(DirectoryStatus.COMPLETED, status(dir))
        assertNull(repository.file(photo))
    }

    private suspend fun DirectoryRepository.snapshot() = Pair(
        allDirectories().map { Triple(it.path, it.status, it.fileCount) }.sortedBy { it.first },
        allFiles().map { listOf(it.path, it.sizeBytes, it.modifiedAt, it.extension) }.sortedBy { it[0].toString() },
    )
}
