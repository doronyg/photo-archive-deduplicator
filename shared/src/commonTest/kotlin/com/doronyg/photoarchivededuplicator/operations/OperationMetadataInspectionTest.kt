package com.doronyg.photoarchivededuplicator.operations

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource

class OperationMetadataInspectionTest {
    @Test
    fun cooperativeCancellationStopsPreparationWithoutCancellingTheCaller() = runTest {
        val fs = FakeFileSystem()
        val control = FileExecutionControl()
        var reads = 0
        val guarded = object : ForwardingFileSystem(fs) {
            override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path {
                if (++reads == 2) control.requestCancellation()
                return path
            }
        }
        assertFailsWith<CancellationException> {
            OperationMetadataInspection(guarded, checkCancellation = control::checkCancellation)
                .prepare(List(100) { "/photo-$it.jpg".toPath() })
        }
        assertEquals(2, reads)
        assertTrue(coroutineContext.isActive)
        fs.checkNoOpenFiles()
    }

    @Test
    fun cancellationStopsThePlannedPassBetweenMetadataCalls() = runTest {
        val fs = FakeFileSystem()
        var reads = 0
        lateinit var job: Job
        val guarded = object : ForwardingFileSystem(fs) {
            override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path {
                if (++reads == 2) job.cancel()
                return path
            }
        }
        job = launch { OperationMetadataInspection(guarded).prepare(List(100) { "/photo-$it.jpg".toPath() }) }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(2, reads)
    }

    @Test
    fun plannedDepthFirstPassReadsSharedAncestorsOnceAndPrunesMissingDestinations() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectories("/archive/shared".toPath())
        val files = List(20_000) { "/archive/shared/file-$it.jpg".toPath() }
        files.forEach { fs.write(it) { writeUtf8("photo") } }
        val bin = "/not/created/bin".toPath()
        val destinations = files.map { bin / it.relativeTo("/".toPath()) }
        val reads = mutableListOf<Path>()
        val guarded = object : ForwardingFileSystem(fs) {
            override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path {
                assertEquals("metadataOrNull", functionName, "Do not list directories or open photo contents")
                reads += path
                return path
            }
        }
        val progress = mutableListOf<OperationMetadataProgress>()
        val inspection = OperationMetadataInspection(guarded, onProgress = { progress += it })
        inspection.prepare(files + destinations + bin)
        val readCount = reads.size
        val findings = mutableListOf<FileOperationFinding>()
        files.forEach { inspection.inspect(it, findings); inspection.checkAncestors(it, findings) }
        destinations.forEach {
            assertNull(inspection.inspect(it, findings))
            inspection.checkAncestors(it, findings)
        }
        assertTrue(findings.isEmpty())
        assertEquals(readCount, reads.size, "Validation after planning must use cached observations and ancestry")
        assertEquals(reads.distinct().size, reads.size)
        assertEquals(files.size + 4, reads.size, "Only root, archive directories, files and the first missing ancestor need IO")
        assertTrue(reads.none { it != "/not".toPath() && it.toString().startsWith("/not/") })
        assertTrue(reads.indexOf("/archive".toPath()) < reads.indexOf("/archive/shared".toPath()))
        assertTrue(reads.indexOf("/archive/shared".toPath()) < reads.indexOf(files.first()))
        assertEquals(0, progress.first().completed)
        assertTrue(progress.last().finished)
        assertEquals(progress.last().total, progress.last().completed)
        assertEquals(reads.size, progress.last().metadataReads)
        fs.checkNoOpenFiles()
    }

    @Test
    fun unsafeAndUnreadableParentsBlockDescendantsWithoutFollowingThem() = runTest {
        val fs = FakeFileSystem()
        fs.allowSymlinks = true
        fs.createDirectory("/outside".toPath())
        fs.createSymlink("/linked".toPath(), "/outside".toPath())
        fs.write("/file".toPath()) { writeUtf8("not a directory") }
        fs.createDirectory("/denied".toPath())
        val reads = mutableListOf<Path>()
        val guarded = object : ForwardingFileSystem(fs) {
            override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path {
                reads += path
                if (path == "/denied".toPath()) throw IOException("permission denied")
                return path
            }
        }
        val inspection = OperationMetadataInspection(guarded)
        val paths = listOf("/linked/photo.jpg", "/file/photo.jpg", "/denied/photo.jpg").map { it.toPath() }
        inspection.prepare(paths)
        assertTrue(reads.none { it in paths })
        paths.forEach { path ->
            val findings = mutableListOf<FileOperationFinding>()
            assertNull(inspection.inspect(path, findings))
            inspection.checkAncestors(path, findings)
            assertTrue(findings.any { it.path == path.parent &&
                it.code == if (path.parent == "/denied".toPath()) FileOperationIssueCode.FILESYSTEM_ERROR
                else FileOperationIssueCode.UNSAFE_PATH })
        }
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun logsProgressAtMostOncePerSecondBesidesStartAndFinish() = runTest {
        val fs = FakeFileSystem()
        val time = TestTimeSource()
        val reported = mutableListOf<Long>()
        var elapsed = 0L
        val guarded = object : ForwardingFileSystem(fs) {
            override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path {
                time += 100.milliseconds
                elapsed += 100
                return path
            }
        }
        val inspection = OperationMetadataInspection(guarded, timeSource = time, onProgress = { reported += elapsed })
        inspection.prepare(List(35) { "/photo-$it.jpg".toPath() })
        assertEquals(0L, reported.first())
        assertEquals(3600L, reported.last())
        assertTrue(reported.dropLast(1).zipWithNext().all { (first, second) -> second - first >= 1000 })
        assertEquals(5, reported.size)
    }

    @Test
    fun separatePassesNeverReuseStaleMissingOrChangedMetadata() = runTest {
        val fs = FakeFileSystem()
        val path = "/photo.jpg".toPath()
        val first = OperationMetadataInspection(fs)
        first.prepare(listOf(path))
        assertNull(first.inspect(path, mutableListOf()))
        fs.write(path) { writeUtf8("new photo") }
        val second = OperationMetadataInspection(fs)
        second.prepare(listOf(path))
        assertEquals(9L, second.inspect(path, mutableListOf())?.size)
    }
}
