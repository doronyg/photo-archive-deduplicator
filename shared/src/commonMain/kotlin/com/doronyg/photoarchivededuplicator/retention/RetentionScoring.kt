package com.doronyg.photoarchivededuplicator.retention

import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.exif.validatedExifTimestamp
import okio.Path
import kotlin.math.floor
import kotlin.time.Instant

data class ScoreIdentity(
    val type: String,
    val algorithmId: String,
    val algorithmVersion: String,
    val configurationId: String,
)

data class FileScoreInput(
    val path: Path,
    val directoryPath: Path,
    val sizeBytes: Long,
    val modifiedAt: Long?,
    val createdAt: Long?,
) {
    companion object {
        fun from(file: FileRecord): FileScoreInput = FileScoreInput(
            file.path, file.directoryPath, file.sizeBytes, file.modifiedAt, file.createdAt,
        )
    }
}

data class ScoreFactor(val key: String, val contribution: Double, val explanation: String)

data class FileScore(
    val input: FileScoreInput,
    val identity: ScoreIdentity,
    val value: Double,
    val factors: List<ScoreFactor>,
    val createdAt: Long,
)

data class RetentionConfig(
    val isoDateBonus: Double = 100.0,
    val alternativeDateBonus: Double = 40.0,
    val yearMonthDateBonus: Double = 45.0,
    val partialDateBonus: Double = 10.0,
    val backupPenalty: Double = -25.0,
    val temporaryPenalty: Double = -60.0,
    val modifiedDateBonus: Double = 2.0,
    val createdDateBonus: Double = 1.0,
    val filenameDateMismatchPenalty: Double = -40.0,
    val exifConfirmedDateScore: Double = 90.0,
    val exifDateMismatchPenalty: Double = -40.0,
    val backupFilenamePenalty: Double = -20.0,
    val numberedFilenamePenalty: Double = -10.0,
    val picasaOriginalsPenalty: Double = -10.0,
    val ambiguityMargin: Double = 10.0,
    val backupDirectoryNames: Set<String> = setOf(
        "MobileBackup", "Google Photos", "Picasa", "Backup", "Backups", "Import", "Imports", "Sync", "PhotoSync",
    ),
    val backupDirectoryWords: Set<String> = setOf("WhatsApp"),
    val temporaryDirectoryNames: Set<String> = setOf("Temp", "Tmp"),
    val temporaryDirectoryPrefixes: Set<String> = setOf(".Picasa3Temp_"),
) {
    init {
        val bonuses = listOf(isoDateBonus, alternativeDateBonus, yearMonthDateBonus, partialDateBonus,
            modifiedDateBonus, createdDateBonus, exifConfirmedDateScore)
        val penalties = listOf(backupPenalty, temporaryPenalty, filenameDateMismatchPenalty, exifDateMismatchPenalty,
            backupFilenamePenalty, numberedFilenamePenalty, picasaOriginalsPenalty)
        require((bonuses + penalties).all { it.isFinite() && it == floor(it) && it in -1_000_000.0..1_000_000.0 }) {
            "Scoring weights must be finite whole numbers within +/- 1,000,000."
        }
        require(bonuses.all { it >= 0.0 } && penalties.all { it <= 0.0 }) {
            "Bonuses must be nonnegative and penalties nonpositive."
        }
        require(isoDateBonus >= maxOf(alternativeDateBonus, yearMonthDateBonus) &&
            minOf(alternativeDateBonus, yearMonthDateBonus) >= partialDateBonus) {
            "Preferred date organization must score at least as highly as other formats; year-only evidence must remain weakest."
        }
        require(temporaryPenalty <= backupPenalty) { "Temporary locations must have at least the backup penalty." }
        require(modifiedDateBonus + createdDateBonus <= partialDateBonus) {
            "Timestamp support must remain weaker than directory organization."
        }
        require(ambiguityMargin.isFinite() && ambiguityMargin in 0.0..1_000_000.0) {
            "Ambiguity margin must be finite and nonnegative."
        }
        require((backupDirectoryNames + temporaryDirectoryNames + temporaryDirectoryPrefixes).all {
            it.isNotBlank() && '/' !in it && '\\' !in it
        }) { "Directory rules must be nonempty individual names or prefixes." }
        require(backupDirectoryWords.all { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }) {
            "Directory words must be nonempty letters/digits."
        }
    }
}

