package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.operations.DuplicateDeletionSelection
import com.doronyg.photoarchivededuplicator.recommendation.DeletionRecommendationSource
import com.doronyg.photoarchivededuplicator.recommendation.DuplicateRecommendationEngine
import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendationRole
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationAction
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassification
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationRecord
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationContext
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import okio.Path.Companion.toPath
import kotlin.test.*

class DuplicateDeletionSelectionTest {
    private val files = listOf("a", "b", "c").map {
        FileRecord("/photos/$it.jpg".toPath(), "/photos".toPath(), "$it.jpg", "jpg", 42, 1, 2)
    }
    private val group = DuplicateDeletionSelection(DeletionRecommendationSource.REGULAR, files)

    @Test
    fun manualSelectionKeepsOtherFilesAndNeverSelectsTheLastCopy() {
        val first = toggleDuplicateDeletionFile(null, group, files.first().path, true)
        assertEquals(setOf(files.first().path), first?.selectedPaths)
        val second = toggleDuplicateDeletionFile(first, group, files[1].path, true)
        assertEquals(setOf(files[0].path, files[1].path), second?.selectedPaths)
        assertFailsWith<IllegalArgumentException> {
            toggleDuplicateDeletionFile(second, group, files.last().path, true)
        }
        assertEquals(first, toggleDuplicateDeletionFile(second, group, files[1].path, false))
        assertNull(toggleDuplicateDeletionFile(first, group, files.first().path, false))
    }

    @Test
    fun manualChoiceReplacesGroupModeWithoutSelectingOtherMembers() {
        assertEquals(setOf(files.last().path),
            toggleDuplicateDeletionFile(group, group, files.last().path, true)?.selectedPaths)
        assertFailsWith<IllegalArgumentException> {
            toggleDuplicateDeletionFile(null, group, "/outside.jpg".toPath(), true)
        }
    }

    @Test
    fun groupKeysAreStableAcrossSortingAndDistinctAcrossSources() {
        assertEquals(duplicateDeletionSelectionKey(group), duplicateDeletionSelectionKey(group.copy(files = files.reversed())))
        assertNotEquals(duplicateDeletionSelectionKey(group),
            duplicateDeletionSelectionKey(group.copy(source = DeletionRecommendationSource.SUSPECTED)))
    }

    @Test
    fun groupModeAllowsKeepingAllAndSupportsMultipleRetainedCopies() {
        val review = DuplicateRecommendationEngine().recommend(files, emptyMap(), emptyMap())
        assertFalse(duplicateDeletionGroupRecommended(null))
        assertTrue(duplicateDeletionGroupRecommended(review))
        assertFalse(duplicateDeletionGroupRecommended(review.copy(action = RecommendationAction.PROTECTED)))
        val multiple = review.copy(action = RecommendationAction.KEEP_MULTIPLE,
            files = review.files.mapValues { (path, advice) ->
                advice.copy(role = if (path == files.last().path) FileRecommendationRole.REDUNDANT_CANDIDATE
                    else FileRecommendationRole.PRESERVE,
                    retainedReplacementPath = if (path == files.last().path) files.first().path else null)
            })
        assertTrue(duplicateDeletionGroupRecommended(multiple))
        assertFalse(duplicateDeletionGroupRecommended(multiple.copy(files = multiple.files.mapValues { (_, advice) ->
            advice.copy(retainedReplacementPath = null)
        })))
    }

    @Test
    fun protectionDisablesBothModesEvenWhenEngineHasCandidates() {
        val review = DuplicateRecommendationEngine().recommend(files, emptyMap(), emptyMap())
        assertFalse(duplicateDeletionGroupProtected(group, review, emptyMap()))
        assertTrue(duplicateDeletionGroupProtected(group, review.copy(action = RecommendationAction.PROTECTED), emptyMap()))
        val protected = DirectoryClassificationRecord(files.first().directoryPath, files.first().directoryPath.toString(),
            DirectoryClassification.PROTECTED, DirectoryClassificationContext("http://localhost", "model"), 1)
        assertTrue(duplicateDeletionGroupProtected(group, review, mapOf(protected.directoryPath to protected)))
    }
}
