package com.doronyg.photoarchivededuplicator.ui

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal suspend fun formatInventoryFilePaths(
    paths: List<String>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): String = withContext(dispatcher) {
    val context = currentCoroutineContext()
    buildString {
        paths.forEachIndexed { index, path ->
            context.ensureActive()
            require('\n' !in path && '\r' !in path) {
                "A stored file path contains a line break and cannot be exported as one path per line."
            }
            if (index > 0) append('\n')
            append(path)
        }
    }
}
