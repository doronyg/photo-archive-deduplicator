package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.strings.AppStrings
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
                AppStrings.inventory.invalidExportPath
            }
            if (index > 0) append('\n')
            append(path)
        }
    }
}
