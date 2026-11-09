package com.doronyg.photoarchivededuplicator.operations

import okio.Path
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.CopyOption
import java.nio.file.FileStore
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Path as NioPath

internal actual fun platformFileMoveIo(): FileMoveIo = NioFileMoveIo()

internal class NioFileMoveIo(
    private val move: (NioPath, NioPath, CopyOption) -> Unit = { source, destination, option ->
        Files.move(source, destination, option)
    },
    private val fileStore: (NioPath) -> FileStore = { Files.getFileStore(it) },
) : FileMoveIo {
    override fun sameFileSystem(source: Path, destinationDirectory: Path): Boolean {
        val sourcePath = checkedPath(source)
        val directory = checkedPath(destinationDirectory)
        requireRegularFile(sourcePath)
        requireDirectoryTree(directory)
        return fileStore(sourcePath) == fileStore(directory)
    }

    override fun moveNoReplace(source: Path, destination: Path) {
        val (sourcePath, destinationPath) = try {
            val sourcePath = checkedPath(source)
            val destinationPath = checkedPath(destination)
            requireRegularFile(sourcePath)
            val directory = destinationPath.parent
                ?: throw FileSystemException(destinationPath.toString(), null, "Destination has no parent")
            requireDirectoryTree(directory)
            requireAbsent(destinationPath)
            if (fileStore(sourcePath) != fileStore(directory)) {
                throw FileSystemException(sourcePath.toString(), destinationPath.toString(), "Cross-filesystem move refused")
            }
            sourcePath to destinationPath
        } catch (failure: Exception) {
            throw FileMoveRejectedException(failure.message ?: failure.toString(), failure)
        }
        // A plain move can copy/delete after EXDEV even when visible FileStores match.
        // Destination preflight and cooperating-writer locks are required separately.
        move(sourcePath, destinationPath, ATOMIC_MOVE)
    }

    override fun flushFile(path: Path) {
        val nativePath = checkedPath(path)
        requireRegularFile(nativePath)
        FileChannel.open(nativePath, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
    }

    private fun checkedPath(path: Path): NioPath {
        val nativePath = Paths.get(path.toString()).toAbsolutePath()
        if (nativePath != nativePath.normalize()) {
            throw FileSystemException(nativePath.toString(), null, "Non-normalized path refused")
        }
        return nativePath
    }

    private fun requireRegularFile(path: NioPath) {
        path.parent?.let(::requireDirectoryTree)
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (attributes.isSymbolicLink || !attributes.isRegularFile) {
            throw FileSystemException(path.toString(), null, "Expected a regular non-symlink file")
        }
    }

    private fun requireDirectoryTree(path: NioPath) {
        for (directory in generateSequence(path) { it.parent }.toList().asReversed()) {
            val attributes = Files.readAttributes(directory, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (attributes.isSymbolicLink || !attributes.isDirectory) {
                throw FileSystemException(directory.toString(), null, "Expected a non-symlink directory")
            }
        }
    }

    private fun requireAbsent(path: NioPath) {
        try {
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return
        }
        throw FileAlreadyExistsException(path.toString())
    }
}
