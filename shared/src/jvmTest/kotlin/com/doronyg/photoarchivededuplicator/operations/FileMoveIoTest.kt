package com.doronyg.photoarchivededuplicator.operations

import okio.Path
import okio.Path.Companion.toPath
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Path as NioPath

class FileMoveIoTest {
    private val io = platformFileMoveIo()

    @Test
    fun missingSourceIsExplicitlyRejectedBeforeNativeRenameAndPreservesCause() = fixture { root ->
        var renamed = false
        val guarded = NioFileMoveIo(move = { _, _, _ -> renamed = true })
        val failure = assertFailsWith<FileMoveRejectedException> {
            guarded.moveNoReplace(root.resolve("missing.jpg").okio(), root.resolve("out.jpg").okio())
        }
        assertTrue(failure.cause is NoSuchFileException)
        assertFalse(renamed)
        assertFalse(Files.exists(root.resolve("out.jpg"), NOFOLLOW_LINKS))
    }

    @Test
    fun sameFilesystemMovePreservesBytesAndRemovesSource() = fixture { root ->
        val source = Files.write(root.resolve("source.jpg"), byteArrayOf(0, 1, -1, 42))
        val directory = Files.createDirectory(root.resolve("bin"))
        val destination = directory.resolve("destination.jpg")
        assertTrue(io.sameFileSystem(source.okio(), directory.okio()))
        io.moveNoReplace(source.okio(), destination.okio())
        assertFalse(Files.exists(source, NOFOLLOW_LINKS))
        assertTrue(Files.readAllBytes(destination).contentEquals(byteArrayOf(0, 1, -1, 42)))
    }

    @Test
    fun unsupportedAtomicRenameNeverRetriesAsACopyDeleteMove() = fixture { root ->
        val source = Files.writeString(root.resolve("source.jpg"), "source")
        val destination = root.resolve("destination.jpg")
        var attempts = 0
        val unsupported = NioFileMoveIo(move = { from, to, option ->
            attempts++
            assertEquals(source, from)
            assertEquals(destination, to)
            assertEquals(ATOMIC_MOVE, option)
            throw AtomicMoveNotSupportedException(from.toString(), to.toString(), "Cross-device or unsupported rename")
        })
        assertFailsWith<AtomicMoveNotSupportedException> {
            unsupported.moveNoReplace(source.okio(), destination.okio())
        }
        assertEquals(1, attempts)
        assertEquals("source", Files.readString(source))
        assertFalse(Files.exists(destination, NOFOLLOW_LINKS))
    }

    @Test
    fun existingDestinationIsNeverChanged() = fixture { root ->
        val source = Files.writeString(root.resolve("source.jpg"), "source")
        val destination = Files.writeString(root.resolve("destination.jpg"), "keep")
        assertFailsWith<FileMoveRejectedException> {
            io.moveNoReplace(source.okio(), destination.okio())
        }
        assertEquals("source", Files.readString(source))
        assertEquals("keep", Files.readString(destination))
        assertFailsWith<FileMoveRejectedException> { io.moveNoReplace(source.okio(), source.okio()) }
        val directory = Files.createDirectory(root.resolve("directory"))
        assertFailsWith<FileMoveRejectedException> {
            io.moveNoReplace(source.okio(), directory.okio())
        }
    }

    @Test
    fun crossFilesystemIsRejectedEvenWithoutCallingProbe() = fixture { root ->
        val source = Files.writeString(root.resolve("source.jpg"), "source")
        val uri = URI.create("jar:${root.resolve("other-filesystem.zip").toUri()}")
        FileSystems.newFileSystem(uri, mapOf("create" to "true")).use { zip ->
            val directory = Files.createDirectory(root.resolve("bin"))
            val destination = directory.resolve("destination.jpg")
            val sourceStore = Files.getFileStore(source)
            val otherStore = Files.getFileStore(zip.getPath("/"))
            // Simulate separate mounted stores without writing outside the test fixture.
            val separateStores = NioFileMoveIo { if (it == source) sourceStore else otherStore }
            assertFalse(separateStores.sameFileSystem(source.okio(), directory.okio()))
            assertFailsWith<FileMoveRejectedException> {
                separateStores.moveNoReplace(source.okio(), destination.okio())
            }
            var changedStore = false
            val changingStores = NioFileMoveIo {
                if (it == source || !changedStore) sourceStore else otherStore
            }
            assertTrue(changingStores.sameFileSystem(source.okio(), directory.okio()))
            changedStore = true
            assertFailsWith<FileMoveRejectedException> {
                changingStores.moveNoReplace(source.okio(), destination.okio())
            }
            assertFalse(Files.exists(destination, NOFOLLOW_LINKS))
        }
        assertEquals("source", Files.readString(source))
    }

