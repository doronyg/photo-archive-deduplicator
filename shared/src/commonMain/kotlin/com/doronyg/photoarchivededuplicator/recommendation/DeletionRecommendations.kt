package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.classification.DirectoryClassification
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationRecord
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.validatedExifTimestamp
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.retention.RetentionScorer
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Path

internal data class DeletionRecommendationCategory(val id: String, val name: String, val description: String) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && description.isNotBlank())
    }
}

internal enum class DeletionRecommendationSource { REGULAR, SUSPECTED }

internal data class DeletionAnalysisSnapshot(
    val files: List<FileRecord>,
    val recommendation: DuplicateGroupRecommendation,
    val exif: Map<Path, ExifRecord>,
    val retention: Map<Path, RetentionPresentation>,
    val directoryClassifications: Map<Path, DirectoryClassificationRecord>,
    val source: DeletionRecommendationSource = DeletionRecommendationSource.SUSPECTED,
)

internal fun DeletionAnalysisSnapshot.hasProtectedMember(): Boolean =
    files.any { directoryClassifications[it.directoryPath]?.classification == DirectoryClassification.PROTECTED }

internal data class DeletionReviewFile(
    val advice: FileRecommendation,
    val exif: ExifRecord?,
    val retention: RetentionPresentation?,
    val directoryClassification: DirectoryClassificationRecord?,
    val evidence: List<Pair<String, String>> = emptyList(),
)

internal data class DeletionRecommendationGroup(
    val id: String,
    val evidenceSummary: String,
    val survivors: List<DeletionReviewFile>,
    val candidates: List<DeletionReviewFile>,
    val recommendation: DuplicateGroupRecommendation,
)

internal interface DeletionRecommendationProvider {
    val category: DeletionRecommendationCategory
    val source: DeletionRecommendationSource get() = DeletionRecommendationSource.SUSPECTED
    suspend fun recommendations(snapshots: List<DeletionAnalysisSnapshot>): List<DeletionRecommendationGroup>
}

/** Selects complete, safe engine decisions; it never chooses a keeper or scores a file itself. */
internal class SameDirectoryMetadataProvider : DeletionRecommendationProvider {
    override val category = DeletionRecommendationCategory(
        "same-directory-metadata",
        AppStrings.review.sameDirectoryName,
        AppStrings.review.sameDirectoryDescription,
    )
    private val scorer = RetentionScorer()

    override suspend fun recommendations(snapshots: List<DeletionAnalysisSnapshot>): List<DeletionRecommendationGroup> {
        val task = currentCoroutineContext()
        return snapshots.mapNotNull {
            task.ensureActive()
            recommend(it)
        }
    }

    fun recommend(snapshot: DeletionAnalysisSnapshot): DeletionRecommendationGroup? {
        if (snapshot.hasProtectedMember()) return null
        val proposal = snapshot.recommendation
        val files = snapshot.files
        if (files.size < 2 || files.map { it.directoryPath }.distinct().size != 1 ||
            files.map { it.path }.distinct().size != files.size ||
            files.map { it.sizeBytes }.distinct().size != 1 ||
            files.map { it.name }.distinct().size < 2 ||
            proposal.action != RecommendationAction.KEEP_ONE) return null
        val captures = mutableSetOf<String>()
        for (file in files) {
            val exif = currentRecommendationExif(file, snapshot.exif[file.path]) ?: return null
            captures += validatedExifTimestamp(exif.result.metadata.capturedAt) ?: return null
            if (currentRecommendationScore(file, exif, snapshot.retention[file.path], scorer) == null) return null
        }
        if (captures.size != 1) return null
        val safe = enforceRetentionPlanSafety(proposal, files, files.map { it.directoryPath }.toSet())
        val survivor = safe.preferredFile ?: return null
        if (safe.action != RecommendationAction.KEEP_ONE || safe.mustPreserve.size != 1 ||
            safe.redundantCandidates.size != files.size - 1 ||
            safe.duplicateConfidence.score < 75 || safe.duplicateConfidence.hasContradictions) return null
        if (safe.files.values.any {
            it.role == FileRecommendationRole.REDUNDANT_CANDIDATE &&
                (it.retainedReplacementPath != survivor.path || it.retentionDecisionConfidence.score < 75)
        }) return null
        fun present(advice: FileRecommendation) = DeletionReviewFile(advice,
            snapshot.exif[advice.file.path], snapshot.retention[advice.file.path],
            snapshot.directoryClassifications[advice.file.directoryPath])
        return DeletionRecommendationGroup(
            id = survivor.path.toString(),
            evidenceSummary = AppStrings.review.sameDirectoryEvidence,
            survivors = listOf(present(safe.files.getValue(survivor.path))),
            candidates = safe.files.values.filter { it.role == FileRecommendationRole.REDUNDANT_CANDIDATE }.map(::present),
            recommendation = safe,
        )
    }
}

internal data class DeletionRecommendationSummary(
    val groupCount: Long = 0,
    val candidates: FileStatistics = FileStatistics(),
)

internal data class DeletionCategoryResults(
    val category: DeletionRecommendationCategory,
    val groups: List<DeletionRecommendationGroup>,
    val summary: DeletionRecommendationSummary,
)

internal data class DeletionRecommendationReport(
    val categories: List<DeletionCategoryResults>,
    val summary: DeletionRecommendationSummary,
)

internal suspend fun buildDeletionRecommendationReport(
    snapshots: List<DeletionAnalysisSnapshot>,
    providers: List<DeletionRecommendationProvider>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): DeletionRecommendationReport = withContext(dispatcher) {
    require(providers.map { it.category.id }.distinct().size == providers.size) {
        "Deletion recommendation category IDs must be unique."
    }
    val task = currentCoroutineContext()
    fun summary(
        groups: List<DeletionRecommendationGroup>,
        groupCount: Long = groups.size.toLong(),
    ): DeletionRecommendationSummary {
        val candidates = mutableMapOf<Path, Long>()
        for (group in groups) {
            task.ensureActive()
            require(group.survivors.isNotEmpty() && group.candidates.isNotEmpty()) {
                "Review recommendations must retain survivors and identify candidates."
            }
            for (candidate in group.candidates) {
                val file = candidate.advice.file
                require(file.sizeBytes >= 0) { "Candidate size must be nonnegative." }
                val previous = candidates.put(file.path, file.sizeBytes)
                require(previous == null || previous == file.sizeBytes) { "Inconsistent candidate size: ${file.path}" }
            }
        }
        var totals = FileStatistics()
        for (size in candidates.values) {
            task.ensureActive()
            totals = totals.addFile(size)
        }
        return DeletionRecommendationSummary(groupCount, totals)
    }
    val categories = providers.map { provider ->
        task.ensureActive()
        val groups = provider.recommendations(snapshots.filter {
            it.source == provider.source && !it.hasProtectedMember()
        })
        require(groups.map { it.id }.distinct().size == groups.size && groups.all { it.id.isNotBlank() }) {
            "Deletion recommendation group IDs must be nonempty and unique within their category."
        }
        DeletionCategoryResults(provider.category, groups, summary(groups))
    }
    task.ensureActive()
    val allGroups = categories.flatMap { it.groups }
    val uniqueGroups = allGroups.map { group ->
        task.ensureActive()
        (group.survivors + group.candidates).map { it.advice.file.path }.toSet()
    }.distinct().size.toLong()
    DeletionRecommendationReport(categories, summary(allGroups, uniqueGroups))
}
