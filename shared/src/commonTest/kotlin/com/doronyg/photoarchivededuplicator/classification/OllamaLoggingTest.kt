package com.doronyg.photoarchivededuplicator.classification

import com.doronyg.photoarchivededuplicator.DiagnosticLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OllamaLoggingTest {
    @Test
    fun logsRequestBeforeServerRespondsThenResponseAndDuration() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val logger = DiagnosticLogger(dispatcher, sink = {}, sinkDispatcher = dispatcher)
        val started = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine {
            started.complete(Unit)
            reply.await()
            respond("""{"done":true,"message":{"role":"assistant","content":"{\"classification\":\"PROTECTED\"}"}}""")
        })
        try {
            val job = async {
                OllamaDirectoryClassifier(client, logger).classify("/Photos/טיול".toPath(),
                    OllamaSettings("http://ollama.test", "chosen-model").context())
            }
            started.await()
            advanceTimeBy(201)
            runCurrent()
            val pending = logger.state.value.entries
            assertTrue(pending.all { it.tag == "Ollama" })
            assertTrue(pending.any { it.message.contains("BEGIN") && it.message.contains("POST http://ollama.test/api/chat") })
            assertTrue(pending.any { it.message.contains("REQUEST") && it.message.contains("chosen-model") && it.message.contains("טיול") })
            assertFalse(pending.any { it.message.contains("RESPONSE") })
            reply.complete(Unit)
            assertEquals(DirectoryClassification.PROTECTED, job.await())
            advanceTimeBy(201)
            runCurrent()
            val messages = logger.state.value.entries.map { it.message }
            assertTrue(messages.any { it.contains("RESPONSE") && it.contains("status=200") && it.contains("duration=") })
            assertTrue(messages.any { it.contains("RESPONSE BODY") && it.contains("PROTECTED") })
            assertTrue(messages.any { it.contains("END") && it.contains("duration=") })
        } finally { client.close(); logger.close() }
    }

    @Test
    fun logsBoundedEscapedErrorResponseAndFailure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val logger = DiagnosticLogger(dispatcher, sink = {}, sinkDispatcher = dispatcher)
        val client = HttpClient(MockEngine { respond("bad model\n" + "x".repeat(12_000), HttpStatusCode.NotFound) })
        try {
            assertFailsWith<DirectoryClassificationException> {
                fetchOllamaModels(client, "http://ollama.test", logger)
            }
            advanceTimeBy(201)
            runCurrent()
            val messages = logger.state.value.entries.map { it.message }
            assertTrue(messages.any { it.contains("GET http://ollama.test/api/tags") })
            assertTrue(messages.any { it.contains("status=404") })
            val body = messages.single { it.contains("RESPONSE BODY") }
            assertTrue("\\n" in body)
            assertFalse('\n' in body)
            assertTrue("truncated" in body)
            assertTrue(body.length < 8192)
            assertTrue(messages.any { it.contains("FAIL") && it.contains("404") })
        } finally { client.close(); logger.close() }
    }

    @Test
    fun cancellationIsLoggedWithoutBecomingSuccess() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val logger = DiagnosticLogger(dispatcher, sink = {}, sinkDispatcher = dispatcher)
        val started = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine { started.complete(Unit); awaitCancellation() })
        try {
            val job = async {
                ollamaResponse(client.prepareGet("http://ollama.test/api/tags"),
                    "GET http://ollama.test/api/tags", logger = logger) { it }
            }
            started.await()
            job.cancel()
            assertFailsWith<CancellationException> { job.await() }
            advanceTimeBy(201)
            runCurrent()
            val messages = logger.state.value.entries.map { it.message }
            assertTrue(messages.any { it.contains("CANCEL") && it.contains("duration=") })
            assertFalse(messages.any { it.contains("END") })
        } finally { client.close(); logger.close() }
    }
}
