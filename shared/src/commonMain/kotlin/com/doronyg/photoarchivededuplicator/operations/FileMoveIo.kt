package com.doronyg.photoarchivededuplicator.operations

import okio.Path
import okio.IOException

/** Rejection before the native rename was invoked: neither photo path was mutated. */
internal class FileMoveRejectedException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Blocking filesystem primitives; the caller must hold the filesystem IoLane.
 *
 * Only existing regular source files and existing, non-symlink directory ancestors
 * are accepted. Moves require an absent destination and equal platform FileStores,
 * checked again inside [moveNoReplace]. Errors (including unsupported operations)
 * propagate; there is no application-level copy/delete fallback.
 *
 * Atomic rename is required: unsupported providers and cross-filesystem renames
 * fail without a copy/delete retry, even when FileStores appeared equal.
 * This is not a race-proof no-clobber transaction: ATOMIC_MOVE can replace a
 * destination appearing after the absence check. Callers must hold cooperating
 * writer locks, and external tools must not change these paths or mounts.
 */
internal interface FileMoveIo {
    fun sameFileSystem(source: Path, destinationDirectory: Path): Boolean

    fun moveNoReplace(source: Path, destination: Path)

    /**
     * Forces an existing regular metadata file through the platform's force(true).
     * Does not read/hash photo content. This does not fsync parent directories or
     * guarantee a remote server/controller has persisted data through power loss.
     */
    fun flushFile(path: Path)
}

internal expect fun platformFileMoveIo(): FileMoveIo
