package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.exif.KIM_VERSION
import com.doronyg.photoarchivededuplicator.exif.isValidExifOffset
import com.doronyg.photoarchivededuplicator.exif.validatedExifTimestamp
import com.doronyg.photoarchivededuplicator.retention.FileScoreInput
import com.doronyg.photoarchivededuplicator.retention.FilenameEvidence
import com.doronyg.photoarchivededuplicator.retention.RetentionLocation
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.retention.RetentionRank
import com.doronyg.photoarchivededuplicator.retention.RetentionScorer
import com.doronyg.photoarchivededuplicator.retention.filenameEvidence
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import okio.Path

/** Pure group analysis of stored snapshots, never content verification or permission to delete. */
class DuplicateRecommendationEngine(private val scorer: RetentionScorer = RetentionScorer()) {
    fun recommend(
        files: List<FileRecord>,
        exif: Map<Path, ExifRecord>,
        retention: Map<Path, RetentionPresentation>,
        evidenceReady: Boolean = true,
    ): DuplicateGroupRecommendation {
        val ordered = files.sortedBy { it.path.toString() }
        val names = ordered.associate { it.path to filenameEvidence(it.path.name) }
        fun review(reason: RecommendationReason, explanation: String) =
            review(ordered, names, reason, explanation)

        if (!evidenceReady) return review(RecommendationReason.EVIDENCE_UPDATING,
            "Stored evidence is being refreshed or a scan/EXIF queue is active. Review later; preserve every file.")
        if (ordered.size < 2) return review(RecommendationReason.INSUFFICIENT_FILES,
            "At least two files are needed for a group recommendation.")
        if (ordered.map { it.path }.distinct().size != ordered.size) {
            return review(RecommendationReason.REPEATED_FILE_PATH, "The group repeats a file path; reload the group.")
        }
        if (ordered.map { it.sizeBytes }.distinct().size != 1) {
            return review(RecommendationReason.SIZE_MISMATCH, "File sizes differ; naming similarity is insufficient.")
        }

        val captures = mutableListOf<String>()
        val offsets = mutableListOf<String?>()
        val subseconds = mutableSetOf<String>()
        for (file in ordered) {
            val record = exif[file.path] ?: return review(RecommendationReason.MISSING_CAPTURE_EVIDENCE,
                "A file has no stored capture evidence. Filename suffixes and file sizes alone are not proof.")
            if (record.input != ExifInput.from(file) || record.readerId != "kim" || record.readerVersion != KIM_VERSION) {
                return review(RecommendationReason.STALE_CAPTURE_EVIDENCE,
                    "Stored EXIF does not match the file or current reader. Refresh evidence before choosing copies.")
            }
            if (record.result.status != ExifStatus.EXTRACTED) {
                return review(RecommendationReason.MISSING_CAPTURE_EVIDENCE,
                    "EXIF extraction was unsuccessful for a file. Preserve every file until capture evidence is available.")
            }
            val metadata = record.result.metadata
            captures += validatedExifTimestamp(metadata.capturedAt)
                ?: return review(RecommendationReason.INVALID_CAPTURE_EVIDENCE, "A stored capture timestamp is missing or invalid.")
            val offset = metadata.offsetOriginal?.trim()?.takeIf { it.isNotEmpty() }
            if (offset != null && !isValidExifOffset(offset)) {
                return review(RecommendationReason.INVALID_CAPTURE_EVIDENCE, "An original capture offset is invalid; no timezone is inferred.")
            }
            offsets += offset
            metadata.subsecondOriginal?.trim()?.takeIf { it.isNotEmpty() }?.let {
                if (!it.all { digit -> digit in '0'..'9' }) {
                    return review(RecommendationReason.INVALID_CAPTURE_EVIDENCE, "Original capture subseconds are invalid.")
                }
                subseconds += it.trimEnd('0')
            }
        }
        if (captures.distinct().size != 1) return review(RecommendationReason.CONFLICTING_CAPTURE_TIMESTAMPS,
            "Original capture timestamps conflict, including time of day. Preserve every file; no automatic action.")
        if (offsets.distinct().size != 1) return review(RecommendationReason.INCOMPATIBLE_CAPTURE_OFFSETS,
            "Original capture offsets differ or mix known and unknown timezones. No timezone conversion is inferred.")
        if (subseconds.size > 1) return review(RecommendationReason.CONFLICTING_CAPTURE_SUBSECONDS,
            "Known original capture subseconds conflict; second-resolution matching is insufficient.")

        for (file in ordered) {
            val presentation = retention[file.path]
            val score = presentation?.score ?: return review(RecommendationReason.MISSING_RETENTION_ASSESSMENT,
                "A current retention assessment is missing. Recompute scores using stored metadata before reviewing.")
            if (presentation.rank in setOf(RetentionRank.MISSING, RetentionRank.STALE, RetentionRank.INCOMPLETE) ||
                score.input != FileScoreInput.from(file) || score.identity != scorer.identity || !score.value.isFinite() ||
                !scorer.hasCurrentExifEvidence(score, file, exif[file.path])) {
                return review(RecommendationReason.STALE_RETENTION_ASSESSMENT,
                    "A retention assessment is outdated for this file or EXIF evidence. Recompute scores before choosing copies.")
            }
        }

        val redundant = mutableMapOf<Path, FileRecord>()
        val localFamilies = ordered.groupBy { it.directoryPath to normalizedFilename(names.getValue(it.path)) }
        for (family in localFamilies.values) {
            val original = family.singleOrNull { !names.getValue(it.path).isCopyVariant } ?: continue
            family.filter { names.getValue(it.path).isCopyVariant }.forEach { redundant[it.path] = original }
        }
        val hasLocalVariants = redundant.isNotEmpty()
        val representatives = ordered.filter { it.path !in redundant }
        val captureDate = captures.first().substringBefore(' ').replace(':', '-')
        val directories = representatives.associate { it.path to scorer.directoryEvidence(it) }
        val sameFamily = names.values.map(::normalizedFilename).distinct().size == 1
        val plainChronological = representatives.all {
            val directory = directories.getValue(it.path)
            directory.directlyDated && directory.location == RetentionLocation.ORDINARY
        }
        val organizationRoots = directories.values.map { it.organizationRoot }.distinct()
        val sameOrganization = organizationRoots.size == 1 && organizationRoots.first() != null
        val dateMatches = representatives.filter { directories.getValue(it.path).date == captureDate }
        val protectedByFilenameDate = representatives.any { file ->
            retention.getValue(file.path).score!!.factors.any {
                it.key == "exif-date-mismatch" && it.contribution == 0.0
            }
        }
        val chronologicalWinner = if (representatives.size > 1 && sameFamily && plainChronological && sameOrganization &&
            dateMatches.size == 1 && !protectedByFilenameDate) dateMatches.single() else null
        if (chronologicalWinner != null) {
            representatives.filter { it.path != chronologicalWinner.path }.forEach {
                redundant[it.path] = chronologicalWinner
            }
            redundant.keys.toList().forEach { redundant[it] = chronologicalWinner }
        }
        val preserved = ordered.filter { it.path !in redundant }
        if (redundant.isEmpty()) {
            return when {
                protectedByFilenameDate && plainChronological -> review(RecommendationReason.DIRECTORY_EVIDENCE_CONFLICT,
                    "Capture and filename/folder-date evidence conflict under the existing retention assessment. Preserve both locations.")
                representatives.map { it.directoryPath }.distinct().size > 1 && (!plainChronological || !sameOrganization) ->
                    review(RecommendationReason.UNKNOWN_OR_SPECIAL_LOCATION,
                        "A location has unknown or special collection significance. A higher retention score cannot justify discarding that location.")
                else -> review(RecommendationReason.NO_UNIQUE_ORIGINAL,
                    "There is no unique original or uniquely supported chronological location. Keep every file for review; score differences alone cannot decide.")
            }
        }
        val preferred = preserved.singleOrNull()
        val action = if (preferred != null) RecommendationAction.KEEP_ONE else RecommendationAction.KEEP_MULTIPLE
        val groupReasons = buildSet {
            add(RecommendationReason.METADATA_ONLY)
            add(RecommendationReason.MATCHING_SIZE_AND_CAPTURE)
            if (hasLocalVariants) add(RecommendationReason.SAME_DIRECTORY_COPY_VARIANTS)
            if (chronologicalWinner != null) add(RecommendationReason.EXIF_MATCHES_DIRECTORY)
            if (preferred == null) add(RecommendationReason.MULTIPLE_RETAINED_LOCATIONS)
        }
        val explanation = when {
            chronologicalWinner != null ->
                "Prefer the unique chronological location matching the capture date, not simply the highest retention score."
            preferred != null ->
                "Retain the unsuffixed original; same-directory naming variants share its size and capture evidence."
            else ->
                "Retain multiple representatives. Only clear same-directory naming variants are candidates; other names and locations remain preserved."
        } + " Recommendations only; files are not content-verified and no files are changed."

        return DuplicateGroupRecommendation(action,
            if (chronologicalWinner != null) EvidenceStrength.SUPPORTED else EvidenceStrength.STRONG,
            groupReasons, explanation, ordered.associate { file ->
                val hints = names.getValue(file.path)
                val original = redundant[file.path]
                val acrossDirectories = original != null && file.directoryPath != original.directoryPath
                val role = when {
                    original != null -> FileRecommendationRole.REDUNDANT_CANDIDATE
                    file == preferred -> FileRecommendationRole.PREFERRED
                    else -> FileRecommendationRole.PRESERVE
                }
                val reasons = buildSet {
                    add(RecommendationReason.MATCHING_SIZE_AND_CAPTURE)
                    if (hints.backupSuffix) add(RecommendationReason.BACKUP_TIMESTAMP_SUFFIX)
                    if (hints.numberedSuffix != null) add(RecommendationReason.NUMBERED_COPY_SUFFIX)
                    if (acrossDirectories) add(RecommendationReason.EXIF_CONTRADICTS_DIRECTORY)
                    else if (original != null) add(RecommendationReason.SAME_DIRECTORY_COPY_VARIANTS)
                    else if (file == chronologicalWinner) add(RecommendationReason.EXIF_MATCHES_DIRECTORY)
                    else if (!hints.isCopyVariant) add(RecommendationReason.UNSUFFIXED_ORIGINAL)
                    if (role == FileRecommendationRole.PRESERVE) add(RecommendationReason.MULTIPLE_RETAINED_LOCATIONS)
                }
                val fileExplanation = when {
                    acrossDirectories ->
                        "Likely redundant candidate: its date-only directory contradicts the shared capture date; prefer ${original.path}."
                    original != null ->
                        "Likely redundant naming variant of ${original.name} in this same directory; matching size and capture evidence."
                    file == chronologicalWinner ->
                        "Preferred copy: this chronological location matches the shared EXIF capture date."
                    role == FileRecommendationRole.PREFERRED ->
                        "Preferred copy: the unique unsuffixed original in this directory and filename family."
                    else ->
                        "Preserve this representative of its name/location; no rule establishes that the collection is expendable."
                }
                file.path to FileRecommendation(file, role, normalizedFilename(hints), reasons, fileExplanation)
            })
    }

    private fun review(
        files: List<FileRecord>,
        names: Map<Path, FilenameEvidence>,
        reason: RecommendationReason,
        explanation: String,
    ): DuplicateGroupRecommendation = DuplicateGroupRecommendation(
        RecommendationAction.REVIEW, EvidenceStrength.INSUFFICIENT,
        setOf(RecommendationReason.METADATA_ONLY, reason),
        "$explanation Recommendations only; not content-verified. No files are changed.",
        files.associate { file ->
            val hints = names.getValue(file.path)
            file.path to FileRecommendation(file, FileRecommendationRole.REVIEW,
                normalizedFilename(hints), buildSet {
                    add(reason)
                    if (hints.backupSuffix) add(RecommendationReason.BACKUP_TIMESTAMP_SUFFIX)
                    if (hints.numberedSuffix != null) add(RecommendationReason.NUMBERED_COPY_SUFFIX)
                },
                "Preserve for review. $explanation")
        },
    )

    private val FilenameEvidence.isCopyVariant: Boolean
        get() = backupSuffix || numberedSuffix != null

    private fun normalizedFilename(hints: FilenameEvidence): String {
        val name = hints.normalizedName
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot + 1) + name.substring(dot + 1).lowercase() else name
    }
}
