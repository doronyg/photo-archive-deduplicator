package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Path

internal data class InventoryFileCounts(val totalFiles: Long = 0, val possibleDuplicateFiles: Long = 0) {
    init {
        require(totalFiles >= 0 && possibleDuplicateFiles >= 0) { AppStrings.analysis.invalidInventoryCounts }
        require(possibleDuplicateFiles <= totalFiles) { AppStrings.analysis.inconsistentInventory }
    }
}

internal data class InventoryStatistics(
    val all: InventoryFileCounts = InventoryFileCounts(),
    val byFileType: Map<StatisticsFileType, InventoryFileCounts> =
        StatisticsFileType.entries.associateWith { InventoryFileCounts() },
)

internal suspend fun aggregateInventoryStatistics(
    countsByExtension: Map<String?, Long>,
    regularGroups: List<List<FileRecord>>,
    suspectedGroups: List<List<FileRecord>>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): InventoryStatistics = withContext(dispatcher) {
    val task = currentCoroutineContext()
    val totals = StatisticsFileType.entries.associateWith { 0L }.toMutableMap()
    val candidates = StatisticsFileType.entries.associateWith { 0L }.toMutableMap()
    for ((extension, count) in countsByExtension) {
        task.ensureActive()
        require(count >= 0) { AppStrings.analysis.invalidInventoryCounts }
        val type = StatisticsFileType.forFilename("file.${extension.orEmpty()}")
        totals[type] = checkedAdd(totals.getValue(type), count)
    }
    val seen = mutableMapOf<Path, FileRecord>()
    for (cohort in listOf(regularGroups, suspectedGroups)) {
        for (group in cohort) for (file in group) {
            task.ensureActive()
            val previous = seen.put(file.path, file)
            require(previous == null || previous == file) { AppStrings.analysis.changedInventoryFile(file.path.toString()) }
            if (previous == null) {
                val type = StatisticsFileType.forFilename(file.name)
                candidates[type] = checkedAdd(candidates.getValue(type), 1)
            }
        }
    }
    var allFiles = 0L
    var allCandidates = 0L
    val byType = StatisticsFileType.entries.associateWith {
        task.ensureActive()
        allFiles = checkedAdd(allFiles, totals.getValue(it))
        allCandidates = checkedAdd(allCandidates, candidates.getValue(it))
        InventoryFileCounts(totals.getValue(it), candidates.getValue(it))
    }
    InventoryStatistics(InventoryFileCounts(allFiles, allCandidates), byType)
}
