package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.classification.DirectoryClassification
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Path

internal data class AiClassificationStatistics(
    val directoryCount: Long = 0,
    val files: FileStatistics = FileStatistics(),
)

internal data class AiRecommendationStatistics(
    val directoryCount: Long,
    val classifiedDirectories: Long,
    val classifiedFiles: FileStatistics,
    val byClassification: Map<DirectoryClassification, AiClassificationStatistics>,
    val missing: AiClassificationStatistics,
    val fullyClassifiedGroups: Long,
    val partiallyClassifiedGroups: Long,
    val unclassifiedGroups: Long,
    val changedGroups: Long,
    val changedActionGroups: Long,
    val changedFiles: FileStatistics,
    val changedReplacements: FileStatistics,
    val baselineRedundantCandidates: FileStatistics,
    val currentRedundantCandidates: FileStatistics,
    val addedRedundantCandidates: FileStatistics,
    val withdrawnRedundantCandidates: FileStatistics,
    val actionTransitions: Map<Pair<RecommendationAction, RecommendationAction>, Long>,
    val roleTransitions: Map<Pair<FileRecommendationRole, FileRecommendationRole>, Long>,
)

/** Compares matching cohorts using actual decisions, not differences in wording or confidence. */
internal suspend fun aggregateAiRecommendationStatistics(
    baseline: List<DuplicateGroupRecommendation>,
    current: List<DuplicateGroupRecommendation>,
    classifications: Map<Path, DirectoryClassificationRecord>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): AiRecommendationStatistics = withContext(dispatcher) {
    require(baseline.size == current.size) { "AI impact requires matching recommendation cohorts." }
    val task = currentCoroutineContext()
    val paths = mutableSetOf<Path>()
    val directories = mutableSetOf<Path>()
    val labelDirectories = DirectoryClassification.entries.associateWith { mutableSetOf<Path>() }
    val labelFiles = DirectoryClassification.entries.associateWith { FileStatistics() }.toMutableMap()
    val missingDirectories = mutableSetOf<Path>()
    var missingFiles = FileStatistics()
    var fullyClassified = 0L
    var partiallyClassified = 0L
    var unclassified = 0L
    var changedGroups = 0L
    var changedActions = 0L
    var changedFiles = FileStatistics()
    var changedReplacements = FileStatistics()
    var baselineRedundant = FileStatistics()
    var currentRedundant = FileStatistics()
    var addedRedundant = FileStatistics()
    var withdrawnRedundant = FileStatistics()
    val actions = mutableMapOf<Pair<RecommendationAction, RecommendationAction>, Long>()
    val roles = mutableMapOf<Pair<FileRecommendationRole, FileRecommendationRole>, Long>()

    for (index in current.indices) {
        task.ensureActive()
        val before = baseline[index]
        val after = current[index]
        require(before.files.keys == after.files.keys) { "AI impact requires identical group membership." }
        var classified = 0
        var decisionChanged = before.action != after.action
        if (decisionChanged) {
            changedActions = checkedAdd(changedActions, 1)
            val transition = before.action to after.action
            actions[transition] = checkedAdd(actions[transition] ?: 0, 1)
        }
        for ((path, advice) in after.files) {
            task.ensureActive()
            val old = before.files.getValue(path)
            val file = advice.file
            require(old.file == file) { "AI impact requires identical file metadata." }
            require(file.sizeBytes >= 0) { "Statistics file size must be nonnegative." }
            require(paths.add(path)) { "Repeated file in AI statistics: $path" }
            directories += file.directoryPath
            val label = classifications[file.directoryPath]?.takeIf { it.directoryPath == file.directoryPath }?.classification
            if (label == null) {
                missingDirectories += file.directoryPath
                missingFiles = missingFiles.addFile(file.sizeBytes)
            } else {
                classified++
                labelDirectories.getValue(label) += file.directoryPath
                labelFiles[label] = labelFiles.getValue(label).addFile(file.sizeBytes)
            }
            val oldCandidate = old.role == FileRecommendationRole.REDUNDANT_CANDIDATE
            val candidate = advice.role == FileRecommendationRole.REDUNDANT_CANDIDATE
            if (oldCandidate) baselineRedundant = baselineRedundant.addFile(file.sizeBytes)
            if (candidate) currentRedundant = currentRedundant.addFile(file.sizeBytes)
            if (!oldCandidate && candidate) addedRedundant = addedRedundant.addFile(file.sizeBytes)
            if (oldCandidate && !candidate) withdrawnRedundant = withdrawnRedundant.addFile(file.sizeBytes)
            val replacementChanged = old.retainedReplacementPath != advice.retainedReplacementPath
            if (replacementChanged && oldCandidate && candidate) {
                changedReplacements = changedReplacements.addFile(file.sizeBytes)
            }
            if (old.role != advice.role || replacementChanged) {
                decisionChanged = true
                changedFiles = changedFiles.addFile(file.sizeBytes)
                if (old.role != advice.role) {
                    val transition = old.role to advice.role
                    roles[transition] = checkedAdd(roles[transition] ?: 0, 1)
                }
            }
        }
        when {
            classified == 0 -> unclassified = checkedAdd(unclassified, 1)
            classified == after.files.size -> fullyClassified = checkedAdd(fullyClassified, 1)
            else -> partiallyClassified = checkedAdd(partiallyClassified, 1)
        }
        if (decisionChanged) changedGroups = checkedAdd(changedGroups, 1)
    }
    var classifiedFiles = FileStatistics()
    for (bucket in labelFiles.values) {
        classifiedFiles = FileStatistics(checkedAdd(classifiedFiles.fileCount, bucket.fileCount),
            checkedAdd(classifiedFiles.sizeBytes, bucket.sizeBytes))
    }
    task.ensureActive()
    AiRecommendationStatistics(
        directoryCount = directories.size.toLong(),
        classifiedDirectories = (directories.size - missingDirectories.size).toLong(),
        classifiedFiles = classifiedFiles,
        byClassification = DirectoryClassification.entries.associateWith {
            AiClassificationStatistics(labelDirectories.getValue(it).size.toLong(), labelFiles.getValue(it))
        },
        missing = AiClassificationStatistics(missingDirectories.size.toLong(), missingFiles),
        fullyClassifiedGroups = fullyClassified, partiallyClassifiedGroups = partiallyClassified,
        unclassifiedGroups = unclassified, changedGroups = changedGroups, changedActionGroups = changedActions,
        changedFiles = changedFiles, changedReplacements = changedReplacements,
        baselineRedundantCandidates = baselineRedundant, currentRedundantCandidates = currentRedundant,
        addedRedundantCandidates = addedRedundant, withdrawnRedundantCandidates = withdrawnRedundant,
        actionTransitions = actions.toMap(), roleTransitions = roles.toMap(),
    )
}
