package com.doronyg.photoarchivededuplicator.classification

import io.ktor.http.URLProtocol
import io.ktor.http.URLParserException
import io.ktor.http.Url
import okio.Path

enum class DirectoryClassification { PREFERRED, BACKUP, PROTECTED, UNKNOWN }

const val DIRECTORY_CLASSIFICATION_PROMPT_VERSION: String = "3"

data class OllamaSettings(
    val baseUrl: String = "",
    val model: String = "",
) {
    fun context(): DirectoryClassificationContext {
        val server = ollamaBaseUrl(baseUrl)
        require(model.isNotBlank()) { "An explicit Ollama model is required" }
        return DirectoryClassificationContext(server, model.trim())
    }
}

internal fun ollamaBaseUrl(baseUrl: String): String {
    val server = baseUrl.trim()
    require(server.startsWith("http://", true) || server.startsWith("https://", true)) {
        "An explicit HTTP(S) Ollama URL is required"
    }
    require(server.substringAfter("://").substringBefore('/').isNotBlank() &&
        server.none { it.isWhitespace() }) { "An explicit Ollama host is required" }
    val url = try {
        Url(server)
    } catch (failure: URLParserException) {
        throw IllegalArgumentException("Invalid Ollama URL", failure)
    }
    require(url.protocol == URLProtocol.HTTP || url.protocol == URLProtocol.HTTPS)
    require(url.host.isNotBlank() && url.user == null && url.password == null &&
        '?' !in server && '#' !in server) { "Ollama URL cannot contain credentials, query, or fragment" }
    require(url.host.none { it in "[]\\" } ||
        (url.host.startsWith("[") && url.host.endsWith("]") &&
            url.host.substring(1, url.host.length - 1).let { ':' in it && it.none { char -> char in "[]\\" } })) {
        "Invalid Ollama host"
    }
    return url.toString().trimEnd('/')
}

data class DirectoryClassificationContext(
    val baseUrl: String,
    val model: String,
    val promptVersion: String = DIRECTORY_CLASSIFICATION_PROMPT_VERSION,
)

data class DirectoryClassificationRecord(
    val directoryPath: Path,
    val originalPath: String,
    val classification: DirectoryClassification,
    val context: DirectoryClassificationContext,
    val classifiedAt: Long,
)

fun interface DirectoryClassifier {
    suspend fun classify(directory: Path, context: DirectoryClassificationContext): DirectoryClassification
}

class DirectoryClassificationException(message: String, cause: Throwable? = null) : Exception(message, cause)
