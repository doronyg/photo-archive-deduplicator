package com.doronyg.photoarchivededuplicator

import com.doronyg.photoarchivededuplicator.scan.TrackingIoDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DesktopPathActionsTest {
    @Test
    fun diagnosticFolderOpensWithoutLoadingTheInventory() = runTest {
        val opened = mutableListOf<URI>()
        val diagnostics = DesktopPathActions(
            kind = { true },
            launch = { opened += it },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val actions = desktopPathActions(
            loadContainer = { error("Database startup failed") },
            diagnosticActions = diagnostics,
        )
        val folder = diagnosticLogDirectory()!!.toPath()
        actions.openFolder(folder)
        assertEquals(folder.toString(), opened.single().path)
        assertFailsWith<IllegalStateException> { actions.openFolder("/ordinary".toPath()) }
    }

    @Test
    fun filesystemProbesAndBlockingNativeOpenLeaveCaller() = runTest {
        val io = TrackingIoDispatcher(StandardTestDispatcher(testScheduler))
        val ui = TrackingIoDispatcher(StandardTestDispatcher(testScheduler))
        val actions = DesktopPathActions(
            kind = {
                io.assertOnIo("file type probe")
                assertFalse(ui.isRunningTask)
                false
            },
            launch = {
                io.assertOnIo("blocking Desktop.open")
                assertFalse(ui.isRunningTask)
            },
            ioDispatcher = io,
        )
        withContext(ui) { actions.openFile("/photos/a.jpg".toPath()) }
    }

    @Test
    fun nativeTargetsAreEscapedAndMissingPathsFail() = runTest {
        val opened = mutableListOf<URI>()
        val actions = DesktopPathActions(
            kind = { if (it.name == "missing") null else it.name == "folder #1" },
            launch = { opened += it },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        actions.openFile("/photos/a #?é.jpg".toPath())
        actions.openFolder("/photos/folder #1".toPath())
        assertEquals("file:/photos/a%20%23%3Fé.jpg", opened[0].toString())
        assertEquals("/photos/folder #1", opened[1].path)
        assertFailsWith<IllegalStateException> { actions.openFile("/photos/missing".toPath()) }
        assertFailsWith<IllegalStateException> { actions.openFolder("/photos/a.jpg".toPath()) }
    }

    @Test
    fun nativeLaunchErrorsAreNotHidden() = runTest {
        val actions = DesktopPathActions(
            kind = { false },
            launch = { error("No default application") },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        assertEquals("No default application", assertFailsWith<IllegalStateException> {
            actions.openFile("/photos/a.jpg".toPath())
        }.message)
    }
}
