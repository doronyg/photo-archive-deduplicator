package com.doronyg.photoarchivededuplicator

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okio.Path
import okio.Path.Companion.toPath
import java.awt.Desktop
import java.io.File
import java.net.URI
import com.doronyg.photoarchivededuplicator.strings.AppStrings

fun desktopPathActions(
    diagnosticActions: PathActions = DesktopPathActions(),
    loadContainer: suspend () -> AppContainer,
): PathActions = object : PathActions {
    override suspend fun openFile(path: Path) =
        DesktopPathActions(filesystemLane = loadContainer().filesystemLane).openFile(path)
    override suspend fun openFolder(path: Path) {
        // Diagnostics must remain usable when inventory initialization fails or hangs.
        if (path.normalized() == diagnosticLogDirectory()?.toPath()?.normalized()) {
            diagnosticActions.openFolder(path)
        } else {
            DesktopPathActions(filesystemLane = loadContainer().filesystemLane).openFolder(path)
        }
    }
}

/** Resolve the chooser's initial location without moving the native dialog off Main. */
suspend fun desktopPickerInitialDirectory(container: AppContainer): File =
    container.filesystemLane.run("picker initial directory canonicalFile") {
        Diagnostics.logger.blockingOperation(LogTags.PICKER, "initial directory canonicalFile") {
            File(System.getProperty("user.home")).canonicalFile
        }
    }

internal class DesktopPathActions(
    /** null = missing, true = directory, false = file. Injectable without touching disk in tests. */
    private val kind: (Path) -> Boolean? = { path ->
        val file = File(path.toString())
        when {
            !Diagnostics.logger.blockingOperation(LogTags.FILE_SYSTEM, "File.exists $path") { file.exists() } -> null
            Diagnostics.logger.blockingOperation(LogTags.FILE_SYSTEM, "File.isDirectory $path") { file.isDirectory } -> true
            Diagnostics.logger.blockingOperation(LogTags.FILE_SYSTEM, "File.isFile $path") { file.isFile } -> false
            else -> error(AppStrings.platform.notRegular(path.toString()))
        }
    },
    private val launch: (URI) -> Unit = { uri ->
        // Desktop.open is a blocking OS integration call, not a Swing UI callback.
        check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            AppStrings.platform.desktopOpenUnsupported
        }
        // Desktop delegates folders to Finder on macOS and files to their default applications.
        Desktop.getDesktop().open(File(uri))
    },
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val filesystemLane: IoLane = IoLane("filesystem-test", ioDispatcher),
) : PathActions {
    override suspend fun openFile(path: Path) = open(path, directory = false)
    override suspend fun openFolder(path: Path) = open(path, directory = true)

    private suspend fun open(path: Path, directory: Boolean) = filesystemLane.run("native open $path") {
        require(path.isAbsolute) { AppStrings.platform.absoluteNativePath }
        val isDirectory = Diagnostics.logger.operation(LogTags.NATIVE_PATH, "file kind exists/isDirectory/isFile $path") {
            kind(path)
        } ?: error(AppStrings.platform.inaccessible(path.toString()))
        check(isDirectory == directory) {
            if (directory) AppStrings.platform.notFolder(path.toString()) else AppStrings.platform.notFile(path.toString())
        }
        // Construct the URI without File.toURI(), which probes the real filesystem.
        // The native launcher receives a decoded File; shell quoting is never involved.
        val nativePath = File(path.toString()).absolutePath.replace(File.separatorChar, '/')
        val uriPath = when {
            nativePath.startsWith("//") -> "//$nativePath"
            nativePath.startsWith("/") -> nativePath
            else -> "/$nativePath"
        }
        Diagnostics.logger.operation(LogTags.NATIVE_PATH, "Desktop.open $path") { launch(URI("file", null, uriPath, null)) }
    }
}
