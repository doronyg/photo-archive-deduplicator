package com.doronyg.photoarchivededuplicator

import okio.Path

/**
 * Native actions, distinct from navigating the stored inventory. Errors reach the caller.
 * Implementations dispatch blocking resolution/OS work off the caller and UI callbacks to Main.
 */
interface PathActions {
    suspend fun openFile(path: Path)
    suspend fun openFolder(path: Path)
}
