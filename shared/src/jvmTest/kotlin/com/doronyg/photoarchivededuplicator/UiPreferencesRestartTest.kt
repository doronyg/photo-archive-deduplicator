package com.doronyg.photoarchivededuplicator

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.doronyg.photoarchivededuplicator.db.FilesDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiPreferencesRestartTest {
    @Test
    fun shutdownWaitsForAlreadyAdmittedEditAndRejectsLateEditsWithoutChangingState() = runBlocking {
        withTimeout(10_000) {
            val container = createAppContainer(
                openDriver = { JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
                    FilesDatabase.Schema.synchronous().create(it)
                } },
                createScanner = { _, _ -> preferencesTestScanner },
            )
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            try {
                coroutineScope {
                    val edit = launch(Dispatchers.Default) {
                        container.uiPreferences.update {
                            entered.complete(Unit)
                            check(release.await(5, TimeUnit.SECONDS))
                            it.copy(scanRoot = "/accepted")
                        }
                    }
                    entered.await()
                    val drain = async(start = CoroutineStart.UNDISPATCHED) { container.uiPreferences.shutdown() }
                    assertFalse(drain.isCompleted)
                    assertFailsWith<IllegalStateException> {
                        container.uiPreferences.update { it.copy(logFilter = "late") }
                    }
                    release.countDown()
                    edit.join()
                    drain.await()
                    assertEquals(UiChoices(scanRoot = "/accepted"), container.repository.loadUiChoices())
                    assertTrue(container.uiPreferences.state.value.logFilter.isEmpty())
                }
            } finally {
                release.countDown()
                container.shutdown()
            }
        }
    }

    @Test
    fun simultaneousUpdatesAreAtomicAndShutdownRejectsNewEdits() = runBlocking {
        withTimeout(10_000) {
            val container = createAppContainer(
                openDriver = { JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
                    FilesDatabase.Schema.synchronous().create(it)
                } },
                createScanner = { _, _ -> preferencesTestScanner },
            )
            try {
                container.uiPreferences.update { it.copy(scanRoot = "0") }
                coroutineScope {
                    repeat(8) {
                        launch(Dispatchers.Default) {
                            repeat(200) {
                                container.uiPreferences.update {
                                    it.copy(scanRoot = (it.scanRoot.toInt() + 1).toString())
                                }
                            }
                        }
                    }
                }
                container.uiPreferences.shutdown()
                assertEquals("1600", container.uiPreferences.state.value.scanRoot)
                assertEquals(container.uiPreferences.state.value, container.repository.loadUiChoices())
            } finally {
                container.shutdown()
            }
        }
    }

    @Test
    fun everyChoiceSurvivesActualSqliteCloseAndReopenAndShrinkingBrowseStack() = runBlocking {
        withTimeout(10_000) {
            val directory = File("build/test-ui-preferences/${UUID.randomUUID()}").absoluteFile
            check(directory.mkdirs())
            val url = "jdbc:sqlite:${File(directory, "preferences.db")}"
            suspend fun open() = createAppContainer(
                openDriver = { JdbcSqliteDriver(url, schema = FilesDatabase.Schema.synchronous()) },
                createScanner = { _, _ -> preferencesTestScanner },
            )
            try {
                val first = open()
                try {
                    first.uiPreferences.update { allUiChoices }
                } finally {
                    first.shutdown()
                }
                val second = open()
                try {
                    assertEquals(allUiChoices, second.uiPreferences.state.value)
                    second.uiPreferences.update { it.copy(browseStack = listOf("/new\n雪")) }
                } finally {
                    second.shutdown()
                }
                val third = open()
                try {
                    assertEquals(allUiChoices.copy(browseStack = listOf("/new\n雪")), third.uiPreferences.state.value)
                    third.uiPreferences.update { it.copy(browseStack = emptyList()) }
                } finally {
                    third.shutdown()
                }
                val fourth = open()
                try {
                    assertEquals(allUiChoices.copy(browseStack = emptyList()), fourth.uiPreferences.state.value)
                } finally {
                    fourth.shutdown()
                }
            } finally {
                directory.deleteRecursively()
            }
        }
    }
}
