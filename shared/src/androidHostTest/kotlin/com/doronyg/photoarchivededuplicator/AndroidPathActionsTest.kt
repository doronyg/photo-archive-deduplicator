package com.doronyg.photoarchivededuplicator

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import com.doronyg.photoarchivededuplicator.scan.TrackingIoDispatcher
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidPathActionsTest {
    @BeforeTest
    fun installMain() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun resetMain() = Dispatchers.resetMain()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun lookupLeavesCallerAndNativeCallbackRunsOnMain() = runTest {
        val main = TrackingIoDispatcher(StandardTestDispatcher(testScheduler))
        val io = TrackingIoDispatcher(StandardTestDispatcher(testScheduler))
        Dispatchers.setMain(main)
        try {
            val actions = AndroidPathActions(
                lookup = {
                    io.assertOnIo("MediaStore lookup")
                    assertFalse(main.isRunningTask)
                    MediaOpenTarget("content://media/42", "image/jpeg")
                },
                launch = {
                    main.assertOnIo("UI callback")
                    assertFalse(io.isRunningTask)
                },
                ioDispatcher = io,
            )
            withContext(Dispatchers.Main) { actions.openFile("/storage/a.jpg".toPath()) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun opensIndexedContentAndReportsMissingOrUnsupportedTargets() = runTest {
        val target = MediaOpenTarget("content://media/external/images/media/42", "image/jpeg")
        val opened = mutableListOf<MediaOpenTarget>()
        val actions = AndroidPathActions(
            lookup = { if (it.name == "a.jpg") target else null },
            launch = { opened += it },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        actions.openFile("/storage/a.jpg".toPath())
        assertEquals(listOf(target), opened)
        assertFailsWith<IllegalStateException> { actions.openFile("/storage/missing.jpg".toPath()) }
        assertFailsWith<UnsupportedOperationException> { actions.openFolder("/storage".toPath()) }
    }

    @Test
    fun permissionAndViewerErrorsAreNotHiddenAndFileUrisAreRejected() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val denied = AndroidPathActions(
            lookup = { throw SecurityException("Photo access denied") },
            launch = { error("Must not launch") },
            ioDispatcher = dispatcher,
        )
        assertEquals("Photo access denied", assertFailsWith<SecurityException> {
            denied.openFile("/storage/a.jpg".toPath())
        }.message)
        val missingViewer = AndroidPathActions(
            lookup = { MediaOpenTarget("content://media/external/images/media/42", "image/jpeg") },
            launch = { error("No image viewer installed") },
            ioDispatcher = dispatcher,
        )
        assertEquals("No image viewer installed", assertFailsWith<IllegalStateException> {
            missingViewer.openFile("/storage/a.jpg".toPath())
        }.message)
        val invalid = AndroidPathActions(
            lookup = { MediaOpenTarget("file:///storage/a.jpg", "image/jpeg") },
            launch = { error("Must not expose a file URI") },
            ioDispatcher = dispatcher,
        )
        assertEquals("A MediaStore content URI is required.", assertFailsWith<IllegalStateException> {
            invalid.openFile("/storage/a.jpg".toPath())
        }.message)
    }
}
