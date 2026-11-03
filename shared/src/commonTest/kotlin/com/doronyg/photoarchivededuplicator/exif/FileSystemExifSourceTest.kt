package com.doronyg.photoarchivededuplicator.exif

import com.doronyg.photoarchivededuplicator.IoLane
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.scan.TrackingIoDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.FileMetadata
import okio.FileNotFoundException
import okio.ForwardingFileSystem
import okio.ForwardingSource
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FileSystemExifSourceTest {
    @Test
    fun readsOnLaneClosesResourcesAndPreservesSource() = runTest {
        val fs = FakeFileSystem()
        val file = fixture(fs)
        val original = fs.read(file.path) { readByteString() }
        val io = TrackingIoDispatcher(StandardTestDispatcher(testScheduler))
        var stats = 0
        var closed = false
        val guarded = object : ForwardingFileSystem(fs) {
            override fun metadataOrNull(path: Path): FileMetadata? {
                io.assertOnIo("stat")
                stats++
                return super.metadataOrNull(path)
            }
            override fun source(file: Path): Source {
                io.assertOnIo("open")
                return object : ForwardingSource(super.source(file)) {
                    override fun close() {
                        io.assertOnIo("close")
                        closed = true
                        super.close()
                    }
                }
            }
        }
        val source = FileSystemExifSource(guarded, IoLane("exif", io)) { input, size, context ->
            io.assertOnIo("parse")
            KimExifReader().read(input, size, context)
        }
        val result = source.read(file)
        assertEquals(ExifStatus.EXTRACTED, result.status)
        assertEquals("Example", result.metadata.make)
        assertEquals(2, stats)
        assertTrue(closed)
        assertEquals(original, fs.read(file.path) { readByteString() })
        fs.checkNoOpenFiles()
    }

    @Test
    fun rejectsMissingDirectoryAndChangedInventoryBeforeOpening() = runTest {
        val fs = FakeFileSystem()
        val file = fixture(fs)
        val source = FileSystemExifSource(fs, IoLane("exif", StandardTestDispatcher(testScheduler))) { _, _, _ ->
            error("Stale inputs must not be parsed")
        }
        val stale = listOf(
            file.copy(path = "/missing.jpg".toPath()),
            file.copy(path = file.directoryPath),
            file.copy(sizeBytes = file.sizeBytes + 1),
            file.copy(modifiedAt = file.modifiedAt!! + 1),
            file.copy(createdAt = file.createdAt!! + 1),
        )
        stale.forEach {
            val result = source.read(it)
            assertEquals(ExifStatus.STALE, result.status)
            assertTrue(result.error.orEmpty().isNotBlank())
        }
        fs.checkNoOpenFiles()
    }

    @Test
    fun discardsParsedMetadataIfFingerprintChangesOrFileDisappears() = runTest {
        for (remove in listOf(false, true)) {
            val fs = FakeFileSystem()
            val file = fixture(fs)
            val changing = object : ForwardingFileSystem(fs) {
                var stats = 0
                override fun metadataOrNull(path: Path): FileMetadata? {
                    if (++stats == 2) {
                        if (remove) return null
                        return super.metadataOrNull(path)!!.copy(lastModifiedAtMillis = file.modifiedAt!! + 1)
                    }
                    return super.metadataOrNull(path)
                }
            }
            val result = FileSystemExifSource(changing, IoLane("exif", StandardTestDispatcher(testScheduler)))
                .read(file)
            assertEquals(ExifStatus.STALE, result.status)
            assertEquals(ExifMetadata(), result.metadata)
            fs.checkNoOpenFiles()
        }
    }

    @Test
    fun ioFailuresAreReportedAndCancellationDuringParsingStillClosesSource() = runTest {
        val fs = FakeFileSystem()
        val file = fixture(fs)
        val lane = IoLane("exif", StandardTestDispatcher(testScheduler))
        val broken = object : ForwardingFileSystem(fs) {
            override fun source(file: Path): Source = throw IOException("offline")
        }
        assertEquals(ExifStatus.ERROR, FileSystemExifSource(broken, lane).read(file).status)
        lateinit var job: kotlinx.coroutines.Deferred<ExifReadResult>
        val source = FileSystemExifSource(fs, lane) { _, _, context ->
            job.cancel()
            context.ensureActive()
            error("Cancellation must escape")
        }
        job = async { source.read(file) }
        job.join()
        assertTrue(job.isCancelled)
        fs.checkNoOpenFiles()
        assertEquals(42, lane.run("released") { 42 })
    }

    @Test
    fun accessDenialIsAFileErrorRatherThanAbortingTheGroupOrClaimingDisappearance() = runTest {
        for (failure in listOf(SecurityException("Access denied"), FileNotFoundException("Permission denied"))) {
            val fs = FakeFileSystem()
            val file = fixture(fs)
            val denied = object : ForwardingFileSystem(fs) {
                override fun source(file: Path): Source = throw failure
            }
            val source = FileSystemExifSource(denied, IoLane("exif", StandardTestDispatcher(testScheduler)))
            val result = source.read(file)
            assertEquals(ExifStatus.ERROR, result.status)
            assertEquals(failure.message, result.error)
            fs.checkNoOpenFiles()
        }
    }

    @Test
    fun parserStatusesAndReadFailuresCloseWithoutChangingTheFile() = runTest {
        val fs = FakeFileSystem()
        val file = fixture(fs)
        val original = fs.read(file.path) { readByteString() }
        val lane = IoLane("exif", StandardTestDispatcher(testScheduler))
        for (status in listOf(ExifStatus.NO_EXIF, ExifStatus.UNSUPPORTED, ExifStatus.ERROR, ExifStatus.LIMIT_EXCEEDED)) {
            val expected = ExifReadResult(status, error = "reader detail")
            val source = FileSystemExifSource(fs, lane) { _, _, _ -> expected }
            assertEquals(expected, source.read(file))
            fs.checkNoOpenFiles()
        }
        val failing = FileSystemExifSource(fs, lane) { _, _, _ -> throw IOException("read failed") }
        assertEquals(ExifReadResult(ExifStatus.ERROR, error = "read failed"), failing.read(file))
        fs.checkNoOpenFiles()
        assertEquals(original, fs.read(file.path) { readByteString() })
    }

    @Test
    fun cancellationQueuedBehindLaneNeverOpensFile() = runTest(timeout = 10.seconds) {
        val lane = IoLane("exif", StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = launch {
            lane.run("owner") { entered.complete(Unit); release.await() }
        }
        try {
            entered.await()
            val fs = FakeFileSystem()
            val file = fixture(fs)
            var touched = false
            val guarded = object : ForwardingFileSystem(fs) {
                override fun metadataOrNull(path: Path): FileMetadata? {
                    touched = true
                    return super.metadataOrNull(path)
                }
            }
            val queued = launch(start = CoroutineStart.UNDISPATCHED) {
                FileSystemExifSource(guarded, lane).read(file)
            }
            queued.cancelAndJoin()
            release.complete(Unit)
            owner.join()
            assertFalse(touched)
            currentCoroutineContext().ensureActive()
            fs.checkNoOpenFiles()
        } finally {
            release.complete(Unit)
            owner.cancelAndJoin()
        }
    }

    private fun fixture(fs: FakeFileSystem): FileRecord {
        val path = "/photos/a.tiff".toPath()
        fs.createDirectories(path.parent!!)
        fs.write(path) { write(exifTiff()) }
        val metadata = fs.metadata(path)
        return FileRecord(path, path.parent!!, path.name, "tiff", metadata.size!!,
            metadata.lastModifiedAtMillis, 1, metadata.createdAtMillis)
    }
}
