package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.PathActions
import com.doronyg.photoarchivededuplicator.UiPreferences
import com.doronyg.photoarchivededuplicator.exif.ExifQueueController
import com.doronyg.photoarchivededuplicator.operations.FILE_BIN_NAME
import com.doronyg.photoarchivededuplicator.operations.FileExecutionPhase
import com.doronyg.photoarchivededuplicator.operations.FileExecutionProgress
import com.doronyg.photoarchivededuplicator.operations.FileMoveExecutionStatus
import com.doronyg.photoarchivededuplicator.operations.FileOperationController
import com.doronyg.photoarchivededuplicator.operations.FileOperationFinding
import com.doronyg.photoarchivededuplicator.operations.FileOperationGroup
import com.doronyg.photoarchivededuplicator.operations.FileOperationMember
import com.doronyg.photoarchivededuplicator.operations.FileOperationProposal
import com.doronyg.photoarchivededuplicator.operations.FileOperationState
import com.doronyg.photoarchivededuplicator.operations.FileOperationSummary
import com.doronyg.photoarchivededuplicator.operations.FileOperationValidationStatus
import com.doronyg.photoarchivededuplicator.operations.JOURNAL_BATCH_SIZE
import com.doronyg.photoarchivededuplicator.operations.ProposedFileMove
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toPath
import kotlin.time.TimeSource

internal fun fileExecutionEtaMillis(progress: FileExecutionProgress, elapsedMillis: Long): Long? {
    if (progress.finished || progress.phase == FileExecutionPhase.PREPARING ||
        progress.verified < 3 || elapsedMillis <= 0) return null
    return (elapsedMillis.toDouble() / progress.verified * progress.remaining.coerceAtLeast(0)).toLong()
}

internal fun fileOperationBinSuggestion(configured: Path?, scanRoot: String, sources: List<Path>): String {
    configured?.let { return it.toString() }
    val normalizedSources = sources.map { it.normalized() }
    fun sibling(root: Path): Path? = root.parent?.resolve(FILE_BIN_NAME)?.takeIf { bin ->
        normalizedSources.none { source ->
            source == bin || generateSequence(source.parent) { it.parent }.any { it == bin }
        } && root != bin && generateSequence(root.parent) { it.parent }.none { it == bin }
    }
    val scan = runCatching { scanRoot.toPath().normalized().takeIf { it.isAbsolute } }.getOrNull()
    scan?.let { sibling(it)?.let { bin -> return bin.toString() } }
    val directories = normalizedSources.map { it.parent ?: return "" }
    if (directories.isEmpty() || directories.any { !it.isAbsolute }) return ""
    var common = directories.first()
    while (directories.any { directory ->
            directory != common && generateSequence(directory.parent) { it.parent }.none { it == common }
        }) {
        common = common.parent ?: return ""
    }
    return sibling(common)?.toString().orEmpty()
}

internal fun fileOperationWritesEnabled(state: FileOperationState, scanning: Boolean, exifRunning: Boolean): Boolean =
    !state.busy && !scanning && !exifRunning

internal fun savedFileOperationResults(
    history: List<FileOperationSummary>,
    proposal: FileOperationProposal?,
): List<Pair<FileOperationSummary, FileOperationProposal?>> {
    val summaries = if (proposal == null || history.any { it.id == proposal.id }) history
        else (history + FileOperationSummary(proposal.id, proposal.createdAt, proposal.archiveRoot, proposal.binRoot,
            proposal.validationStatus, proposal.groups.size.toLong(), proposal.moves.size.toLong(), proposal.candidateBytes))
            .sortedWith(compareByDescending<FileOperationSummary> { it.createdAt }.thenBy { it.id })
    return summaries.map { it to proposal?.takeIf { proposal -> proposal.id == it.id } }
}

internal fun operationMemberDisposition(
    path: Path,
    moves: List<ProposedFileMove>,
    rejected: Set<Path> = emptySet(),
): String {
    if (path in rejected) return AppStrings.operations.skippedMove
    val move = moves.firstOrNull { it.sourcePath == path } ?: return AppStrings.operations.fileStays
    return move.destinationPath?.let { AppStrings.operations.fileMoves(it.toString()) }
        ?: AppStrings.operations.missingDestination
}

