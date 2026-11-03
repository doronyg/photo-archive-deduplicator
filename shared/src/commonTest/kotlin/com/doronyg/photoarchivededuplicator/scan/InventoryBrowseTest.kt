package com.doronyg.photoarchivededuplicator.scan

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import okio.Path.Companion.toPath

class InventoryBrowseTest {
    private val env = ScanTestEnvironment()
    private val root = env.root

    @AfterTest
    fun tearDown() = env.close()

    @Test
    fun rootsAreDirectoriesWithoutParent() = env.test {
        val rootFileContent = "123"
        env.writeFile(root / "a.jpg", rootFileContent)
        env.writeFile(root / "sub" / "b.jpg")
        with(env) { scanner() }.startScan(root)

        val roots = env.repository.childOverviews(parent = null)

        assertEquals(listOf(root), roots.map { it.directory.path })
        assertEquals(rootFileContent.length.toLong(), roots.single().fileBytes)
    }

    @Test
    fun childOverviewsAreSortedWithDirectFileTotalsOnly() = env.test {
        val albumA = root / "a"
        val albumB = root / "b"
        val bFiles = listOf("one" to albumB / "1.jpg", "three" to albumB / "3.jpg")
        env.writeFile(albumA / "a.jpg", "a")
        env.writeFile(albumA / "nested" / "deep.jpg", "not counted in a")
        bFiles.forEach { (content, path) -> env.writeFile(path, content) }
        with(env) { scanner() }.startScan(root)

        val children = env.repository.childOverviews(parent = root)

        assertEquals(listOf(albumA, albumB), children.map { it.directory.path })
        assertEquals(1L, children[0].fileBytes)
        assertEquals(bFiles.sumOf { it.first.length }.toLong(), children[1].fileBytes)
        assertEquals(bFiles.size, children[1].directory.fileCount)
    }

    @Test
    fun filesInDirectoryAreSortedByName() = env.test {
        val names = listOf("c.jpg", "a.png", "b.heic")
        names.forEach { env.writeFile(root / it) }
        env.writeFile(root / "sub" / "other.jpg")
        with(env) { scanner() }.startScan(root)

        val files = env.repository.filesIn(root)

        assertEquals(names.sorted(), files.map { it.name })
    }

    @Test
    fun exportedPathsIncludeTheWholeSelectedTreeButNotSiblingPrefixes() = env.test {
        val selected = root / "selected"
        val expected = listOf(selected / "z.jpg", selected / "nested" / "a.jpg").map { it.toString() }.sorted()
        expected.forEach { env.writeFile(it.toPath()) }
        env.writeFile(root / "selected-sibling" / "outside.jpg")
        env.writeFile(root / "Selected" / "different-case.jpg")
        env.writeFile(root / "outside.jpg")
        with(env) { scanner() }.startScan(root)
        env.fs.onBlockingCall = { error("Export must use the stored inventory, not the filesystem") }

        assertEquals(expected, env.repository.filePathsUnder(selected / "."))
        assertEquals(emptyList(), env.repository.filePathsUnder(root / "missing"))
    }

    @Test
    fun exportedPathPrefixesTreatSqlWildcardCharactersLiterally() = env.test {
        val selected = root / "album_100%"
        env.writeFile(selected / "nested" / "photo.jpg")
        env.writeFile(root / "albumX100Y" / "wrong.jpg")
        env.writeFile(root / "album_100%extra" / "wrong.jpg")
        with(env) { scanner() }.startScan(root)

        assertEquals(listOf((selected / "nested" / "photo.jpg").toString()), env.repository.filePathsUnder(selected))
    }

    @Test
    fun exportingAllRootsIncludesEveryStoredFileIncludingRetainedExcludedTrees() = env.test {
        val otherRoot = "/other".toPath()
        val retained = root / "excluded"
        val paths = listOf(root / "one.jpg", retained / "two.jpg", otherRoot / "three.txt")
        paths.forEach { env.writeFile(it) }
        with(env) { scanner() }.startScan(root)
        with(env) { scanner() }.startScan(otherRoot)
        env.repository.addExclusion(retained)
        env.fs.onBlockingCall = { error("Export must not inspect source files") }

        assertEquals(paths.map { it.toString() }.sorted(), env.repository.filePathsUnder())
        assertEquals(paths.map { it.toString() }.sorted(), env.repository.filePathsUnder("/".toPath()))
        assertEquals(listOf((retained / "two.jpg").toString()), env.repository.filePathsUnder(retained))
    }

    @Test
    fun exportingAnEmptyInventoryProducesNoPaths() = env.test {
        assertEquals(emptyList(), env.repository.filePathsUnder())
        assertEquals(emptyList(), env.repository.filePathsUnder(root))
    }

    @Test
    fun exportedPathsRespectWindowsDirectoryAndDriveBoundaries() = env.test {
        val selected = "C:\\Photos".toPath()
        val nested = selected / "nested"
        val sibling = "C:\\Photos-backup".toPath()
        for (directory in listOf(selected, nested, sibling)) {
            env.repository.seedRoot(directory)
            env.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(directory / "photo.jpg", 12, null),
            )), 1)
        }
        val expected = listOf(selected, nested).map { (it / "photo.jpg").toString() }.sorted()
        assertEquals(expected, env.repository.filePathsUnder(selected))
        assertEquals(3, env.repository.filePathsUnder("C:\\".toPath()).size)
        assertEquals(emptyList(), env.repository.filePathsUnder("D:\\".toPath()))
    }

    @Test
    fun summaryCountsDirectoriesByStatusAndTotalsFiles() = env.test {
        val contents = listOf("12", "345")
        val failing = root / "broken"
        env.writeFile(root / "a" / "1.jpg", contents[0])
        env.writeFile(root / "2.jpg", contents[1])
        env.fakeFs.createDirectories(failing)
        env.fs.failListing(failing)
        with(env) { scanner() }.startScan(root)

        val summary = env.repository.summary()

        assertEquals(
            mapOf(DirectoryStatus.COMPLETED to 2L, DirectoryStatus.FAILED to 1L),
            summary.directoriesByStatus,
        )
        assertEquals(3L, summary.directoryCount)
        assertEquals(contents.size.toLong(), summary.fileCount)
        assertEquals(contents.sumOf { it.length }.toLong(), summary.totalBytes)
        assertEquals(listOf(failing), env.repository.directoriesWithStatus(DirectoryStatus.FAILED).map { it.path })
    }

    @Test
    fun summaryOfEmptyInventoryIsZero() = env.test {
        val summary = env.repository.summary()

        assertEquals(emptyMap(), summary.directoriesByStatus)
        assertEquals(0L, summary.directoryCount)
        assertEquals(0L, summary.fileCount)
        assertEquals(0L, summary.totalBytes)
    }
}
