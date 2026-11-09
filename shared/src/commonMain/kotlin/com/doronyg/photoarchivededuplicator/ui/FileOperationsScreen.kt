package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
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
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.PathActions
import com.doronyg.photoarchivededuplicator.UiPreferences
import com.doronyg.photoarchivededuplicator.exif.ExifQueueController
import com.doronyg.photoarchivededuplicator.operations.FILE_BIN_NAME
import com.doronyg.photoarchivededuplicator.operations.FileOperationController
import com.doronyg.photoarchivededuplicator.operations.FileOperationMember
import com.doronyg.photoarchivededuplicator.operations.FileOperationProposal
import com.doronyg.photoarchivededuplicator.operations.FileOperationFinding
import com.doronyg.photoarchivededuplicator.operations.FileOperationState
import com.doronyg.photoarchivededuplicator.operations.FileOperationValidationStatus
import com.doronyg.photoarchivededuplicator.operations.FileMoveExecutionStatus
import com.doronyg.photoarchivededuplicator.operations.ProposedFileMove
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import okio.Path
import okio.Path.Companion.toPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

internal fun operationMemberDisposition(path: Path, moves: List<ProposedFileMove>): String {
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
        proposal.findings.forEach { context.ensureActive(); finding(it) }
        proposal.groups.forEach { group ->
            context.ensureActive()
            appendLine()
            field(strings.selectedGroups, group.categoryName)
            field(strings.explanation, group.explanation)
            field(strings.duplicateConfidence, "${group.duplicateConfidence}/100")
            group.members.forEach { member ->
                context.ensureActive()
                appendLine()
                field(strings.source, member.file.path.toString())
                // Quote the entire outcome so unusual destination characters stay on one export line.
                val memberMoves = movesBySource[member.file.path].orEmpty()
                field(strings.suggestedOutcome, operationMemberDisposition(member.file.path, memberMoves))
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
            field(strings.suggestedOutcome, operationMemberDisposition(it.sourcePath, listOf(it)))
            move(it)
        }
    }
}

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
            state.selectedGroups.flatMap { it.group.recommendation.files.keys })
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
    LaunchedEffect(controller) { controller.refresh() }

    Row(modifier) {
        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    if (state.busy) Text(strings.busy)
                    else if (!writesEnabled) Text(strings.waiting)
                    state.error?.let { Text(strings.finding(it), color = MaterialTheme.colorScheme.error) }
                }
            }
            item(key = "selection-heading") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.selectedGroups, style = MaterialTheme.typography.titleMedium)
                    Text(strings.selection(state.selectedGroups.size))
                    if (state.selectedGroups.isEmpty()) Text(strings.emptySelection)
                    Button(
                        enabled = writesEnabled && state.binRoot != null && state.selectedGroups.isNotEmpty(),
                        onClick = { controller.createDryRun() },
                    ) { Text(strings.createDryRun) }
                }
            }
            if (state.proposal == null) state.selectedGroups.forEach { selected ->
                item(key = "selection:${selected.key}") {
                    Column {
                        Text(strings.group(selected.category.name, selected.group.candidates.size,
                            selected.group.recommendation.files.size), style = MaterialTheme.typography.titleSmall)
                        OperationEvidence {
                            Text(selected.group.evidenceSummary)
                        }
                    }
                }
                items(selected.group.recommendation.files.values.toList(),
                    key = { "selected-member:${selected.key}:${it.file.path}" }) { member ->
                    Column(Modifier.padding(start = 12.dp)) {
                        FilePathLinks(member.file, pathActions)
                        OperationEvidence {
                            Text(AppStrings.field(strings.engineRole, strings.role(member.role)))
                            Text(member.explanation, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            state.proposal?.let { proposal ->
                item(key = "proposal-heading:${proposal.id}") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(strings.proposal, style = MaterialTheme.typography.titleLarge)
                        Text(strings.status(proposal.validationStatus))
                        Text(AppStrings.field(strings.createdAt, proposal.createdAt.toString()))
                        Text(AppStrings.field(strings.validatedAt, proposal.validatedAt.toString()))
                        Text(AppStrings.field(strings.binRoot, proposal.binRoot.toString()))
                        Text(strings.summary(proposal.groups.size.toLong(), proposal.moves.size.toLong(),
                            formatBytes(proposal.candidateBytes)))
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
                            enabled = writesEnabled && proposal.validationStatus == FileOperationValidationStatus.READY &&
                                state.executionResult?.operationId != proposal.id,
                            onClick = { executionProposal = proposal },
                        ) { Text(strings.realRun) }
                        state.executionProgress?.let { progress ->
                            Text(if (progress.finished) strings.executionFinished else strings.executionRunning,
                                style = MaterialTheme.typography.titleMedium)
                            Text(strings.executionCounts(progress.total, progress.completed, progress.failed, progress.uncertain,
                                progress.total - progress.completed - progress.failed - progress.uncertain))
                            Text(AppStrings.field(strings.logicalBytesMoved, formatBytes(progress.logicalBytesMoved)))
                            progress.currentSource?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                item(key = "history-heading") {
                    Column {
                        Text(strings.history, style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(enabled = !state.busy, onClick = { controller.refresh() }) { Text(AppStrings.refresh) }
                        if (state.history.isEmpty()) Text(strings.noHistory)
                    }
                }
                items(state.history, key = { "history:${it.id}" }) { saved ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(strings.summary(saved.groupCount, saved.candidateCount, formatBytes(saved.candidateBytes)))
                            Text(strings.status(saved.validationStatus))
                            Text(AppStrings.field(strings.createdAt, saved.createdAt.toString()))
                            OutlinedButton(enabled = !state.busy, onClick = { controller.openProposal(saved.id) }) {
                                Text(strings.open)
                            }
                        }
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
                proposal.groups.forEach { group ->
                    item(key = "proposal-group:${proposal.id}:${group.key}") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(group.categoryName, style = MaterialTheme.typography.titleMedium)
                            OperationEvidence {
                                Text(group.explanation)
                                Text(AppStrings.field(strings.duplicateConfidence, "${group.duplicateConfidence}/100"))
                            }
                        }
                    }
                    items(group.members, key = { "member:${proposal.id}:${group.key}:${it.file.path}" }) { member ->
                        OperationMemberCard(member, proposal, pathActions)
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
                    Text(AppStrings.field(strings.binRoot, proposal.binRoot.toString()))
                    Text(strings.realRunChecks)
                    Text(strings.realRunAiNote)
                    Text(strings.realRunHistory)
                    Row {
                        Checkbox(checked = executionApproved, onCheckedChange = { executionApproved = it })
                        Text(strings.approveMoves(proposal.moves.size))
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = writesEnabled && executionApproved, onClick = {
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
private fun OperationMemberCard(member: FileOperationMember, proposal: FileOperationProposal, pathActions: PathActions) {
    val strings = AppStrings.operations
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(AppStrings.field(strings.suggestedOutcome, operationMemberDisposition(member.file.path, proposal.moves)),
                style = MaterialTheme.typography.labelLarge)
            FilePathLinks(member.file, pathActions)
            Text(AppStrings.fileSize(member.file.sizeBytes, formatBytes(member.file.sizeBytes)))
            val findings = proposal.moves.filter { it.sourcePath == member.file.path }.flatMap { it.findings } +
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
