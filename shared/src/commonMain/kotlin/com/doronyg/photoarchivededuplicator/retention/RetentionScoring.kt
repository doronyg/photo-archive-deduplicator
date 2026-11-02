package com.doronyg.photoarchivededuplicator.retention

import com.doronyg.photoarchivededuplicator.scan.FileRecord
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
    val yearMonthDateBonus: Double = 30.0,
    val partialDateBonus: Double = 10.0,
    val backupPenalty: Double = -25.0,
    val temporaryPenalty: Double = -60.0,
    val modifiedDateBonus: Double = 2.0,
    val createdDateBonus: Double = 1.0,
    val filenameDateMismatchPenalty: Double = -80.0,
    val ambiguityMargin: Double = 5.0,
    val backupDirectoryNames: Set<String> = setOf(
        "MobileBackup", "Google Photos", "Picasa", "Backup", "Backups", "Import", "Imports", "Sync", "PhotoSync",
    ),
    val backupDirectoryWords: Set<String> = setOf("WhatsApp"),
    val temporaryDirectoryNames: Set<String> = setOf("Temp", "Tmp"),
    val temporaryDirectoryPrefixes: Set<String> = setOf(".Picasa3Temp_"),
) {
    init {
        val bonuses = listOf(isoDateBonus, alternativeDateBonus, yearMonthDateBonus, partialDateBonus, modifiedDateBonus, createdDateBonus)
        val penalties = listOf(backupPenalty, temporaryPenalty, filenameDateMismatchPenalty)
        require((bonuses + penalties).all { it.isFinite() && it == floor(it) && it in -1_000_000.0..1_000_000.0 }) {
            "Scoring weights must be finite whole numbers within +/- 1,000,000."
        }
        require(bonuses.all { it >= 0.0 } && penalties.all { it <= 0.0 }) {
            "Bonuses must be nonnegative and penalties nonpositive."
        }
        require(isoDateBonus >= alternativeDateBonus && alternativeDateBonus >= yearMonthDateBonus &&
            yearMonthDateBonus >= partialDateBonus) {
            "Stronger date organization must not earn a smaller bonus."
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
        algorithmVersion = "3",
        configurationId = configurationId(this.config),
    )
    val ambiguityMargin: Double = config.ambiguityMargin

    fun score(file: FileRecord, assessedAt: Long): FileScore {
        val components = file.directoryPath.segments
        val date = strongestDate(components)
        val fullDate = date?.isoDate
        val filenameDate = if (fullDate != null) filenameDate(file.path.name) else null
        val dateMismatch = fullDate != null && filenameDate != null && fullDate != filenameDate
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
            if (dateMismatch) {
                add(ScoreFactor("filename-date-mismatch", config.filenameDateMismatchPenalty,
                    "Filename date $filenameDate disagrees with folder date $fullDate; filename dates are advisory, not verified capture dates."))
            }
            val lowercase = components.map { it.lowercase() }
            val temporary = lowercase.any { component ->
                component in config.temporaryDirectoryNames ||
                    config.temporaryDirectoryPrefixes.any { component.startsWith(it) }
            }
            if (temporary) {
                add(ScoreFactor("temporary-location", config.temporaryPenalty, "Temporary directory location (counted once)."))
            } else if (lowercase.any { component ->
                component in config.backupDirectoryNames || config.backupDirectoryWords.any { containsWord(component, it) }
            }) {
                add(ScoreFactor("backup-location", config.backupPenalty, "Backup, import, or sync directory location (counted once)."))
            }
            // Copied timestamps agreeing with an already contradicted folder must not restore its bonus.
            if (fullDate != null && !dateMismatch) {
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

    private data class DirectoryDate(val strength: Int, val isoDate: String?, val description: String)

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
                YEAR_PATTERN.matches(component) && component.toInt() in 1..9999 ->
                    DirectoryDate(YEAR, null, "year")
                index >= 1 && YEAR_PATTERN.matches(components[index - 1]) &&
                    components[index - 1].toInt() in 1..9999 &&
                    MONTH_OR_DAY.matches(component) && component.toInt() in 1..12 ->
                    DirectoryDate(YEAR_MONTH, null, "year/month")
                else -> null
            }
            if (candidate != null && candidate.strength >= (strongest?.strength ?: 0)) strongest = candidate
        }
        return strongest
    }

    private fun filenameDate(name: String): String? {
        val match = WHATSAPP_FILENAME.matchEntire(name) ?: return null
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
        val ISO_DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
        val LABELED_ISO_DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}[-_ ]+[^\\s_-].*")
        val WHATSAPP_FILENAME = Regex("(?:IMG|VID)-([0-9]{4})([0-9]{2})([0-9]{2})-WA[0-9]+(?:\\.[A-Z0-9]+)?",
            RegexOption.IGNORE_CASE)
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
