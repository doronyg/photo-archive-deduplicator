package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.exif.KIM_VERSION
import com.doronyg.photoarchivededuplicator.exif.isValidExifOffset
import com.doronyg.photoarchivededuplicator.exif.validatedExifTimestamp
import com.doronyg.photoarchivededuplicator.retention.FileScore
import com.doronyg.photoarchivededuplicator.retention.FileScoreInput
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.retention.RetentionRank
import com.doronyg.photoarchivededuplicator.retention.RetentionScorer
import com.doronyg.photoarchivededuplicator.retention.filenameEvidence
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import okio.Path

internal fun normalizedRecommendationFilename(name: String): String {
    val normalized = filenameEvidence(name).normalizedName
    val extension = normalized.lastIndexOf('.')
    return if (extension > 0) normalized.substring(0, extension) + normalized.substring(extension).lowercase()
    else normalized
}

internal fun currentRecommendationScore(
    file: FileRecord,
    exif: ExifRecord?,
    presentation: RetentionPresentation?,
    scorer: RetentionScorer,
): FileScore? {
    if (presentation == null || presentation.rank in setOf(
            RetentionRank.MISSING, RetentionRank.STALE, RetentionRank.INCOMPLETE)) return null
    return presentation.score?.takeIf {
        it.input == FileScoreInput.from(file) && it.identity == scorer.identity && it.value.isFinite() &&
            scorer.hasCurrentExifEvidence(it, file, exif)
    }
}

internal fun currentRecommendationExif(file: FileRecord, record: ExifRecord?): ExifRecord? =
    record?.takeIf {
        it.input == ExifInput.from(file) && it.result.status == ExifStatus.EXTRACTED &&
            it.readerId == "kim" && it.readerVersion == KIM_VERSION
    }

private fun validOffset(raw: String?): String? = raw?.trim()?.takeIf(::isValidExifOffset)

private fun validSubsecond(raw: String?): String? = raw?.trim()?.takeIf {
    it.isNotEmpty() && it.all { digit -> digit in '0'..'9' }
}?.trimEnd('0')

