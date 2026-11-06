package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.UiChoices
import com.doronyg.photoarchivededuplicator.UiPreferences
import com.doronyg.photoarchivededuplicator.operations.OperationHistoryController
import com.doronyg.photoarchivededuplicator.operations.OperationHistoryState
import com.doronyg.photoarchivededuplicator.operations.history.OperationHistoryItem
import com.doronyg.photoarchivededuplicator.operations.history.OperationHistoryMoveStatus
import com.doronyg.photoarchivededuplicator.operations.history.OperationHistoryRestoreStatus
import com.doronyg.photoarchivededuplicator.operations.history.OperationHistorySnapshot
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import com.doronyg.photoarchivededuplicator.strings.OperationHistoryStrings
import okio.Path
import okio.Path.Companion.toPath

internal fun operationHistoryArchiveSuggestion(choices: UiChoices): String =
    choices.operationHistoryArchiveRoot.ifBlank { choices.scanRoot }

internal fun operationHistoryTotalsPartial(snapshot: OperationHistorySnapshot): Boolean =
    !snapshot.readable || !snapshot.complete || snapshot.problems.isNotEmpty() ||
        snapshot.operations.any { it.incomplete || !it.journalValid || it.problems.isNotEmpty() }

internal fun operationHistoryStatisticsTitle(snapshot: OperationHistorySnapshot): String =
    if (operationHistoryTotalsPartial(snapshot)) OperationHistoryStrings.partialStatistics else OperationHistoryStrings.statistics

internal fun operationHistoryMappingStatus(state: OperationHistoryState, archiveRoot: String, binRoot: Path?): String {
    val binding = state.binding
    val verified = binding != null && !state.busy && state.error == null &&
        state.snapshot?.identity == binding.identity && state.binRoot == binRoot &&
        binding.binRoot == binRoot && runCatching { archiveRoot.trim().toPath() == binding.archiveRoot }.getOrDefault(false)
    return if (verified) OperationHistoryStrings.verified else OperationHistoryStrings.unmapped
}

internal fun operationHistoryStatisticsFields(snapshot: OperationHistorySnapshot): List<Pair<String, String>> {
    val stats = snapshot.statistics
    val strings = OperationHistoryStrings
    return listOf(
        strings.moves to stats.completedMoves.toString(),
        strings.bytesMoved to formatBytes(stats.logicalBytesMoved),
        strings.inBin to stats.filesInBin.toString(),
        strings.bytesInBin to formatBytes(stats.logicalBytesInBin),
        strings.restores to stats.completedRestores.toString(),
        strings.deleted to stats.permanentlyDeleted.toString(),
    )
}

internal fun operationHistoryItemPaths(item: OperationHistoryItem): List<Pair<String, String>> = listOf(
    OperationHistoryStrings.original to item.originalRelativePath,
    OperationHistoryStrings.binPath to item.binRelativePath,
)

internal fun operationHistoryItemDetails(item: OperationHistoryItem, expanded: Boolean = false): List<Pair<String, String>> {
    if (!expanded) return emptyList()
    val strings = OperationHistoryStrings
    return listOf(
        strings.itemId to item.itemId,
        strings.size to formatBytes(item.sizeBytes),
        strings.modifiedAt to (item.modifiedAtMillis?.toString() ?: AppStrings.unavailable),
        strings.fileCreatedAt to (item.createdAtMillis?.toString() ?: AppStrings.unavailable),
        strings.category to item.categoryId,
        strings.explanation to item.explanation,
        strings.retained to (item.retainedRelativePath ?: AppStrings.unavailable),
        strings.moveStatus to when (item.moveStatus) {
            OperationHistoryMoveStatus.NOT_STARTED -> strings.notStarted
            OperationHistoryMoveStatus.PENDING -> strings.pending
            OperationHistoryMoveStatus.COMPLETED -> strings.completed
            OperationHistoryMoveStatus.FAILED -> strings.failed
        },
        strings.restoreStatus to when (item.restoreStatus) {
            OperationHistoryRestoreStatus.NOT_STARTED -> strings.notStarted
            OperationHistoryRestoreStatus.PENDING -> strings.pending
            OperationHistoryRestoreStatus.COMPLETED -> strings.restored
            OperationHistoryRestoreStatus.FAILED -> strings.failed
        },
        strings.recordedInBin to if (item.confirmedInBin) strings.yes else strings.no,
        strings.moves to item.completedMoves.toString(),
        strings.restores to item.completedRestores.toString(),
        strings.movedAt to (item.movedAt ?: AppStrings.unavailable),
        strings.restoredAt to (item.restoredAt ?: AppStrings.unavailable),
        strings.lastError to (item.lastError ?: AppStrings.unavailable),
    )
}

