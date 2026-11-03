package com.doronyg.photoarchivededuplicator.retention

import com.doronyg.photoarchivededuplicator.scan.FileRecord
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class RetentionScoringTest {
    private val scorer = RetentionScorer()

    private fun file(
        directory: String,
        modifiedAt: Long? = null,
        createdAt: Long? = null,
        name: String = "photo.jpg",
        lastScanned: Long = 1,
    ): FileRecord {
        val parent = directory.toPath()
        return FileRecord(parent / name, parent, name, "jpg", 123, modifiedAt, lastScanned, createdAt)
    }

    private fun value(directory: String): Double = scorer.score(file(directory), 99).value

    private fun instant(date: String): Long = Instant.parse("${date}T12:00:00Z").toEpochMilliseconds()

    @Test
    fun approvedExamplePairsHaveExpectedValuesAndNoInventedDateAgePreference() {
        val pairs = listOf(
            Triple("/Pictures/2007-06-06", "/Pictures/Casio/Image Library/2007_06_06", 40.0),
            Triple("/Pictures/2013-06-25", "/Pictures/Zoo", 0.0),
            Triple("/Pictures/2019-12-10", "/MobileBackup/device/Google Photos/2019", -15.0),
            Triple("/Pictures/2017-05-28", "/Pictures/Google Photos", -25.0),
            Triple("/Pictures/2007-06-06", "/Pictures/2024-08-16", 100.0),
        )
        for ((preferred, other, expected) in pairs) {
            assertEquals(100.0, value(preferred))
            assertEquals(expected, value(other), other)
        }
        assertTrue(scorer.score(file(pairs.first().first), 1).factors.first().explanation.startsWith("Preferred"))
        assertTrue(scorer.score(file(pairs.first().second), 1).factors.first().explanation.startsWith("Alternative"))
    }

    @Test
    fun fiveRepresentativeLocationsHaveExplainableScores() {
        assertEquals(100.0, value("/archive/2007-06-06"))
        assertEquals(-25.0, value("/archive/MobileBackup"))
        assertEquals(-25.0, value("/archive/Google Photos"))
        assertEquals(-60.0, value("/archive/.Picasa3Temp_123"))
        assertEquals(0.0, value("/archive/Zoo/picturesToPrint"))
        val combined = scorer.score(file("/Backup/2007-06-06"), 99)
        assertEquals(75.0, combined.value)
        assertEquals(combined.value, combined.factors.sumOf { it.contribution })
        assertTrue(combined.factors.all { it.key.isNotBlank() && it.explanation.isNotBlank() })
    }

    @Test
    fun alternativeAndSplitDatesAndPartialYearsAreRecognized() {
        listOf("/archive/2007_06_06", "/archive/20070606", "/archive/2007/06/06").forEach {
            assertEquals(40.0, value(it), it)
        }
        assertEquals(10.0, value("/archive/2007"))
        listOf("/archive/2007/06", "/archive/2007/12/event").forEach {
            assertEquals(45.0, value(it), it)
        }
        assertEquals(100.0, value("/2007/06/06/2008_07_07/2009-08-08"))
        assertEquals(100.0, value("/2007-06-06/event/2008-07-07"))
    }

    @Test
    fun invalidCalendarDatesNeverReceiveFullDateBonus() {
        listOf("2023-02-29", "1900-02-29", "2024-02-30", "2024-04-31",
            "2024-13-01", "2024-00-01", "2024-01-00", "0000-01-01",
            "20230229", "2023_02_29", "prefix2024-02-29", "2024-02-29suffix").forEach {
            assertEquals(0.0, value("/archive/$it"), it)
        }
        assertEquals(100.0, value("/archive/2000-02-29"))
        assertEquals(100.0, value("/archive/2024-02-29"))
        assertEquals(40.0, value("/archive/2024/02/29"))
        assertEquals(45.0, value("/archive/2023/02/29"), "The valid year/month remains partial evidence, not a valid complete date")
    }

    @Test
    fun onlyDirectoryComponentsNotFilenameSupplyEvidence() {
        assertEquals(0.0, scorer.score(file("/archive", name = "2024-02-29.jpg"), 1).value)
        assertEquals(100.0, value("/archive/2024-02-29/deep/nested"))
    }

    @Test
    fun backupNamesAreExactCaseInsensitiveAndCountedOnce() {
        listOf("MobileBackup", "Google Photos", "Picasa", "Backup", "Backups", "Import", "Imports", "Sync").forEach {
            assertEquals(-25.0, value("/archive/${it.uppercase()}"), it)
        }
        assertEquals(-25.0, value("/Backup/Imports/Sync/MobileBackup"))
        listOf("myBackup", "Backup-copy", "Syncing", "Picasa3", "Imported", "Zoo", "picturesToPrint").forEach {
            assertEquals(0.0, value("/archive/$it"), it)
        }
    }

    @Test
    fun temporaryLocationsReplaceBackupPenaltyAndCountOnce() {
        listOf("Temp", "Tmp", ".Picasa3Temp_123", ".picasa3temp_").forEach {
            assertEquals(-60.0, value("/Backup/$it"), it)
        }
        assertEquals(-60.0, value("/TEMP/tmp/.Picasa3Temp_1/Import"))
        assertEquals(40.0, value("/Temp/2024-02-29"))
        listOf("Temperature", "TmpFiles", ".Picasa3Temp", "x.Picasa3Temp_1").forEach {
            assertEquals(0.0, value("/archive/$it"), it)
        }
    }

    @Test
    fun timestampSupportIsWeakAndUsesUtcStoredDatesOnly() {
        val date = instant("2024-02-29")
        assertEquals(103.0, scorer.score(file("/2024-02-29", date, date), 1).value)
        assertEquals(102.0, scorer.score(file("/2024-02-29", date), 1).value)
        assertEquals(101.0, scorer.score(file("/2024-02-29", createdAt = date), 1).value)
        assertEquals(43.0, scorer.score(file("/2024/02/29", date, date), 1).value)
        val offsetDate = Instant.parse("2024-03-01T00:30:00+02:00").toEpochMilliseconds()
        assertEquals(102.0, scorer.score(file("/2024-02-29", offsetDate), 1).value)
        assertEquals(10.0, scorer.score(file("/2024", date, date), 1).value)
        assertEquals(0.0, scorer.score(file("/Zoo", date, date), 1).value)
    }

    @Test
    fun missingMismatchingAndUnreliableTimestampsHaveNoAgePreference() {
        val old = instant("1900-01-01")
        val recent = instant("2025-01-01")
        assertEquals(100.0, scorer.score(file("/2024-02-29"), 1).value)
        assertEquals(100.0, scorer.score(file("/2024-02-29", old, recent), 1).value)
        assertEquals(0.0, scorer.score(file("/Zoo", old, old), 1).value)
        assertEquals(0.0, scorer.score(file("/Zoo", recent, recent), 1).value)
        assertEquals(100.0, scorer.score(file("/2024-02-29", Long.MIN_VALUE, Long.MAX_VALUE), 1).value)
    }

    @Test
    fun nearestEquallyStrongFolderDateSupportsTimestampsNotOldest() {
        val old = instant("2000-01-01")
        val recent = instant("2024-02-29")
        assertEquals(100.0, scorer.score(file("/2000-01-01/2024-02-29", old, old), 1).value)
        assertEquals(103.0, scorer.score(file("/2000-01-01/2024-02-29", recent, recent), 1).value)
        assertEquals(103.0, scorer.score(file("/2024-02-29/2000-01-01", old, old), 1).value)
        assertEquals(100.0, scorer.score(file("/2024-02-29/20000101", old, old), 1).value)
        assertEquals(43.0, scorer.score(file("/20000101/2024/02/29", recent, recent), 1).value)
    }

    @Test
    fun windowsAndPosixPathsHaveEquivalentRules() {
        assertEquals(100.0, value("C:\\archive\\2024-02-29\\event"))
        assertEquals(40.0, value("C:\\archive\\2024\\02\\29"))
        assertEquals(-60.0, value("\\\\server\\share\\Backup\\TEMP"))
    }

    @Test
    fun scoresAreIndependentOfScanOrderAndLastScanned() {
        val files = listOf(file("/2024-02-29"), file("/Zoo"), file("/Backup"))
        val first = files.associate { it.path to scorer.score(it, 123) }
        val reversed = files.reversed().associate { it.path to scorer.score(it, 123) }
        assertEquals(first, reversed)
        assertEquals(scorer.score(file("/Zoo", lastScanned = 1), 123),
            scorer.score(file("/Zoo", lastScanned = 999), 123))
    }

    @Test
    fun sameMetadataAndAssessmentClockAreReproducible() {
        val file = file("/Backup/2024-02-29", instant("2024-02-29"))
        assertEquals(scorer.score(file, 123), RetentionScorer().score(file, 123))
        val first = scorer.score(file, 123)
        assertEquals(first.copy(createdAt = 456), scorer.score(file, 456))
        assertEquals(FileScoreInput.from(file), first.input)
        assertEquals(ScoreIdentity("retention", "directory-retention", "5", scorer.identity.configurationId),
            first.identity)
        assertEquals(10.0, scorer.ambiguityMargin)
    }

    @Test
    fun configurationIdentityIsCanonicalAndSensitiveToEveryRuleAndWeight() {
        val base = RetentionConfig()
        val variants = listOf(
            base.copy(isoDateBonus = 101.0),
            base.copy(alternativeDateBonus = 41.0),
            base.copy(partialDateBonus = 11.0),
            base.copy(backupPenalty = -26.0),
            base.copy(temporaryPenalty = -61.0),
            base.copy(modifiedDateBonus = 3.0),
            base.copy(createdDateBonus = 2.0),
            base.copy(ambiguityMargin = 6.0),
            base.copy(backupDirectoryNames = base.backupDirectoryNames + "OtherBackup"),
            base.copy(temporaryDirectoryNames = base.temporaryDirectoryNames + "Scratch"),
            base.copy(temporaryDirectoryPrefixes = base.temporaryDirectoryPrefixes + ".Scratch_"),
            base.copy(filenameDateMismatchPenalty = -70.0),
            base.copy(backupDirectoryWords = base.backupDirectoryWords + "Messenger"),
            base.copy(yearMonthDateBonus = 31.0),
            base.copy(exifConfirmedDateScore = 89.0),
            base.copy(exifDateMismatchPenalty = -79.0),
            base.copy(backupFilenamePenalty = -21.0),
            base.copy(numberedFilenamePenalty = -11.0),
        )
        variants.forEach { assertNotEquals(scorer.identity, RetentionScorer(it).identity) }
        val reordered = base.copy(
            backupDirectoryNames = base.backupDirectoryNames.reversed().map { it.uppercase() }.toSet(),
            backupDirectoryWords = base.backupDirectoryWords.reversed().map { it.uppercase() }.toSet(),
            temporaryDirectoryNames = base.temporaryDirectoryNames.reversed().toSet(),
            temporaryDirectoryPrefixes = base.temporaryDirectoryPrefixes.reversed().toSet(),
        )
        assertEquals(scorer.identity, RetentionScorer(reordered).identity)
        assertEquals(101.0, RetentionScorer(variants.first()).score(file("/2024-02-29"), 1).value)
        assertEquals(-25.0, RetentionScorer(variants[8]).score(file("/OtherBackup"), 1).value)
    }

    @Test
    fun invalidConfigurationIsRejected() {
        listOf(
            { RetentionConfig(isoDateBonus = Double.NaN) },
            { RetentionConfig(backupPenalty = Double.NEGATIVE_INFINITY) },
            { RetentionConfig(createdDateBonus = -1.0) },
            { RetentionConfig(backupPenalty = 1.0) },
            { RetentionConfig(ambiguityMargin = -1.0) },
            { RetentionConfig(temporaryPenalty = -1.0) },
            { RetentionConfig(modifiedDateBonus = 1.5) },
            { RetentionConfig(backupDirectoryNames = setOf("")) },
            { RetentionConfig(temporaryDirectoryPrefixes = setOf("a/b")) },
            { RetentionConfig(filenameDateMismatchPenalty = 80.0) },
            { RetentionConfig(filenameDateMismatchPenalty = Double.NaN) },
            { RetentionConfig(backupDirectoryWords = setOf("")) },
            { RetentionConfig(yearMonthDateBonus = 101.0) },
            { RetentionConfig(yearMonthDateBonus = 1.0) },
            { RetentionConfig(exifConfirmedDateScore = -1.0) },
            { RetentionConfig(exifConfirmedDateScore = Double.NaN) },
            { RetentionConfig(exifDateMismatchPenalty = 1.0) },
            { RetentionConfig(exifDateMismatchPenalty = Double.NaN) },
        ).forEach { invalid -> assertFailsWith<IllegalArgumentException> { invalid() } }
    }

    @Test
    fun mutableCallerRuleSetsCannotChangeAnExistingScorer() {
        val rules = mutableSetOf("Backup")
        val configured = RetentionScorer(RetentionConfig(backupDirectoryNames = rules))
        val identity = configured.identity
        rules.clear()
        rules += "Zoo"
        assertEquals(identity, configured.identity)
        assertEquals(-25.0, configured.score(file("/Backup"), 1).value)
        assertEquals(0.0, configured.score(file("/Zoo"), 1).value)
        val words = mutableSetOf("WhatsApp")
        val wordScorer = RetentionScorer(RetentionConfig(backupDirectoryWords = words))
        words.clear()
        assertEquals(-25.0, wordScorer.score(file("/WhatsApp Images"), 1).value)
    }
}