internal fun operationMemberDetails(
    member: FileOperationMember,
    expanded: Boolean = false,
    includeRawEvidence: Boolean = true,
): List<Pair<String, String>> {
    if (!expanded) return emptyList()
    val strings = AppStrings.operations
    return listOf(
        strings.engineRole to strings.role(member.role),
        strings.explanation to member.explanation,
        strings.decisionConfidence to "${member.decisionConfidence}/100",
        strings.survivor to (member.retainedReplacementPath?.toString() ?: AppStrings.unavailable),
        strings.captureTime to (member.capturedAt ?: AppStrings.unavailable),
        strings.classification to (member.classification?.name ?: AppStrings.unavailable),
    ) + if (includeRawEvidence) listOf(strings.evidence to member.evidence.toString()) else emptyList()
}

internal fun proposedMoveFields(move: ProposedFileMove): List<Pair<String, String>> = listOf(
    AppStrings.operations.source to move.sourcePath.toString(),
    AppStrings.operations.relativePath to (move.relativePath?.toString() ?: AppStrings.operations.unmapped),
    AppStrings.operations.destination to (move.destinationPath?.toString() ?: AppStrings.operations.unmapped),
    AppStrings.operations.survivor to (move.survivorPath?.toString() ?: AppStrings.unavailable),
)