/** Pure metadata-only scoring. Dates in the nearest equally strong directory supply timestamp support. */
class RetentionScorer(config: RetentionConfig = RetentionConfig()) {
    private val config = config.copy(
        backupDirectoryNames = config.backupDirectoryNames.map { it.lowercase() }.toSet(),
        backupDirectoryWords = config.backupDirectoryWords.map { it.lowercase() }.toSet(),
        temporaryDirectoryNames = config.temporaryDirectoryNames.map { it.lowercase() }.toSet(),
        temporaryDirectoryPrefixes = config.temporaryDirectoryPrefixes.map { it.lowercase() }.toSet(),
    )

    val identity: ScoreIdentity = ScoreIdentity(
        type = "retention",
        algorithmId = "directory-retention",
        algorithmVersion = "6",
        configurationId = configurationId(this.config),
    )
    val ambiguityMargin: Double = config.ambiguityMargin

    fun score(file: FileRecord, assessedAt: Long, exif: ExifRecord? = null): FileScore {
        val components = file.directoryPath.segments
        val date = strongestDate(components)
        val fullDate = date?.isoDate
        val filename = filenameEvidence(file.path.name)
        val filenameDate = if (fullDate != null) filenameDate(filename.normalizedName) else null
        val filenameMismatch = fullDate != null && filenameDate != null && fullDate != filenameDate
        val exifDate = if (fullDate != null) usableExifDate(file, exif) else null
        val exifMatches = fullDate != null && exifDate == fullDate
        val exifMismatch = exifDate != null && exifDate != fullDate
        val filenameProtectsFolder = exifMismatch && filenameDate(filename.normalizedName, includeCameraDates = true) == fullDate
        val penalizeFilename = filenameMismatch && !exifMatches
        val penalizeExif = exifMismatch && !filenameProtectsFolder
        val factors = buildList {
            if (date != null) {
                val bonus = when (date.strength) {
                    PREFERRED_DATE -> config.isoDateBonus
                    ALTERNATIVE_DATE -> config.alternativeDateBonus
                    YEAR_MONTH -> config.yearMonthDateBonus
                    else -> config.partialDateBonus
                }
                val explanation = when (date.strength) {
                    PREFERRED_DATE -> "Preferred chronological folder yyyy-MM-dd or date-prefixed folder (${date.isoDate})."
                    ALTERNATIVE_DATE -> "Alternative date-folder organization (${date.isoDate})."
                    else -> "Partial ${date.description} directory organization."
                }
                add(ScoreFactor("directory-date", bonus, explanation))
            }
            if (filenameMismatch) {
                add(ScoreFactor("filename-date-mismatch", if (penalizeFilename) config.filenameDateMismatchPenalty else 0.0,
                    if (exifMatches) "Filename date $filenameDate disagrees with folder date $fullDate, but stored EXIF agrees with the folder; no filename penalty."
                    else "Filename date $filenameDate disagrees with folder date $fullDate; filename dates are advisory, not verified capture dates."))
            }
            exifEvidence(file, exif)?.let(::add)
            if (exifMatches && date != null) {
                val base = if (date.strength == PREFERRED_DATE) config.isoDateBonus else config.alternativeDateBonus
                val bonus = maxOf(0.0, minOf(config.isoDateBonus, config.exifConfirmedDateScore) - base)
                add(ScoreFactor("exif-date-match", bonus,
                    "Stored EXIF capture date $exifDate matches folder date $fullDate; confirmed complete-date organization."))
            } else if (exifMismatch) {
                val alreadyApplied = if (penalizeFilename) config.filenameDateMismatchPenalty else 0.0
                val penalty = if (filenameProtectsFolder) 0.0 else minOf(config.exifDateMismatchPenalty, alreadyApplied) - alreadyApplied
                val explanation = when {
                    filenameProtectsFolder ->
                        "Stored EXIF capture date $exifDate disagrees with folder date $fullDate, but the filename matches the folder; no penalty."
                    penalizeFilename ->
                        "Stored EXIF capture date $exifDate disagrees with folder date $fullDate; overlapping filename/EXIF date penalties count only the strongest once."
                    else -> "Stored EXIF capture date $exifDate disagrees with folder date $fullDate."
                }
                add(ScoreFactor("exif-date-mismatch", penalty, explanation))
            }
            val location = directoryEvidence(file).location
            if (location == RetentionLocation.TEMPORARY) {
                add(ScoreFactor("temporary-location", config.temporaryPenalty, "Temporary directory location (counted once)."))
            } else if (hasBackupLocation(components)) {
                add(ScoreFactor("backup-location", config.backupPenalty, "Backup, import, or sync directory location (counted once)."))
            }
            if (isPicasaOriginals(file)) {
                add(ScoreFactor("picasa-originals-location", config.picasaOriginalsPenalty,
                    "Picasa originals backup directory (.picasaoriginals); additional location penalty counted once."))
            }
            if (filename.backupSuffix) {
                add(ScoreFactor("backup-filename", config.backupFilenamePenalty,
                    "Backup-style UTC timestamp suffix in filename (counted once); not capture-date evidence."))
            }
            filename.numberedSuffix?.let {
                add(ScoreFactor("numbered-filename", config.numberedFilenamePenalty,
                    "Numbered filename suffix $it (counted once); possible copy naming, not proof of duplication."))
            }
            // Copied timestamps agreeing with an already contradicted folder must not restore its bonus.
            if (fullDate != null && !penalizeFilename && !penalizeExif) {
                if (utcDate(file.modifiedAt) == fullDate) {
                    add(ScoreFactor("modified-date-support", config.modifiedDateBonus, "Stored UTC modified date agrees with folder date $fullDate."))
                }
                if (utcDate(file.createdAt) == fullDate) {
                    add(ScoreFactor("created-date-support", config.createdDateBonus, "Stored UTC created date agrees with folder date $fullDate."))
                }
            }
        }
        return FileScore(FileScoreInput.from(file), identity, factors.sumOf { it.contribution }, factors, assessedAt)
    }