    @Test
    fun symbolicLinksAndLinkedAncestorsAreRejected() = fixture { root ->
        val source = Files.writeString(root.resolve("source.jpg"), "source")
        val directory = Files.createDirectory(root.resolve("bin"))
        val link = root.resolve("link")
        try {
            Files.createSymbolicLink(link, source)
        } catch (_: UnsupportedOperationException) {
            return@fixture
        }
        assertFailsWith<FileMoveRejectedException> { io.moveNoReplace(link.okio(), directory.resolve("photo").okio()) }
        assertFailsWith<FileSystemException> { io.sameFileSystem(link.okio(), directory.okio()) }
        assertFailsWith<FileSystemException> { io.flushFile(link.okio()) }
        assertFailsWith<FileMoveRejectedException> { io.moveNoReplace(source.okio(), link.okio()) }
        Files.delete(link)
        Files.createSymbolicLink(link, root.resolve("missing"))
        assertFailsWith<FileMoveRejectedException> { io.moveNoReplace(source.okio(), link.okio()) }
        Files.delete(link)
        Files.createSymbolicLink(link, directory)
        assertFailsWith<FileSystemException> { io.sameFileSystem(source.okio(), link.okio()) }
        assertFailsWith<FileMoveRejectedException> { io.moveNoReplace(source.okio(), link.resolve("photo").okio()) }
        Files.writeString(directory.resolve("nested.jpg"), "nested")
        assertFailsWith<FileMoveRejectedException> {
            io.moveNoReplace(link.resolve("nested.jpg").okio(), root.resolve("out.jpg").okio())
        }
        assertEquals("source", Files.readString(source))
        assertEquals("nested", Files.readString(directory.resolve("nested.jpg")))
    }

    @Test
    fun missingPathsAndUnsupportedFileTypesPropagateErrors() = fixture { root ->
        val missing = root.resolve("missing").okio()
        val directory = Files.createDirectory(root.resolve("bin"))
        val source = Files.writeString(root.resolve("source.jpg"), "source")
        assertFailsWith<NoSuchFileException> { io.sameFileSystem(missing, directory.okio()) }
        assertFailsWith<FileMoveRejectedException> { io.moveNoReplace(missing, root.resolve("out").okio()) }
        assertFailsWith<FileMoveRejectedException> {
            io.moveNoReplace(source.okio(), root.resolve("missing/out").okio())
        }
        assertFailsWith<FileMoveRejectedException> {
            io.moveNoReplace(directory.okio(), root.resolve("out").okio())
        }
        assertFailsWith<FileSystemException> { io.sameFileSystem(source.okio(), source.okio()) }
        assertFailsWith<NoSuchFileException> { io.flushFile(missing) }
        assertFailsWith<FileSystemException> { io.flushFile(directory.okio()) }
        assertEquals("source", Files.readString(source))
    }

    @Test
    fun flushPreservesExistingManifestWithoutCreatingMissingFiles() = fixture { root ->
        val manifest = Files.writeString(root.resolve("manifest.json"), """{"version":1}""")
        io.flushFile(manifest.okio())
        assertEquals("""{"version":1}""", Files.readString(manifest))
        assertFalse(Files.exists(root.resolve("missing"), NOFOLLOW_LINKS))
    }

    @Test
    fun unsupportedStoreProbeIsNotHiddenOrTreatedAsSuccess() = fixture { root ->
        val source = Files.writeString(root.resolve("source.jpg"), "source")
        val destination = root.resolve("out.jpg")
        val unsupported = NioFileMoveIo { throw UnsupportedOperationException("store unavailable") }
        assertFailsWith<UnsupportedOperationException> { unsupported.sameFileSystem(source.okio(), root.okio()) }
        assertFailsWith<FileMoveRejectedException> { unsupported.moveNoReplace(source.okio(), destination.okio()) }
        assertEquals("source", Files.readString(source))
        assertFalse(Files.exists(destination, NOFOLLOW_LINKS))
    }

    private fun NioPath.okio(): Path = toString().toPath()

    private fun fixture(block: (NioPath) -> Unit) {
        val parent = java.nio.file.Paths.get("build", "file-move-io-tests").toAbsolutePath()
        Files.createDirectories(parent)
        val root = Files.createDirectory(parent.resolve(java.util.UUID.randomUUID().toString())).toRealPath()
        try {
            block(root)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
        }
    }
}
