package com.doronyg.photoarchivededuplicator.operations

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.FileMetadata
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal data class OperationMetadataProgress(
    val completed: Int,
    val total: Int,
    val metadataReads: Int,
    val skippedMissing: Int,
    val blocked: Int,
    val currentPath: Path? = null,
    val finished: Boolean = false,
)

/** One metadata-only pass over planned paths, called on the filesystem lane. */
internal class OperationMetadataInspection(
    private val fileSystem: FileSystem,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val onProgress: (OperationMetadataProgress) -> Unit = {},
) {
    private class Node(val path: Path, val parent: Node?) {
        val name = path.name
        val children = mutableListOf<Node>()
    }

    private data class Observation(
        val metadata: FileMetadata?,
        val failure: FileOperationFinding? = null,
        val ancestorFinding: FileOperationFinding? = null,
    ) {
        fun childProblem(path: Path): FileOperationFinding? = ancestorFinding ?: failure ?:
            metadata?.takeIf { it.symlinkTarget != null || !it.isDirectory }
                ?.let { FileOperationFinding(FileOperationIssueCode.UNSAFE_PATH, path) }
    }

    private val observations = mutableMapOf<Path, Observation>()
    private var prepared = false

    suspend fun prepare(paths: Iterable<Path>) {
        check(!prepared && observations.isEmpty()) { "Create a fresh metadata inspection for each run" }
        val context = currentCoroutineContext()
        val nodes = mutableMapOf<Path, Node>()
        val roots = mutableListOf<Node>()
        for (path in paths) {
            context.ensureActive()
            val missing = mutableListOf<Path>()
            var ancestor: Path? = path
            while (ancestor != null && ancestor !in nodes) {
                missing += ancestor
                ancestor = ancestor.parent
            }
            var parent = ancestor?.let { nodes.getValue(it) }
            for (part in missing.asReversed()) {
                val node = Node(part, parent)
                nodes[part] = node
                if (parent == null) roots += node else parent.children += node
                parent = node
            }
        }
        var reads = 0
        var skipped = 0
        var blocked = 0
        var lastProgress = timeSource.markNow()
        fun report(path: Path? = null, finished: Boolean = false) {
            onProgress(OperationMetadataProgress(observations.size, nodes.size, reads, skipped, blocked, path, finished))
            lastProgress = timeSource.markNow()
        }
        report()
        val stack = ArrayDeque<Node>()
        roots.sortedByDescending { it.name }.forEach { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            context.ensureActive()
            val node = stack.removeLast()
            val parent = node.parent?.let { observations.getValue(it.path) }
            val problem = node.parent?.let { parent?.childProblem(it.path) }
            val observation = when {
                problem != null -> {
                    blocked++
                    Observation(null, ancestorFinding = problem)
                }
                parent != null && parent.metadata == null -> {
                    skipped++
                    Observation(null)
                }
                else -> {
                    reads++
                    try {
                        Observation(fileSystem.metadataOrNull(node.path))
                    } catch (failure: IOException) {
                        Observation(null, FileOperationFinding(FileOperationIssueCode.FILESYSTEM_ERROR,
                            node.path, failure.message))
                    }
                }
            }
            observations[node.path] = observation
            node.children.sortedByDescending { it.name }.forEach { stack.addLast(it) }
            if (lastProgress.elapsedNow() >= 1.seconds) report(node.path)
        }
        prepared = true
        report(finished = true)
    }

    fun inspect(path: Path, findings: MutableList<FileOperationFinding>): FileMetadata? {
        val observation = observation(path)
        observation.failure?.let { findings += it }
        return observation.metadata
    }

    fun checkAncestors(path: Path, findings: MutableList<FileOperationFinding>) {
        val observation = observation(path)
        observation.ancestorFinding?.let { findings += it }
        observation.failure?.let { findings += it }
        if (observation.metadata?.symlinkTarget != null)
            findings += FileOperationFinding(FileOperationIssueCode.UNSAFE_PATH, path)
    }

    private fun observation(path: Path): Observation {
        check(prepared) { "Prepare the metadata inspection before validating paths" }
        return observations.getValue(path)
    }
}
