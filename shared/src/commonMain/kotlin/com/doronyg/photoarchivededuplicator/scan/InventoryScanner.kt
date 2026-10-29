package com.doronyg.photoarchivededuplicator.scan

import okio.Path

/** Fills the inventory database. Implementations differ in where they read from. */
interface InventoryScanner {
    /** Scans (or rescans) [root]; previously recorded data is reconciled, not discarded. */
    suspend fun startScan(root: Path, onEvent: suspend (ScanEvent) -> Unit = {})

    /** Continues unfinished work, including work left by a previous app session. */
    suspend fun resume(onEvent: suspend (ScanEvent) -> Unit = {})
}