private fun cameraComponent(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

/** Cached metadata supports review only; it cannot establish byte-for-byte identity. */
class DuplicateConfidenceCalculator(
    private val scorer: RetentionScorer = RetentionScorer(),
    private val config: DuplicateConfidenceConfig = DuplicateConfidenceConfig(),
) {
    fun assess(
        files: List<FileRecord>,
        exif: Map<Path, ExifRecord>,
        retention: Map<Path, RetentionPresentation>,
    ): DuplicateConfidenceAssessment {
        fun unavailable(reason: ConfidenceReason, explanation: String) =
            DuplicateConfidenceAssessment(null, ConfidenceAssessment(0, setOf(reason), explanation), emptyMap())
        if (files.size < 2) return unavailable(ConfidenceReason.INSUFFICIENT_FILES,
            AppStrings.recommendations.insufficientConfidenceFiles)
        if (files.map { it.path }.distinct().size != files.size) return unavailable(ConfidenceReason.REPEATED_FILE_PATH,
            AppStrings.recommendations.repeatedConfidencePath)
        if (files.map { it.sizeBytes }.distinct().size != 1) return unavailable(ConfidenceReason.SIZE_MISMATCH,
            AppStrings.recommendations.confidenceSizeMismatch)

        val ordered = files.sortedBy { it.path.toString() }
        val scored = ordered.mapNotNull { file ->
            currentRecommendationScore(file, exif[file.path], retention[file.path], scorer)?.let { file to it.value }
        }.sortedWith(compareByDescending<Pair<FileRecord, Double>> { it.second }.thenBy { it.first.path.toString() })
        val reference = scored.firstOrNull()?.first ?: ordered.first()
        val referenceReason = if (scored.isEmpty()) ConfidenceReason.DETERMINISTIC_REFERENCE_FALLBACK
            else ConfidenceReason.HIGHEST_CURRENT_RETENTION_REFERENCE
        val referenceExplanation = if (scored.isEmpty())
            AppStrings.recommendations.fallbackReference(reference.path)
        else AppStrings.recommendations.highestScoreReference(reference.path)
        val comparisons = ordered.filter { it.path != reference.path }.associate { file ->
            file.path to compare(file, reference, exif)
        }
        val weakest = comparisons.values.minBy { it.assessment.score }
        val groupConflicts = groupConflicts(ordered, exif)
        val score = if (groupConflicts.isEmpty()) weakest.assessment.score
            else minOf(weakest.assessment.score, config.contradictionCap)
        val reasons = comparisons.values.flatMap { it.assessment.reasonCodes }.toSet() +
            referenceReason + ConfidenceReason.WEAKEST_MEMBER +
            (if (scored.isEmpty()) setOf(ConfidenceReason.EVIDENCE_UNAVAILABLE) else emptySet()) +
            groupConflicts.map { it.reasonCode } +
            (if (groupConflicts.isNotEmpty()) setOf(ConfidenceReason.CONFLICTING_EXIF_METADATA) else emptySet()) +
            (if (score < weakest.assessment.score) setOf(ConfidenceReason.CONTRADICTION_CAP) else emptySet())
        val conflictExplanation = if (groupConflicts.isEmpty()) "" else
            AppStrings.recommendations.groupExifContradictions(groupConflicts.joinToString(" ") { it.explanation }) + " " +
                if (score < weakest.assessment.score)
                    AppStrings.recommendations.cappedGroupConfidence(weakest.assessment.score, score)
                else AppStrings.recommendations.uncappedGroupConfidence(score)
        return DuplicateConfidenceAssessment(reference, ConfidenceAssessment(score, reasons,
            AppStrings.recommendations.groupConfidence(referenceExplanation, weakest.file.path, reference.path,
                weakest.assessment.score, conflictExplanation, score)), comparisons)
    }

    private fun groupConflicts(files: List<FileRecord>, exif: Map<Path, ExifRecord>): List<ConfidenceEvidence> {
        val known = mutableMapOf<String, Pair<Any, Path>>()
        val conflicted = mutableSetOf<String>()
        val conflicts = mutableListOf<ConfidenceEvidence>()
        for (file in files) {
            val metadata = currentRecommendationExif(file, exif[file.path])?.result?.metadata ?: continue
            fun check(label: String, value: Any?, reason: ConfidenceReason) {
                if (value == null || label in conflicted) return
                val first = known[label]
                if (first == null) {
                    known[label] = value to file.path
                } else if (first.first != value) {
                    conflicted += label
                    conflicts += ConfidenceEvidence(reason, 0,
                        AppStrings.recommendations.groupFieldConflict(label, first.second, first.first, file.path, value))
                }
            }
            check(AppStrings.recommendations.captureTimestamp, validatedExifTimestamp(metadata.capturedAt), ConfidenceReason.CAPTURE_TIMESTAMP_CONFLICT)
            check(AppStrings.recommendations.cameraMake, cameraComponent(metadata.make), ConfidenceReason.CAMERA_MAKE_CONFLICT)
            check(AppStrings.recommendations.cameraModel, cameraComponent(metadata.model), ConfidenceReason.CAMERA_MODEL_CONFLICT)
            check(AppStrings.recommendations.positiveWidth, metadata.width?.takeIf { it > 0 }, ConfidenceReason.DIMENSIONS_CONFLICT)
            check(AppStrings.recommendations.positiveHeight, metadata.height?.takeIf { it > 0 }, ConfidenceReason.DIMENSIONS_CONFLICT)
            check(AppStrings.recommendations.captureOffset, validOffset(metadata.offsetOriginal), ConfidenceReason.CAPTURE_OFFSET_CONFLICT)
            check(AppStrings.recommendations.captureSubseconds, validSubsecond(metadata.subsecondOriginal), ConfidenceReason.CAPTURE_SUBSECOND_CONFLICT)
            check(AppStrings.recommendations.orientation, metadata.orientation?.takeIf { it in 1..8 }, ConfidenceReason.ORIENTATION_CONFLICT)
        }
        return conflicts
    }

    private fun compare(file: FileRecord, reference: FileRecord, exif: Map<Path, ExifRecord>): FileDuplicateConfidence {
        val evidence = mutableListOf<ConfidenceEvidence>()
        var contradictions = false
        fun add(reason: ConfidenceReason, points: Int, explanation: String) {
            evidence += ConfidenceEvidence(reason, points, explanation)
        }
        fun <T> field(
            label: String, left: T?, right: T?, weight: Int,
            match: ConfidenceReason, mismatch: ConfidenceReason, semantic: Boolean = true,
        ) {
            when {
                left == null || right == null -> add(ConfidenceReason.EVIDENCE_UNAVAILABLE, 0,
                    AppStrings.recommendations.unavailableField(label, weight))
                left == right -> add(match, weight, AppStrings.recommendations.matchingField(label, left, weight))
                else -> {
                    if (semantic) contradictions = true
                    add(mismatch, 0, AppStrings.recommendations.differingField(label, left, right, weight, semantic))
                }
            }
        }

        add(ConfidenceReason.SIZE_NOT_SCORED, 0, AppStrings.recommendations.equalSizePrerequisite)
        field(AppStrings.recommendations.normalizedFilename, normalizedRecommendationFilename(file.name), normalizedRecommendationFilename(reference.name),
            config.filenameWeight, ConfidenceReason.NORMALIZED_FILENAME_MATCH, ConfidenceReason.NORMALIZED_FILENAME_DIFFERS, false)
        field(AppStrings.recommendations.storedModifiedTimestamp, file.modifiedAt, reference.modifiedAt, config.modifiedWeight,
            ConfidenceReason.MODIFIED_TIMESTAMP_MATCH, ConfidenceReason.MODIFIED_TIMESTAMP_DIFFERS, false)
        fun metadata(member: FileRecord): ExifMetadata? {
            val stored = exif[member.path]
            val current = currentRecommendationExif(member, stored)
            if (current == null) add(ConfidenceReason.EVIDENCE_UNAVAILABLE, 0,
                AppStrings.recommendations.unavailableExif(member.path))
            return current?.result?.metadata
        }
        val left = metadata(file)
        val right = metadata(reference)
        field(AppStrings.recommendations.captureTimestampSeconds,
            validatedExifTimestamp(left?.capturedAt), validatedExifTimestamp(right?.capturedAt),
            config.captureWeight, ConfidenceReason.CAPTURE_TIMESTAMP_MATCH, ConfidenceReason.CAPTURE_TIMESTAMP_CONFLICT)
        field(AppStrings.recommendations.cameraMake, cameraComponent(left?.make),
            cameraComponent(right?.make), config.cameraMakeWeight,
            ConfidenceReason.CAMERA_MAKE_MATCH, ConfidenceReason.CAMERA_MAKE_CONFLICT)
        field(AppStrings.recommendations.cameraModel, cameraComponent(left?.model),
            cameraComponent(right?.model), config.cameraModelWeight,
            ConfidenceReason.CAMERA_MODEL_MATCH, ConfidenceReason.CAMERA_MODEL_CONFLICT)

        val lw = left?.width?.takeIf { it > 0 }
        val lh = left?.height?.takeIf { it > 0 }
        val rw = right?.width?.takeIf { it > 0 }
        val rh = right?.height?.takeIf { it > 0 }
        if ((lw != null && rw != null && lw != rw) || (lh != null && rh != null && lh != rh)) {
            contradictions = true
            add(ConfidenceReason.DIMENSIONS_CONFLICT, 0,
                AppStrings.recommendations.conflictingDimensions(lw, lh, rw, rh, config.dimensionsWeight))
        } else if (lw != null && lh != null && rw != null && rh != null) {
            add(ConfidenceReason.DIMENSIONS_MATCH, config.dimensionsWeight,
                AppStrings.recommendations.matchingDimensions(lw, lh, config.dimensionsWeight))
        } else {
            add(ConfidenceReason.EVIDENCE_UNAVAILABLE, 0,
                AppStrings.recommendations.unavailableDimensions(config.dimensionsWeight))
        }
        field(AppStrings.recommendations.captureOffsetNoPoints, validOffset(left?.offsetOriginal), validOffset(right?.offsetOriginal),
            0, ConfidenceReason.CAPTURE_OFFSET_MATCH, ConfidenceReason.CAPTURE_OFFSET_CONFLICT)
        field(AppStrings.recommendations.captureSubsecondsNoPoints,
            validSubsecond(left?.subsecondOriginal), validSubsecond(right?.subsecondOriginal), 0,
            ConfidenceReason.CAPTURE_SUBSECOND_MATCH, ConfidenceReason.CAPTURE_SUBSECOND_CONFLICT)
        field(AppStrings.recommendations.orientationNoPoints, left?.orientation?.takeIf { it in 1..8 },
            right?.orientation?.takeIf { it in 1..8 }, 0, ConfidenceReason.ORIENTATION_MATCH, ConfidenceReason.ORIENTATION_CONFLICT)
        val rawScore = evidence.sumOf { it.points }
        if (contradictions) {
            add(ConfidenceReason.CONFLICTING_EXIF_METADATA, 0,
                AppStrings.recommendations.exifBlocksRecommendation)
            if (rawScore > config.contradictionCap) add(ConfidenceReason.CONTRADICTION_CAP,
                config.contradictionCap - rawScore,
                AppStrings.recommendations.contradictionCap(rawScore, config.contradictionCap))
        }
        val score = evidence.sumOf { it.points }
        return FileDuplicateConfidence(file, reference, ConfidenceAssessment(score, evidence.map { it.reasonCode }.toSet(),
            AppStrings.recommendations.fileConfidence(file.path, reference.path, score, contradictions)), evidence, contradictions)
    }
}
