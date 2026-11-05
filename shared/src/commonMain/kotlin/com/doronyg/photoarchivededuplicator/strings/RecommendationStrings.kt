package com.doronyg.photoarchivededuplicator.strings

import okio.Path

internal object RecommendationStrings {
    const val highestRetentionScore = "Highest retention score"
    const val tiedRetentionScores = "Tied - ambiguous"
    const val closeRetentionScores = "Close scores - ambiguous"
    const val lowerRetentionScore = "Lower retention score"
    const val notScored = "Not scored"
    const val outdatedScore = "Outdated score"
    const val incompleteRetentionGroup = "Incomplete group - no recommendation"
    const val noRetentionEvidence = "No positive or negative retention evidence"
    const val missingScore = "-"

    // Score-factor explanations are persisted and participate in EXIF cache validity checks.
    fun preferredFolderDate(date: String?) =
        "Preferred chronological folder yyyy-MM-dd or date-prefixed folder ($date)."
    fun alternativeFolderDate(date: String?) = "Alternative date-folder organization ($date)."
    fun partialFolderDate(description: String) = "Partial $description directory organization."
    const val yearOrganization = "year"
    const val yearMonthOrganization = "year/month"
    fun fullDateOrganization(date: String) = "full date $date"
    fun filenameDateMatchesExif(filenameDate: String?, folderDate: String?) =
        "Filename date $filenameDate disagrees with folder date $folderDate, but stored EXIF agrees with the folder; no filename penalty."
    fun filenameDateMismatch(filenameDate: String?, folderDate: String?) =
        "Filename date $filenameDate disagrees with folder date $folderDate; filename dates are advisory, not verified capture dates."
    fun exifDateMatch(exifDate: String?, folderDate: String?) =
        "Stored EXIF capture date $exifDate matches folder date $folderDate; confirmed complete-date organization."
    fun exifMismatchFilenameProtectsFolder(exifDate: String?, folderDate: String?) =
        "Stored EXIF capture date $exifDate disagrees with folder date $folderDate, but the filename matches the folder; no penalty."
    fun overlappingDatePenalties(exifDate: String?, folderDate: String?) =
        "Stored EXIF capture date $exifDate disagrees with folder date $folderDate; overlapping filename/EXIF date penalties count only the strongest once."
    fun exifDateMismatch(exifDate: String?, folderDate: String?) =
        "Stored EXIF capture date $exifDate disagrees with folder date $folderDate."
    const val temporaryLocation = "Temporary directory location (counted once)."
    const val backupLocation = "Backup, import, or sync directory location (counted once)."
    const val picasaOriginalsLocation =
        "Picasa originals backup directory (.picasaoriginals); additional location penalty counted once."
    const val backupFilename = "Backup-style UTC timestamp suffix in filename (counted once); not capture-date evidence."
    fun numberedFilename(suffix: String) =
        "Numbered filename suffix $suffix (counted once); possible copy naming, not proof of duplication."
    fun modifiedDateSupport(date: String) = "Stored UTC modified date agrees with folder date $date."
    fun createdDateSupport(date: String) = "Stored UTC created date agrees with folder date $date."
    fun usableExifDate(date: String) =
        "Usable stored EXIF capture date $date; compared as a camera-local calendar date, without timezone conversion."

