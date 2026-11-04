package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.classification.DirectoryClassification
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationContext
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationRecord
import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.retention.RetentionRank
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.retention.RetentionScorer
import com.doronyg.photoarchivededuplicator.scan.DirectoryListing
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.scan.concurrencyTest
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiDirectoryRecommendationTest {
    private val scorer = RetentionScorer()
    private val engine = DuplicateRecommendationEngine(scorer)
    private val context = DirectoryClassificationContext("http://classification.invalid:11434", "fixture-model")

    private fun file(directory: String, name: String = "IMG.jpg"): FileRecord {
        val folder = directory.toPath()
        return FileRecord(folder / name, folder, name, "jpg", 100, 1, 2)
    }

    private fun record(file: FileRecord) = ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED,
        ExifMetadata(capturedAt = "2023:02:18 12:44:25", make = "Canon", model = "Camera",
            width = 4000, height = 3000)), 3)

    private fun classifications(vararg entries: Pair<FileRecord, DirectoryClassification>) =
        entries.associate { (file, value) ->
            file.directoryPath to DirectoryClassificationRecord(file.directoryPath, file.directoryPath.toString(),
                value, context, 4)
        }

    private fun recommend(
        files: List<FileRecord>,
        classified: Map<Path, DirectoryClassificationRecord> = emptyMap(),
        records: Map<Path, ExifRecord> = files.associate { it.path to record(it) },
        scores: Map<Path, RetentionPresentation> = files.associate {
            it.path to RetentionPresentation(scorer.score(it, 5, records[it.path]), RetentionRank.CLOSE)
        },
        protectedPaths: Set<Path> = emptySet(),
    ) = engine.recommend(files, records, scores, protectedPaths = protectedPaths,
        directoryClassifications = classified).also(::assertSafePlan)

    private fun assertSafePlan(result: DuplicateGroupRecommendation) {
        if (result.files.isEmpty()) return
        val retained = result.mustPreserve.map { it.path }.toSet()
        assertTrue(retained.isNotEmpty())
        result.files.values.forEach { advice ->
            if (advice.role == FileRecommendationRole.REDUNDANT_CANDIDATE) {
                val replacement = assertNotNull(advice.retainedReplacementPath)
                assertTrue(replacement in retained)
                assertTrue(replacement != advice.file.path)
                assertTrue(advice.retentionDecisionConfidence.score <= result.duplicateConfidence.score)
            } else assertNull(advice.retainedReplacementPath)
        }
    }

    @Test
    fun picasaOriginalsIsDeterministicBackupEvenIfAiCallsItProtectedOrPreferred() {
        val normal = file("/Photos/Family")
        val picasa = file("/Photos/Family/.picasaoriginals")
        for (classification in DirectoryClassification.entries) {
            val result = recommend(listOf(normal, picasa), classifications(picasa to classification))
            assertEquals(normal, result.preferredFile)
            assertEquals(listOf(picasa), result.redundantCandidates)
            assertEquals(normal.path, result.files.getValue(picasa.path).retainedReplacementPath)
        }
        val explicit = recommend(listOf(normal, picasa), protectedPaths = setOf(picasa.path))
        assertEquals(RecommendationAction.PROTECTED, explicit.action)
        assertTrue(explicit.redundantCandidates.isEmpty())
        val weak = recommend(listOf(normal, picasa), records = emptyMap())
        assertTrue(weak.redundantCandidates.isEmpty())
    }

    @Test
    fun protectedCollectionUnderBackupAncestorRetainsItsRepresentativeButNotEveryLocalVariant() {
        val keeper = file("/Photos/2023-02-18")
        val protected = file("/Photos/Backup/Family")
        val variant = file("/Photos/Backup/Family", "IMG-001.jpg")
        val result = recommend(listOf(variant, protected, keeper), classifications(
            protected to DirectoryClassification.PROTECTED, file("/Photos/Backup") to DirectoryClassification.BACKUP))
        assertEquals(RecommendationAction.KEEP_MULTIPLE, result.action)
        assertEquals(setOf(keeper, protected), result.mustPreserve.toSet())
        assertEquals(listOf(variant), result.redundantCandidates)
        assertEquals(protected.path, result.files.getValue(variant.path).retainedReplacementPath)
        val advice = result.files.getValue(protected.path)
        assertTrue(RecommendationReason.AI_PROTECTED in advice.reasonCodes)
        assertTrue(ConfidenceReason.AI_PROTECTED in advice.retentionDecisionConfidence.reasonCodes)
        assertTrue(advice.explanation.contains("AI"))
        assertTrue(advice.explanation.contains(protected.directoryPath.toString()))
    }

    @Test
    fun unknownExactBackupDirectoryVetoesLegacyReductionAndChronologicalCollectionLoss() {
        val keeper = file("/Photos/2023-02-18")
        for (directory in listOf("/Photos/Backup/Album", "/Photos/2023-02-19")) {
            val unknown = file(directory)
            val result = recommend(listOf(keeper, unknown), classifications(unknown to DirectoryClassification.UNKNOWN))
            assertEquals(RecommendationAction.KEEP_MULTIPLE, result.action)
            assertEquals(setOf(keeper, unknown), result.mustPreserve.toSet())
            assertTrue(result.redundantCandidates.isEmpty())
            assertTrue(RecommendationReason.AI_UNKNOWN in result.files.getValue(unknown.path).reasonCodes)
        }
    }

    @Test
    fun uniquePreferredLocationCanReplaceAiBackupDespiteLowerRawScoreWithoutChangingScores() {
        val preferred = file("/Photos/Preferred")
        val backup = file("/Photos/Upload")
        val files = listOf(preferred, backup)
        val records = files.associate { it.path to record(it) }
        val scores = files.associate { file ->
            file.path to RetentionPresentation(scorer.score(file, 5, records[file.path]).copy(
                value = if (file == backup) 900.0 else 0.0), RetentionRank.CLOSE)
        }
        val before = scores.toMap()
        val result = recommend(files, classifications(preferred to DirectoryClassification.PREFERRED,
            backup to DirectoryClassification.BACKUP), records, scores)
        assertEquals(RecommendationAction.KEEP_ONE, result.action)
        assertEquals(preferred, result.preferredFile)
        assertEquals(preferred.path, result.files.getValue(backup.path).retainedReplacementPath)
        assertTrue(RecommendationReason.AI_PREFERRED in result.files.getValue(preferred.path).reasonCodes)
        val candidate = result.files.getValue(backup.path)
        assertTrue(RecommendationReason.AI_BACKUP in candidate.reasonCodes)
        assertTrue(ConfidenceReason.AI_BACKUP in candidate.retentionDecisionConfidence.reasonCodes)
        assertTrue(ConfidenceReason.AI_PREFERRED in candidate.retentionDecisionConfidence.reasonCodes)
        assertTrue(candidate.explanation.contains("AI"))
        assertTrue(candidate.explanation.contains(backup.directoryPath.toString()))
        assertEquals(before, scores)
    }

    @Test
    fun preferredNeverMakesUnknownOrProtectedOrOtherOrdinaryCollectionsExpendable() {
        val preferred = file("/Photos/Preferred")
        val protected = file("/Photos/Protected")
        val unknown = file("/Photos/Unknown")
        val ordinary = file("/Photos/Family")
        val result = recommend(listOf(preferred, protected, unknown, ordinary), classifications(
            preferred to DirectoryClassification.PREFERRED, protected to DirectoryClassification.PROTECTED,
            unknown to DirectoryClassification.UNKNOWN))
        assertEquals(RecommendationAction.KEEP_MULTIPLE, result.action)
        assertEquals(setOf(preferred, protected, unknown, ordinary), result.mustPreserve.toSet())
        assertTrue(result.redundantCandidates.isEmpty())
    }

    @Test
    fun multipleProtectedCollectionsRetainRepresentativesAndExplicitFileProtectionHasPriority() {
        val first = file("/Photos/Backup/Family")
        val second = file("/Photos/Backup/Zoo")
        val copy = file("/Photos/Backup/Family", "IMG-001.jpg")
        val classified = classifications(first to DirectoryClassification.PROTECTED, second to DirectoryClassification.PROTECTED)
        val files = listOf(copy, second, first)
        val result = recommend(files, classified)
        assertEquals(setOf(first, second), result.mustPreserve.toSet())
        assertEquals(listOf(copy), result.redundantCandidates)
        val explicit = recommend(files, classified, protectedPaths = setOf(copy.path))
        assertEquals(RecommendationAction.PROTECTED, explicit.action)
        assertEquals(files.toSet(), explicit.mustPreserve.toSet())
        assertTrue(explicit.files.values.all { it.retentionDecisionConfidence.score == 100 })
    }

    @Test
    fun aiParentBackupDoesNotClassifyAnOrdinaryChildAndUnclassifiedLegacyFallbackIsUnchanged() {
        val preferred = file("/Photos/Preferred")
        val child = file("/Photos/Upload/Family")
        val parent = file("/Photos/Upload")
        val result = recommend(listOf(preferred, child), classifications(
            preferred to DirectoryClassification.PREFERRED, parent to DirectoryClassification.BACKUP))
        assertEquals(setOf(preferred, child), result.mustPreserve.toSet())
        assertFalse(RecommendationReason.AI_BACKUP in result.files.getValue(child.path).reasonCodes)
        val legacyChild = file("/Photos/Backup/Camera")
        val baseline = recommend(listOf(preferred, legacyChild))
        assertEquals(baseline, recommend(listOf(preferred, legacyChild), classifications(
            parent to DirectoryClassification.BACKUP)))
    }

    @Test
    fun backupClassificationAloneCannotRemoveTheLastCopyOrBypassDuplicateEvidence() {
        val first = file("/Photos/Upload")
        val second = file("/Photos/OtherUpload")
        val classified = classifications(first to DirectoryClassification.BACKUP, second to DirectoryClassification.BACKUP)
        val singleton = recommend(listOf(first), classified)
        assertEquals(RecommendationAction.REVIEW, singleton.action)
        val equal = recommend(listOf(first, second), classified)
        assertEquals(setOf(first, second), equal.mustPreserve.toSet())
        val preferred = file("/Photos/Preferred")
        val files = listOf(first, preferred)
        val low = recommend(files, classifications(first to DirectoryClassification.BACKUP,
            preferred to DirectoryClassification.PREFERRED), records = emptyMap())
        assertEquals(RecommendationAction.REVIEW, low.action)
        assertTrue(low.redundantCandidates.isEmpty())
        assertTrue(RecommendationReason.AI_BACKUP in low.files.getValue(first.path).reasonCodes)
        assertEquals(0, low.files.getValue(first.path).retentionDecisionConfidence.score)
        assertTrue(low.files.getValue(first.path).explanation.contains(first.directoryPath.toString()))
    }

    @Test
    fun exactAiBackupCanUseABetterDatedReplacementWithoutClassifyingItsDescendants() {
        val keeper = file("/Photos/2023-02-18")
        val backup = file("/Photos/Upload")
        val child = file("/Photos/Upload/Camera")
        val result = recommend(listOf(child, backup, keeper), classifications(backup to DirectoryClassification.BACKUP))
        assertEquals(setOf(child, keeper), result.mustPreserve.toSet())
        assertEquals(listOf(backup), result.redundantCandidates)
        assertEquals(keeper.path, result.files.getValue(backup.path).retainedReplacementPath)
        assertTrue(RecommendationReason.AI_BACKUP in result.files.getValue(backup.path).reasonCodes)
        assertFalse(RecommendationReason.AI_BACKUP in result.files.getValue(child.path).reasonCodes)
    }

    @Test
    fun preferredBackupPlanStillRequiresStrongDirectEvidenceWithTheActualSurvivor() {
        val reference = file("/Photos/Protected")
        val preferred = file("/Photos/Preferred")
        val backup = file("/Photos/Upload").copy(modifiedAt = 2)
        val files = listOf(reference, preferred, backup)
        val records = files.associate { file ->
            val stored = record(file)
            file.path to if (file == preferred) stored.copy(result = stored.result.copy(
                metadata = stored.result.metadata.copy(make = null, model = null, width = null, height = null)))
            else stored
        }
        val scores = files.associate { file ->
            file.path to RetentionPresentation(scorer.score(file, 5, records[file.path]).copy(
                value = if (file == reference) 900.0 else 0.0), RetentionRank.CLOSE)
        }
        val result = recommend(files, classifications(reference to DirectoryClassification.PROTECTED,
            preferred to DirectoryClassification.PREFERRED, backup to DirectoryClassification.BACKUP), records, scores)
        assertEquals(reference, result.duplicateConfidence.referenceFile)
        assertEquals(75, result.duplicateConfidence.score)
        assertEquals(RecommendationAction.REVIEW, result.action)
        assertTrue(result.redundantCandidates.isEmpty())
        assertTrue(result.explanation.contains("60/100"))
        assertTrue(result.files.values.all { it.retentionDecisionConfidence.score == 0 })
    }

    @Test
    fun multiplePreferredLocationsDoNotInventAUniqueWinnerAndAnyPathCanBePreferred() {
        val first = file("/Photos/Preferred")
        val second = file("/Photos/OtherPreferred")
        val result = recommend(listOf(first, second), classifications(first to DirectoryClassification.PREFERRED,
            second to DirectoryClassification.PREFERRED))
        assertEquals(setOf(first, second), result.mustPreserve.toSet())
        val inferred = file("/Photos/Backup/AIChosenLocation")
        val keeper = file("/Photos/2023-02-18")
        val independent = recommend(listOf(inferred, keeper), classifications(inferred to DirectoryClassification.PREFERRED))
        assertEquals(setOf(inferred, keeper), independent.mustPreserve.toSet())
        assertTrue(RecommendationReason.AI_PREFERRED in independent.files.getValue(inferred.path).reasonCodes)
        assertFalse(RecommendationReason.AI_UNKNOWN in independent.files.getValue(inferred.path).reasonCodes)
    }

    @Test
    fun ordinaryPreferredChronologicalLocationsStillUseCaptureDateAndProtectedFloorSurvives() {
        val first = file("/Photos/Preferred/2023-02-18")
        val second = file("/Photos/Preferred/2023-02-19")
        val classified = classifications(first to DirectoryClassification.PREFERRED, second to DirectoryClassification.PREFERRED)
        val dated = recommend(listOf(first, second), classified)
        assertEquals(first, dated.preferredFile)
        val protected = recommend(listOf(first, second), classifications(first to DirectoryClassification.PREFERRED,
            second to DirectoryClassification.PROTECTED))
        assertEquals(setOf(first, second), protected.mustPreserve.toSet())
    }

    @Test
    fun emptyClassificationMapExactlyPreservesLegacyResultsAndGroupHelperForwardsCachedData() = runTest {
        val files = listOf(file("/Photos/2023-02-18"), file("/Photos/Backup/Family"))
        val records = files.associate { it.path to record(it) }
        val scores = files.associate {
            it.path to RetentionPresentation(scorer.score(it, 5, records[it.path]), RetentionRank.CLOSE)
        }
        assertEquals(engine.recommend(files, records, scores), recommend(files, records = records, scores = scores))
        val classified = classifications(files.last() to DirectoryClassification.PROTECTED)
        val expected = recommend(files, classified, records, scores)
        val indexed = recommendDuplicateGroups(listOf(files), records, scores, directoryClassifications = classified)
        assertTrue(indexed.values.all { it == expected })
    }

    @Test
    fun statisticsReadsOnlyExactProfileCachedClassificationsWithoutSourceReadsOrScoreWrites() = concurrencyTest { f ->
        val preferred = f.root / "Preferred"
        val backup = f.root / "Upload"
        val profile = context
        val directories = listOf(preferred, backup)
        for (directory in directories) {
            f.repository.seedRoot(directory)
            f.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(directory / "IMG.jpg", 100, 1))), 2)
            f.repository.filesIn(directory).forEach { f.repository.saveFileExif(record(it)) }
            val type = if (directory == preferred) DirectoryClassification.PREFERRED else DirectoryClassification.BACKUP
            f.repository.saveDirectoryClassification(DirectoryClassificationRecord(directory, directory.toString(), type, profile, 4))
        }
        val retention = RetentionScoreService(f.repository, dispatcher = f.dispatcher)
        val groups = f.repository.duplicateCandidates()
        retention.assess(groups)
        val before = retention.stored(groups)
        f.fs.onBlockingCall = { error("Cached recommendation statistics must not access source files") }
        val classified = RecommendationStatisticsService(f.repository, retention, f.dispatcher, profile).load()
        assertEquals(1L, classified.regular.groupCounts.getValue(RecommendationAction.KEEP_ONE))
        val otherProfile = RecommendationStatisticsService(f.repository, retention, f.dispatcher,
            profile.copy(model = "other-model")).load()
        assertEquals(1L, otherProfile.regular.groupCounts.getValue(RecommendationAction.KEEP_MULTIPLE))
        assertEquals(before, retention.stored(groups))
    }
}
