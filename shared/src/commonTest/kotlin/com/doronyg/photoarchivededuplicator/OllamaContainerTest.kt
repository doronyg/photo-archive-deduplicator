package com.doronyg.photoarchivededuplicator

import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationPhase
import com.doronyg.photoarchivededuplicator.classification.OllamaSettings
import com.doronyg.photoarchivededuplicator.exif.ExifSource
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.scan.InventoryScanner
import com.doronyg.photoarchivededuplicator.scan.ScanEvent
import com.doronyg.photoarchivededuplicator.scan.createInMemoryDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class OllamaContainerTest {
    private val scanner = object : InventoryScanner {
        override suspend fun startScan(root: Path, onEvent: suspend (ScanEvent) -> Unit) = Unit
        override suspend fun resume(onEvent: suspend (ScanEvent) -> Unit) = Unit
    }

    @Test
    fun startupSettingsAndShutdownNeverCreateAnHttpClientWithoutAnExplicitJob() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var clients = 0
        val container = createAppContainer(dispatcher, dispatcher, openDriver = { createInMemoryDriver() },
            createScanner = { _, _ -> scanner },
            createOllamaClient = { clients++; error("HTTP must remain lazy") })
        container.uiPreferences.update { it.copy(ollama = OllamaSettings("http://ollama.test", "model")) }
        runCurrent()
        assertEquals(0, clients)
        container.shutdown()
        assertEquals(0, clients)
    }

    @Test
    fun explicitModelDiscoveryReusesOwnedClientWithoutSavingSettings() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var clients = 0
        var requests = 0
        lateinit var client: HttpClient
        val container = createAppContainer(dispatcher, dispatcher, openDriver = { createInMemoryDriver() },
            createScanner = { _, _ -> scanner },
            createOllamaClient = {
                clients++
                HttpClient(MockEngine {
                    requests++
                    respond("""{"models":[{"name":"installed:latest"}]}""")
                }).also { client = it }
            })
        try {
            assertFailsWith<IllegalArgumentException> { container.availableOllamaModels("") }
            assertEquals(0, clients)
            repeat(2) {
                assertEquals(listOf("installed:latest"), container.availableOllamaModels("http://ollama.test"))
            }
            assertEquals(1, clients)
            assertEquals(2, requests)
            assertEquals(OllamaSettings(), container.uiPreferences.state.value.ollama)
        } finally { container.shutdown() }
        assertFalse(client.coroutineContext[Job]!!.isActive)
        assertFailsWith<IllegalStateException> { container.availableOllamaModels("http://ollama.test") }
    }

    @Test
    fun shutdownCancelsModelDiscoveryBeforeClosingTheClient() = runTest(timeout = 15.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val started = CompletableDeferred<Unit>()
        var stopped = false
        val container = createAppContainer(dispatcher, dispatcher, openDriver = { createInMemoryDriver() },
            createScanner = { _, _ -> scanner },
            createOllamaClient = {
                HttpClient(MockEngine {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { stopped = true }
                })
            })
        val request = async { container.availableOllamaModels("http://ollama.test") }
        started.await()
        container.shutdown()
        assertTrue(stopped)
        assertFailsWith<CancellationException> { request.await() }
    }

    @Test
    fun shutdownCancelsClassificationBeforeClosingItsClientAndDatabase() = runTest(timeout = 15.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val started = CompletableDeferred<Unit>()
        var stopped = false
        lateinit var client: HttpClient
        val container = createAppContainer(dispatcher, dispatcher, openDriver = { createInMemoryDriver() },
            createScanner = { _, _ -> scanner },
            createExifSource = { ExifSource { error("AI must not extract EXIF") } },
            createOllamaClient = {
                HttpClient(MockEngine {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        stopped = true
                    }
                }).also { client = it }
            })
        val folder = "/Photos/ישראל".toPath()
        val files = listOf("a.jpg", "b.jpg").map {
            FileRecord(folder / it, folder, it, "jpg", 120_000, 1, 2)
        }
        assertTrue(container.directoryClassifications.start(listOf(files),
            OllamaSettings("http://ollama.test", "model").context()))
        started.await()
        container.shutdown()
        assertTrue(stopped)
        assertEquals(DirectoryClassificationPhase.CANCELLED, container.directoryClassifications.state.value.phase)
        assertFalse(client.coroutineContext[Job]!!.isActive)
        assertEquals(0, container.directoryClassifications.state.value.completed)
        assertFalse(container.directoryClassifications.start(listOf(files),
            OllamaSettings("http://ollama.test", "model").context()))
    }
}
