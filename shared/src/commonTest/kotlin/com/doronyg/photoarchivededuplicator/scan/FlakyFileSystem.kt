package com.doronyg.photoarchivededuplicator.scan

import okio.FileMetadata
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path

/**
 * Wraps a (fake) file system to simulate an unreliable network share:
 * injects listing failures per directory, records every list() call,
 * and lets tests verify which thread context performs the blocking calls.
 */
class FlakyFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {
    private val remainingFailures = mutableMapOf<Path, Int>()
    val listCalls = mutableListOf<Path>()
    var onList: (Path) -> Unit = {}
    var onBlockingCall: (String) -> Unit = {}

    fun failListing(dir: Path, times: Int = Int.MAX_VALUE) {
        remainingFailures[dir] = times
    }

    override fun list(dir: Path): List<Path> {
        onBlockingCall("list($dir)")
        listCalls += dir
        onList(dir)
        val remaining = remainingFailures[dir] ?: 0
        if (remaining > 0) {
            remainingFailures[dir] = remaining - 1
            throw IOException("Simulated network failure listing $dir")
        }
        return super.list(dir)
    }

    override fun metadataOrNull(path: Path): FileMetadata? {
        onBlockingCall("metadataOrNull($path)")
        return super.metadataOrNull(path)
    }
}