    const val insufficientConfidenceFiles = "At least two distinct files are needed; no duplicate confidence is inferred."
    const val repeatedConfidencePath = "A repeated path is not a second file; no duplicate confidence is inferred."
    const val confidenceSizeMismatch = "Different sizes contradict exact duplication. Equal sizes would earn no points."
    fun fallbackReference(path: Path) =
        "No current valid retention score is available; $path is a deterministic path-order reference, not a retention preference."
    fun highestScoreReference(path: Path) =
        "$path is the highest current retention-score reference; equal scores use path order."
    fun groupExifContradictions(evidence: String) =
        "Whole-group EXIF contradictions block recommendation regardless of the reference's missing fields: $evidence"
    fun cappedGroupConfidence(raw: Int, score: Int) =
        "Group confidence is capped from $raw to $score (adjustment ${score - raw})."
    fun uncappedGroupConfidence(score: Int) =
        "Group confidence remains $score, at or below the contradiction cap."
    fun groupConfidence(reference: String, weakest: Path, referencePath: Path, weakestScore: Int, conflicts: String, score: Int) =
        "$reference Weakest member $weakest compared with $referencePath scores $weakestScore/100." +
            (if (conflicts.isEmpty()) "" else " $conflicts") + " Group confidence is $score/100."
    fun groupFieldConflict(label: String, firstPath: Path, first: Any, path: Path, value: Any) =
        "$label differs between $firstPath ($first) and $path ($value)."
    fun unavailableField(label: String, weight: Int) =
        "$label unavailable or invalid for one or both files: +0/$weight; missing evidence is not a conflict."
    fun matchingField(label: String, value: Any, weight: Int) = "$label agrees ($value): +$weight."
    fun differingField(label: String, left: Any, right: Any, weight: Int, semantic: Boolean) =
        "$label differs ($left versus $right): +0/$weight. " +
            if (semantic) "Known EXIF disagreement contradicts exact duplication."
            else "Supporting evidence is absent; this is not a content contradiction."
    const val equalSizePrerequisite = "Equal stored sizes are a prerequisite only: +0."
    fun unavailableExif(path: Path) =
        "EXIF for $path is absent, failed, stale, or from a noncurrent reader; cached fields are unavailable."
    fun conflictingDimensions(lw: Int?, lh: Int?, rw: Int?, rh: Int?, weight: Int) =
        "Known positive dimensions conflict (${lw}x$lh versus ${rw}x$rh): +0/$weight; exact duplication is contradicted even if another dimension is unavailable."
    fun matchingDimensions(width: Int, height: Int, weight: Int) =
        "Complete positive dimensions agree (${width}x$height): +$weight."
    fun unavailableDimensions(weight: Int) =
        "Complete positive dimensions unavailable: +0/$weight; no conflict is inferred from missing fields."
    const val exifBlocksRecommendation =
        "Known reliable EXIF metadata contradicts exact duplication; recommendations must be blocked regardless of score."
    fun contradictionCap(raw: Int, cap: Int) =
        "EXIF contradictions cap raw confidence $raw at $cap: ${cap - raw} points."
    fun fileConfidence(path: Path, reference: Path, score: Int, contradictions: Boolean) =
        "$path versus $reference: $score/100 from cached metadata." +
            if (contradictions) " Known EXIF contradictions prevent an exact-duplicate recommendation." else ""
    const val captureTimestamp = "Camera-local capture timestamp"
    const val cameraMake = "Camera make"
    const val cameraModel = "Camera model"
    const val positiveWidth = "Positive width"
    const val positiveHeight = "Positive height"
    const val captureOffset = "Original capture offset"
    const val captureSubseconds = "Original capture subseconds"
    const val orientation = "EXIF orientation"
    const val normalizedFilename = "Normalized filename"
    const val storedModifiedTimestamp = "Exact stored modifiedAt milliseconds"
    const val captureTimestampSeconds = "Camera-local capture timestamp (seconds; no timezone conversion)"
    const val captureOffsetNoPoints = "Original capture offset (no correlated extra points)"
    const val captureSubsecondsNoPoints = "Original capture subseconds (numeric fraction; no correlated extra points)"
    const val orientationNoPoints = "EXIF orientation (no correlated extra points)"