    internal fun hasCurrentExifEvidence(score: FileScore, file: FileRecord, exif: ExifRecord?): Boolean =
        score.factors.filter { it.key == EXIF_EVIDENCE } == listOfNotNull(exifEvidence(file, exif))

    internal fun hasCompleteDirectoryDate(file: FileRecord): Boolean =
        strongestDate(file.directoryPath.segments)?.isoDate != null

    internal fun filenameDateEvidence(file: FileRecord): String? =
        filenameDate(filenameEvidence(file.name).normalizedName, includeCameraDates = true)

    internal fun isPicasaOriginals(file: FileRecord): Boolean =
        file.directoryPath.segments.any { it.equals(".picasaoriginals", ignoreCase = true) }

    private fun hasBackupLocation(components: List<String>): Boolean = components.any { name ->
        val component = name.lowercase()
        component in config.backupDirectoryNames ||
            config.backupDirectoryWords.any { containsWord(component, it) }
    }

    internal fun directoryEvidence(file: FileRecord): DirectoryRetentionEvidence {
        val components = file.directoryPath.segments
        val date = strongestDate(components)
        val lowercase = components.map { it.lowercase() }
        val location = when {
            lowercase.any { component ->
                component in config.temporaryDirectoryNames ||
                    config.temporaryDirectoryPrefixes.any { component.startsWith(it) }
            } -> RetentionLocation.TEMPORARY
            hasBackupLocation(components) || isPicasaOriginals(file) -> RetentionLocation.BACKUP
            else -> RetentionLocation.ORDINARY
        }
        val directlyDated = date?.isoDate != null && date.endIndex == components.lastIndex &&
            !LABELED_ISO_DATE.matches(components.last())
        var organizationRoot: Path? = if (directlyDated) file.directoryPath else null
        if (directlyDated) repeat(date!!.componentCount) { organizationRoot = organizationRoot?.parent }
        return DirectoryRetentionEvidence(
            date = date?.isoDate,
            directlyDated = directlyDated,
            location = location,
            organizationRoot = organizationRoot,
        )
    }

