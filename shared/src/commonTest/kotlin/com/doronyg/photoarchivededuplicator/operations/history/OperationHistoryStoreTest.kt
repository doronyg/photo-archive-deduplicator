package com.doronyg.photoarchivededuplicator.operations.history

import com.doronyg.photoarchivededuplicator.IoLane
import com.doronyg.photoarchivededuplicator.scan.TrackingIoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.FileMetadata
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OperationHistoryStoreTest {
    private val archive = "/archive".toPath()
    private val bin = "/bin".toPath()

    @Test
    fun explicitRegistrationSurvivesFreshStoreAndNeverTouchesContent() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectory(archive)
        fs.write(archive / "photo.jpg") { writeUtf8("untouched") }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = OperationHistoryStore(IoLane("history", dispatcher), fs, dispatcher)
        val binding = store.register(archive, bin)
        val rebuilt = OperationHistoryStore(IoLane("rebuilt", dispatcher), fs, dispatcher)
        assertEquals(binding, rebuilt.verify(archive, bin))
        assertEquals(binding, rebuilt.register(archive, bin))
        assertEquals("untouched", fs.read(archive / "photo.jpg") { readUtf8() })
        assertEquals(setOf("bin.json", "operations"), fs.list(bin / HISTORY_DIRECTORY_NAME).map { it.name }.toSet())
        assertFalse(store.moveCapabilityEnabled)
        fs.checkNoOpenFiles()
    }

    @Test
    fun existingBinRequiresBothIdentifiersAndNeverStampsWrongRoot() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectory(archive)
        fs.createDirectory("/other".toPath())
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = OperationHistoryStore(IoLane("history", dispatcher), fs, dispatcher)
        store.register(archive, bin)
        assertFailsWith<IllegalArgumentException> { store.register("/other".toPath(), bin) }
        assertFalse(fs.exists("/other".toPath() / ARCHIVE_MARKER_NAME))
        val marker = fs.read(archive / ARCHIVE_MARKER_NAME) { readUtf8() }
        val token = store.verify(archive, bin).identity.registrationToken
        fs.write("/other".toPath() / ARCHIVE_MARKER_NAME) {
            writeUtf8(marker.replace(token, "different-token"))
        }
        assertFailsWith<IllegalArgumentException> { store.verify("/other".toPath(), bin) }
        assertFailsWith<IllegalArgumentException> { store.register("/other".toPath(), bin) }
    }

    @Test
    fun missingMalformedAndFutureMetadataAreReadOnlyProblems() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectory(archive)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = OperationHistoryStore(IoLane("history", dispatcher), fs, dispatcher)
        assertTrue(store.read(bin).problems.isNotEmpty())
        assertFalse(fs.exists(bin))
        store.register(archive, bin)
        val metadata = bin / HISTORY_DIRECTORY_NAME / "bin.json"
        fs.write(metadata) { writeUtf8("""{"formatVersion":99}""") }
        val before = fs.read(metadata) { readUtf8() }
        assertTrue(store.read(bin).problems.isNotEmpty())
        assertEquals(before, fs.read(metadata) { readUtf8() })
        assertFailsWith<IllegalArgumentException> { store.register(archive, bin) }
        fs.write(metadata) { writeUtf8("{") }
        assertTrue(store.read(bin).problems.isNotEmpty())
    }

    @Test
    fun confirmedCyclesCountLifetimeMovesButOnlyCurrentOccupancy() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectory(archive)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = OperationHistoryStore(IoLane("history", dispatcher), fs, dispatcher)
        val binding = store.register(archive, bin)
        operation(fs, binding, "op1", journal =
            event(1, "MOVE", "INTENT", "a") + event(2, "MOVE", "COMPLETED", "a") +
                event(3, "RESTORE", "INTENT", "b") + event(4, "RESTORE", "COMPLETED", "b") +
                event(5, "MOVE", "INTENT", "c") + event(6, "MOVE", "COMPLETED", "c"))
        val snapshot = store.read(bin)
        assertTrue(snapshot.complete)
        assertEquals(OperationHistoryStatistics(2, 20, 1, 10, 1, 0), snapshot.statistics)
        assertEquals(OperationHistoryMoveStatus.COMPLETED, snapshot.operations.single().items.single().moveStatus)
        assertEquals(snapshot, store.read(bin))
        fs.checkNoOpenFiles()
    }

    @Test
    fun plansIntentsAndTornTailNeverFabricateCompletedMoves() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectory(archive)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = OperationHistoryStore(IoLane("history", dispatcher), fs, dispatcher)
        val binding = store.register(archive, bin)
        operation(fs, binding, "op1", journal = "")
        assertEquals(OperationHistoryStatistics(), store.read(bin).statistics)
        assertFalse(store.read(bin).complete, "An untouched plan is not a finished operation")
        operation(fs, binding, "op1", journal = event(1, "MOVE", "INTENT", "a") +
            event(2, "MOVE", "COMPLETED", "a").trimEnd())
        val snapshot = store.read(bin)
        assertEquals(OperationHistoryStatistics(), snapshot.statistics)
        assertTrue(snapshot.operations.single().incomplete)
        assertTrue(snapshot.operations.single().problems.isNotEmpty())
    }

    @Test
    fun duplicateEventsDeduplicateButConflictsGapsAndMissingIntentFailClosed() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectory(archive)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = OperationHistoryStore(IoLane("history", dispatcher), fs, dispatcher)
        val binding = store.register(archive, bin)
        val intent = event(1, "MOVE", "INTENT", "a")
        val completed = event(2, "MOVE", "COMPLETED", "a")
        operation(fs, binding, "op1", journal = intent + intent + completed + completed)
        assertEquals(1L, store.read(bin).statistics.completedMoves)
        for (invalid in listOf(
            completed, intent + event(3, "MOVE", "COMPLETED", "a"),
            intent + completed + completed.replace("COMPLETED", "FAILED"),
            intent + completed + "{bad}\n",
        )) {
            operation(fs, binding, "op1", journal = invalid)
            store.clearCache()
            val snapshot = store.read(bin)
            assertEquals(OperationHistoryStatistics(), snapshot.statistics)
            assertTrue(snapshot.operations.single().incomplete)
        }
    }

    @Test
    fun binPathConflictsAcrossOperationsSuppressAmbiguousOccupancy() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectory(archive)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = OperationHistoryStore(IoLane("history", dispatcher), fs, dispatcher)
        val binding = store.register(archive, bin)
        for (id in listOf("op2", "op1")) operation(fs, binding, id, journal =
            event(1, "MOVE", "INTENT", "a") + event(2, "MOVE", "COMPLETED", "a"))
        val snapshot = store.read(bin)
        assertEquals(listOf("op1", "op2"), snapshot.operations.map { it.operationId })
        assertFalse(snapshot.complete)
        assertEquals(0L, snapshot.statistics.filesInBin)
        assertEquals(2L, snapshot.statistics.completedMoves)
        assertTrue(snapshot.problems.isNotEmpty())
    }

    @Test
    fun rejectsSymlinkAncestorsUnsafeRootsAndPersistentRegistrationLock() = runTest {
        val fs = FakeFileSystem().apply { allowSymlinks = true }
        fs.createDirectory(archive)
        fs.createSymlink("/alias".toPath(), archive)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = OperationHistoryStore(IoLane("history", dispatcher), fs, dispatcher)
        for ((root, trash) in listOf("/alias".toPath() to bin, archive to "/".toPath(),
            "relative".toPath() to bin, archive to archive)) {
            assertFailsWith<IllegalArgumentException> { store.register(root, trash) }
        }
        store.register(archive, bin)
        fs.createDirectory(bin / HISTORY_DIRECTORY_NAME / ".registration.lock")
        assertFailsWith<IllegalArgumentException> { store.register(archive, bin) }
        assertNotNull(store.verify(archive, bin))
    }

    @Test
    fun filesystemReadsAndRegistrationRunOnlyOnInjectedLaneAndNeverStatContentItems() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectory(archive)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val io = TrackingIoDispatcher(dispatcher)
        var readOnly = false
        val metadataReads = mutableMapOf<Path, Int>()
        val guarded = object : ForwardingFileSystem(fs) {
            override fun metadataOrNull(path: Path): FileMetadata? {
                io.assertOnIo("history stat")
                assertFalse(path.name == "photo.jpg")
                metadataReads[path] = metadataReads.getOrElse(path) { 0 } + 1
                return super.metadataOrNull(path)
            }
            override fun source(file: Path): Source {
                io.assertOnIo("history source")
                return super.source(file)
            }
            override fun createDirectory(dir: Path, mustCreate: Boolean) {
                io.assertOnIo("history mkdir")
                assertFalse(readOnly)
                super.createDirectory(dir, mustCreate)
            }
            override fun sink(file: Path, mustCreate: Boolean): Sink {
                io.assertOnIo("history sink")
                assertFalse(readOnly)
                return super.sink(file, mustCreate)
            }
            override fun atomicMove(source: Path, target: Path) {
                io.assertOnIo("history publish")
                assertFalse(readOnly)
                super.atomicMove(source, target)
            }
            override fun delete(path: Path, mustExist: Boolean) {
                io.assertOnIo("history cleanup")
                assertFalse(readOnly)
                super.delete(path, mustExist)
            }
            override fun list(dir: Path): List<Path> {
                io.assertOnIo("history list")
                return super.list(dir)
            }
        }
        val store = OperationHistoryStore(IoLane("history", io), guarded, dispatcher)
        val binding = store.register(archive, bin)
        operation(fs, binding, "op1", "")
        readOnly = true
        metadataReads.clear()
        assertFalse(store.read(bin).complete, "The read-only operation contains a plan, not completed moves")
        assertTrue(metadataReads.values.all { it == 1 }, "Shared ancestors are inspected only once per refresh")
        assertEquals(binding, store.verify(archive, bin))
    }

    @Test
    fun portablePathsRejectEscapeAndWindowsIncompatibleNames() {
        for (invalid in listOf("", "/a", "a\\b", "C:a", "../a", "a/../b", "a//b",
            "a/./b", "a\u0000b", "CON.jpg", "a. ", "a?", "a/")) {
            assertFailsWith<IllegalArgumentException>(invalid) { resolvePortablePath(archive, invalid) }
        }
        assertEquals("/archive/2024/photo.jpg".toPath(), resolvePortablePath(archive, "2024/photo.jpg"))
    }

    @Test
    fun unsupportedMalformedAndEscapingManifestsNeverContributeStatistics() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectory(archive)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = OperationHistoryStore(IoLane("history", dispatcher), fs, dispatcher)
        val binding = store.register(archive, bin)
        operation(fs, binding, "op1", event(1, "MOVE", "INTENT", "a") + event(2, "MOVE", "COMPLETED", "a"))
        val manifest = bin / HISTORY_DIRECTORY_NAME / "operations" / "op1" / "manifest.json"
        val valid = fs.read(manifest) { readUtf8() }
        for (invalid in listOf("{", valid.replace("\"formatVersion\":1", "\"formatVersion\":2"),
            valid.replace("photo.jpg", "../photo.jpg"), valid.replace(binding.identity.archiveId, "another-archive"))) {
            fs.write(manifest) { writeUtf8(invalid) }
            store.clearCache()
            val result = store.read(bin)
            assertFalse(result.complete)
            assertEquals(OperationHistoryStatistics(), result.statistics)
            assertTrue(result.problems.isNotEmpty())
        }
    }

    @Test
    fun futureArchiveMarkerIsNotSilentlyReplacedAndNestedBinIsAllowed() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectory(archive)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = OperationHistoryStore(IoLane("history", dispatcher), fs, dispatcher)
        val marker = archive / ARCHIVE_MARKER_NAME
        val unsupported = """{"formatVersion":2,"archiveId":"foreign","registrationToken":"token"}"""
        fs.write(marker) { writeUtf8(unsupported) }
        assertFailsWith<IllegalArgumentException> { store.register(archive, bin) }
        assertEquals(unsupported, fs.read(marker) { readUtf8() })
        assertFalse(fs.exists(bin))
        fs.delete(marker)
        val nested = archive / "DeduplicatorGarbageBin"
        assertEquals(nested, store.register(archive, nested).binRoot)
    }

    @Test
    fun unconfirmedRestoreDoesNotClaimConfirmedBinOccupancy() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectory(archive)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = OperationHistoryStore(IoLane("history", dispatcher), fs, dispatcher)
        val binding = store.register(archive, bin)
        operation(fs, binding, "op1", event(1, "MOVE", "INTENT", "a") +
            event(2, "MOVE", "COMPLETED", "a") + event(3, "RESTORE", "INTENT", "b"))
        val result = store.read(bin)
        assertEquals(OperationHistoryStatistics(1, 10), result.statistics)
        assertFalse(result.complete)
    }

    @Test
    fun parsedOperationCacheIsBoundedAndCanBeExplicitlyCleared() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectory(archive)
        val dispatcher = StandardTestDispatcher(testScheduler)
        var manifestReads = 0
        val counted = object : ForwardingFileSystem(fs) {
            override fun source(file: Path): Source {
                if (file.name == "manifest.json") manifestReads++
                return super.source(file)
            }
        }
        val store = OperationHistoryStore(IoLane("history", dispatcher), counted, dispatcher)
        val binding = store.register(archive, bin)
        operation(fs, binding, "op1", "")
        store.read(bin)
        manifestReads = 0
        store.read(bin)
        assertEquals(0, manifestReads)
        store.clearCache()
        store.read(bin)
        assertEquals(1, manifestReads)
        for (index in 2..129) operation(fs, binding, "op$index", "")
        store.read(bin)
        manifestReads = 0
        assertEquals(129, store.read(bin).operations.size)
        assertTrue(manifestReads > 0, "A bounded cache must evict parsed entries")
    }

    @Test
    fun sourceLockPrecedesBinLockAndBlocksOtherBinsWithoutReplacingIdentity() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectory(archive)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val locks = mutableListOf<Path>()
        var metadataWrites = 0
        val guarded = object : ForwardingFileSystem(fs) {
            override fun createDirectory(dir: Path, mustCreate: Boolean) {
                if (dir.name.endsWith(".lock")) {
                    assertTrue(mustCreate)
                    locks += dir
                }
                super.createDirectory(dir, mustCreate)
            }
            override fun sink(file: Path, mustCreate: Boolean): Sink {
                metadataWrites++
                assertTrue(fs.exists(archive / "$ARCHIVE_MARKER_NAME.lock"))
                assertTrue(fs.exists(bin / HISTORY_DIRECTORY_NAME / ".registration.lock"))
                return super.sink(file, mustCreate)
            }
        }
        val store = OperationHistoryStore(IoLane("history", dispatcher), guarded, dispatcher)
        val original = store.register(archive, bin)
        assertEquals(listOf(archive / "$ARCHIVE_MARKER_NAME.lock",
            bin / HISTORY_DIRECTORY_NAME / ".registration.lock"), locks)
        val publishedWrites = metadataWrites
        assertEquals(original, store.register(archive, bin))
        assertEquals(publishedWrites, metadataWrites, "Existing identity files must not be rewritten")
        val marker = fs.read(archive / ARCHIVE_MARKER_NAME) { readUtf8() }
        fs.createDirectory(archive / "$ARCHIVE_MARKER_NAME.lock")
        val otherStore = OperationHistoryStore(IoLane("other installation", dispatcher), guarded, dispatcher)
        assertFailsWith<IllegalArgumentException> { otherStore.register(archive, "/other-bin".toPath()) }
        assertFalse(fs.exists("/other-bin".toPath()))
        assertEquals(marker, fs.read(archive / ARCHIVE_MARKER_NAME) { readUtf8() })
        assertTrue(fs.exists(archive / "$ARCHIVE_MARKER_NAME.lock"), "An existing lock must not be removed")
    }

    @Test
    fun historyPreservesCompletionTimesAndFailedRestorationEvidence() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectory(archive)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = OperationHistoryStore(IoLane("history", dispatcher), fs, dispatcher)
        val binding = store.register(archive, bin)
        operation(fs, binding, "op1", event(1, "MOVE", "INTENT", "a") +
            event(2, "MOVE", "COMPLETED", "a") + event(3, "RESTORE", "INTENT", "b") +
            event(4, "RESTORE", "FAILED", "b").replace(
                "\"occurredAt\":", "\"error\":\"Original path occupied\",\"occurredAt\":"))
        var item = store.read(bin).operations.single().items.single()
        assertEquals("2026-01-01T00:00:00Z", item.movedAt)
        assertEquals(null, item.restoredAt)
        assertEquals("Original path occupied", item.lastError)
        assertTrue(item.confirmedInBin)
        operation(fs, binding, "op1", event(1, "MOVE", "INTENT", "a") +
            event(2, "MOVE", "COMPLETED", "a") + event(3, "RESTORE", "INTENT", "b") +
            event(4, "RESTORE", "COMPLETED", "b").replace("2026-01-01", "2026-01-02"))
        store.clearCache()
        item = store.read(bin).operations.single().items.single()
        assertEquals("2026-01-02T00:00:00Z", item.restoredAt)
        assertEquals(null, item.lastError)
        assertFalse(item.confirmedInBin)
    }

    @Test
    fun parserRejectsMetadataTargetsAndPropagatesCancellation() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectory(archive)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = OperationHistoryStore(IoLane("history", dispatcher), fs, dispatcher)
        val binding = store.register(archive, bin)
        operation(fs, binding, "op1", event(1, "MOVE", "INTENT", "a"))
        val manifest = fs.read(bin / HISTORY_DIRECTORY_NAME / "operations/op1/manifest.json") { readUtf8() }
        for (invalid in listOf(
            manifest.replace("items/photo.jpg", "$HISTORY_DIRECTORY_NAME/bin.json"),
            manifest.replace("photo.jpg", ARCHIVE_MARKER_NAME),
            manifest.replace("kept.jpg", "photo.jpg"),
        )) {
            assertFailsWith<IllegalArgumentException> { parseHistoryOperation("op1", binding.identity.archiveId, invalid, "") }
        }
        var checks = 0
        assertFailsWith<CancellationException> {
            parseHistoryOperation("op1", binding.identity.archiveId, manifest, event(1, "MOVE", "INTENT", "a")) {
                if (++checks > 1) throw CancellationException("Test cancellation")
            }
        }
    }

    private fun operation(fs: FakeFileSystem, binding: ArchiveBinding, id: String, journal: String) {
        val directory = bin / HISTORY_DIRECTORY_NAME / "operations" / id
        fs.createDirectories(directory)
        fs.write(directory / "manifest.json") {
            writeUtf8("""{"formatVersion":1,"operationId":"$id","createdAt":"2026-01-01T00:00:00Z","archiveId":"${binding.identity.archiveId}","items":[{"itemId":"i1","originalRelativePath":"photo.jpg","binRelativePath":"items/photo.jpg","sizeBytes":10,"modifiedAtMillis":null,"createdAtMillis":null,"categoryId":"duplicate","explanation":"Exact copy","retainedRelativePath":"kept.jpg"}]}""")
        }
        fs.write(directory / "events.jsonl") { writeUtf8(journal) }
    }

    private fun event(sequence: Int, action: String, state: String, attempt: String) =
        """{"formatVersion":1,"eventId":"e$sequence","sequence":$sequence,"itemId":"i1","attemptId":"$attempt","action":"$action","state":"$state","occurredAt":"2026-01-01T00:00:00Z"}""" + "\n"
}
