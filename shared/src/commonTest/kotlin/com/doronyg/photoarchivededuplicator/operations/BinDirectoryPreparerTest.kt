package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.IoLane
import com.doronyg.photoarchivededuplicator.operations.history.ArchiveBinding
import com.doronyg.photoarchivededuplicator.operations.history.OperationHistoryStore
import com.doronyg.photoarchivededuplicator.scan.TrackingIoDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Buffer
import okio.ForwardingSink
import okio.IOException
import okio.Sink
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class BinDirectoryPreparerTest {
    private val archive = "/archive".toPath()
    private val bin = "/bin".toPath()

    private fun proposal(binding: ArchiveBinding, source: Path = archive / "Trip/photo.jpg"): FileOperationProposal {
        val destination = requireNotNull(operationDestinationPath(source, binding.binRoot))
        return FileOperationProposal("proposal", 1, 1, null, binding.binRoot, emptyList(),
            listOf(ProposedFileMove(source, destination.relativeTo(binding.binRoot), destination,
                archive / "kept.jpg", 5, emptyList())), emptyList())
    }

    @Test
    fun createsMappedDirectoriesAndMarkersWithoutMovingOrReadingPhotosAndCanBeRepeated() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectories(archive / "Trip")
        fs.write(archive / "Trip/photo.jpg") { writeUtf8("photo") }
        fs.write(archive / "kept.jpg") { writeUtf8("photo") }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val lane = IoLane("preparation", dispatcher)
        val binding = OperationHistoryStore(lane, fs, dispatcher).register(archive, bin)
        val guarded = object : ForwardingFileSystem(fs) {
            override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path {
                if (functionName in setOf("source", "sink", "openReadOnly", "openReadWrite"))
                    check(!path.name.endsWith(".jpg")) { "Photo contents must not be accessed" }
                return path
            }
        }
        val preparer = BinDirectoryPreparer(lane, guarded, dispatcher)
        val first = preparer.prepare(proposal(binding), binding)
        assertEquals(2, first.createdDirectories)
        assertEquals(3, first.writtenMarkers)
        val mapping = decodeBinDirectoryMarker(fs.read(bin / "archive/Trip" / BIN_DIRECTORY_MARKER_NAME) { readUtf8() })
        assertEquals(binding.identity, mapping.identity)
        assertEquals("archive/Trip", mapping.binRelativeDirectory)
        assertEquals("Trip", mapping.originalArchiveRelativeDirectory)
        assertEquals("/archive/Trip", mapping.originalDirectory)
        assertNull(decodeBinDirectoryMarker(fs.read(bin / BIN_DIRECTORY_MARKER_NAME) { readUtf8() }).originalDirectory)
        assertEquals(0, preparer.prepare(proposal(binding), binding).writtenMarkers)
        assertTrue(fs.exists(archive / "Trip/photo.jpg"))
        assertFalse(fs.exists(bin / "archive/Trip/photo.jpg"))
        assertEquals("photo", fs.read(archive / "Trip/photo.jpg") { readUtf8() })
        fs.checkNoOpenFiles()
    }

    @Test
    fun rootMarkerIsPortableAcrossMountsAndSavedAbsoluteHintsAreNotIdentity() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectory(archive)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val lane = IoLane("preparation", dispatcher)
        val store = OperationHistoryStore(lane, fs, dispatcher)
        val binding = store.register(archive, bin)
        val preparer = BinDirectoryPreparer(lane, fs, dispatcher)
        preparer.prepare(proposal(binding), binding)
        val marker = bin / "archive/Trip" / BIN_DIRECTORY_MARKER_NAME
        val previous = decodeBinDirectoryMarker(fs.read(marker) { readUtf8() })
        fs.write(marker) { writeUtf8(encodeBinDirectoryMarker(previous.copy(originalDirectory = "/old-mount/Trip"))) }
        assertEquals(0, preparer.prepare(proposal(binding), binding).writtenMarkers)
        assertEquals("/old-mount/Trip", decodeBinDirectoryMarker(fs.read(marker) { readUtf8() }).originalDirectory)
        val otherMount = "/another-mount".toPath()
        fs.createDirectory(otherMount)
        fs.write(otherMount / ".photo-archive-id.json") {
            writeUtf8(fs.read(archive / ".photo-archive-id.json") { readUtf8() })
        }
        val remapped = store.verify(otherMount, bin)
        val source = otherMount / "New/photo.jpg"
        val destination = requireNotNull(operationDestinationPath(source, bin))
        val remappedProposal = proposal(binding).copy(moves = listOf(
            ProposedFileMove(source, destination.relativeTo(bin), destination, otherMount / "keep.jpg", 5, emptyList())))
        preparer.prepare(remappedProposal, remapped)
        assertEquals("New", decodeBinDirectoryMarker(
            fs.read(bin / "another-mount/New" / BIN_DIRECTORY_MARKER_NAME) { readUtf8() }
        ).originalArchiveRelativeDirectory)
    }

    @Test
    fun windowsDirectoryMarkersUsePortableRelativePathsAndPreserveDriveMapping() = runTest {
        val fs = FakeFileSystem()
        val windowsArchive = "C:\\Photos".toPath()
        val windowsBin = "D:\\Bin".toPath()
        fs.createDirectories(windowsArchive)
        fs.createDirectories("D:\\".toPath())
        val dispatcher = StandardTestDispatcher(testScheduler)
        val lane = IoLane("preparation", dispatcher)
        val binding = OperationHistoryStore(lane, fs, dispatcher).register(windowsArchive, windowsBin)
        val source = windowsArchive / "Trip/photo.jpg"
        val destination = requireNotNull(operationDestinationPath(source, windowsBin))
        val plan = FileOperationProposal("windows-proposal", 1, 1, null, windowsBin, emptyList(),
            listOf(ProposedFileMove(source, destination.relativeTo(windowsBin), destination,
                windowsArchive / "keep.jpg", 5, emptyList())), emptyList())
        BinDirectoryPreparer(lane, fs, dispatcher).prepare(plan, binding)
        val marker = decodeBinDirectoryMarker(
            fs.read(windowsBin / "C/Photos/Trip" / BIN_DIRECTORY_MARKER_NAME) { readUtf8() })
        assertEquals("C/Photos/Trip", marker.binRelativeDirectory)
        assertEquals("Trip", marker.originalArchiveRelativeDirectory)
        assertEquals("C:\\Photos\\Trip", marker.originalDirectory)
        assertNull(decodeBinDirectoryMarker(
            fs.read(windowsBin / BIN_DIRECTORY_MARKER_NAME) { readUtf8() }).originalDirectory)
        assertFalse(fs.exists(destination))
    }

    @Test
    fun rejectsMismatchedMalformedAndSymlinkMarkersWithoutOverwritingThem() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectory(archive)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val lane = IoLane("preparation", dispatcher)
        val binding = OperationHistoryStore(lane, fs, dispatcher).register(archive, bin)
        val preparer = BinDirectoryPreparer(lane, fs, dispatcher)
        val marker = bin / BIN_DIRECTORY_MARKER_NAME
        fs.write(marker) { writeUtf8("{") }
        assertFailsWith<IllegalArgumentException> { preparer.prepare(proposal(binding), binding) }
        assertEquals("{", fs.read(marker) { readUtf8() })
        assertFalse(fs.exists(bin / "archive"))
        fs.delete(marker)
        preparer.prepare(proposal(binding), binding)
        val valid = fs.read(marker) { readUtf8() }
        fs.write(marker) { writeUtf8(valid.replace(binding.identity.archiveId, "another-archive")) }
        assertFailsWith<IllegalArgumentException> { preparer.prepare(proposal(binding), binding) }
        fs.delete(marker)
        fs.allowSymlinks = true
        fs.write("/other.json".toPath()) { writeUtf8(valid) }
        fs.createSymlink(marker, "/other.json".toPath())
        assertFailsWith<IllegalArgumentException> { preparer.prepare(proposal(binding), binding) }
        assertEquals(valid, fs.read("/other.json".toPath()) { readUtf8() })
    }

    @Test
    fun preflightsAllPathsBeforeWritingAndNeverCreatesFoldersForAnUnsafeProposal() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectory(archive)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val lane = IoLane("preparation", dispatcher)
        val binding = OperationHistoryStore(lane, fs, dispatcher).register(archive, bin)
        val preparer = BinDirectoryPreparer(lane, fs, dispatcher)
        val valid = proposal(binding)
        val bad = valid.copy(moves = valid.moves + valid.moves.single().copy(destinationPath = "/outside/photo.jpg".toPath()))
        assertFailsWith<IllegalArgumentException> { preparer.prepare(bad, binding) }
        assertFalse(fs.exists(bin / BIN_DIRECTORY_MARKER_NAME))
        assertFalse(fs.exists(bin / "archive"))
        assertFailsWith<IllegalArgumentException> {
            preparer.prepare(valid.copy(findings = listOf(FileOperationFinding(FileOperationIssueCode.PROTECTED_GROUP))), binding)
        }
        assertFalse(fs.exists(bin / BIN_DIRECTORY_MARKER_NAME))
    }

    @Test
    fun rejectsReservedMetadataAndCaseFoldedFileDirectoryCollisionsBeforeCreatingMarkers() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectory(archive)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val lane = IoLane("preparation", dispatcher)
        val binding = OperationHistoryStore(lane, fs, dispatcher).register(archive, bin)
        val preparer = BinDirectoryPreparer(lane, fs, dispatcher)
        assertFailsWith<IllegalArgumentException> {
            preparer.prepare(proposal(binding, archive / BIN_DIRECTORY_MARKER_NAME), binding)
        }
        val file = proposal(binding, archive / "folder")
        val nested = proposal(binding, archive / "Folder/photo.jpg")
        assertFailsWith<IllegalArgumentException> {
            preparer.prepare(file.copy(moves = file.moves + nested.moves), binding)
        }
        assertFalse(fs.exists(bin / BIN_DIRECTORY_MARKER_NAME))
        assertFalse(fs.exists(bin / "archive"))
    }

    @Test
    fun interruptedMarkerWriteLeavesAnExcludedMarkerAndIsNeverSilentlyOverwritten() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectory(archive)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val lane = IoLane("preparation", dispatcher)
        val binding = OperationHistoryStore(lane, fs, dispatcher).register(archive, bin)
        val failing = object : ForwardingFileSystem(fs) {
            override fun sink(file: Path, mustCreate: Boolean): Sink =
                object : ForwardingSink(super.sink(file, mustCreate)) {
                    override fun write(source: Buffer, byteCount: Long) {
                        super.write(source, minOf(8L, byteCount))
                        throw IOException("Disconnected during marker write")
                    }
                }
        }
        assertFailsWith<IOException> {
            BinDirectoryPreparer(lane, failing, dispatcher).prepare(proposal(binding), binding)
        }
        val marker = bin / BIN_DIRECTORY_MARKER_NAME
        val partial = fs.read(marker) { readUtf8() }
        assertTrue(partial.isNotEmpty())
        assertFalse(fs.exists(bin / ".deduplicator-history/.directory-preparation.lock"))
        assertFailsWith<IllegalArgumentException> {
            BinDirectoryPreparer(lane, fs, dispatcher).prepare(proposal(binding), binding)
        }
        assertEquals(partial, fs.read(marker) { readUtf8() })
        assertFalse(fs.exists(bin / "archive"))
        fs.checkNoOpenFiles()
    }

    @Test
    fun refusesDestinationCollisionsSymlinkAncestorsAndChangedArchiveIdentity() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectory(archive)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val lane = IoLane("preparation", dispatcher)
        val binding = OperationHistoryStore(lane, fs, dispatcher).register(archive, bin)
        val preparer = BinDirectoryPreparer(lane, fs, dispatcher)
        fs.createDirectories(bin / "archive/Trip")
        fs.write(bin / "archive/Trip/photo.jpg") { writeUtf8("existing") }
        assertFailsWith<IllegalArgumentException> { preparer.prepare(proposal(binding), binding) }
        assertFalse(fs.exists(bin / BIN_DIRECTORY_MARKER_NAME))
        fs.delete(bin / "archive/Trip/photo.jpg")
        fs.delete(bin / "archive/Trip")
        fs.delete(bin / "archive")
        fs.allowSymlinks = true
        fs.createSymlink(bin / "archive", archive)
        assertFailsWith<IllegalArgumentException> { preparer.prepare(proposal(binding), binding) }
        fs.delete(bin / "archive")
        val marker = archive / ".photo-archive-id.json"
        fs.write(marker) { writeUtf8("""{"formatVersion":1,"archiveId":"wrong","registrationToken":"wrong"}""") }
        assertFailsWith<IllegalArgumentException> { preparer.prepare(proposal(binding), binding) }
        assertFalse(fs.exists(bin / BIN_DIRECTORY_MARKER_NAME))
    }

    @Test
    fun preparationRunsOnFilesystemLaneAndReportsExistingLockWithoutTakingItOver() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectory(archive)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val io = TrackingIoDispatcher(dispatcher)
        val lane = IoLane("preparation", io)
        val binding = OperationHistoryStore(lane, fs, dispatcher).register(archive, bin)
        val guarded = object : ForwardingFileSystem(fs) {
            override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path {
                assertTrue(io.isRunningTask, "Filesystem access must use the filesystem lane")
                return path
            }
        }
        val preparer = BinDirectoryPreparer(lane, guarded, dispatcher)
        val lock = bin / ".deduplicator-history/.directory-preparation.lock"
        fs.createDirectory(lock)
        assertFailsWith<IllegalArgumentException> { preparer.prepare(proposal(binding), binding) }
        assertTrue(fs.exists(lock))
        fs.delete(lock)
        preparer.prepare(proposal(binding), binding)
        assertFalse(fs.exists(lock))
    }
}