    private fun exifEvidence(file: FileRecord, exif: ExifRecord?): ScoreFactor? {
        val date = usableExifDate(file, exif) ?: return null
        if (strongestDate(file.directoryPath.segments)?.isoDate == null) return null
        return ScoreFactor(EXIF_EVIDENCE, 0.0,
            "Usable stored EXIF capture date $date; compared as a camera-local calendar date, without timezone conversion.")
    }

    internal fun usableExifDate(file: FileRecord, record: ExifRecord?): String? {
        if (record == null || record.input != ExifInput.from(file) || record.result.status != ExifStatus.EXTRACTED) return null
        return validatedExifTimestamp(record.result.metadata.capturedAt)
            ?.substringBefore(' ')?.replace(':', '-')
    }

    private data class DirectoryDate(
        val strength: Int,
        val isoDate: String?,
        val description: String,
        val endIndex: Int = -1,
        val componentCount: Int = 1,
    )

    private fun strongestDate(components: List<String>): DirectoryDate? {
        var strongest: DirectoryDate? = null
        components.forEachIndexed { index, component ->
            val candidate = when {
                ISO_DATE.matches(component) -> parseDate(component, "-", PREFERRED_DATE)
                LABELED_ISO_DATE.matches(component) -> parseDate(component.substring(0, 10), "-", PREFERRED_DATE)
                UNDERSCORE_DATE.matches(component) -> parseDate(component, "_", ALTERNATIVE_DATE)
                COMPACT_DATE.matches(component) -> completeDate(
                    component.substring(0, 4), component.substring(4, 6), component.substring(6, 8), ALTERNATIVE_DATE,
                )
                index >= 2 && YEAR_PATTERN.matches(components[index - 2]) &&
                    MONTH_OR_DAY.matches(components[index - 1]) && MONTH_OR_DAY.matches(component) ->
                    completeDate(components[index - 2], components[index - 1], component, ALTERNATIVE_DATE)
                        ?.copy(componentCount = 3)
                YEAR_PATTERN.matches(component) && component.toInt() in 1..9999 ->
                    DirectoryDate(YEAR, null, "year")
                index >= 1 && YEAR_PATTERN.matches(components[index - 1]) &&
                    components[index - 1].toInt() in 1..9999 &&
                    MONTH_OR_DAY.matches(component) && component.toInt() in 1..12 ->
                    DirectoryDate(YEAR_MONTH, null, "year/month")
                else -> null
            }
            if (candidate != null && candidate.strength >= (strongest?.strength ?: 0)) {
                strongest = candidate.copy(endIndex = index)
            }
        }
        return strongest
    }

    private fun filenameDate(name: String, includeCameraDates: Boolean = false): String? {
        val match = WHATSAPP_FILENAME.matchEntire(name)
            ?: (if (includeCameraDates) CAMERA_DATE_FILENAME.matchEntire(name) else null) ?: return null
        return completeDate(match.groupValues[1], match.groupValues[2], match.groupValues[3], 0)?.isoDate
    }

    private fun containsWord(component: String, word: String): Boolean {
        var index = component.indexOf(word)
        while (index >= 0) {
            val end = index + word.length
            if ((index == 0 || !component[index - 1].isLetterOrDigit()) &&
                (end == component.length || !component[end].isLetterOrDigit())) return true
            index = component.indexOf(word, index + 1)
        }
        return false
    }

