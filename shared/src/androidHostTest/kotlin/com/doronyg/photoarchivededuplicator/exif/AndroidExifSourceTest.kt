package com.doronyg.photoarchivededuplicator.exif

import com.doronyg.photoarchivededuplicator.IoLane
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.scan.TrackingIoDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.ForwardingSource
import okio.IOException
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AndroidExifSourceTest {
    private val file = FileRecord("/storage/ABCD/photos/a.jpg".toPath(),
        "/storage/ABCD/photos".toPath(), "a.jpg", "jpg", 4, 12_000, 1)
    private val target = MediaExifTarget("content://media/ABCD/images/media/42", 4, 12_000)

    @Test
    fun looksUpParsesClosesAndRechecksOnLaneUsingVolumeSpecificUri() = runTest {
        val io = TrackingIoDispatcher(StandardTestDispatcher(testScheduler))
        val events = mutableListOf<String>()
        val source = AndroidExifSource(
            lookup = {
                io.assertOnIo("lookup")
                assertEquals(file.path, it)
                events += "lookup"
                target
            },
            open = {
                io.assertOnIo("open")
                assertEquals(target.contentUri, it)
                events += "open"
                object : ForwardingSource(Buffer().writeUtf8("jpeg")) {
                    override fun close() {
                        io.assertOnIo("close")
                        events += "close"
                        super.close()
                    }
                }
            },
            lane = IoLane("exif", io),
            readMetadata = { input, size, context ->
                io.assertOnIo("parse")
                context.ensureActive()
                assertEquals(4L, size)
                assertEquals("jpeg", input.readUtf8())
                events += "parse"
                ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(make = "Camera"))
            },
        )
        assertEquals(ExifStatus.EXTRACTED, source.read(file).status)
        assertEquals(listOf("lookup", "open", "parse", "close", "lookup"), events)
    }

    @Test
    fun rejectsMissingOrChangedInventoryWithoutOpening() = runTest {
        for (current in listOf(null, target.copy(sizeBytes = 5), target.copy(modifiedAt = 13_000))) {
            val source = AndroidExifSource(
                lookup = { current },
                open = { error("Stale input must not be opened") },
                lane = IoLane("exif", StandardTestDispatcher(testScheduler)),
            )
            val result = source.read(file)
            assertEquals(ExifStatus.STALE, result.status)
            assertTrue(result.error.orEmpty().isNotBlank())
        }
    }

    @Test
    fun rejectsDisappearanceOrReplacementDuringParsing() = runTest {
        for (after in listOf(null, target.copy(contentUri = "content://media/ABCD/images/media/99"),
            target.copy(sizeBytes = 5), target.copy(modifiedAt = null))) {
            var lookups = 0
            val source = AndroidExifSource(
                lookup = { if (++lookups == 1) target else after },
                open = { Buffer().writeUtf8("jpeg") },
                lane = IoLane("exif", StandardTestDispatcher(testScheduler)),
                readMetadata = { _, _, _ -> ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(make = "Camera")) },
            )
            val result = source.read(file)
            assertEquals(ExifStatus.STALE, result.status)
            assertEquals(ExifMetadata(), result.metadata)
        }
    }

    @Test
    fun errorsAndPermissionFailuresAreReportedWithoutRawPathFallback() = runTest {
        val lane = IoLane("exif", StandardTestDispatcher(testScheduler))
        for (failure in listOf(IOException("offline"), SecurityException("permission denied"))) {
            val source = AndroidExifSource(lookup = { target }, open = { throw failure }, lane = lane)
            val result = source.read(file)
            assertEquals(ExifStatus.ERROR, result.status)
            assertEquals(failure.message, result.error)
        }
        assertEquals(ExifStatus.ERROR,
            AndroidExifSource(lookup = { target }, open = { null }, lane = lane).read(file).status)
        var opened = false
        val invalid = AndroidExifSource(
            lookup = { target.copy(contentUri = "file:///storage/a.jpg") },
            open = { opened = true; Buffer() }, lane = lane,
        )
        assertEquals(ExifStatus.ERROR, invalid.read(file).status)
        assertFalse(opened)
    }

    @Test
    fun parserReadFailureClosesTheStreamAndUnknownInventoryDateIsAllowed() = runTest {
        var closed = false
        val source = AndroidExifSource(
            lookup = { target.copy(modifiedAt = null) },
            open = {
                object : ForwardingSource(Buffer().writeUtf8("jpeg")) {
                    override fun close() { closed = true; super.close() }
                }
            },
            lane = IoLane("exif", StandardTestDispatcher(testScheduler)),
            readMetadata = { _, _, _ -> throw IOException("read failed") },
        )
        val result = source.read(file.copy(modifiedAt = null))
        assertEquals(ExifReadResult(ExifStatus.ERROR, error = "read failed"), result)
        assertTrue(closed)
    }

    @Test
    fun cancellationInsideParserClosesStreamAndReleasesLane() = runTest {
        val lane = IoLane("exif", StandardTestDispatcher(testScheduler))
        var closed = false
        lateinit var job: kotlinx.coroutines.Deferred<ExifReadResult>
        val source = AndroidExifSource(
            lookup = { target },
            open = {
                object : ForwardingSource(Buffer().writeUtf8("jpeg")) {
                    override fun close() { closed = true; super.close() }
                }
            },
            lane = lane,
            readMetadata = { _, _, context ->
                job.cancel()
                context.ensureActive()
                error("Cancellation must escape")
            },
        )
        job = async { source.read(file) }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(closed)
        assertEquals(42, lane.run("released") { 42 })
    }

    @Test
    fun cancellationWhileWaitingForLaneDoesNotQueryOrOpen() = runTest(timeout = 10.seconds) {
        val lane = IoLane("exif", StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = launch {
            lane.run("owner") { entered.complete(Unit); release.await() }
        }
        try {
            entered.await()
            val source = AndroidExifSource(
                lookup = { error("Cancelled waiter must not query") },
                open = { error("Cancelled waiter must not open") }, lane = lane,
            )
            val queued = launch(start = CoroutineStart.UNDISPATCHED) { source.read(file) }
            queued.cancelAndJoin()
            release.complete(Unit)
            owner.join()
        } finally {
            release.complete(Unit)
            owner.cancelAndJoin()
        }
    }
}
