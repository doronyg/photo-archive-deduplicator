package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.IoLane
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassification
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationContext
import com.doronyg.photoarchivededuplicator.recommendation.DeletionRecommendationService
import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendationRole
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.retention.RetentionScorer
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.scan.isInTree
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.FileMetadata
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import kotlin.time.Clock
import kotlin.uuid.Uuid

internal fun operationDestinationPath(source: Path, binRoot: Path): Path? {
    if (!source.isAbsolute || !binRoot.isAbsolute || source == source.root ||
        source != source.normalized() || binRoot != binRoot.normalized() ||
        binRoot.parent == null || source.isInTree(binRoot)) return null
    val original = if (source.root.toString() == "/") source.toString()
        else source.toString().replace('\\', '/')
    val relative = (if (original.getOrNull(1) == ':') original.removeRange(1, 2)
        else original).trimStart('/').toPath()
    return (binRoot / relative).takeIf { it.isInTree(binRoot) && it != binRoot }
}

/** Metadata-only proposal generation. This service has no filesystem mutation or AI API. */
internal class FileOperationService(
    private val repository: DirectoryRepository,
    private val proposals: FileOperationRepository,
    private val retention: RetentionScoreService,
    private val filesystemLane: IoLane,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val classificationContext: () -> DirectoryClassificationContext? = { null },
    private val admissionBlocked: () -> Boolean = { false },
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val nextId: () -> String = { Uuid.random().toString() },
) {
    suspend fun createProposal(selected: List<SelectedDeletionGroup>): FileOperationProposal =
        withContext(dispatcher) {
            if (admissionBlocked()) throw FileOperationException(FileOperationFinding(FileOperationIssueCode.BUSY))
            repository.scanning {
                if (admissionBlocked()) throw FileOperationException(FileOperationFinding(FileOperationIssueCode.BUSY))
                createInSession(selected)
            }
        }

    /** Caller holds the scan session for validation and subsequent execution. */
    suspend fun revalidateForExecution(proposal: FileOperationProposal): DirectoryClassificationContext? =
        withContext(dispatcher) {
            if (admissionBlocked()) throw FileOperationException(FileOperationFinding(FileOperationIssueCode.BUSY))
            if (proposal.validationStatus != FileOperationValidationStatus.READY || proposal.archiveRoot != null)
                throw FileOperationException(FileOperationFinding(FileOperationIssueCode.STALE_RECOMMENDATION))
            if (repository.binRoot() != proposal.binRoot)
                throw FileOperationException(FileOperationFinding(FileOperationIssueCode.BIN_ROOT_CHANGED))
            val context = classificationContext()
            val fresh = currentRecommendations(context)
            val selection = proposal.groups.map { group ->
                fresh[group.key] ?: throw FileOperationException(
                    FileOperationFinding(FileOperationIssueCode.STALE_RECOMMENDATION, detail = group.categoryName))
            }
            val current = createInSession(selection, save = false, freshRecommendations = fresh)
            val finding = current.findings.firstOrNull() ?: current.moves.flatMap { it.findings }.firstOrNull()
            if (finding != null) throw FileOperationException(finding)
            if (current.groups != proposal.groups || current.moves != proposal.moves || classificationContext() != context)
                throw FileOperationException(FileOperationFinding(FileOperationIssueCode.STALE_RECOMMENDATION))
            context
        }

    suspend fun validateBeforeMove(
        proposal: FileOperationProposal,
        move: ProposedFileMove,
        expectedContext: DirectoryClassificationContext?,
    ) {
        if (admissionBlocked()) throw FileOperationException(FileOperationFinding(FileOperationIssueCode.BUSY))
        if (repository.binRoot() != proposal.binRoot)
            throw FileOperationException(FileOperationFinding(FileOperationIssueCode.BIN_ROOT_CHANGED))
        if (classificationContext() != expectedContext)
            throw FileOperationException(FileOperationFinding(FileOperationIssueCode.STALE_RECOMMENDATION))
        check(move in proposal.moves) { "Move is not part of the approved proposal" }
        val groups = proposal.groups.filter { group -> group.members.any { it.file.path == move.sourcePath } }
        check(groups.isNotEmpty()) { "Move has no saved recommendation group" }
        val members = groups.flatMap { it.members }.distinctBy { it.file.path }
        val classifications = expectedContext?.let {
            repository.directoryClassifications(members.map { member -> member.file.directoryPath }.distinct(), it)
                .associateBy { record -> record.directoryPath }
        }.orEmpty()
        if (members.any { it.classification == DirectoryClassification.PROTECTED } ||
            classifications.values.any { it.classification == DirectoryClassification.PROTECTED })
            throw FileOperationException(FileOperationFinding(FileOperationIssueCode.PROTECTED_GROUP, move.sourcePath))
        if (members.any {
            operationClassificationEvidence(classifications[it.file.directoryPath]) != it.evidence["classification"]
        }) throw FileOperationException(FileOperationFinding(FileOperationIssueCode.STALE_RECOMMENDATION, move.sourcePath))
        val exclusions = repository.exclusions()
        if (exclusions.any { move.sourcePath.isInTree(it.path) || move.survivorPath?.isInTree(it.path) == true })
            throw FileOperationException(FileOperationFinding(FileOperationIssueCode.UNSAFE_PATH, move.sourcePath))
    }

    private suspend fun currentRecommendations(context: DirectoryClassificationContext?) =
        DeletionRecommendationService(repository, retention, dispatcher, context).load()
            .categories.flatMap { category -> category.groups.map { SelectedDeletionGroup(category.category, it) } }
            .associateBy { it.key }

    private suspend fun createInSession(
        selected: List<SelectedDeletionGroup>,
        save: Boolean = true,
        freshRecommendations: Map<String, SelectedDeletionGroup>? = null,
    ): FileOperationProposal {
        if (selected.isEmpty())
            throw FileOperationException(FileOperationFinding(FileOperationIssueCode.EMPTY_SELECTION))
        val binRoot = repository.binRoot()
            ?: throw FileOperationException(FileOperationFinding(FileOperationIssueCode.BIN_NOT_CONFIGURED))
        val bins = repository.registeredBinRoots()
        val context = classificationContext()
        val selection = selected.distinctBy { it.key }
        val createdAt = clock()
        val fresh = freshRecommendations ?: currentRecommendations(context)
        val findings = mutableListOf<FileOperationFinding>()
        for (group in selection) {
            if (fresh[group.key] != group)
                findings += FileOperationFinding(FileOperationIssueCode.STALE_RECOMMENDATION, detail = group.category.name)
        }
        val files = selection.flatMap { it.group.recommendation.files.values }.map { it.file }.distinctBy { it.path }
        val paths = files.map { it.path }
        val exif = repository.fileExif(paths, currentOnly = true).associateBy { it.input.path }
        val scores = repository.fileScores(paths, RetentionScorer().identity, currentOnly = true).associateBy { it.input.path }
        val classifications = context?.let {
            repository.directoryClassifications(files.map { file -> file.directoryPath }.distinct(), it)
                .associateBy { record -> record.directoryPath }
        }.orEmpty()
        val groups = selection.map { selectedGroup ->
            val group = selectedGroup.group
            if (group.recommendation.files.values.any {
                classifications[it.file.directoryPath]?.classification == DirectoryClassification.PROTECTED
            } || (group.survivors + group.candidates).any {
                it.directoryClassification?.classification == DirectoryClassification.PROTECTED
            }) findings += FileOperationFinding(FileOperationIssueCode.PROTECTED_GROUP, detail = selectedGroup.category.name)
            FileOperationGroup(selectedGroup.key, selectedGroup.category.id, selectedGroup.category.name,
                "${group.evidenceSummary}\n${group.recommendation.explanation}", group.recommendation.duplicateConfidence.score,
                group.recommendation.files.values.map { advice ->
                    val storedExif = exif[advice.file.path]
                    val classification = classifications[advice.file.directoryPath]
                    FileOperationMember(advice.file, advice.role, advice.explanation,
                        advice.retentionDecisionConfidence.score, advice.retainedReplacementPath,
                        storedExif?.result?.metadata?.capturedAt, classification?.classification,
                        operationEvidence(advice, storedExif, scores[advice.file.path], classification))
                })
        }
        val candidates = selection.flatMap { it.group.candidates }.map { it.advice }
        val sourcePaths = candidates.map { it.file.path }.toSet()
        val keptMembers = groups.flatMap { it.members }.filter {
            it.role in setOf(FileRecommendationRole.PREFERRED, FileRecommendationRole.PRESERVE)
        }
        val keptPaths = keptMembers.map { it.file.path }.toSet()
        val destinations = mutableSetOf<Path>()
        val moves = filesystemLane.run("validate dry-run proposal metadata") {
            val inspector = MetadataInspection()
            inspector.checkBin(binRoot, findings)
            for (member in keptMembers.distinctBy { it.file.path }) {
                currentCoroutineContext().ensureActive()
                if (member.file.path in sourcePaths)
                    findings += FileOperationFinding(FileOperationIssueCode.SURVIVOR_SELECTED, member.file.path)
                if (bins.any { member.file.path.isInTree(it) })
                    findings += FileOperationFinding(FileOperationIssueCode.SURVIVOR_IN_BIN, member.file.path)
                inspector.checkFile(member.file, survivor = true, findings)
            }
            candidates.groupBy { it.file.path }.map { (path, advice) ->
                currentCoroutineContext().ensureActive()
                val candidate = advice.first()
                val itemFindings = mutableListOf<FileOperationFinding>()
                if (advice.any { it != candidate })
                    itemFindings += FileOperationFinding(FileOperationIssueCode.STALE_RECOMMENDATION, path)
                val destination = operationDestinationPath(path, binRoot)
                if (destination == null)
                    itemFindings += FileOperationFinding(FileOperationIssueCode.UNSAFE_PATH, path)
                if (bins.any { path.isInTree(it) })
                    itemFindings += FileOperationFinding(FileOperationIssueCode.UNSAFE_PATH, path)
                val replacement = candidate.retainedReplacementPath
                if (replacement == null || replacement !in keptPaths || replacement in sourcePaths)
                    itemFindings += FileOperationFinding(FileOperationIssueCode.SURVIVOR_SELECTED, replacement)
                inspector.checkFile(candidate.file, survivor = false, itemFindings)
                if (destination != null) {
                    if (!destinations.add(destination))
                        itemFindings += FileOperationFinding(FileOperationIssueCode.DESTINATION_COLLISION, destination)
                    inspector.inspect(destination, itemFindings)?.let {
                        itemFindings += FileOperationFinding(FileOperationIssueCode.DESTINATION_EXISTS, destination)
                    }
                    inspector.checkAncestors(destination, itemFindings)
                }
                ProposedFileMove(path, destination?.relativeTo(binRoot), destination,
                    replacement, candidate.file.sizeBytes, itemFindings)
            }
        }
        if (repository.binRoot() != binRoot)
            findings += FileOperationFinding(FileOperationIssueCode.BIN_ROOT_CHANGED, binRoot)
        if (classificationContext() != context ||
            repository.fileExif(paths, currentOnly = true).associateBy { it.input.path } != exif ||
            repository.fileScores(paths, RetentionScorer().identity, currentOnly = true).associateBy { it.input.path } != scores ||
            (context?.let { repository.directoryClassifications(files.map { file -> file.directoryPath }.distinct(), it)
                .associateBy { record -> record.directoryPath } }.orEmpty()) != classifications)
            findings += FileOperationFinding(FileOperationIssueCode.STALE_RECOMMENDATION)
        return FileOperationProposal(nextId(), createdAt, clock(), null, binRoot, groups, moves,
            findings.distinct()).also { if (save) proposals.save(it) }
    }

    private inner class MetadataInspection {
        private val observations = mutableMapOf<Path, Observation>()

        fun inspect(path: Path, findings: MutableList<FileOperationFinding>): FileMetadata? {
            val observation = observations.getOrPut(path) {
                try {
                    Observation(fileSystem.metadataOrNull(path))
                } catch (failure: IOException) {
                    Observation(null, FileOperationFinding(FileOperationIssueCode.FILESYSTEM_ERROR, path, failure.message))
                }
            }
            observation.failure?.let { findings += it }
            return observation.metadata
        }

        fun checkAncestors(path: Path, findings: MutableList<FileOperationFinding>) {
            for (ancestor in generateSequence(path) { it.parent }) {
                val metadata = inspect(ancestor, findings)
                if (metadata != null && (metadata.symlinkTarget != null || (ancestor != path && !metadata.isDirectory))) {
                    findings += FileOperationFinding(FileOperationIssueCode.UNSAFE_PATH, ancestor)
                    return
                }
            }
        }

        fun checkBin(path: Path, findings: MutableList<FileOperationFinding>) {
            val metadata = inspect(path, findings)
            if (metadata != null && !metadata.isDirectory)
                findings += FileOperationFinding(FileOperationIssueCode.BIN_NOT_DIRECTORY, path)
            checkAncestors(path, findings)
        }

        fun checkFile(file: FileRecord, survivor: Boolean, findings: MutableList<FileOperationFinding>) {
            val metadata = inspect(file.path, findings)
            val missing = if (survivor) FileOperationIssueCode.SURVIVOR_MISSING else FileOperationIssueCode.SOURCE_MISSING
            val changed = if (survivor) FileOperationIssueCode.SURVIVOR_CHANGED else FileOperationIssueCode.SOURCE_CHANGED
            when {
                metadata == null -> findings += FileOperationFinding(missing, file.path)
                !metadata.isRegularFile || metadata.symlinkTarget != null ->
                    findings += FileOperationFinding(FileOperationIssueCode.SOURCE_NOT_REGULAR, file.path)
                metadata.size != file.sizeBytes ||
                    (file.modifiedAt != null && metadata.lastModifiedAtMillis != file.modifiedAt) ||
                    (file.createdAt != null && metadata.createdAtMillis != file.createdAt) ->
                    findings += FileOperationFinding(changed, file.path)
            }
            checkAncestors(file.path, findings)
        }
    }

    private data class Observation(val metadata: FileMetadata?, val failure: FileOperationFinding? = null)
}