    const val planChangedInputs = "The plan does not account for every original member with its current metadata."
    const val planNoCopy = "The plan would retain no copy."
    fun missingCollectionRepresentatives(directories: List<Path>) =
        "Required collections lose their representatives: $directories."
    fun preservedFileDependency(path: Path) = "A preserved file cannot depend on another keeper: $path."
    fun protectedFileRedundant(path: Path) = "A protected file is marked redundant: $path."
    fun missingReplacement(path: Path) =
        "Redundant candidate $path lacks a different, directly retained replacement in this group."
    fun lowDecisionConfidence(path: Path) = "Redundant candidate $path lacks high per-file decision confidence."
    const val invalidKeepOne = "KEEP_ONE must designate exactly one retained preferred file."
    const val invalidKeepMultiple = "KEEP_MULTIPLE must retain multiple representatives without claiming a single preferred file."
    const val invalidReviewCandidates = "Review/protection cannot mark files redundant."
    fun rejectedPlan(problems: List<String>) =
        "Unsafe retention plan rejected; preserve every file for review. ${problems.joinToString(" ")}"
    const val reassessChangedGroup =
        "Group membership or input metadata changed; reassess duplicate evidence before using this plan."

    fun exactDirectoryClassification(classification: String, directory: Path) =
        "Cached AI $classification applies to this exact directory $directory only; classifications are not inherited from ancestors."
    const val picasaPolicyPrecedence =
        "Deterministic .picasaoriginals backup policy takes precedence over AI location hints."
    const val evidenceUpdatingConfidence = "Evidence is updating; confidence has not been assessed."
    fun reviewDecision(explanation: String) =
        "Preserve for review; a safe redundant-candidate decision is not established. $explanation"
    const val insufficientGroupFiles = "At least two files are needed for a group recommendation."
    const val repeatedGroupPath = "The group repeats a file path; reload the group."
    const val groupSizeMismatch = "File sizes differ; naming similarity is insufficient."
    const val explicitProtection = "An explicitly protected file is present. Preserve every file; confidence never overrides protection."
    const val evidenceRefreshing =
        "Stored evidence is being refreshed or a scan/EXIF queue is active. Review later; preserve every file."
    const val invalidCaptureTimestamp = "A stored capture timestamp is invalid."
    const val invalidCaptureOffset = "An original capture offset is invalid; no timezone is inferred."
    const val invalidCaptureSubseconds = "Original capture subseconds are invalid."
    const val conflictingCaptureTimestamps =
        "Original capture timestamps conflict, including time of day. Preserve every file."
    const val incompatibleCaptureOffsets =
        "Original capture offsets differ or mix known and unknown timezones. No timezone conversion is inferred."
    const val conflictingCaptureSubseconds =
        "Known original capture subseconds conflict; second-resolution matching is insufficient."
    const val missingRetentionAssessment =
        "A current retention assessment is missing. Recompute scores using stored metadata before reviewing."
    const val staleRetentionAssessment =
        "A retention assessment is outdated for this file or EXIF evidence. Recompute scores before choosing copies."
    const val replacementCycle = "Replacement dependencies form a cycle; preserve every file."
    const val legacyBackupEvidence =
        "The existing backup/import/sync classification marks this location and its descendants expendable " +
            "when a higher-scoring retained replacement exists without known folder-date contradictions."
    const val classifiedBackupEvidence =
        "An exact-directory AI BACKUP result, or the unchanged legacy backup rule for an unclassified directory, " +
            "permits considering this location expendable only with a stronger retained replacement. " +
            "A unique AI PREFERRED location can supply that preference without a larger numerical score; " +
            "known folder-date contradictions and duplicate/replacement evidence still control the decision."
    const val conflictingExif =
        "Known EXIF metadata conflicts. Equal size and other matching evidence cannot establish exact duplicates."
    fun lowDuplicateConfidence(score: Int, minimum: Int) =
        "The weakest reference comparison scores $score/100, below $minimum. Review whether these are duplicates."
    fun insufficientReplacementEvidence(path: Path, replacement: Path, score: Int) =
        "The direct comparison between $path and its proposed retained replacement $replacement " +
            "scores $score/100 or has contradictory evidence. Reference-only matching cannot justify this dependency."
    private fun supportedDateSource(hasCaptureDate: Boolean) = if (hasCaptureDate) "capture" else "filename"
    fun preferChronologicalLocation(hasCaptureDate: Boolean) =
        "Prefer the unique chronological location matching the ${supportedDateSource(hasCaptureDate)} date, not simply the highest retention score."
    const val preferStrongerBackupReplacement =
        "Prefer stronger retained copies over expendable backup/import/sync locations, including their descendants. Preserve every other required collection."
    const val retainSameDirectoryRepresentative =
        "Retain the highest-scoring same-directory representative; equal scores use a stable path tie-break, not proof of a unique original."
    const val retainRequiredRepresentatives =
        "Retain the required representatives. No stronger qualifying replacement was found for remaining backup copies; other collections remain preserved."
    fun redundantClassifiedBackup(replacement: Path, classification: String) =
        "Likely redundant backup copy: a qualifying retained replacement $replacement remains preserved. $classification"
    fun redundantLegacyBackup(replacement: Path) =
        "Likely redundant backup/import/sync copy: this location is expendable because the higher-scoring copy $replacement remains preserved."
    fun redundantChronologicalCopy(replacement: Path) =
        "Likely redundant candidate: its date-only directory does not match the supported date; prefer $replacement."
    fun redundantLocalCopy(name: String) =
        "Likely redundant copy; retain the highest-scoring representative $name in this same directory."
    fun preferredChronologicalCopy(hasCaptureDate: Boolean) =
        "Preferred copy: this chronological location matches the supported ${supportedDateSource(hasCaptureDate)} date."
    const val preferredRepresentative =
        "Preferred representative: highest retention score in this directory; equal scores use stable path order."
    const val preserveBackupCopy = "Preserve this backup copy: no stronger retained replacement satisfies the evidence rules."
    const val preserveCollectionRepresentative =
        "Preserve this representative of its name/location; no rule establishes that the collection is expendable."
    fun collectionFloor(classification: String) =
        "Preserve a representative of this collection regardless of backup ancestors or another preferred location. $classification"
    const val necessaryBackupFallback =
        "No stronger qualifying retained replacement establishes that this backup copy can be redundant; preserve it."
    fun localRepresentativeEvidence(location: String, local: String) = "$location Local representative: $local"
    fun candidateDecision(replacement: Path, evidence: String, replacementScore: Int, groupScore: Int, directEvidence: String) =
        "Candidate only while $replacement remains preserved. $evidence " +
            "Decision confidence is limited by the direct replacement comparison " +
            "($replacementScore/100) and group evidence ($groupScore/100). Direct evidence: $directEvidence"
    fun namedEvidence(reason: String, explanation: String) = "$reason: $explanation"
    fun preserveDesignatedSurvivor(path: Path, evidence: String) =
        "Preserve $path as the designated surviving representative. $evidence"
    fun preserveNecessaryBackup(path: Path, evidence: String) =
        "Preserve $path as a necessary backup fallback. $evidence"
    fun preserveCollection(path: Path, evidence: String) =
        "Preserve $path to keep this collection represented. $evidence"
    fun preserveForReview(explanation: String) = "Preserve for review. $explanation"
    fun chronologicalRetentionEvidence(hasCaptureDate: Boolean) =
        "One date-only directory matches the ${supportedDateSource(hasCaptureDate)} date within the same organization. Location evidence, not the numerical score gap, selects it."
    const val preserveDatedLocation =
        "Preserve this dated location because no unique, uncontradicted preference makes it expendable. Format bonuses do not decide which location to discard."
    const val preserveMultipleCollections =
        "Preserving one representative per collection is supported; confidence in that conservative decision does not establish a globally preferred collection."
    fun sameDirectoryRetentionEvidence(reason: String) =
        "High duplicate confidence supports keeping one representative in this same directory. $reason"
    const val equalRetentionScores =
        "Retention scores are equal; any representative is acceptable and path order breaks the tie."
    fun smallScoreGap(gap: Double) =
        "The score gap $gap is small, not decisive evidence of a better original; the highest score is only a representative preference."
    fun meaningfulScoreGap(gap: Double) = "A score gap of $gap is supported by filename/capture retention factors."
    fun unsupportedScoreGap(gap: Double) =
        "The numerical gap $gap lacks stronger distinguishing factors; confidence comes from preserving the same directory, not the gap."
}
