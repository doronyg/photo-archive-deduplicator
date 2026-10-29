package com.doronyg.photoarchivededuplicator

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

private const val MAC_DIRECTORY_DIALOG_PROPERTY = "apple.awt.fileDialogForDirectories"

/** Native folder chooser; returns the absolute path, or null if cancelled. Blocks the UI thread while open (modal). */
fun pickDirectory(parent: Frame): String? =
    if ("mac" in System.getProperty("os.name").lowercase()) pickWithMacDialog(parent) else pickWithSwing(parent)

private fun pickWithMacDialog(parent: Frame): String? {
    // Only the native macOS dialog looks right there, and it picks folders only via this property
    System.setProperty(MAC_DIRECTORY_DIALOG_PROPERTY, "true")
    try {
        val dialog = FileDialog(parent, "Choose a folder to scan", FileDialog.LOAD)
        dialog.isVisible = true
        val name = dialog.file ?: return null
        return File(dialog.directory, name).absolutePath
    } finally {
        System.setProperty(MAC_DIRECTORY_DIALOG_PROPERTY, "false")
    }
}

private fun pickWithSwing(parent: Frame): String? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Choose a folder to scan"
    }
    return if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
}
