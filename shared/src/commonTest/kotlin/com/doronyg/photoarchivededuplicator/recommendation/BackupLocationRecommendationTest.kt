package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.retention.RetentionConfig
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.retention.RetentionRank
import com.doronyg.photoarchivededuplicator.retention.RetentionScorer
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupLocationRecommendationTest {
    private val scorer = RetentionScorer()
    private val engine = DuplicateRecommendationEngine(scorer)

    private fun file(directory: String, name: String = "IMG_20170711_163509.jpg", modified: Long = 1): FileRecord {
        val folder = directory.toPath()
        return FileRecord(folder / name, folder, name, "jpg", 120_000, modified, 2, 3)
    }

    private fun records(files: List<FileRecord>) = files.associate { file ->
        file.path to ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED,
            ExifMetadata(capturedAt = "2017:07:11 16:35:10", make = "LeMobile", model = "Le X822",
                width = 3464, height = 4618)), 4)
    }

    private fun scores(files: List<FileRecord>, records: Map<Path, ExifRecord>) =
        files.associate { it.path to RetentionPresentation(scorer.score(it, 5, records[it.path]), RetentionRank.CLOSE) }

    private fun recommend(files: List<FileRecord>): DuplicateGroupRecommendation {
        val records = records(files)
        return engine.recommend(files, records, scores(files, records))
    }

    private fun assertBackupCandidate(result: DuplicateGroupRecommendation, backup: FileRecord, keeper: FileRecord) {
        val advice = result.files.getValue(backup.path)
        assertEquals(FileRecommendationRole.REDUNDANT_CANDIDATE, advice.role)
        assertEquals(keeper.path, advice.retainedReplacementPath)
        assertTrue(RecommendationReason.EXPENDABLE_BACKUP_LOCATION in advice.reasonCodes)
        assertTrue(ConfidenceReason.EXPENDABLE_BACKUP_LOCATION in advice.retentionDecisionConfidence.reasonCodes)
        assertTrue(advice.explanation.contains("backup", ignoreCase = true))
        assertTrue(advice.retentionDecisionConfidence.score >= 75)
        assertTrue(result.mustPreserve.any { it.path == keeper.path })
    }

    @Test
    fun googlePhotosRootUsesABetterChronologicalCopyEvenWhenModifiedTimesDiffer() {
        val keeper = file("/Photos/2017-07-11")
        val backup = file("/Photos/Google Photos", modified = 99)
        val result = recommend(listOf(backup, keeper))
        assertEquals(RecommendationAction.KEEP_ONE, result.action)
        assertEquals(75, result.duplicateConfidence.score)
        assertBackupCandidate(result, backup, keeper)
        assertEquals(listOf(keeper), result.mustPreserve)
    }

    @Test
    fun photoSyncDeviceCameraFoldersAndAllRecognizedBackupDescendantsAreExpendable() {
        val config = RetentionConfig()
        val directories = config.backupDirectoryNames.flatMap { listOf("/Photos/$it", "/Photos/$it/Album/Camera") } +
            config.backupDirectoryWords.map { "/Photos/$it Images/Device/Camera" } +
            "/Photos/PhotoSync/Samsung SM-A705MN/Camera"
        val keeper = file("/Photos/2017-07-11")
        for (directory in directories) {
            val backup = file(directory)
            val result = recommend(listOf(backup, keeper))
            assertEquals(RecommendationAction.KEEP_ONE, result.action, directory)
            assertBackupCandidate(result, backup, keeper)
        }
    }

    @Test
    fun collectionRepresentativesRemainPreservedWhileBackupsReferenceASpecificSurvivor() {
        val family = file("/Photos/Family")
        val zoo = file("/Photos/Zoo")
        val backup = file("/Photos/PhotoSync/Device/Camera")
        val result = recommend(listOf(backup, zoo, family))
        assertEquals(RecommendationAction.KEEP_MULTIPLE, result.action)
        assertEquals(setOf(family, zoo), result.mustPreserve.toSet())
        assertBackupCandidate(result, backup, family)
    }

    @Test
    fun localBackupVariantsPointDirectlyToTheFinalOrdinaryKeeper() {
        val keeper = file("/Photos/2017-07-11")
        val backup = file("/Photos/Google Photos")
        val variant = file("/Photos/Google Photos", "IMG_20170711_163509-001.jpg")
        val result = recommend(listOf(variant, backup, keeper))
        assertEquals(RecommendationAction.KEEP_ONE, result.action)
        assertBackupCandidate(result, backup, keeper)
        assertBackupCandidate(result, variant, keeper)
        assertEquals(listOf(keeper), result.mustPreserve)
    }

    @Test
    fun backupOnlyGroupsRetainTheBestAvailableCopyAndEqualCopiesAreNotDiscardedArbitrarily() {
        val original = file("/Photos/Google Photos")
        val variant = file("/Photos/PhotoSync/Device/Camera", "IMG_20170711_163509-001.jpg")
        val result = recommend(listOf(variant, original))
        assertEquals(RecommendationAction.KEEP_ONE, result.action)
        assertBackupCandidate(result, variant, original)
        val equal = file("/Photos/PhotoSync/Device/Camera")
        val tied = recommend(listOf(original, equal))
        assertEquals(RecommendationAction.KEEP_MULTIPLE, tied.action)
        assertTrue(tied.redundantCandidates.isEmpty())
        assertEquals(setOf(original, equal), tied.mustPreserve.toSet())
    }

    @Test
    fun protectionAndInsufficientOrConflictingDuplicateEvidenceStillPreventReduction() {
        val keeper = file("/Photos/2017-07-11")
        val backup = file("/Photos/Google Photos")
        val files = listOf(keeper, backup)
        val records = records(files)
        val protected = engine.recommend(files, records, scores(files, records), protectedPaths = setOf(backup.path))
        assertEquals(RecommendationAction.PROTECTED, protected.action)
        assertTrue(protected.redundantCandidates.isEmpty())
        val missing = engine.recommend(files, emptyMap(), scores(files, emptyMap()))
        assertEquals(RecommendationAction.REVIEW, missing.action)
        assertTrue(missing.redundantCandidates.isEmpty())
        val conflicting = records.toMutableMap()
        conflicting[backup.path] = conflicting.getValue(backup.path).let {
            it.copy(result = it.result.copy(metadata = it.result.metadata.copy(model = "Other camera")))
        }
        val conflict = engine.recommend(files, conflicting, scores(files, conflicting))
        assertEquals(RecommendationAction.REVIEW, conflict.action)
        assertTrue(conflict.redundantCandidates.isEmpty())
    }

    @Test
    fun aHigherNumericalScoreInAContradictedDateFolderIsNotABetterReplacement() {
        val backup = file("/Photos/Google Photos")
        val wrongDate = file("/Photos/2017-07-12")
        val result = recommend(listOf(backup, wrongDate))
        assertEquals(RecommendationAction.KEEP_MULTIPLE, result.action)
        assertTrue(result.redundantCandidates.isEmpty())
        assertEquals(setOf(backup, wrongDate), result.mustPreserve.toSet())
    }

    @Test
    fun unsupportedLookalikeFolderNamesAreNotClassifiedAsBackupCollections() {
        val keeper = file("/Photos/2017-07-11")
        for (directory in listOf("/Photos/PhotoSync album", "/Photos/Google Photos collection", "/Photos/MyWhatsAppImages")) {
            val file = file(directory)
            val result = recommend(listOf(keeper, file))
            assertEquals(RecommendationAction.KEEP_MULTIPLE, result.action, directory)
            assertTrue(result.redundantCandidates.isEmpty(), directory)
        }
    }
}
