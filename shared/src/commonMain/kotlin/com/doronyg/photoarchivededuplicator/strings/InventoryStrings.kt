package com.doronyg.photoarchivededuplicator.strings

internal object InventoryStrings {
    const val loadingInventory = "Loading inventory..."
    const val pause = "Pause"
    const val resume = "Resume"
    const val folderToScan = "Folder to scan"
    const val chooseFolder = "Choose..."
    const val startScan = "Start scan"
    const val scanControlsHelp =
        "Start rescans this folder and resets its timer. Resume continues its saved queue, or the last selected scan when the field is empty."
    const val idle = "Idle"
    const val scanning = "Scanning..."
    const val paused = "Paused"
    const val completed = "Completed"
    const val copying = "Copying..."
    const val copyFilePaths = "Copy file paths"
    const val copyFilePathsHelp =
        "Copies all stored file paths in this folder and its subfolders, one full path per line. All roots copies the entire inventory."
    const val excludedTreeNotice =
        "This folder tree is excluded from scans and candidate searches. Its existing inventory is retained."
    const val excludeFolderTree = "Exclude this folder tree"
    const val nothingScanned = "Nothing scanned yet."
    const val emptyFolder = "Empty folder."
    const val openInFileManager = "Open in file manager"
    const val allRoots = "All roots"
    const val breadcrumbSeparator = ">"
    const val directoryIcon = ">"
    const val exclusionsTitle = "Excluded directory trees"
    const val exclusionsHelp =
        "Excluded folders and their subfolders are skipped during scans and candidate searches. Existing inventory is retained. Paths need not exist or have been scanned."
    const val pauseToEditExclusions = "Pause the active scan to edit exclusions."
    const val exclusionPath = "Absolute folder path to exclude"
    const val exclusionReason = "Reason (optional)"
    const val addOrUpdateExclusion = "Add or update exclusion"
    const val noExclusions = "No user exclusions."
    const val logPersistenceNotice = "Clear affects this view, not saved log files."
    const val logFilter = "Filter tag, message or thread"
    const val clearLogs = "Clear"
    const val copyVisibleLogs = "Copy all visible"
    const val pauseScroll = "Pause scroll"
    const val followLogs = "Follow"
    const val openPathFailed = "Could not open this path."
    const val invalidExportPath =
        "A stored file path contains a line break and cannot be exported as one path per line."
    const val missingDate = "-"
    val byteUnits = listOf("B", "KB", "MB", "GB", "TB")

    fun startupFailed(message: String?) = "Startup failed: ${message ?: AppStrings.unknown}"
    fun openInventoryFailed(message: String?) = "Could not open inventory: ${message ?: AppStrings.unknown}"
    fun chooseFolderFailed(message: String?) = "Could not choose folder: ${message ?: AppStrings.unknown}"
    fun loadInventoryFailed(message: String?) = "Could not load inventory: ${message ?: AppStrings.unknown}"
    fun excludeFolderFailed(message: String?) = "Could not exclude folder: ${message ?: AppStrings.unknown}"
    fun updateExclusionsFailed(message: String?) = "Could not update exclusions: ${message ?: AppStrings.unknown}"
    fun loadExclusionsFailed(message: String?) = "Could not load exclusions: ${message ?: AppStrings.unknown}"
    fun scanFailed(message: String) = "Failed: $message"
    fun scannedDirectories(count: Long) = "Directories scanned: $count"
    fun pendingDirectories(count: Long) = "Directories pending: $count"
    fun failedDirectories(count: Long) = "Directories failed: $count"
    fun filesFound(count: Long) = "Files found: $count"
    fun scanRoot(path: String) = "Scan root: $path"
    fun currentRun(elapsed: String) = "This run: $elapsed"
    fun totalActive(elapsed: String) = "Total active: $elapsed"
    fun currentDirectory(path: String) = "Current: $path"
    fun copiedFilePaths(count: Int, path: String?) =
        "Copied $count file paths from ${path ?: allRoots.lowercase()}, including subfolders."
    fun copyFilePathsFailed(path: String?, message: String?) =
        "Could not copy file paths from ${path ?: allRoots.lowercase()}: ${message ?: AppStrings.unknown}"
    fun openFolderInFileManager(path: String) = "Open folder in file manager: $path"
    fun directoryStatusCount(status: String, count: Long) = "$status: $count"
    fun summary(directoryCount: Long, statuses: String, fileCount: Long, bytes: String) =
        "$directoryCount folders ($statuses) - $fileCount files - $bytes"
    fun directoryDetails(status: String, fileCount: Int, bytes: String, scanned: String, retryCount: Int) =
        "$status - $fileCount files - $bytes - scanned $scanned" +
            if (retryCount > 0) " - $retryCount retries" else ""
    fun fileDetails(bytes: String, created: String, modified: String) =
        "$bytes - created $created - modified $modified"
    fun removeExclusion(path: String) = "Remove exclusion for $path"
    fun logSummary(visible: Int) = "Local diagnostics - epoch milliseconds - $visible retained/visible"
    fun logSinkStatus(dropped: Long, sinkDropped: Long, sinkFailures: Long) =
        "Dropped: $dropped - native sink dropped: $sinkDropped - sink failures: $sinkFailures"
    fun openLogFolder(path: String) = "Open local log folder: $path"
    fun logFileStatus(dropped: Long, failures: Long) =
        "Files: 5 x 5 MiB maximum - file queue dropped: $dropped - file failures: $failures"
    fun lastLogFileError(message: String) = "Last file error: $message"
    fun resolveLogFolderFailed(message: String?) = "Could not resolve log folder: ${message ?: AppStrings.unknown}"
    fun minimumLogLevel(name: String) = "$name+"
    fun folderPath(path: String) = "Folder: $path"
    fun bytes(value: Long) = "$value B"
    fun scaledBytes(whole: Long, fraction: Long, unit: String) = "$whole.$fraction $unit"
    fun elapsed(minutes: Long, seconds: Long) = "${minutes}m ${seconds}s"
}
