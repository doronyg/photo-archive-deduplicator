package com.doronyg.photoarchivededuplicator.classification

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OllamaModelsTest {
    @Test
    fun fetchesInstalledNamesWithoutModelConfigurationOrDirectoryData() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests++
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("https://ollama.test/prefix/api/tags", request.url.toString())
            respond("""{"models":[{"name":"z-model:latest","size":123},{"name":"a-model:7b"},{"name":"z-model:latest"}]}""")
        })
        try {
            assertEquals(0, requests)
            assertEquals(listOf("a-model:7b", "z-model:latest"),
                fetchOllamaModels(client, " https://ollama.test/prefix/ "))
            assertEquals(1, requests)
        } finally { client.close() }
    }

    @Test
    fun emptyListIsValidButMalformedResponsesAreFailures() = runTest {
        for (payload in listOf("""{"models":[]}""", "invalid", "{}", """{"models":null}""",
            """{"models":[{}]}""", """{"models":[{"name":""}]}""",
            """{"models":[{"name":1}]}""", " ".repeat(65_537))) {
            val client = HttpClient(MockEngine { respond(payload) })
            try {
                if (payload == """{"models":[]}""") {
                    assertEquals(emptyList(), fetchOllamaModels(client, "http://ollama.test"))
                } else {
                    assertFailsWith<DirectoryClassificationException> {
                        fetchOllamaModels(client, "http://ollama.test")
                    }
                }
            } finally { client.close() }
        }
    }

    @Test
    fun invalidUrlFailsBeforeAnyRequest() = runTest {
        val client = HttpClient(MockEngine { error("Must not contact server") })
        try {
            for (url in listOf("", "file:///private", "https://user:password@ollama.test", "https://ollama.test?query")) {
                assertFailsWith<IllegalArgumentException> { fetchOllamaModels(client, url) }
            }
        } finally { client.close() }
    }

    @Test
    fun serverFailuresAreExplicitAndCancellationPropagates() = runTest {
        val unavailable = HttpClient(MockEngine { respond("Offline", HttpStatusCode.ServiceUnavailable) })
        try {
            assertFailsWith<DirectoryClassificationException> { fetchOllamaModels(unavailable, "http://ollama.test") }
        } finally { unavailable.close() }
        val failed = HttpClient(MockEngine { throw IOException("Connection refused") })
        try {
            assertFailsWith<DirectoryClassificationException> { fetchOllamaModels(failed, "http://ollama.test") }
        } finally { failed.close() }
        val cancelled = HttpClient(MockEngine { throw CancellationException("Cancelled") })
        try {
            assertFailsWith<CancellationException> { fetchOllamaModels(cancelled, "http://ollama.test") }
        } finally { cancelled.close() }
    }

    @Test
    fun requestTimeoutIsAnExplicitFailure() = runTest {
        val client = HttpClient(MockEngine { awaitCancellation() }) {
            install(HttpTimeout) { requestTimeoutMillis = 50 }
        }
        try {
            assertFailsWith<DirectoryClassificationException> { fetchOllamaModels(client, "http://ollama.test") }
        } finally { client.close() }
    }
}
