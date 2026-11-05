package com.doronyg.photoarchivededuplicator.strings

internal object ReviewStrings {
    const val notice = "Review before deleting: matching metadata does not prove identical file contents."
    const val sameDirectoryName = "Same-directory metadata matches"
    const val sameDirectoryDescription = "Different filenames in the same folder with matching size and capture metadata."
    const val sameDirectoryEvidence = "Same folder, different filenames, equal sizes and matching capture metadata."
    const val folderMismatchName = "EXIF and folder date mismatch"
    const val folderMismatchDescription = "Copies share a capture date, but only the retained copy matches its folder date."
    const val fullConfidenceRegularName = "Regular duplicates (100/100)"
    const val fullConfidenceRegularDescription = "Matching filenames and sizes, with 100/100 duplicate confidence and redundant copies identified."
    const val fullConfidenceRegularEvidence = "Same filename and size; duplicate confidence is 100/100."
    const val directoryDate = "Directory date"
    const val captureDate = "EXIF capture date"
    const val dateComparison = "Date comparison"
    const val datesMatch = "Matches"
    const val datesDiffer = "Does not match"
    const val refresh = "Refresh candidates"
    const val waiting = "Waiting for scanning or EXIF analysis to finish."
    const val loading = "Loading recommendations..."
    const val selectedCategory = "Selected"
    const val selectCategory = "Select"
    const val noCategories = "No recommendation categories available."
    const val emptyCategory = "No recommendations in this category."
    const val showEvidence = "Show files and evidence"
    const val retainedCopy = "Retain"
    const val redundantCopy = "Redundant copy"

    fun folderMismatchEvidence(date: String) =
        "Shared capture date: $date. The retained copy matches its folder date; redundant copies do not. " +
            "Only folder-date mismatches are shown."
    fun configurationError(message: String?) = "Check Ollama settings: $message"
    fun loadError(message: String?) = "Could not load recommendations: $message"
    fun summary(groups: Long, files: Long, size: String) = "$groups groups | $files files | $size"
    fun overall(summary: String) = "Overall: $summary"
    fun retain(names: String) = "Retain: $names"
}
