package com.doronyg.photoarchivededuplicator.classification

import com.doronyg.photoarchivededuplicator.DiagnosticLogger
import com.doronyg.photoarchivededuplicator.Diagnostics
import com.doronyg.photoarchivededuplicator.LogCounter
import com.doronyg.photoarchivededuplicator.LogTags
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import okio.IOException
import kotlin.time.TimeSource

internal suspend fun fetchOllamaModels(
    client: HttpClient,
    baseUrl: String,
    logger: DiagnosticLogger = Diagnostics.logger,
): List<String> {
    val endpoint = "${ollamaBaseUrl(baseUrl)}/api/tags"
    return ollamaResponse(client.prepareGet(endpoint), "GET $endpoint", logger = logger) { text ->
        val response = Json.parseToJsonElement(text) as? JsonObject ?: invalidModels()
        val models = response["models"] as? JsonArray ?: invalidModels()
        models.map { entry ->
            val name = (entry as? JsonObject)?.get("name") as? JsonPrimitive ?: invalidModels()
            if (!name.isString || name.content.isBlank()) invalidModels()
            name.content
        }.distinct().sorted()
    }
}

private fun invalidModels(): Nothing = throw DirectoryClassificationException("Invalid Ollama model list response")

private val ollamaRequestIds = LogCounter()

internal fun ollamaLogPreview(text: String): String {
    val escaped = JsonPrimitive(text.take(4096)).toString()
    return if (text.length > 4096 || escaped.length > 4096) {
        escaped.take(4096) + "... [truncated; ${text.length} chars]"
    } else escaped
}

internal suspend fun <T> ollamaResponse(
    statement: HttpStatement,
    description: String,
    requestBody: String? = null,
    logger: DiagnosticLogger = Diagnostics.logger,
    parse: (String) -> T,
): T {
    val label = "request#${ollamaRequestIds.increment()} $description"
    return logger.operation(LogTags.OLLAMA, label) {
        val started = TimeSource.Monotonic.markNow()
        logger.d(LogTags.OLLAMA, "REQUEST $label body=${requestBody?.let(::ollamaLogPreview) ?: "<none>"}")
        try {
            statement.execute { response ->
                logger.i(LogTags.OLLAMA, "RESPONSE $label status=${response.status.value} duration=${started.elapsedNow()}")
                // Read at most one byte beyond the limit, including for chunked responses.
                val limit = 64 * 1024
                val bytes = ByteArray(limit + 1)
                var size = 0
                val channel = response.bodyAsChannel()
                while (size < bytes.size) {
                    val read = channel.readAvailable(bytes, size, bytes.size - size)
                    if (read == -1) break
                    size += read
                }
                if (size > limit) throw DirectoryClassificationException("Ollama response exceeds 64 KiB")
                val text = bytes.decodeToString(0, size)
                logger.d(LogTags.OLLAMA, "RESPONSE BODY $label bytes=$size body=${ollamaLogPreview(text)}")
                if (!response.status.isSuccess()) {
                    throw DirectoryClassificationException("Ollama HTTP status ${response.status.value}")
                }
                parse(text)
            }
        } catch (failure: HttpRequestTimeoutException) {
            currentCoroutineContext().ensureActive()
            throw DirectoryClassificationException("Ollama request timed out", failure)
        } catch (failure: CancellationException) {
            currentCoroutineContext().ensureActive()
            // Some engines expose the request's timeout as the cancellation's cause.
            val timeout = generateSequence(failure.cause) { it.cause }.firstOrNull {
                it is HttpRequestTimeoutException || it is ConnectTimeoutException || it is SocketTimeoutException
            }
            if (timeout != null) throw DirectoryClassificationException("Ollama request timed out", timeout)
            throw failure
        } catch (failure: ResponseException) {
            throw DirectoryClassificationException("Ollama HTTP request failed", failure)
        } catch (failure: IOException) {
            currentCoroutineContext().ensureActive()
            throw DirectoryClassificationException("Ollama connection failed", failure)
        } catch (failure: SerializationException) {
            throw DirectoryClassificationException("Invalid Ollama JSON response", failure)
        }
    }
}