@Composable
internal fun OperationHistoryScreen(
    controller: OperationHistoryController,
    preferences: UiPreferences,
    binRoot: Path?,
    registrationBlocked: Boolean,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val choices by preferences.state.collectAsState()
    val suggestion = operationHistoryArchiveSuggestion(choices)
    var archiveInput by remember(controller) { mutableStateOf(suggestion) }
    var inputEdited by remember(controller) { mutableStateOf(false) }
    var registration by remember(controller) { mutableStateOf<Pair<String, Path>?>(null) }
    var expandedOperations by remember(controller, binRoot, state.snapshot?.operations) { mutableStateOf(setOf<Int>()) }
    val listState = rememberLazyListState()
    val strings = OperationHistoryStrings
    val canRead = binRoot != null && !state.busy
    val canRegister = canRead && !registrationBlocked && archiveInput.isNotBlank()
    // A changed Bin must never display the preceding Bin's history as its own.
    val snapshot = state.snapshot.takeIf { state.binRoot == binRoot && binRoot != null }

    LaunchedEffect(controller, binRoot) {
        if (binRoot != null) controller.refresh()
    }
    LaunchedEffect(suggestion) {
        if (!inputEdited) archiveInput = suggestion
    }

    Row(modifier) {
        LazyColumn(
            Modifier.weight(1f).testTag("operation-history-list"),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "configuration") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.title, style = MaterialTheme.typography.titleLarge)
                    Text(strings.description)
                    Text(AppStrings.field(strings.binRoot, binRoot?.toString() ?: AppStrings.unavailable))
                    if (binRoot == null) Text(strings.configureBin)
                    OutlinedTextField(
                        value = archiveInput,
                        onValueChange = { archiveInput = it; inputEdited = true },
                        enabled = !state.busy,
                        label = { Text(strings.archiveRoot) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("operation-history-archive-root"),
                    )
                    Text(strings.archiveHint, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = { controller.connect(archiveInput) },
                        enabled = canRead && archiveInput.isNotBlank(),
                        modifier = Modifier.testTag("operation-history-connect"),
                    ) { Text(strings.connect) }
                    Text(strings.connectNote, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = { binRoot?.let { registration = archiveInput.trim() to it } },
                        enabled = canRegister,
                        modifier = Modifier.testTag("operation-history-register"),
                    ) { Text(strings.register) }
                    Text(strings.registrationNote, style = MaterialTheme.typography.bodySmall)
                    if (registrationBlocked) Text(strings.busyRestriction)
                    Text(operationHistoryMappingStatus(state, archiveInput, binRoot))
                    Text(strings.clonedIdentityNote, style = MaterialTheme.typography.bodySmall)
                    Text(strings.actionsDisabled)
                    OutlinedButton(
                        onClick = { controller.refresh() },
                        enabled = canRead,
                        modifier = Modifier.testTag("operation-history-refresh"),
                    ) { Text(strings.refresh) }
                    if (state.busy) Text(strings.loading)
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        if (snapshot != null) Text(strings.stale)
                    }
                    if (snapshot != null) state.refreshedAt?.let {
                        Text(AppStrings.field(strings.refreshed, it.toString()))
                    }
                }
            }
            snapshot?.let { saved ->
                item(key = "statistics-heading") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(operationHistoryStatisticsTitle(saved), style = MaterialTheme.typography.titleMedium)
                        if (operationHistoryTotalsPartial(saved)) Text(strings.incomplete)
                        saved.identity?.let { Text(AppStrings.field(strings.archiveId, it.archiveId)) }
                        Text(strings.eventCountNote, style = MaterialTheme.typography.bodySmall)
                        Text(strings.physicalSpace, style = MaterialTheme.typography.bodySmall)
                    }
                }
                itemsIndexed(operationHistoryStatisticsFields(saved), key = { index, _ -> "statistic:$index" }) { _, field ->
                    Text(AppStrings.field(field.first, field.second))
                }
                if (saved.problems.isNotEmpty()) item(key = "problems-heading") {
                    Text(strings.problems, style = MaterialTheme.typography.titleMedium)
                }
                itemsIndexed(saved.problems, key = { index, _ -> "problem:$index" }) { _, problem ->
                    Text(AppStrings.field(problem.path, problem.message), color = MaterialTheme.colorScheme.error)
                }
                item(key = "history-heading") {
                    Column {
                        Text(strings.history, style = MaterialTheme.typography.titleMedium)
                        if (saved.operations.isEmpty()) Text(strings.noHistory)
                    }
                }
                saved.operations.forEachIndexed { index, operation ->
                    val expanded = index in expandedOperations
                    item(key = "operation:$index") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(AppStrings.field(strings.operationId, operation.operationId),
                                style = MaterialTheme.typography.titleSmall)
                            Text(AppStrings.field(strings.createdAt, operation.createdAt))
                            Text(AppStrings.field(strings.archiveId, operation.archiveId))
                            Text(AppStrings.field(strings.itemCount, operation.items.size.toString()))
                            if (operation.incomplete) Text(strings.incomplete, color = MaterialTheme.colorScheme.error)
                            if (operation.items.any { it.lastError != null })
                                Text(strings.failedActions, color = MaterialTheme.colorScheme.error)
                            TextButton(
                                onClick = {
                                    expandedOperations = if (expanded) expandedOperations - index else expandedOperations + index
                                },
                                modifier = Modifier.testTag("operation-history-operation:$index"),
                            ) { Text(if (expanded) strings.hideDetails else strings.showDetails) }
                        }
                    }
                    itemsIndexed(operation.problems, key = { problemIndex, _ -> "operation:$index:problem:$problemIndex" }) { _, problem ->
                        Text(problem, color = MaterialTheme.colorScheme.error)
                    }
                    if (expanded) itemsIndexed(
                        operation.items,
                        key = { itemIndex, _ -> "operation:$index:item:$itemIndex" },
                    ) { _, recordedItem ->
                        OperationHistoryItemRow(recordedItem)
                    }
                }
            }
        }
        ResultsScrollbar(listState, Modifier.fillMaxHeight())
    }

    registration?.let { (archive, bin) ->
        AlertDialog(
            onDismissRequest = { registration = null },
            title = { Text(strings.confirmRegistration) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.registrationConfirmation)
                    Text(AppStrings.field(strings.archiveRoot, archive))
                    Text(AppStrings.field(strings.binRoot, bin.toString()))
                    Text(strings.registrationNote)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canRegister && binRoot == bin && archiveInput.trim() == archive,
                    onClick = { registration = null; controller.register(archive) },
                    modifier = Modifier.testTag("operation-history-confirm-registration"),
                ) { Text(strings.confirmRegistration) }
            },
            dismissButton = {
                TextButton(onClick = { registration = null }) { Text(strings.cancel) }
            },
        )
    }
}

@Composable
private fun OperationHistoryItemRow(item: OperationHistoryItem) {
    var expanded by remember(item) { mutableStateOf(false) }
    Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        operationHistoryItemPaths(item).forEach { (label, value) -> Text(AppStrings.field(label, value)) }
        item.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) OperationHistoryStrings.hideEvidence else OperationHistoryStrings.showEvidence)
        }
        operationHistoryItemDetails(item, expanded).forEach { (label, value) ->
            Text(AppStrings.field(label, value), style = MaterialTheme.typography.bodySmall)
        }
    }
}