internal suspend fun formatFileOperationResults(
    proposal: FileOperationProposal,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): String = withContext(dispatcher) {
    val context = currentCoroutineContext()
    val strings = AppStrings.operations
    val movesBySource = proposal.moves.groupBy { it.sourcePath }
    val eligibility = proposal.executionEligibility
    val findingsByPath = proposal.findings.groupBy { it.path }
    buildString {
        fun field(label: String, value: String) {
            appendLine(AppStrings.field(label, quoteResultText(value)))
        }
        fun finding(value: FileOperationFinding) {
            field(value.code.name, strings.reason(value.code))
            value.path?.let { field(strings.source, it.toString()) }
            value.detail?.let { field(strings.explanation, it) }
        }
        fun move(value: ProposedFileMove) {
            proposedMoveFields(value).forEach { (label, text) -> field(label, text) }
            appendLine(AppStrings.fileSize(value.sizeBytes, formatBytes(value.sizeBytes)))
            value.findings.forEach { finding(it) }
        }
        appendLine(strings.proposal)
        appendLine(strings.availability)
        appendLine(strings.aiStatus)
        field(strings.proposalId, proposal.id)
        appendLine(strings.status(proposal.validationStatus))
        field(strings.createdAt, proposal.createdAt.toString())
        field(strings.validatedAt, proposal.validatedAt.toString())
        field(strings.binRoot, proposal.binRoot.toString())
        appendLine(strings.summary(proposal.groups.size.toLong(), proposal.moves.size.toLong(), formatBytes(proposal.candidateBytes)))
        appendLine(strings.eligibleMoves(eligibility.moves.size, proposal.moves.size,
            formatBytes(eligibility.moves.sumOf { it.sizeBytes })))
        appendLine(strings.dryRunSourceNote)
        proposal.findings.forEach { context.ensureActive(); finding(it) }
        proposal.groups.forEach { group ->
            context.ensureActive()
            appendLine()
            field(strings.selectedGroups, group.categoryName)
            field(strings.explanation, group.explanation)
            field(strings.duplicateConfidence, "${group.duplicateConfidence}/100")
            group.findings.forEach { finding(it) }
            group.members.forEach { member ->
                context.ensureActive()
                appendLine()
                field(strings.source, member.file.path.toString())
                // Quote the entire outcome so unusual destination characters stay on one export line.
                val memberMoves = movesBySource[member.file.path].orEmpty()
                field(strings.suggestedOutcome, operationMemberDisposition(member.file.path, memberMoves, eligibility.rejected.keys))
                appendLine(AppStrings.fileSize(member.file.sizeBytes, formatBytes(member.file.sizeBytes)))
                field(strings.fileCreatedAt, member.file.createdAt?.toString() ?: AppStrings.unavailable)
                field(strings.modifiedAt, member.file.modifiedAt?.toString() ?: AppStrings.unavailable)
                operationMemberDetails(member, expanded = true, includeRawEvidence = false)
                    .forEach { (label, text) -> field(label, text) }
                memberMoves.forEach { move(it) }
                findingsByPath[member.file.path].orEmpty().forEach { finding(it) }
            }
        }
        val members = proposal.groups.flatMap { it.members }.mapTo(mutableSetOf()) { it.file.path }
        proposal.moves.filter { it.sourcePath !in members }.forEach {
            context.ensureActive()
            field(strings.suggestedOutcome, operationMemberDisposition(it.sourcePath, listOf(it), eligibility.rejected.keys))
            move(it)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileOperationsScreen(
    controller: FileOperationController,
    preferences: UiPreferences,
    queue: ExifQueueController,
    pathActions: PathActions,
    scanning: Boolean,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val choices by preferences.state.collectAsState()
    val queueState by queue.state.collectAsState()
    val suggestedBin = remember(state.binRoot, choices.scanRoot, state.selectedGroups) {
        fileOperationBinSuggestion(state.binRoot, choices.scanRoot,
            if (state.binRoot == null) state.selectedGroups.flatMap { it.group.recommendation.files.keys } else emptyList())
    }
    val selectedKeys = remember(state.selectedGroups) { state.selectedGroups.mapTo(mutableSetOf()) { it.key } }
    val savedResults = remember(state.history, state.proposal) { savedFileOperationResults(state.history, state.proposal) }
    val proposalRows: List<Pair<FileOperationGroup, FileOperationMember?>> = remember(state.proposal) {
        state.proposal?.groups?.flatMap { group -> listOf(group to null) + group.members.map { group to it } }.orEmpty()
    }
    var binInput by remember(state.binRoot) { mutableStateOf(suggestedBin) }
    var binEdited by remember(state.binRoot) { mutableStateOf(false) }
    var preparationProposal by remember { mutableStateOf<FileOperationProposal?>(null) }
    var executionProposal by remember { mutableStateOf<FileOperationProposal?>(null) }
    var executionApproved by remember(executionProposal?.id) { mutableStateOf(false) }
    LaunchedEffect(suggestedBin) {
        if (!binEdited) binInput = suggestedBin
    }
    val writesEnabled = fileOperationWritesEnabled(state, scanning, queueState.isRunning)
    val listState = rememberLazyListState()
    val strings = AppStrings.operations
    LaunchedEffect(controller) {
        controller.state.first { !it.busy }
        controller.refresh()
    }

    Row(modifier) {
        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.executionProgress != null) stickyHeader(key = "execution-progress") {
                OperationExecutionProgress(state, controller)
            }
            item(key = "configuration") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(AppStrings.fileOperationsTab, style = MaterialTheme.typography.titleLarge)
                    Text(strings.availability)
                    Text(strings.aiStatus, style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = binInput,
                        onValueChange = { binInput = it; binEdited = true },
                        enabled = writesEnabled,
                        label = { Text(strings.binRoot) },
                        placeholder = { Text(strings.binPlaceholder) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        enabled = writesEnabled && binInput.isNotBlank(),
                        onClick = { controller.configureBin(binInput) },
                    ) { Text(strings.saveBin) }
                    Text(strings.configureNote, style = MaterialTheme.typography.bodySmall)
                    Text(strings.mappingNote, style = MaterialTheme.typography.bodySmall)
                    if (state.busy) {
                        if (state.executionProgress == null) Text(strings.busy)
                    }
                    else if (!writesEnabled) Text(strings.waiting)
                    state.error?.let { Text(strings.finding(it), color = MaterialTheme.colorScheme.error) }
                }
            }
            item(key = "selection-heading") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.selectedGroups, style = MaterialTheme.typography.titleMedium)
                    Text(strings.selection(state.selectedGroups.size))
                    if (state.selectedGroups.isEmpty())
                        Text(if (state.availableGroups.isEmpty()) strings.emptySelection else strings.pendingSelectionHint)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = writesEnabled && state.selectedGroups.size < state.availableGroups.size,
                            onClick = { controller.selectAllGroups() },
                        ) { Text(strings.selectAll) }
                        OutlinedButton(
                            enabled = writesEnabled && state.selectedGroups.isNotEmpty(),
                            onClick = { controller.clearGroupSelection() },
                        ) { Text(strings.clearSelection) }
                    }
                    Text(strings.operationSelectionNote, style = MaterialTheme.typography.bodySmall)
                    Button(
                        enabled = writesEnabled && state.binRoot != null && state.selectedGroups.isNotEmpty(),
                        onClick = { controller.createDryRun() },
                    ) { Text(strings.createDryRun) }
                }
            }
            if (state.proposal == null) items(state.availableGroups, key = { "selection:${it.key}" }) { selected ->
                Column {
                    Row {
                        Checkbox(
                            checked = selected.key in selectedKeys,
                            enabled = writesEnabled,
                            onCheckedChange = { controller.setGroupSelected(selected.key, it) },
                        )
                        Text(strings.selectGroup, Modifier.padding(top = 12.dp))
                    }
                    Text(strings.group(selected.category.name, selected.group.candidates.size,
                        selected.group.recommendation.files.size), style = MaterialTheme.typography.titleSmall)
                    OperationEvidence {
                        Text(selected.group.evidenceSummary)
                        selected.group.recommendation.files.values.forEach { member ->
                            FilePathLinks(member.file, pathActions)
                            Text(AppStrings.field(strings.engineRole, strings.role(member.role)))
                            Text(member.explanation, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item(key = "history-heading") {
                Column {
                    Text(strings.history, style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(enabled = !state.busy, onClick = { controller.refresh() }) { Text(AppStrings.refresh) }
                    if (savedResults.isEmpty()) Text(strings.noHistory)
                }
            }
            savedResults.forEach { (saved, expandedProposal) ->
                item(key = "history:${saved.id}") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(strings.summary(saved.groupCount, saved.candidateCount, formatBytes(saved.candidateBytes)))
                            Text(strings.status(saved.validationStatus))
                            Text(AppStrings.field(strings.createdAt, saved.createdAt.toString()))
                            OutlinedButton(enabled = !state.busy && expandedProposal == null,
                                onClick = { controller.openProposal(saved.id) }) {
                                Text(if (expandedProposal == null) strings.open else strings.openedResult)
                            }
                        }
                    }
                }
                expandedProposal?.let { proposal ->
                    item(key = "proposal-heading:${proposal.id}") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(strings.proposal, style = MaterialTheme.typography.titleLarge)
                            Text(strings.status(proposal.validationStatus))
                            Text(AppStrings.field(strings.createdAt, proposal.createdAt.toString()))
                            Text(AppStrings.field(strings.validatedAt, proposal.validatedAt.toString()))
                            Text(AppStrings.field(strings.binRoot, proposal.binRoot.toString()))
                            Text(strings.summary(proposal.groups.size.toLong(), proposal.moves.size.toLong(),
                                formatBytes(proposal.candidateBytes)))
                            Text(strings.eligibleMoves(proposal.executionEligibility.moves.size, proposal.moves.size,
                                formatBytes(proposal.executionEligibility.moves.sumOf { it.sizeBytes })))
                            Text(strings.dryRunSourceNote, style = MaterialTheme.typography.bodySmall)
                            CopyFileOperationResults(proposal)
                            Text(strings.preparationNote, style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(
                                enabled = writesEnabled && proposal.validationStatus == FileOperationValidationStatus.READY &&
                                    state.executionResult?.operationId != proposal.id,
                                onClick = { preparationProposal = proposal },
                            ) { Text(strings.prepareDirectories) }
                            Text(strings.preparationNotApproval, style = MaterialTheme.typography.bodySmall)
                            state.directoryPreparation?.let {
                                Text(strings.preparedDirectories(it.createdDirectories, it.writtenMarkers, it.checkedDirectories))
                            }
                            Button(
                                enabled = writesEnabled && proposal.canExecute &&
                                    state.executionResult?.operationId != proposal.id,
                                onClick = { executionProposal = proposal },
                            ) { Text(strings.realRun) }
                        }
                    }
                    items(proposal.findings) { Text(strings.finding(it), color = MaterialTheme.colorScheme.error) }
                    state.executionResult?.takeIf { it.operationId == proposal.id }?.let { result ->
                        item(key = "execution-heading:${result.operationId}") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(strings.executionResults, style = MaterialTheme.typography.titleMedium)
                                result.statisticsError?.let {
                                    Text("${strings.statisticsSaveError}\n$it", color = MaterialTheme.colorScheme.error)
                                }
                                if (result.items.any { it.status == FileMoveExecutionStatus.UNCERTAIN || it.postProcessingError != null })
                                    Text(strings.reconcileRun, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        items(result.items, key = { "execution:${result.operationId}:${it.move.sourcePath}" }) { item ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(strings.executionStatus(item.status), style = MaterialTheme.typography.titleSmall)
                                    Text(AppStrings.field(strings.source, item.move.sourcePath.toString()))
                                    Text(AppStrings.field(strings.destination, item.move.destinationPath?.toString() ?: strings.unmapped))
                                    item.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                    item.postProcessingError?.let {
                                        Text(AppStrings.field(strings.inventoryUpdateError, it), color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                    items(proposalRows, key = { (group, member) ->
                        if (member == null) "proposal-group:${proposal.id}:${group.key}"
                        else "member:${proposal.id}:${group.key}:${member.file.path}"
                    }) { (group, member) ->
                        if (member == null) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(group.categoryName, style = MaterialTheme.typography.titleMedium)
                                group.findings.forEach { Text(strings.finding(it), color = MaterialTheme.colorScheme.error) }
                                OperationEvidence {
                                    Text(group.explanation)
                                    Text(AppStrings.field(strings.duplicateConfidence, "${group.duplicateConfidence}/100"))
                                }
                            }
                        } else {
                            OperationMemberCard(member, proposal, pathActions)
                        }
                    }
                }
            }
        }
        ResultsScrollbar(listState, Modifier.fillMaxHeight())
    }
    preparationProposal?.let { proposal ->
        AlertDialog(
            onDismissRequest = { preparationProposal = null },
            title = { Text(strings.confirmPreparation) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(AppStrings.field(strings.binRoot, proposal.binRoot.toString()))
                    Text(strings.preparationNote)
                    Text(strings.preparationNotApproval)
                }
            },
            confirmButton = {
                TextButton(enabled = writesEnabled, onClick = {
                    preparationProposal = null
                    controller.prepareDirectories(proposal.id)
                }) { Text(strings.prepareDirectories) }
            },
            dismissButton = {
                TextButton(onClick = { preparationProposal = null }) { Text(AppStrings.cancel) }
            },
        )
    }
    executionProposal?.let { proposal ->
        AlertDialog(
            onDismissRequest = { executionProposal = null },
            title = { Text(strings.confirmRealRun) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.summary(proposal.groups.size.toLong(), proposal.moves.size.toLong(), formatBytes(proposal.candidateBytes)))
                    Text(strings.eligibleMoves(proposal.executionEligibility.moves.size, proposal.moves.size,
                        formatBytes(proposal.executionEligibility.moves.sumOf { it.sizeBytes })))
                    Text(AppStrings.field(strings.binRoot, proposal.binRoot.toString()))
                    Text(strings.realRunChecks)
                    Text(strings.realRunAiNote)
                    Text(strings.realRunHistory)
                    Text(strings.batchExecutionNote)
                    Text(strings.journalBatchNote(JOURNAL_BATCH_SIZE))
                    Row {
                        Checkbox(checked = executionApproved, onCheckedChange = { executionApproved = it })
                        Text(strings.approveMoves(proposal.executionEligibility.moves.size))
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = writesEnabled && executionApproved && proposal.canExecute, onClick = {
                    executionProposal = null
                    controller.runRealOperation(proposal.id)
                }) { Text(strings.realRun) }
            },
            dismissButton = {
                TextButton(onClick = { executionProposal = null }) { Text(AppStrings.cancel) }
            },
        )
    }
}

