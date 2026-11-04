package com.doronyg.photoarchivededuplicator.retention

import com.doronyg.photoarchivededuplicator.exif.validatedExifTimestamp
import okio.Path

internal data class FilenameEvidence(
    val normalizedName: String,
    val backupSuffix: Boolean,
    val numberedSuffix: String?,
)

internal fun filenameEvidence(name: String): FilenameEvidence {
    val extensionIndex = name.lastIndexOf('.').takeIf { it > 0 }
    val extension = extensionIndex?.let { name.substring(it) }.orEmpty()
    var stem = extensionIndex?.let { name.substring(0, it) } ?: name
    var backup = false
    var numbered: String? = null
    while (true) {
        val backupMatch = BACKUP_COPY_SUFFIX.matchEntire(stem)
        if (backupMatch != null) {
            val parts = backupMatch.groupValues
            val timestamp = "${parts[2]}:${parts[3]}:${parts[4]} ${parts[5]}:${parts[6]}:${parts[7]}"
            if (validatedExifTimestamp(timestamp) == null) break
            backup = true
            stem = parts[1]
            continue
        }
        val match = NUMBERED_COPY_SUFFIX.matchEntire(stem) ?: break
        if (match.groupValues[2].toInt() == 0) break
        if (numbered == null) numbered = "-${match.groupValues[2]}"
        stem = match.groupValues[1]
    }
    return FilenameEvidence(stem + extension, backup, numbered)
}

internal enum class RetentionLocation { ORDINARY, BACKUP, TEMPORARY }

internal data class DirectoryRetentionEvidence(
    val date: String?,
    val directlyDated: Boolean,
    val location: RetentionLocation,
    val organizationRoot: Path? = null,
)

private val BACKUP_COPY_SUFFIX = Regex(
    "(.*\\S) \\(([0-9]{4})_([0-9]{2})_([0-9]{2}) ([0-9]{2})_([0-9]{2})_([0-9]{2}) UTC\\)",
    RegexOption.IGNORE_CASE,
)
private val NUMBERED_COPY_SUFFIX = Regex("(.+)-([0-9]{3})")
