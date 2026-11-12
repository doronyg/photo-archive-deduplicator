package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.Diagnostics
import com.doronyg.photoarchivededuplicator.IoLane
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassification
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationContext
import com.doronyg.photoarchivededuplicator.recommendation.ConfidenceAssessment
import com.doronyg.photoarchivededuplicator.recommendation.DeletionRecommendationService
import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendation
import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendationRole
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.retention.RetentionScorer
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.scan.isBinMarkerMetadata
import com.doronyg.photoarchivededuplicator.scan.isHistoryMetadata
import com.doronyg.photoarchivededuplicator.scan.isInTree
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.FileSystem
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
    suspend fun resolveDuplicateSelections(selections: List<DuplicateDeletionSelection>): List<SelectedDeletionGroup> =
        withContext(dispatcher) {
            if (admissionBlocked()) throw FileOperationException(FileOperationFinding(FileOperationIssueCode.BUSY))
            repository.scanning {
                if (admissionBlocked()) throw FileOperationException(FileOperationFinding(FileOperationIssueCode.BUSY))
                val context = classificationContext()
                val index = duplicateSnapshots(selections, context)
                val groups = selections.map { selection ->
                    val snapshot = index[duplicateDeletionGroupId(selection)]
                        ?: throw FileOperationException(FileOperationFinding(FileOperationIssueCode.STALE_RECOMMENDATION,
                            detail = "The original duplicate group is no longer current."))
                    duplicateDeletionGroup(selection, snapshot)
                }
                if (classificationContext() != context)
                    throw FileOperationException(FileOperationFinding(FileOperationIssueCode.STALE_RECOMMENDATION))
                groups
            }
        }

    suspend fun createProposal(selected: List<SelectedDeletionGroup>): FileOperationProposal =
        withContext(dispatcher) {
            if (admissionBlocked()) throw FileOperationException(FileOperationFinding(FileOperationIssueCode.BUSY), global = true)
            repository.scanning {
                if (admissionBlocked()) throw FileOperationException(FileOperationFinding(FileOperationIssueCode.BUSY), global = true)
                createInSession(selected)
            }
        }

    /** Caller holds the scan session for validation and subsequent execution. */
    suspend fun revalidateForExecution(
        proposal: FileOperationProposal,
        checkCancellation: () -> Unit = {},
    ): FileOperationExecutionValidation =
        withContext(dispatcher) {
            checkCancellation()
            if (admissionBlocked()) throw FileOperationException(FileOperationFinding(FileOperationIssueCode.BUSY), global = true)
            if (!proposal.canExecute || proposal.archiveRoot != null)
                throw FileOperationException(FileOperationFinding(FileOperationIssueCode.STALE_RECOMMENDATION), global = true)
            if (repository.binRoot() != proposal.binRoot)
                throw FileOperationException(FileOperationFinding(FileOperationIssueCode.BIN_ROOT_CHANGED), global = true)
            val context = classificationContext()
            val fresh = currentRecommendations(context, proposal.groups.mapNotNull { it.duplicateProvenance?.selection },
                includeProviders = proposal.groups.any { it.duplicateProvenance == null })
            checkCancellation()
            val current = snapshotGroups(proposal.groups.mapNotNull { fresh[it.key] }, context)
                .associateBy { it.key }
            val classifications = context?.let {
                repository.directoryClassifications(proposal.groups.flatMap { group ->
                    group.members.map { member -> member.file.directoryPath }
                }.distinct(), it).associateBy { record -> record.directoryPath }
            }.orEmpty()
            val failures = linkedMapOf<String, List<FileOperationFinding>>()
            val eligible = proposal.executionEligibility
            for (group in proposal.groups) {
                checkCancellation()
                val problems = group.findings + group.members.flatMap { eligible.rejected[it.file.path].orEmpty() }
                val snapshot = current[group.key]
                val protected = if (group.members.any {
                    it.classification == DirectoryClassification.PROTECTED ||
                        classifications[it.file.directoryPath]?.classification == DirectoryClassification.PROTECTED
                } && !(group.allowsSingleProtectedDirectory() && snapshot?.allowsSingleProtectedDirectory() == true &&
                    group.members.all {
                        classifications[it.file.directoryPath]?.classification == DirectoryClassification.PROTECTED
                    })) listOf(FileOperationFinding(FileOperationIssueCode.PROTECTED_GROUP, detail = group.categoryName))
                    else emptyList()
                failures[group.key] = (problems + protected + when {
                    snapshot == null -> listOf(FileOperationFinding(FileOperationIssueCode.STALE_RECOMMENDATION,
                        detail = group.categoryName))
                    snapshot.copy(findings = emptyList()) != group.copy(findings = emptyList()) ->
                        snapshot.findings + FileOperationFinding(FileOperationIssueCode.STALE_RECOMMENDATION,
                            detail = group.categoryName)
                    else -> snapshot.findings
                }).distinct()
            }
            val global = mutableListOf<FileOperationFinding>()
            val checked = validateMetadata(proposal.groups, proposal.moves, proposal.binRoot,
                repository.registeredBinRoots(), global, checkCancellation)
            global.firstOrNull()?.let { throw FileOperationException(it, global = true) }
            val bySource = checked.associateBy { it.sourcePath }
            val endSnapshots = snapshotGroups(proposal.groups.mapNotNull { fresh[it.key] }, context).associateBy { it.key }
            for (group in proposal.groups) {
                checkCancellation()
                failures[group.key] = (failures[group.key].orEmpty() +
                    group.members.flatMap { bySource[it.file.path]?.findings.orEmpty() } +
                    if (endSnapshots[group.key] != current[group.key])
                        listOf(FileOperationFinding(FileOperationIssueCode.STALE_RECOMMENDATION,
                            detail = group.categoryName)) else emptyList()).distinct()
            }
            if (classificationContext() != context)
                throw FileOperationException(FileOperationFinding(FileOperationIssueCode.STALE_RECOMMENDATION), global = true)
            if (repository.binRoot() != proposal.binRoot)
                throw FileOperationException(FileOperationFinding(FileOperationIssueCode.BIN_ROOT_CHANGED), global = true)
            val sources = proposal.moves.mapTo(mutableSetOf()) { it.sourcePath }
            val groupsBySource = mutableMapOf<Path, MutableSet<String>>()
            val sourcesByGroup = proposal.groups.associate { group ->
                group.key to group.members.map { it.file.path }.filter { it in sources }.also { paths ->
                    for (path in paths) groupsBySource.getOrPut(path) { mutableSetOf() } += group.key
                }
            }
            val queue = ArrayDeque(failures.filterValues { it.isNotEmpty() }.keys)
            while (queue.isNotEmpty()) {
                val key = queue.removeFirst()
                for (path in sourcesByGroup[key].orEmpty()) for (related in groupsBySource[path].orEmpty())
                    if (failures[related].isNullOrEmpty()) {
                        failures[related] = failures.getValue(key)
                        queue.addLast(related)
                    }
            }
            FileOperationExecutionValidation(context, failures.filterValues { it.isNotEmpty() })
        }

    fun executionMoveValidator(
        proposal: FileOperationProposal,
        validation: FileOperationExecutionValidation,
    ): suspend (ProposedFileMove) -> Unit {
        val moves = proposal.moves.associateBy { it.sourcePath }
        val groupsByPath = proposal.groups.flatMap { group -> group.members.map { it.file.path to group } }
            .groupBy({ it.first }, { it.second })
        return { move ->
            val groups = groupsByPath[move.sourcePath].orEmpty()
            validateBeforeMove(proposal, move, validation.classificationContext, moves[move.sourcePath],
                groups, groups.firstNotNullOfOrNull { validation.groupFindings[it.key]?.firstOrNull() })
        }
    }

    private suspend fun validateBeforeMove(
        proposal: FileOperationProposal,
        move: ProposedFileMove,
        expectedContext: DirectoryClassificationContext?,
        approvedMove: ProposedFileMove?,
        groups: List<FileOperationGroup>,
        groupFailure: FileOperationFinding?,
    ) {
        if (admissionBlocked()) throw FileOperationException(FileOperationFinding(FileOperationIssueCode.BUSY), global = true)
        if (repository.binRoot() != proposal.binRoot)
            throw FileOperationException(FileOperationFinding(FileOperationIssueCode.BIN_ROOT_CHANGED), global = true)
        if (classificationContext() != expectedContext)
            throw FileOperationException(FileOperationFinding(FileOperationIssueCode.STALE_RECOMMENDATION), global = true)
        groupFailure?.let { throw FileOperationException(it) }
        check(move == approvedMove) { "Move is not part of the approved proposal" }
        check(groups.isNotEmpty()) { "Move has no saved recommendation group" }
        val members = groups.flatMap { it.members }.distinctBy { it.file.path }
        val classifications = expectedContext?.let {
            repository.directoryClassifications(members.map { member -> member.file.directoryPath }.distinct(), it)
                .associateBy { record -> record.directoryPath }
        }.orEmpty()
        if ((members.any { it.classification == DirectoryClassification.PROTECTED } ||
            classifications.values.any { it.classification == DirectoryClassification.PROTECTED }) &&
            !groups.all { group -> group.allowsSingleProtectedDirectory() && group.members.all {
                classifications[it.file.directoryPath]?.classification == DirectoryClassification.PROTECTED
            } })
            throw FileOperationException(FileOperationFinding(FileOperationIssueCode.PROTECTED_GROUP, move.sourcePath))
        if (members.any {
            operationClassificationEvidence(classifications[it.file.directoryPath]) != it.evidence["classification"]
        }) throw FileOperationException(FileOperationFinding(FileOperationIssueCode.STALE_RECOMMENDATION, move.sourcePath))
        if (groups.any { it.allowsSingleProtectedDirectory() }) {
            val paths = members.map { it.file.path }
            val exif = repository.fileExif(paths, currentOnly = true).associateBy { it.input.path }
            val scores = repository.fileScores(paths, RetentionScorer().identity, currentOnly = true).associateBy { it.input.path }
            for (member in members) {
                // Earlier journal batches remove completed candidates from the inventory.
                val current = repository.file(member.file.path)
                if (current == null && member.role == FileRecommendationRole.REDUNDANT_CANDIDATE &&
                    member.file.path != move.sourcePath) continue
                val evidence = operationEvidence(
                    FileRecommendation(
                        member.file, member.role, "", emptySet(), member.explanation,
                        ConfidenceAssessment(
                            member.decisionConfidence, emptySet(), ""), member.retainedReplacementPath),
                    exif[member.file.path], scores[member.file.path], classifications[member.file.directoryPath])
                if (current != member.file || evidence["exif"] != member.evidence["exif"] ||
                    evidence["retention"] != member.evidence["retention"])
                    throw FileOperationException(FileOperationFinding(FileOperationIssueCode.STALE_RECOMMENDATION, move.sourcePath))
            }
        }
        val exclusions = repository.exclusions()
        if (exclusions.any { move.sourcePath.isInTree(it.path) || move.survivorPath?.isInTree(it.path) == true })
            throw FileOperationException(FileOperationFinding(FileOperationIssueCode.UNSAFE_PATH, move.sourcePath))
    }

    private suspend fun duplicateSnapshots(
        selections: List<DuplicateDeletionSelection>,
        context: DirectoryClassificationContext?,
    ) = DeletionRecommendationService(repository, retention, dispatcher, context).let { service ->
        selections.map { it.source }.distinct().flatMap { service.snapshots(it) }.associateBy {
            duplicateDeletionGroupId(DuplicateDeletionSelection(it.source, it.files))
        }
    }

    private suspend fun currentRecommendations(
        context: DirectoryClassificationContext?,
        duplicates: List<DuplicateDeletionSelection>,
        includeProviders: Boolean,
    ): Map<String, SelectedDeletionGroup> {
        val recommended = if (includeProviders) DeletionRecommendationService(repository, retention, dispatcher, context).load()
            .categories.flatMap { category -> category.groups.map { SelectedDeletionGroup(category.category, it) } }
            else emptyList()
        val snapshots = duplicateSnapshots(duplicates, context)
        val duplicateGroups = duplicates.mapNotNull { selection ->
            val snapshot = snapshots[duplicateDeletionGroupId(selection)] ?: return@mapNotNull null
            try {
                duplicateDeletionGroup(selection, snapshot)
            } catch (_: FileOperationException) {
                null
            }
        }
        return (recommended + duplicateGroups).associateBy { it.key }
    }

    private suspend fun snapshotGroups(
        selected: List<SelectedDeletionGroup>,
        context: DirectoryClassificationContext?,
    ): List<FileOperationGroup> {
        val files = selected.flatMap { it.group.recommendation.files.values }.map { it.file }.distinctBy { it.path }
        val paths = files.map { it.path }
        val exif = repository.fileExif(paths, currentOnly = true).associateBy { it.input.path }
        val scores = repository.fileScores(paths, RetentionScorer().identity, currentOnly = true).associateBy { it.input.path }
        val classifications = context?.let {
            repository.directoryClassifications(files.map { file -> file.directoryPath }.distinct(), it)
                .associateBy { record -> record.directoryPath }
        }.orEmpty()
        return selected.map { selection ->
            val group = selection.group
            val protected = group.recommendation.files.values.any {
                classifications[it.file.directoryPath]?.classification == DirectoryClassification.PROTECTED
            } || (group.survivors + group.candidates).any {
                it.directoryClassification?.classification == DirectoryClassification.PROTECTED
            }
            val operationGroup = FileOperationGroup(selection.key, selection.category.id, selection.category.name,
                "${group.evidenceSummary}\n${group.recommendation.explanation}", group.recommendation.duplicateConfidence.score,
                group.recommendation.files.values.map { advice ->
                    val storedExif = exif[advice.file.path]
                    val classification = classifications[advice.file.directoryPath]
                    FileOperationMember(advice.file, advice.role, advice.explanation,
                        advice.retentionDecisionConfidence.score, advice.retainedReplacementPath,
                        storedExif?.result?.metadata?.capturedAt, classification?.classification,
                        operationEvidence(advice, storedExif, scores[advice.file.path], classification))
                }, duplicateProvenance = selection.duplicateProvenance, policy = group.policy)
            if (protected && !operationGroup.allowsSingleProtectedDirectory())
                operationGroup.copy(findings = listOf(FileOperationFinding(FileOperationIssueCode.PROTECTED_GROUP,
                    detail = selection.category.name)))
            else operationGroup
        }
    }

    private suspend fun createInSession(
        selected: List<SelectedDeletionGroup>,
        save: Boolean = true,
        freshRecommendations: Map<String, SelectedDeletionGroup>? = null,
    ): FileOperationProposal {
        if (selected.isEmpty())
            throw FileOperationException(FileOperationFinding(FileOperationIssueCode.EMPTY_SELECTION), global = true)
        val binRoot = repository.binRoot()
            ?: throw FileOperationException(FileOperationFinding(FileOperationIssueCode.BIN_NOT_CONFIGURED), global = true)
        val bins = repository.registeredBinRoots()
        val context = classificationContext()
        val selection = selected.distinctBy { it.key }
        val createdAt = clock()
        Diagnostics.logger.i("FileOperations", "Preparing dry run for ${selection.size} groups: loading current recommendations")
        val fresh = freshRecommendations ?: currentRecommendations(context,
            selection.mapNotNull { it.duplicateProvenance?.selection },
            includeProviders = selection.any { it.duplicateProvenance == null })
        val findings = mutableListOf<FileOperationFinding>()
        val selectedByKey = selection.associateBy { it.key }
        val groups = snapshotGroups(selection, context).map { group ->
            if (fresh[group.key] == selectedByKey[group.key]) group else
                group.copy(findings = group.findings + FileOperationFinding(FileOperationIssueCode.STALE_RECOMMENDATION,
                    detail = group.categoryName))
        }
        val candidates = selection.flatMap { it.group.candidates }.map { it.advice }
        val candidatesByPath = candidates.groupBy { it.file.path }
        val moves = candidatesByPath.map { (path, advice) ->
            val candidate = advice.first()
            val destination = operationDestinationPath(path, binRoot)
            ProposedFileMove(path, destination?.relativeTo(binRoot), destination, candidate.retainedReplacementPath,
                candidate.file.sizeBytes, if (advice.any { it != candidate })
                    listOf(FileOperationFinding(FileOperationIssueCode.STALE_RECOMMENDATION, path)) else emptyList())
        }
        val checkedMoves = validateMetadata(groups, moves, binRoot, bins, findings)
        Diagnostics.logger.i("FileOperations", "Metadata validation complete for ${moves.size} moves; rechecking saved evidence")
        if (repository.binRoot() != binRoot)
            findings += FileOperationFinding(FileOperationIssueCode.BIN_ROOT_CHANGED, binRoot)
        if (classificationContext() != context)
            findings += FileOperationFinding(FileOperationIssueCode.STALE_RECOMMENDATION)
        val endGroups = snapshotGroups(selection, context).associateBy { it.key }
        val checkedGroups = groups.map { group ->
            if (endGroups[group.key]?.copy(findings = emptyList()) == group.copy(findings = emptyList())) group
            else group.copy(findings = (group.findings +
                FileOperationFinding(FileOperationIssueCode.STALE_RECOMMENDATION, detail = group.categoryName)).distinct())
        }
        Diagnostics.logger.i("FileOperations", if (save) "Saving dry run for ${moves.size} moves" else "Fresh dry-run validation complete")
        return FileOperationProposal(nextId(), createdAt, clock(), null, binRoot, checkedGroups, checkedMoves,
            findings.distinct()).also { if (save) proposals.save(it) }
    }

    private suspend fun validateMetadata(
        groups: List<FileOperationGroup>,
        moves: List<ProposedFileMove>,
        binRoot: Path,
        bins: List<Path>,
        findings: MutableList<FileOperationFinding>,
        checkCancellation: () -> Unit = {},
    ): List<ProposedFileMove> {
        val sourcePaths = moves.mapTo(mutableSetOf()) { it.sourcePath }
        val keptMembers = groups.flatMap { it.members }.filter {
            it.role != FileRecommendationRole.REDUNDANT_CANDIDATE
        }
        val keptPaths = keptMembers.map { it.file.path }.toSet()
        // A selected candidate must never become a directory metadata probe, even
        // when an invalid destination or retained path would otherwise traverse it.
        fun safeToInspect(path: Path) = generateSequence(path) { it.parent }.none { it in sourcePaths }
        val exclusions = repository.exclusions()
        val keeperFindings = mutableMapOf<Path, List<FileOperationFinding>>()
        val destinations = mutableSetOf<Path>()
        Diagnostics.logger.i("FileOperations",
            "Planning metadata pass for ${keptPaths.size} retained files and ${moves.size} candidates")
        return filesystemLane.run("validate dry-run proposal metadata") {
            val inspector = OperationMetadataInspection(fileSystem, checkCancellation = checkCancellation, onProgress = { progress ->
                Diagnostics.logger.i("FileOperations",
                    "Dry-run metadata ${if (progress.finished) "finished" else "progress"}: " +
                        "${progress.completed}/${progress.total} planned paths; ${progress.metadataReads} filesystem reads; " +
                        "${progress.skippedMissing} missing descendants skipped; ${progress.blocked} blocked" +
                        (progress.currentPath?.let { "; current=$it" } ?: ""))
            })
            inspector.prepare(buildList {
                if (safeToInspect(binRoot)) add(binRoot)
                keptMembers.forEach { if (safeToInspect(it.file.path)) add(it.file.path) }
                moves.mapNotNull { it.destinationPath }.forEach { if (safeToInspect(it)) add(it) }
            })
            if (!safeToInspect(binRoot)) {
                findings += FileOperationFinding(FileOperationIssueCode.UNSAFE_PATH, binRoot)
                return@run moves
            }
            val binMetadata = inspector.inspect(binRoot, findings)
            if (binMetadata != null && !binMetadata.isDirectory)
                findings += FileOperationFinding(FileOperationIssueCode.BIN_NOT_DIRECTORY, binRoot)
            inspector.checkAncestors(binRoot, findings)
            for ((file) in keptMembers.distinctBy { it.file.path }) {
                currentCoroutineContext().ensureActive()
                checkCancellation()
                val problems = mutableListOf<FileOperationFinding>()
                if (file.path in sourcePaths)
                    problems += FileOperationFinding(FileOperationIssueCode.SURVIVOR_SELECTED, file.path)
                if (bins.any { file.path.isInTree(it) })
                    problems += FileOperationFinding(FileOperationIssueCode.SURVIVOR_IN_BIN, file.path)
                if (exclusions.any { file.path.isInTree(it.path) })
                    problems += FileOperationFinding(FileOperationIssueCode.UNSAFE_PATH, file.path)
                if (safeToInspect(file.path)) checkRetainedFile(inspector, file, problems)
                else problems += FileOperationFinding(FileOperationIssueCode.UNSAFE_PATH, file.path)
                keeperFindings[file.path] = problems
            }
            val groupKeeperFindings = groups.associate { group ->
                group.key to group.members.flatMap { keeperFindings[it.file.path].orEmpty() }
            }
            val findingsBySource = mutableMapOf<Path, MutableList<FileOperationFinding>>()
            for (group in groups) for (member in group.members) if (member.file.path in sourcePaths)
                findingsBySource.getOrPut(member.file.path) { mutableListOf() }.addAll(groupKeeperFindings[group.key].orEmpty())
            moves.map { move ->
                currentCoroutineContext().ensureActive()
                checkCancellation()
                val path = move.sourcePath
                val itemFindings = (move.findings + findingsBySource[path].orEmpty()).toMutableList()
                val destination = move.destinationPath
                if (destination == null || destination != operationDestinationPath(path, binRoot) ||
                    move.relativePath != destination.relativeTo(binRoot))
                    itemFindings += FileOperationFinding(FileOperationIssueCode.UNSAFE_PATH, path)
                if (bins.any { path.isInTree(it) })
                    itemFindings += FileOperationFinding(FileOperationIssueCode.UNSAFE_PATH, path)
                if (path.isHistoryMetadata() || path.isBinMarkerMetadata())
                    itemFindings += FileOperationFinding(FileOperationIssueCode.UNSAFE_PATH, path)
                if (exclusions.any { path.isInTree(it.path) })
                    itemFindings += FileOperationFinding(FileOperationIssueCode.UNSAFE_PATH, path)
                val replacement = move.survivorPath
                if (replacement == null || replacement !in keptPaths || replacement in sourcePaths)
                    itemFindings += FileOperationFinding(FileOperationIssueCode.SURVIVOR_SELECTED, replacement)
                if (destination != null && safeToInspect(destination)) {
                    if (!destinations.add(destination))
                        itemFindings += FileOperationFinding(FileOperationIssueCode.DESTINATION_COLLISION, destination)
                    inspector.inspect(destination, itemFindings)?.let {
                        itemFindings += FileOperationFinding(FileOperationIssueCode.DESTINATION_EXISTS, destination)
                    }
                    inspector.checkAncestors(destination, itemFindings)
                } else if (destination != null) itemFindings += FileOperationFinding(FileOperationIssueCode.UNSAFE_PATH, destination)
                move.copy(findings = itemFindings.distinct())
            }
        }
    }

    private fun checkRetainedFile(
        inspector: OperationMetadataInspection,
        file: FileRecord,
        findings: MutableList<FileOperationFinding>,
    ) {
        val metadata = inspector.inspect(file.path, findings)
        when {
            metadata == null -> findings += FileOperationFinding(FileOperationIssueCode.SURVIVOR_MISSING, file.path)
            !metadata.isRegularFile || metadata.symlinkTarget != null ->
                findings += FileOperationFinding(FileOperationIssueCode.SOURCE_NOT_REGULAR, file.path)
            metadata.size != file.sizeBytes ||
                (file.modifiedAt != null && metadata.lastModifiedAtMillis != file.modifiedAt) ||
                (file.createdAt != null && metadata.createdAtMillis != file.createdAt) ->
                findings += FileOperationFinding(FileOperationIssueCode.SURVIVOR_CHANGED, file.path)
        }
        inspector.checkAncestors(file.path, findings)
    }
}