@Composable
private fun OperationExecutionProgress(state: FileOperationState, controller: FileOperationController) {
    val progress = state.executionProgress ?: return
    val strings = AppStrings.operations
    var sinceUpdate by remember(progress) { mutableStateOf(0L) }
    LaunchedEffect(progress, state.busy) {
        if (state.busy && !progress.finished) {
            val started = TimeSource.Monotonic.markNow()
            while (true) {
                delay(1000)
                sinceUpdate = started.elapsedNow().inWholeMilliseconds
            }
        }
    }
    val elapsed = progress.elapsedMillis + sinceUpdate
    val estimate = if (state.cancellationRequested) null else fileExecutionEtaMillis(progress, elapsed)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (state.cancellationRequested && !progress.finished) strings.stoppingOperation
                    else if (progress.finished && progress.phase != FileExecutionPhase.CANCELLED) strings.executionFinished
                    else strings.executionPhase(progress.phase),
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (state.busy && !progress.finished) OutlinedButton(
                    enabled = !state.cancellationRequested, onClick = controller::cancelOperation,
                ) { Text(strings.cancelOperation) }
            }
            if (progress.total > 0) LinearProgressIndicator(
                progress = { ((progress.total - progress.remaining).toFloat() / progress.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth())
            Text(strings.executionCounts(progress.total, progress.completed, progress.failed, progress.uncertain, progress.remaining))
            Text(strings.verifiedMoves(progress.verified), style = MaterialTheme.typography.bodySmall)
            Text(AppStrings.field(strings.logicalBytesMoved, formatBytes(progress.logicalBytesMoved)))
            Text(strings.executionTime(formatElapsed(elapsed), estimate?.let(::formatElapsed)),
                style = MaterialTheme.typography.bodySmall)
            progress.currentSource?.let {
                Text(it.toString(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            if (state.busy && !progress.finished) Text(strings.cancellationNote, style = MaterialTheme.typography.bodySmall)
            if (state.executionResult?.items?.any {
                it.status == FileMoveExecutionStatus.UNCERTAIN || it.postProcessingError != null
            } == true) Text(strings.reconcileRun, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun OperationMemberCard(member: FileOperationMember, proposal: FileOperationProposal, pathActions: PathActions) {
    val strings = AppStrings.operations
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(AppStrings.field(strings.suggestedOutcome,
                operationMemberDisposition(member.file.path, proposal.moves, proposal.executionEligibility.rejected.keys)),
                style = MaterialTheme.typography.labelLarge)
            FilePathLinks(member.file, pathActions)
            Text(AppStrings.fileSize(member.file.sizeBytes, formatBytes(member.file.sizeBytes)))
            val findings = proposal.executionEligibility.rejected[member.file.path].orEmpty() +
                proposal.findings.filter { it.path == member.file.path }
            findings.distinct().forEach { Text(strings.finding(it), color = MaterialTheme.colorScheme.error) }
            OperationEvidence {
                operationMemberDetails(member, expanded = true).forEach { (label, value) ->
                    Text(AppStrings.field(label, value), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun OperationEvidence(content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) AppStrings.operations.hideEvidence else AppStrings.operations.showEvidence)
    }
    if (expanded) content()
}

@Composable
private fun CopyFileOperationResults(proposal: FileOperationProposal) {
    var copying by remember(proposal) { mutableStateOf(false) }
    var copyStatus by remember(proposal) { mutableStateOf<String?>(null) }
    var copyError by remember(proposal) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val strings = AppStrings.operations
    OutlinedButton(enabled = !copying, onClick = {
        copying = true
        copyStatus = null
        copyError = null
        scope.launch {
            try {
                val text = formatFileOperationResults(proposal)
                clipboard.setText(AnnotatedString(text))
                copyStatus = strings.copiedResults
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                copyError = strings.copyResultsError(e.message)
            } finally {
                copying = false
            }
        }
    }) { Text(if (copying) strings.copying else strings.copyResults) }
    copyStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    copyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