    private fun parseDate(component: String, delimiter: String, strength: Int): DirectoryDate? {
        val parts = component.split(delimiter)
        return completeDate(parts[0], parts[1], parts[2], strength)
    }

    private fun completeDate(year: String, month: String, day: String, strength: Int): DirectoryDate? {
        val y = year.toInt()
        val m = month.toInt()
        val d = day.toInt()
        if (y !in 1..9999 || m !in 1..12) return null
        val days = when (m) {
            2 -> if (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        if (d !in 1..days) return null
        val iso = "$year-$month-$day"
        return DirectoryDate(strength, iso, "full date $iso")
    }

    private fun utcDate(epochMillis: Long?): String? = epochMillis?.let {
        // Instant renders in UTC; out-of-calendar-range filesystem timestamps cannot match a folder date.
        Instant.fromEpochMilliseconds(it).toString().substringBefore('T')
    }

    private companion object {
        const val PREFERRED_DATE = 4
        const val ALTERNATIVE_DATE = 3
        const val YEAR_MONTH = 2
        const val YEAR = 1
        const val EXIF_EVIDENCE = "exif-date-evidence"
        val ISO_DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
        val LABELED_ISO_DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}[-_ ]+[^\\s_-].*")
        val WHATSAPP_FILENAME = Regex("(?:IMG|VID)-([0-9]{4})([0-9]{2})([0-9]{2})-WA[0-9]+(?:\\.[A-Z0-9]+)?",
            RegexOption.IGNORE_CASE)
        val CAMERA_DATE_FILENAME = Regex(
            "(?:IMG[_-]|VID[_-]|PXL[_-])?([0-9]{4})[-_]?([0-9]{2})[-_]?([0-9]{2})(?:[_-][0-9]{6}(?:[0-9]{3})?(?:[_.-][A-Z0-9]+)*)?(?:\\.[A-Z0-9]+)?",
            RegexOption.IGNORE_CASE,
        )
        val UNDERSCORE_DATE = Regex("[0-9]{4}_[0-9]{2}_[0-9]{2}")
        val COMPACT_DATE = Regex("[0-9]{8}")
        val YEAR_PATTERN = Regex("[0-9]{4}")
        val MONTH_OR_DAY = Regex("[0-9]{2}")

        fun configurationId(config: RetentionConfig): String = buildString {
            listOf(
                "isoDateBonus" to config.isoDateBonus,
                "alternativeDateBonus" to config.alternativeDateBonus,
                "yearMonthDateBonus" to config.yearMonthDateBonus,
                "partialDateBonus" to config.partialDateBonus,
                "backupPenalty" to config.backupPenalty,
                "temporaryPenalty" to config.temporaryPenalty,
                "modifiedDateBonus" to config.modifiedDateBonus,
                "createdDateBonus" to config.createdDateBonus,
                "filenameDateMismatchPenalty" to config.filenameDateMismatchPenalty,
                "exifConfirmedDateScore" to config.exifConfirmedDateScore,
                "exifDateMismatchPenalty" to config.exifDateMismatchPenalty,
                "backupFilenamePenalty" to config.backupFilenamePenalty,
                "numberedFilenamePenalty" to config.numberedFilenamePenalty,
                "picasaOriginalsPenalty" to config.picasaOriginalsPenalty,
                "ambiguityMargin" to config.ambiguityMargin,
            ).forEach { (key, value) ->
                append(key).append('=').append(if (value == 0.0) 0.0 else value).append(';')
            }
            listOf(
                "backupDirectoryNames" to config.backupDirectoryNames,
                "backupDirectoryWords" to config.backupDirectoryWords,
                "temporaryDirectoryNames" to config.temporaryDirectoryNames,
                "temporaryDirectoryPrefixes" to config.temporaryDirectoryPrefixes,
            ).forEach { (key, values) ->
                append(key).append('=')
                values.sorted().forEach { append(it.length).append(':').append(it) }
                append(';')
            }
        }
    }
}
