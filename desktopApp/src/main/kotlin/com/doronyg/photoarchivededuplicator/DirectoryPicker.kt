package com.doronyg.photoarchivededuplicator

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.doronyg.photoarchivededuplicator.strings.AppStrings

private const val MAC_DIRECTORY_DIALOG_PROPERTY = "apple.awt.fileDialogForDirectories"

/** Native modal UI belongs on Main; resolve its initial filesystem location on IO first. */
suspend fun pickDirectory(parent: Frame, container: AppContainer): String? {
    val initialDirectory = desktopPickerInitialDirectory(container)
    return withContext(Dispatchers.Main) {
        Diagnostics.logger.operation(LogTags.PICKER, "native chooser setup/show (UI toolkit on Main)") {
            if ("mac" in System.getProperty("os.name").lowercase()) pickWithMacDialog(parent)
            else pickWithSwing(parent, initialDirectory)
        }
    }
}

private fun pickWithMacDialog(parent: Frame): String? {
    // Only the native macOS dialog looks right there, and it picks folders only via this property
    System.setProperty(MAC_DIRECTORY_DIALOG_PROPERTY, "true")
    try {
        val dialog = FileDialog(parent, AppStrings.platform.chooseScanFolder, FileDialog.LOAD)
        dialog.isVisible = true
        val name = dialog.file ?: return null
        return File(dialog.directory, name).absolutePath
    } finally {
        System.setProperty(MAC_DIRECTORY_DIALOG_PROPERTY, "false")
    }
}

private fun pickWithSwing(parent: Frame, initialDirectory: File): String? {
    // Swing/native chooser construction and its own browsing are UI toolkit operations.
    val chooser = JFileChooser(initialDirectory).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = AppStrings.platform.chooseScanFolder
    }
    return if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
}
