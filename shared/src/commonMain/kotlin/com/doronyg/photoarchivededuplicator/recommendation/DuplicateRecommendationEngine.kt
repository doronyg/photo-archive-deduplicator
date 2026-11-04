package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.classification.DirectoryClassification
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationRecord
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.isValidExifOffset
import com.doronyg.photoarchivededuplicator.exif.validatedExifTimestamp
import com.doronyg.photoarchivededuplicator.retention.FilenameEvidence
import com.doronyg.photoarchivededuplicator.retention.RetentionLocation
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.retention.RetentionScorer
import com.doronyg.photoarchivededuplicator.retention.filenameEvidence
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import okio.Path

/** Pure group analysis of stored snapshots, never content verification or permission to delete. */
class DuplicateRecommendationEngine(
    private val scorer: RetentionScorer = RetentionScorer(),
    confidenceConfig: DuplicateConfidenceConfig = DuplicateConfidenceConfig(),
    private val minimumDuplicateConfidence: Int = 75,
) {
    private val confidenceCalculator = DuplicateConfidenceCalculator(scorer, confidenceConfig)

    init {
        require(minimumDuplicateConfidence in 75..100) { "Redundant candidates require at least high duplicate confidence." }
    }

    fun recommend(
        files: List<FileRecord>,
        exif: Map<Path, ExifRecord>,
        retention: Map<Path, RetentionPresentation>,
        evidenceReady: Boolean = true,
        protectedPaths: Set<Path> = emptySet(),
        directoryClassifications: Map<Path, DirectoryClassificationRecord> = emptyMap(),
    ): DuplicateGroupRecommendation {
        val ordered = files.sortedBy { it.path.toString() }
        val filesByPath = ordered.associateBy { it.path }
        val names = ordered.associate { it.path to filenameEvidence(it.path.name) }
        val classified = ordered.map { it.directoryPath }.distinct().mapNotNull { directory ->
            directoryClassifications[directory]?.let { record ->
                val type = if (record.directoryPath == directory) record.classification else DirectoryClassification.UNKNOWN
                directory to type
            }
        }.toMap()
        fun collectionFloor(file: FileRecord): Boolean = classified[file.directoryPath] in
            setOf(DirectoryClassification.PROTECTED, DirectoryClassification.UNKNOWN)
        fun aiExplanation(directory: Path): String = classified[directory]?.let {
            "Cached AI ${it.name} applies to this exact directory $directory only; classifications are not inherited from ancestors."
        }.orEmpty()
        val duplicateConfidence = if (evidenceReady) confidenceCalculator.assess(ordered, exif, retention)
            else DuplicateConfidenceAssessment(null, ConfidenceAssessment(0,
                setOf(ConfidenceReason.EVIDENCE_UNAVAILABLE), "Evidence is updating; confidence has not been assessed."), emptyMap())
        fun review(reason: RecommendationReason, explanation: String): DuplicateGroupRecommendation {
            val result = review(ordered, names, reason, explanation, duplicateConfidence, ConfidenceAssessment(0,
                setOf(when (reason) {
                    RecommendationReason.INSUFFICIENT_FILES -> ConfidenceReason.INSUFFICIENT_FILES
                    RecommendationReason.SIZE_MISMATCH -> ConfidenceReason.SIZE_MISMATCH
                    RecommendationReason.REPEATED_FILE_PATH -> ConfidenceReason.REPEATED_FILE_PATH
                    RecommendationReason.MISSING_RETENTION_ASSESSMENT -> ConfidenceReason.MISSING_RETENTION_ASSESSMENT
                    RecommendationReason.STALE_RETENTION_ASSESSMENT -> ConfidenceReason.STALE_RETENTION_ASSESSMENT
                    RecommendationReason.EVIDENCE_UPDATING -> ConfidenceReason.EVIDENCE_UPDATING
                    RecommendationReason.DUPLICATE_CONFIDENCE_TOO_LOW -> ConfidenceReason.DUPLICATE_CONFIDENCE_INSUFFICIENT
                    RecommendationReason.CONFLICTING_EXIF_METADATA, RecommendationReason.CONFLICTING_CAPTURE_TIMESTAMPS,
                    RecommendationReason.CONFLICTING_CAPTURE_SUBSECONDS -> ConfidenceReason.CONFLICTING_EXIF_METADATA
                    else -> ConfidenceReason.EVIDENCE_UNAVAILABLE
                }), "Preserve for review; a safe redundant-candidate decision is not established. $explanation"))
            val annotated = if (classified.isEmpty()) result else result.copy(
                reasonCodes = result.reasonCodes + classified.values.map { it.recommendationReason },
                files = result.files.mapValues { (_, advice) ->
                    val type = classified[advice.file.directoryPath] ?: return@mapValues advice
                    val explanation = aiExplanation(advice.file.directoryPath)
                    advice.copy(reasonCodes = advice.reasonCodes + type.recommendationReason,
                        explanation = "${advice.explanation} $explanation",
                        retentionDecisionConfidence = advice.retentionDecisionConfidence.copy(
                            reasonCodes = advice.retentionDecisionConfidence.reasonCodes + type.confidenceReason,
                            explanation = "${advice.retentionDecisionConfidence.explanation} $explanation"))
                })
            return enforceRetentionPlanSafety(annotated, ordered, ordered.map { it.directoryPath }.toSet(), protectedPaths)
        }

        if (ordered.size < 2) return review(RecommendationReason.INSUFFICIENT_FILES,
            "At least two files are needed for a group recommendation.")
        if (ordered.map { it.path }.distinct().size != ordered.size) {
            return review(RecommendationReason.REPEATED_FILE_PATH, "The group repeats a file path; reload the group.")
        }
        if (ordered.map { it.sizeBytes }.distinct().size != 1) {
            return review(RecommendationReason.SIZE_MISMATCH, "File sizes differ; naming similarity is insufficient.")
        }
        if (ordered.any { it.path in protectedPaths }) {
            val explanation = "An explicitly protected file is present. Preserve every file; confidence never overrides protection."
            val decision = ConfidenceAssessment(100, setOf(ConfidenceReason.EXPLICIT_PROTECTION), explanation)
            return enforceRetentionPlanSafety(DuplicateGroupRecommendation(RecommendationAction.PROTECTED, EvidenceStrength.SUPPORTED,
                setOf(RecommendationReason.METADATA_ONLY, RecommendationReason.EXPLICIT_PROTECTION), explanation,
                ordered.associate { file -> file.path to FileRecommendation(file, FileRecommendationRole.PRESERVE,
                    normalizedRecommendationFilename(file.name), setOf(RecommendationReason.EXPLICIT_PROTECTION), explanation, decision) },
                duplicateConfidence), ordered, ordered.map { it.directoryPath }.toSet(), protectedPaths)
        }
        if (!evidenceReady) return review(RecommendationReason.EVIDENCE_UPDATING,
            "Stored evidence is being refreshed or a scan/EXIF queue is active. Review later; preserve every file.")

        val captures = mutableListOf<String>()
        val offsets = mutableListOf<String?>()
        val subseconds = mutableSetOf<String>()
        var invalidCapture: String? = null
        for (file in ordered) {
            val record = currentRecommendationExif(file, exif[file.path]) ?: continue
            val metadata = record.result.metadata
            if (metadata.capturedAt != null) {
                val capture = validatedExifTimestamp(metadata.capturedAt)
                if (capture != null) captures += capture else invalidCapture = "A stored capture timestamp is invalid."
            }
            val offset = metadata.offsetOriginal?.trim()?.takeIf { it.isNotEmpty() }
            if (offset != null && !isValidExifOffset(offset)) {
                invalidCapture = "An original capture offset is invalid; no timezone is inferred."
            }
            offsets += offset
            metadata.subsecondOriginal?.trim()?.takeIf { it.isNotEmpty() }?.let {
                if (!it.all { digit -> digit in '0'..'9' }) {
                    invalidCapture = "Original capture subseconds are invalid."
                }
                subseconds += it.trimEnd('0')
            }
        }
        val captureIssue = invalidCapture?.let { RecommendationReason.INVALID_CAPTURE_EVIDENCE to it } ?: when {
            captures.distinct().size > 1 -> RecommendationReason.CONFLICTING_CAPTURE_TIMESTAMPS to
                "Original capture timestamps conflict, including time of day. Preserve every file; no automatic action."
            offsets.distinct().size > 1 -> RecommendationReason.INCOMPATIBLE_CAPTURE_OFFSETS to
                "Original capture offsets differ or mix known and unknown timezones. No timezone conversion is inferred."
            subseconds.size > 1 -> RecommendationReason.CONFLICTING_CAPTURE_SUBSECONDS to
                "Known original capture subseconds conflict; second-resolution matching is insufficient."
            else -> null
        }

        for (file in ordered) {
            val presentation = retention[file.path]
            presentation?.score ?: return review(RecommendationReason.MISSING_RETENTION_ASSESSMENT,
                "A current retention assessment is missing. Recompute scores using stored metadata before reviewing.")
            if (currentRecommendationScore(file, exif[file.path], presentation, scorer) == null) {
                return review(RecommendationReason.STALE_RETENTION_ASSESSMENT,
                    "A retention assessment is outdated for this file or EXIF evidence. Recompute scores before choosing copies.")
            }
        }

        val redundant = mutableMapOf<Path, FileRecord>()
        val localDirectories = ordered.groupBy { it.directoryPath }
        for (members in localDirectories.values) {
            val representative = members.maxBy { retention.getValue(it.path).score!!.value }
            members.filter { it.path != representative.path }.forEach { redundant[it.path] = representative }
        }
        val hasLocalCopies = redundant.isNotEmpty()
        val hasLocalVariants = redundant.keys.any { names.getValue(it).isCopyVariant }
        val representatives = ordered.filter { it.path !in redundant }
        val captureDate = captures.distinct().singleOrNull()?.substringBefore(' ')?.replace(':', '-')
        val filenameDates = ordered.mapNotNull { scorer.filenameDateEvidence(it) }.distinct()
        val filenameDate = filenameDates.singleOrNull()
        val conflictingDates = filenameDates.size > 1 || (captureDate != null && filenameDate != null && captureDate != filenameDate)
        val preferredDate = captureDate ?: filenameDate
        val directories = representatives.associate { it.path to scorer.directoryEvidence(it) }
        val plainChronological = representatives.all {
            val directory = directories.getValue(it.path)
            directory.directlyDated && directory.location == RetentionLocation.ORDINARY
        }
        val organizationRoots = directories.values.map { it.organizationRoot }.distinct()
        val sameOrganization = organizationRoots.size == 1 && organizationRoots.first() != null
        val dateMatches = representatives.filter { preferredDate != null && directories.getValue(it.path).date == preferredDate }
        val protectedByFilenameDate = representatives.any { file ->
            retention.getValue(file.path).score!!.factors.any {
                it.key == "exif-date-mismatch" && it.contribution == 0.0
            }
        }
        val chronologicalWinner = if (representatives.size > 1 && plainChronological && sameOrganization &&
            dateMatches.size == 1 && !protectedByFilenameDate && !conflictingDates) dateMatches.single() else null
        if (chronologicalWinner != null) {
            representatives.filter { it.path != chronologicalWinner.path && !collectionFloor(it) }.forEach {
                redundant[it.path] = chronologicalWinner
            }
        }
        val rankedRepresentatives = representatives.sortedWith(
            compareByDescending<FileRecord> { retention.getValue(it.path).score!!.value }.thenBy { it.path.toString() })
        fun isBackup(file: FileRecord): Boolean = when (classified[file.directoryPath]) {
            DirectoryClassification.BACKUP -> true
            DirectoryClassification.PREFERRED, DirectoryClassification.PROTECTED, DirectoryClassification.UNKNOWN -> false
            null -> directories.getValue(file.path).location == RetentionLocation.BACKUP
        }
        fun qualifiesAsReplacement(candidate: FileRecord): Boolean {
            val location = directories.getValue(candidate.path)
            return candidate.path !in redundant &&
                (classified[candidate.directoryPath] == DirectoryClassification.PREFERRED ||
                    location.location != RetentionLocation.TEMPORARY) &&
                (location.date == null || (!conflictingDates && (preferredDate == null || location.date == preferredDate)))
        }
        val aiPreferred = representatives.filter {
            classified[it.directoryPath] == DirectoryClassification.PREFERRED && qualifiesAsReplacement(it)
        }.singleOrNull()
        val backupReplacement = aiPreferred ?: rankedRepresentatives.firstOrNull(::qualifiesAsReplacement)
        val reducedBackupDirectories = mutableSetOf<Path>()
        for (backup in rankedRepresentatives) {
            if (backup.path in redundant || !isBackup(backup)) continue
            if (backupReplacement == null || backupReplacement.path == backup.path ||
                (backupReplacement != aiPreferred &&
                    retention.getValue(backupReplacement.path).score!!.value <= retention.getValue(backup.path).score!!.value)) continue
            redundant[backup.path] = backupReplacement
            reducedBackupDirectories += backup.directoryPath
        }
        // Local variants must depend on the final survivor, never on a backup also marked redundant.
        for (path in redundant.keys.toList()) {
            var keeper = redundant.getValue(path)
            val visited = mutableSetOf(path)
            while (keeper.path in redundant) {
                if (!visited.add(keeper.path)) return review(RecommendationReason.RETENTION_PLAN_INVALID,
                    "Replacement dependencies form a cycle; preserve every file.")
                keeper = redundant.getValue(keeper.path)
            }
            redundant[path] = keeper
        }
        val preserved = ordered.filter { it.path !in redundant }
        val preferred = preserved.singleOrNull()
        val action = if (preferred != null) RecommendationAction.KEEP_ONE else RecommendationAction.KEEP_MULTIPLE
        val groupReasons = buildSet {
            add(RecommendationReason.METADATA_ONLY)
            add(if (captures.size == ordered.size && captures.distinct().size == 1) RecommendationReason.MATCHING_SIZE_AND_CAPTURE
                else RecommendationReason.MATCHING_DUPLICATE_EVIDENCE)
            if (hasLocalVariants) add(RecommendationReason.SAME_DIRECTORY_COPY_VARIANTS)
            if (hasLocalCopies) add(RecommendationReason.SAME_DIRECTORY_REPRESENTATIVE)
            if (chronologicalWinner != null) add(if (captureDate != null) RecommendationReason.EXIF_MATCHES_DIRECTORY
                else RecommendationReason.FILENAME_MATCHES_DIRECTORY)
            if (preferred == null) add(RecommendationReason.MULTIPLE_RETAINED_LOCATIONS)
            if (!plainChronological || !sameOrganization) add(RecommendationReason.UNKNOWN_OR_SPECIAL_LOCATION)
            if (conflictingDates || protectedByFilenameDate) add(RecommendationReason.DIRECTORY_EVIDENCE_CONFLICT)
            if (reducedBackupDirectories.isNotEmpty()) add(RecommendationReason.EXPENDABLE_BACKUP_LOCATION)
            classified.values.forEach { add(it.recommendationReason) }
        }
        val backupEvidence = ConfidenceAssessment(95, setOf(ConfidenceReason.EXPENDABLE_BACKUP_LOCATION,
            ConfidenceReason.BETTER_RETAINED_LOCATION),
            if (classified.isEmpty())
                "The existing backup/import/sync classification marks this location and its descendants expendable " +
                    "when a higher-scoring retained replacement exists without known folder-date contradictions."
            else "An exact-directory AI BACKUP result, or the unchanged legacy backup rule for an unclassified directory, " +
                "permits considering this location expendable only with a stronger retained replacement. " +
                "A unique AI PREFERRED location can supply that preference without a larger numerical score; " +
                "known folder-date contradictions and duplicate/replacement evidence still control the decision.")
        val decision = if (reducedBackupDirectories.isNotEmpty() && preferred != null) backupEvidence else
            retentionEvidence(localDirectories.values.toList(), retention, chronologicalWinner,
                captureDate, plainChronological && sameOrganization, conflictingDates || protectedByFilenameDate)
        if (captureIssue != null) return review(captureIssue.first, captureIssue.second)
        if (duplicateConfidence.hasContradictions) return review(RecommendationReason.CONFLICTING_EXIF_METADATA,
            "Known EXIF metadata conflicts. Equal size and other matching evidence cannot establish exact duplicates.")
        if (duplicateConfidence.score < minimumDuplicateConfidence) return review(RecommendationReason.DUPLICATE_CONFIDENCE_TOO_LOW,
            "The weakest reference comparison scores ${duplicateConfidence.score}/100, below $minimumDuplicateConfidence. Review whether these are duplicates.")
        val replacementConfidence = redundant.mapValues { (path, keeper) ->
            confidenceCalculator.assess(listOf(filesByPath.getValue(path), keeper), exif, retention)
        }
        replacementConfidence.entries.firstOrNull {
            it.value.hasContradictions || it.value.score < minimumDuplicateConfidence
        }?.let { (path, assessment) ->
            return review(RecommendationReason.DUPLICATE_CONFIDENCE_TOO_LOW,
                "The direct comparison between $path and its proposed retained replacement ${redundant.getValue(path).path} " +
                    "scores ${assessment.score}/100 or has contradictory evidence. Reference-only matching cannot justify this dependency.")
        }
        val localEvidence = localDirectories.mapValues { (_, members) ->
            if (members.size > 1) retentionEvidence(listOf(members), retention, null, null, false, false) else decision
        }
        val explanation = when {
            chronologicalWinner != null ->
                "Prefer the unique chronological location matching the ${if (captureDate != null) "capture" else "filename"} date, not simply the highest retention score."
            reducedBackupDirectories.isNotEmpty() ->
                "Prefer stronger retained copies over expendable backup/import/sync locations, including their descendants. Preserve every other required collection."
            preferred != null ->
                "Retain the highest-scoring same-directory representative; equal scores use a stable path tie-break, not proof of a unique original."
            else ->
                "Retain the required representatives. No stronger qualifying replacement was found for remaining backup copies; other collections remain preserved."
        } + " Recommendations only; files are not content-verified and no files are changed."

        val proposal = DuplicateGroupRecommendation(action,
            if (decision.score >= 90) EvidenceStrength.STRONG else EvidenceStrength.SUPPORTED,
            groupReasons, explanation, ordered.associate { file ->
                val hints = names.getValue(file.path)
                val original = redundant[file.path]
                val acrossDirectories = original != null && file.directoryPath != original.directoryPath
                val backupReduction = acrossDirectories && file.directoryPath in reducedBackupDirectories
                val backupFallback = original == null && isBackup(file)
                val classification = classified[file.directoryPath]
                val replacementClassification = original?.let { classified[it.directoryPath] }
                val role = when {
                    original != null -> FileRecommendationRole.REDUNDANT_CANDIDATE
                    file == preferred -> FileRecommendationRole.PREFERRED
                    else -> FileRecommendationRole.PRESERVE
                }
                val reasons = buildSet {
                    add(if (captures.size == ordered.size && captures.distinct().size == 1) RecommendationReason.MATCHING_SIZE_AND_CAPTURE
                        else RecommendationReason.MATCHING_DUPLICATE_EVIDENCE)
                    if (hints.backupSuffix) add(RecommendationReason.BACKUP_TIMESTAMP_SUFFIX)
                    if (hints.numberedSuffix != null) add(RecommendationReason.NUMBERED_COPY_SUFFIX)
                    if (backupReduction) add(RecommendationReason.EXPENDABLE_BACKUP_LOCATION)
                    else if (acrossDirectories) add(if (captureDate != null) RecommendationReason.EXIF_CONTRADICTS_DIRECTORY
                        else RecommendationReason.FILENAME_CONTRADICTS_DIRECTORY)
                    else if (original != null) add(RecommendationReason.SAME_DIRECTORY_REPRESENTATIVE)
                    else if (file == chronologicalWinner) add(if (captureDate != null) RecommendationReason.EXIF_MATCHES_DIRECTORY
                        else RecommendationReason.FILENAME_MATCHES_DIRECTORY)
                    else if (!hints.isCopyVariant) add(RecommendationReason.UNSUFFIXED_ORIGINAL)
                    if (original != null && hints.isCopyVariant) add(RecommendationReason.SAME_DIRECTORY_COPY_VARIANTS)
                    if (original != null) add(RecommendationReason.RETAINED_REPLACEMENT)
                    if (role == FileRecommendationRole.PRESERVE) add(RecommendationReason.MULTIPLE_RETAINED_LOCATIONS)
                    if (backupFallback) add(RecommendationReason.BACKUP_FALLBACK_COPY)
                    classification?.let { add(it.recommendationReason) }
                    if (acrossDirectories) replacementClassification?.let { add(it.recommendationReason) }
                }
                val baseFileExplanation = when {
                    backupReduction ->
                        if (classification != null || classified[original.directoryPath] != null)
                            "Likely redundant backup copy: a qualifying retained replacement ${original.path} remains preserved. " +
                                aiExplanation(original.directoryPath)
                        else "Likely redundant backup/import/sync copy: this location is expendable because the higher-scoring copy ${original.path} remains preserved."
                    acrossDirectories ->
                        "Likely redundant candidate: its date-only directory does not match the supported date; prefer ${original.path}."
                    original != null ->
                        "Likely redundant copy; retain the highest-scoring representative ${original.name} in this same directory."
                    file == chronologicalWinner ->
                        "Preferred copy: this chronological location matches the supported ${if (captureDate != null) "capture" else "filename"} date."
                    role == FileRecommendationRole.PREFERRED ->
                        "Preferred representative: highest retention score in this directory; equal scores use stable path order."
                    backupFallback ->
                        "Preserve this backup copy: no stronger retained replacement satisfies the evidence rules."
                    else ->
                        "Preserve this representative of its name/location; no rule establishes that the collection is expendable."
                }
                val fileExplanation = if (classification == null) baseFileExplanation
                    else "$baseFileExplanation ${aiExplanation(file.directoryPath)}"
                val baseLocationEvidence = when {
                    original == null && collectionFloor(file) -> ConfidenceAssessment(90,
                        setOf(classification!!.confidenceReason, ConfidenceReason.COLLECTION_REPRESENTATIVE),
                        "Preserve a representative of this collection regardless of backup ancestors or another preferred location. " +
                            aiExplanation(file.directoryPath))
                    backupReduction -> backupEvidence
                    backupFallback -> ConfidenceAssessment(90, setOf(ConfidenceReason.BACKUP_FALLBACK_COPY),
                        "No stronger qualifying retained replacement establishes that this backup copy can be redundant; preserve it.")
                    chronologicalWinner != null && (acrossDirectories || file == chronologicalWinner) -> decision
                    original != null || preferred != null -> localEvidence.getValue(file.directoryPath)
                    else -> decision
                }
                val classificationReasons = buildSet {
                    classification?.let { add(it.confidenceReason) }
                    if (acrossDirectories) replacementClassification?.let { add(it.confidenceReason) }
                }
                val locationEvidence = if (classificationReasons.isEmpty()) baseLocationEvidence else
                    baseLocationEvidence.copy(reasonCodes = baseLocationEvidence.reasonCodes + classificationReasons,
                        explanation = "${baseLocationEvidence.explanation} ${aiExplanation(file.directoryPath)} " +
                            if (acrossDirectories) aiExplanation(original.directoryPath) else "")
                val evidence = if (role == FileRecommendationRole.PRESERVE &&
                    localDirectories.getValue(file.directoryPath).size > 1) {
                    val local = localEvidence.getValue(file.directoryPath)
                    locationEvidence.copy(reasonCodes = locationEvidence.reasonCodes + local.reasonCodes,
                        explanation = "${locationEvidence.explanation} Local representative: ${local.explanation}")
                } else locationEvidence
                val confidence = if (original != null) {
                    val directComparison = replacementConfidence.getValue(file.path).comparisons.values.single()
                    ConfidenceAssessment(
                    minOf(evidence.score, duplicateConfidence.score, replacementConfidence.getValue(file.path).score),
                    evidence.reasonCodes + directComparison.assessment.reasonCodes + setOf(ConfidenceReason.RETAINED_REPLACEMENT,
                        ConfidenceReason.DIRECT_REPLACEMENT_EVIDENCE, ConfidenceReason.CONTENT_NOT_VERIFIED),
                    "Candidate only while ${original.path} remains preserved. ${evidence.explanation} " +
                        "Decision confidence is limited by the direct replacement comparison " +
                        "(${replacementConfidence.getValue(file.path).score}/100) and group evidence (${duplicateConfidence.score}/100). " +
                        "Direct evidence: ${directComparison.evidence.joinToString("; ") { "${it.reasonCode.name}: ${it.explanation}" }} " +
                        "Metadata is not byte verification or permission to delete.")
                } else evidence.copy(
                    reasonCodes = evidence.reasonCodes + if (role == FileRecommendationRole.PREFERRED)
                        setOf(ConfidenceReason.DESIGNATED_SURVIVOR)
                    else if (backupFallback) setOf(ConfidenceReason.BACKUP_FALLBACK_COPY)
                    else setOf(ConfidenceReason.COLLECTION_REPRESENTATIVE),
                    explanation = if (role == FileRecommendationRole.PREFERRED)
                        "Preserve ${file.path} as the designated surviving representative. ${evidence.explanation}"
                    else if (backupFallback) "Preserve ${file.path} as a necessary backup fallback. ${evidence.explanation}"
                    else "Preserve ${file.path} to keep this collection represented. ${evidence.explanation}",
                )
                file.path to FileRecommendation(file, role, normalizedRecommendationFilename(file.name), reasons,
                    fileExplanation, confidence, original?.path)
            }, duplicateConfidence)
        val requiredDirectories = (if (chronologicalWinner != null) setOf(chronologicalWinner.directoryPath)
            else localDirectories.keys - reducedBackupDirectories) +
            representatives.filter(::collectionFloor).map { it.directoryPath }
        return enforceRetentionPlanSafety(proposal, ordered, requiredDirectories, protectedPaths)
    }

    private fun review(
        files: List<FileRecord>,
        names: Map<Path, FilenameEvidence>,
        reason: RecommendationReason,
        explanation: String,
        duplicateConfidence: DuplicateConfidenceAssessment,
        decisionConfidence: ConfidenceAssessment,
    ): DuplicateGroupRecommendation = DuplicateGroupRecommendation(
        RecommendationAction.REVIEW, EvidenceStrength.INSUFFICIENT,
        setOf(RecommendationReason.METADATA_ONLY, reason),
        "$explanation Recommendations only; not content-verified. No files are changed.",
        files.associate { file ->
            val hints = names.getValue(file.path)
            file.path to FileRecommendation(file, FileRecommendationRole.REVIEW,
                normalizedRecommendationFilename(file.name), buildSet {
                    add(reason)
                    if (hints.backupSuffix) add(RecommendationReason.BACKUP_TIMESTAMP_SUFFIX)
                    if (hints.numberedSuffix != null) add(RecommendationReason.NUMBERED_COPY_SUFFIX)
                },
                "Preserve for review. $explanation", decisionConfidence)
        }, duplicateConfidence,
    )

    private val FilenameEvidence.isCopyVariant: Boolean
        get() = backupSuffix || numberedSuffix != null

    private val DirectoryClassification.recommendationReason: RecommendationReason
        get() = when (this) {
            DirectoryClassification.PREFERRED -> RecommendationReason.AI_PREFERRED
            DirectoryClassification.BACKUP -> RecommendationReason.AI_BACKUP
            DirectoryClassification.PROTECTED -> RecommendationReason.AI_PROTECTED
            DirectoryClassification.UNKNOWN -> RecommendationReason.AI_UNKNOWN
        }

    private val DirectoryClassification.confidenceReason: ConfidenceReason
        get() = when (this) {
            DirectoryClassification.PREFERRED -> ConfidenceReason.AI_PREFERRED
            DirectoryClassification.BACKUP -> ConfidenceReason.AI_BACKUP
            DirectoryClassification.PROTECTED -> ConfidenceReason.AI_PROTECTED
            DirectoryClassification.UNKNOWN -> ConfidenceReason.AI_UNKNOWN
        }

    private fun retentionEvidence(
        directories: List<List<FileRecord>>,
        retention: Map<Path, RetentionPresentation>,
        chronologicalWinner: FileRecord?,
        captureDate: String?,
        plainChronological: Boolean,
        conflictingDates: Boolean,
    ): ConfidenceAssessment {
        if (chronologicalWinner != null) return ConfidenceAssessment(95, setOf(
            if (captureDate != null) ConfidenceReason.CAPTURE_DATE_MATCHES_DIRECTORY else ConfidenceReason.FILENAME_DATE_MATCHES_DIRECTORY,
        ), "One date-only directory matches the ${if (captureDate != null) "capture" else "filename"} date within the same organization. Location evidence, not the numerical score gap, selects it.")
        if (directories.size > 1) {
            return if (plainChronological) ConfidenceAssessment(90, setOf(
                ConfidenceReason.NO_RELIABLE_LOCATION_PREFERENCE, ConfidenceReason.MULTIPLE_RETAINED_LOCATIONS,
            ) + if (conflictingDates) setOf(ConfidenceReason.CONFLICTING_DIRECTORY_DATES) else emptySet(),
                "Preserve this dated location because no unique, uncontradicted preference makes it expendable. Format bonuses do not decide which location to discard.")
            else ConfidenceAssessment(90, setOf(ConfidenceReason.MULTIPLE_NONSTANDARD_DIRECTORIES,
                ConfidenceReason.UNKNOWN_OR_SPECIAL_LOCATION, ConfidenceReason.MULTIPLE_RETAINED_LOCATIONS),
                "Preserving one representative per collection is supported; confidence in that conservative decision does not establish a globally preferred collection.")
        }
        val scores = directories.single().map { retention.getValue(it.path).score!! }.sortedByDescending { it.value }
        val gap = scores.first().value - scores[1].value
        val reasons = mutableSetOf(ConfidenceReason.SAME_DIRECTORY, ConfidenceReason.HIGHEST_RETENTION_SCORE)
        val meaningfulKeys = setOf("backup-filename", "numbered-filename", "filename-date-mismatch", "exif-date-match", "exif-date-mismatch")
        val leaderFactors = scores.first().factors.filter { it.key in meaningfulKeys }.associate { it.key to it.contribution }
        val meaningful = scores.drop(1).all { alternative ->
            val factors = alternative.factors.filter { it.key in meaningfulKeys }.associate { it.key to it.contribution }
            (leaderFactors.keys + factors.keys).any { (leaderFactors[it] ?: 0.0) > (factors[it] ?: 0.0) }
        }
        when {
            gap == 0.0 -> reasons += ConfidenceReason.EQUAL_RETENTION_SCORES
            gap <= scorer.ambiguityMargin -> reasons += ConfidenceReason.CLOSE_RETENTION_SCORES
            meaningful -> reasons += ConfidenceReason.MEANINGFUL_RETENTION_ADVANTAGE
            else -> reasons += ConfidenceReason.SCORE_GAP_WITHOUT_SUPPORT
        }
        return ConfidenceAssessment(if (gap > scorer.ambiguityMargin && meaningful) 95 else 90, reasons,
            "High duplicate confidence supports keeping one representative in this same directory. " + when {
                gap == 0.0 -> "Retention scores are equal; any representative is acceptable and path order breaks the tie."
                gap <= scorer.ambiguityMargin -> "The score gap $gap is small, not decisive evidence of a better original; the highest score is only a representative preference."
                meaningful -> "A score gap of $gap is supported by filename/capture retention factors."
                else -> "The numerical gap $gap lacks stronger distinguishing factors; confidence comes from preserving the same directory, not the gap."
            })
    }
}
