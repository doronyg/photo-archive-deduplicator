package com.doronyg.photoarchivededuplicator.scan

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DuplicateCandidateRepositoryFilterTest {
    private val env = ScanTestEnvironment()

    @AfterTest
    fun close() = env.close()

    private suspend fun record(folder: String, names: List<String>, size: Long = 12) {
        val directory = env.root / folder
        env.repository.seedRoot(directory)
        env.repository.recordListing(
            directory,
            DirectoryListing(emptyList(), names.map { DirectoryListing.Entry(directory / it, size, null) }),
            1,
        )
    }

    @Test
    fun filtersReturnWholeGroupsWithoutChangingInventoryOrDuplicateIdentity() = env.test {
        val names = listOf("IMG_1.JPG", "IMG_1.jpg", "movie.mp4", "notes.pdf", "archive.zip", "README")
        record("a", names)
        record("b", names)
        record("c", listOf("IMG_1.JPG"), size = 99)
        record("d", listOf("IMG_2.JPG"))
        val unfiltered = env.repository.duplicateCandidates(env.root)
        assertEquals(names.toSet(), unfiltered.map { it.name }.toSet())

        val images = env.repository.duplicateCandidates(
            env.root, DuplicateCandidateFilter(DuplicateFileType.IMAGES, "img*.jpg"),
        )
        assertEquals(setOf("IMG_1.JPG", "IMG_1.jpg"), images.map { it.name }.toSet())
        for (group in images) {
            assertEquals(12L, group.sizeBytes)
            assertEquals(listOf(env.root / "a" / group.name, env.root / "b" / group.name), group.files.map { it.path })
        }
        assertEquals(listOf("movie.mp4"), env.repository.duplicateCandidates(
            env.root, DuplicateCandidateFilter(DuplicateFileType.VIDEOS),
        ).map { it.name })
        assertEquals(listOf("notes.pdf"), env.repository.duplicateCandidates(
            env.root, DuplicateCandidateFilter(DuplicateFileType.DOCUMENTS),
        ).map { it.name })
        assertTrue(env.repository.duplicateCandidates(
            env.root, DuplicateCandidateFilter(DuplicateFileType.IMAGES, "movie*"),
        ).isEmpty())
        assertEquals(unfiltered, env.repository.duplicateCandidates(env.root))
    }

    @Test
    fun filteredCandidatesStillRespectRootBoundariesAndExclusions() = env.test {
        record("a", listOf("IMG_1.jpg"))
        record("b", listOf("IMG_1.jpg"))
        record("private", listOf("IMG_1.jpg"))
        val filter = DuplicateCandidateFilter(filenamePattern = "IMG*.jpg")
        env.repository.addExclusion(env.root / "private")
        assertEquals(2, env.repository.duplicateCandidates(env.root, filter).single().files.size)
        assertTrue(env.repository.duplicateCandidates(env.root / "a", filter).isEmpty())
        env.repository.addExclusion(env.root / "a")
        assertTrue(env.repository.duplicateCandidates(env.root, filter).isEmpty())
    }
}
