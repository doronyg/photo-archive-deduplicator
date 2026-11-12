package com.doronyg.photoarchivededuplicator.classification

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.IOException
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OllamaDirectoryClassifierTest {
    private val context = OllamaSettings("http://ollama.test/prefix/", "my-model").context()
    private fun response(content: String, done: Boolean = true, role: String = "assistant") =
        buildJsonObject {
            put("done", done)
            put("model", "my-model")
            put("message", buildJsonObject { put("role", role); put("content", content) })
        }.toString()

    @Test
    fun allLabelsAndUnicodeRoundTripWithPrivateSchemaConstrainedRequest() = runTest {
        for (label in DirectoryClassification.entries) {
            var requests = 0
            val client = HttpClient(MockEngine { request ->
                requests++
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("http://ollama.test/prefix/api/chat", request.url.toString())
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals(setOf("model", "stream", "think", "options", "format", "messages"), body.keys)
                assertEquals("my-model", body["model"]!!.jsonPrimitive.content)
                assertEquals(false, body["stream"]!!.jsonPrimitive.boolean)
                assertEquals(false, body["think"]!!.jsonPrimitive.boolean)
                assertEquals(0, body["options"]!!.jsonObject["temperature"]!!.jsonPrimitive.int)
                val schema = body["format"]!!.jsonObject
                assertEquals("object", schema["type"]!!.jsonPrimitive.content)
                assertEquals(false, schema["additionalProperties"]!!.jsonPrimitive.boolean)
                assertEquals(listOf("classification"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
                assertEquals(DirectoryClassification.entries.map { it.name },
                    schema["properties"]!!.jsonObject["classification"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
                val messages = body["messages"]!!.jsonArray
                assertEquals(2, messages.size)
                val system = messages[0].jsonObject
                assertEquals("system", system["role"]!!.jsonPrimitive.content)
                val prompt = system["content"]!!.jsonPrimitive.content
                assertFalse("preferredRoots" in prompt)
                assertFalse("configured roots" in prompt)
                for (evidence in listOf("family archives", "documents", "Bialik documents",
                    "mama's documents", "full directory path", "PROTECTED over UNKNOWN")) {
                    assertTrue(evidence in prompt, evidence)
                }
                for (word in listOf("data", "instructions", "Hebrew", "PROTECTED", "UNKNOWN", "BACKUP", "PREFERRED")) {
                    assertTrue(word in prompt)
                }
                assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
                val data = Json.parseToJsonElement(messages[1].jsonObject["content"]!!.jsonPrimitive.content).jsonObject
                assertEquals(setOf("directory", "directoryName"), data.keys)
                assertEquals("/Photos/טיול", data["directory"]!!.jsonPrimitive.content)
                assertEquals("טיול", data["directoryName"]!!.jsonPrimitive.content)
                respond(response("""{"classification":"${label.name}"}"""), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            })
            try {
                val classifier = OllamaDirectoryClassifier(client)
                assertEquals(0, requests)
                assertEquals(label, classifier.classify("/Photos/טיול".toPath(), context))
                assertEquals(1, requests)
            } finally { client.close() }
        }
    }

    @Test
    fun rejectsMalformedInnerAndOuterResponsesRatherThanReturningUnknown() = runTest {
        val invalid = listOf(
            "not JSON", "[]", "null", """{"classification":"backup"}""",
            """{"classification":"OTHER"}""", """{"classification":1}""",
            """{"classification":null}""", """{"classification":true}""",
            """{"classification":"BACKUP","extra":0}""", "{}",
            "```json\n{\"classification\":\"BACKUP\"}\n```",
        ).map(::response) + listOf(
            "not JSON", "[]", """{"done":true}""",
            response("""{"classification":"BACKUP"}""", done = false),
            response("""{"classification":"BACKUP"}""", role = "user"),
            """{"done":true,"message":{"role":"assistant","content":{}}}""",
            " ".repeat(65_537),
        )
        for (payload in invalid) {
            val client = HttpClient(MockEngine { respond(payload) })
            try {
                assertFailsWith<DirectoryClassificationException>(payload.take(80)) {
                    OllamaDirectoryClassifier(client).classify("/Photos/A".toPath(), context)
                }
            } finally { client.close() }
        }
    }

    @Test
    fun preferredIsAcceptedForAnyAbsoluteDirectoryWithoutConfiguredRoots() = runTest {
        val client = HttpClient(MockEngine { respond(response("""{"classification":"PREFERRED"}""")) })
        try {
            val classifier = OllamaDirectoryClassifier(client)
            val unconstrained = OllamaSettings(context.baseUrl, context.model).context()
            for (path in listOf("/Photos", "/Photos/A", "/Photos/B/../A", "/Other", "/PhotosOther/A")) {
                assertEquals(DirectoryClassification.PREFERRED, classifier.classify(path.toPath(), unconstrained))
            }
        } finally { client.close() }
    }

    @Test
    fun httpIoAndTimeoutFailuresAreExplicitButCancellationAndBugsPropagate() = runTest {
        val badStatus = HttpClient(MockEngine { respond("unavailable", HttpStatusCode.ServiceUnavailable) })
        try {
            assertFailsWith<DirectoryClassificationException> {
                OllamaDirectoryClassifier(badStatus).classify("/Photos".toPath(), context)
            }
        } finally { badStatus.close() }
        val failures: List<(HttpRequestData) -> Throwable> = listOf(
            { IOException("unavailable") }, { HttpRequestTimeoutException(it) },
            { CancellationException("request timeout", HttpRequestTimeoutException(it)) },
            { CancellationException("cancelled") }, { IllegalStateException("programming bug") },
        )
        for ((index, failure) in failures.withIndex()) {
            val client = HttpClient(MockEngine { throw failure(it) })
            try {
                when (index) {
                    0, 1, 2 -> assertFailsWith<DirectoryClassificationException> {
                        OllamaDirectoryClassifier(client).classify("/Photos".toPath(), context)
                    }
                    3 -> assertFailsWith<CancellationException> {
                        OllamaDirectoryClassifier(client).classify("/Photos".toPath(), context)
                    }
                    else -> assertFailsWith<IllegalStateException> {
                        OllamaDirectoryClassifier(client).classify("/Photos".toPath(), context)
                    }
                }
            } finally { client.close() }
        }
        val strictStatus = HttpClient(MockEngine { respond("unavailable", HttpStatusCode.BadRequest) }) {
            expectSuccess = true
        }
        try {
            assertFailsWith<DirectoryClassificationException> {
                OllamaDirectoryClassifier(strictStatus).classify("/Photos".toPath(), context)
            }
        } finally { strictStatus.close() }
    }

    @Test
    fun invalidConfigurationAndRelativeDirectoriesFailBeforeAnyRequest() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine { requests++; respond(response("""{"classification":"UNKNOWN"}""")) })
        try {
            val classifier = OllamaDirectoryClassifier(client)
            assertFailsWith<DirectoryClassificationException> { classifier.classify("relative".toPath(), context) }
            assertFailsWith<DirectoryClassificationException> {
                classifier.classify("/Photos".toPath(), context.copy(baseUrl = ""))
            }
            assertEquals(0, requests)
        } finally { client.close() }
    }

    @Test
    fun cancellingTheJobDuringARequestIsNeverConvertedIntoATechnicalFailure() = runTest {
        val started = CompletableDeferred<Unit>()
        var convertedCancellation = false
        val client = HttpClient(MockEngine { started.complete(Unit); awaitCancellation() })
        try {
            val job = async {
                try {
                    OllamaDirectoryClassifier(client).classify("/Photos".toPath(), context)
                } catch (failure: DirectoryClassificationException) {
                    convertedCancellation = true
                    throw failure
                }
            }
            started.await()
            job.cancel(CancellationException("user cancelled", HttpRequestTimeoutException("http://ollama.test", 1)))
            assertFailsWith<CancellationException> { job.await() }
            assertFalse(convertedCancellation)
        } finally { client.close() }
    }

    @Test
    fun installedRequestTimeoutRemainsARetryableTechnicalFailure() = runTest {
        val client = HttpClient(MockEngine { awaitCancellation() }) {
            install(HttpTimeout) { requestTimeoutMillis = 50 }
        }
        try {
            assertFailsWith<DirectoryClassificationException> {
                OllamaDirectoryClassifier(client).classify("/Photos".toPath(), context)
            }
        } finally { client.close() }
    }

    @Test
    fun ownedClientHasTimeoutsWithoutSendingRequests() {
        val client = createOllamaHttpClient()
        try {
            assertNotNull(client.plugin(HttpTimeout))
            client.config { assertFalse(followRedirects) }.close()
        } finally { client.close() }
    }

    @Test
    fun redirectsAreTechnicalFailuresAndNeverRepostPathsToAnotherServer() = runTest {
        for (status in listOf(HttpStatusCode.MovedPermanently, HttpStatusCode.Found,
            HttpStatusCode.SeeOther, HttpStatusCode.TemporaryRedirect, HttpStatusCode.PermanentRedirect)) {
            var requests = 0
            val client = HttpClient(MockEngine {
                requests++
                assertEquals("ollama.test", it.url.host)
                respond("", status, headersOf(HttpHeaders.Location, "http://unconfigured.test/api/chat"))
            }) {
                followRedirects = false
            }
            try {
                assertFailsWith<DirectoryClassificationException> {
                    OllamaDirectoryClassifier(client).classify("/Photos/private".toPath(), context)
                }
                assertEquals(1, requests)
            } finally { client.close() }
        }
    }

    @Test
    fun settingsRequireOnlyExplicitServerAndModel() {
        val settings = OllamaSettings("https://server.test/prefix///", " model ")
        val canonical = settings.context()
        assertEquals("https://server.test/prefix", canonical.baseUrl)
        assertEquals("model", canonical.model)
        assertEquals("3", canonical.promptVersion)
        for (url in listOf("", "file:///a", "server.test", "http://", "http:///path", "http://[",
            "http://server.test:bad", "http://u:p@server.test", "http://server.test?q=1", "http://server.test#x")) {
            assertFailsWith<IllegalArgumentException>(url) { settings.copy(baseUrl = url).context() }
        }
        assertFailsWith<IllegalArgumentException> { OllamaSettings().context() }
        assertFailsWith<IllegalArgumentException> { settings.copy(model = " ").context() }
    }
}
