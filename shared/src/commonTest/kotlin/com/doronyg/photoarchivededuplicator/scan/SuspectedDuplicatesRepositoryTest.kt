package com.doronyg.photoarchivededuplicator.scan

import com.doronyg.photoarchivededuplicator.exif.*
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.*

class SuspectedDuplicatesRepositoryTest {
    private suspend fun seed(
        f: ConcurrencyFixture,
        directory: Path = f.root,
        size: Long = 100_001,
        vararg names: String,
    ): List<FileRecord> {
        f.repository.seedRoot(directory)
        f.repository.recordListing(directory, DirectoryListing(emptyList(), names.map {
            DirectoryListing.Entry(directory / it, size, null)
        }), 1)
        return f.repository.filesIn(directory)
    }

    private suspend fun exif(
        f: ConcurrencyFixture,
        file: FileRecord,
        date: String? = "2024:02:29 12:34:56",
        offset: String? = null,
        status: ExifStatus = ExifStatus.EXTRACTED,
        reader: String = "kim",
        version: String = KIM_VERSION,
    ) {
        assertTrue(f.repository.saveFileExif(ExifRecord(
            ExifInput.from(file), ExifReadResult(status, ExifMetadata(date, offset)), 2, reader, version,
        )))
    }

    @Test
    fun sizeFirstQualificationIncludesWholeMixedNameBucketsAndSkipsOtherInventory() = concurrencyTest { f ->
        seed(f, f.root / "boundary", 100_000, "a.jpg", "b.jpg")
        seed(f, f.root / "small", 99_999, "a.jpg", "b.jpg")
        seed(f, f.root / "singleton", 200_000, "single.jpg", "not-image.mp4")
        seed(f, f.root / "same-a", 300_000, "same.jpg")
        seed(f, f.root / "same-b", 300_000, "same.jpg")
        val a = seed(f, f.root / "mixed-a", 100_001, "same.JPG", "different.raw", "skip.txt")
        val b = seed(f, f.root / "mixed-b", 100_001, "same.JPG")
        val caseOnly = seed(f, f.root / "case", 400_000, "PHOTO.png", "photo.png")
        val before = f.driver.statements.size
        val groups = f.repository.suspectedExifCandidates()
        assertTrue(f.driver.statements.drop(before).none { "fileExif" in it })
        assertEquals(listOf(100_001L, 400_000L), groups.map { it.sizeBytes })
        assertEquals((a.filter { it.extension != "txt" } + b).sortedBy { it.path.toString() }, groups[0].files)
        assertEquals(caseOnly.sortedBy { it.path.toString() }, groups[1].files)
        f.repository.allFiles().forEach { exif(f, it) }
        assertEquals(groups.map { it.files }, f.repository.suspectedDuplicates().map { it.files })
        assertNull(f.fakeFs.metadataOrNull(f.root)) // All seeded source paths are nonexistent.
    }

    @Test
    fun rootAndExclusionsUseLiteralCaseSensitiveTreeBoundariesIncludingWindows() = concurrencyTest { f ->
        val root = "/photos%_".toPath()
        val inside = seed(f, root / "inside", 100_001, "a.jpg", "b.jpg")
        seed(f, "/photosXY/inside".toPath(), 100_001, "c.jpg", "d.jpg")
        seed(f, "/photos%_sibling".toPath(), 100_001, "c.jpg", "d.jpg")
        seed(f, "/Photos%_/inside".toPath(), 100_001, "c.jpg", "d.jpg")
        seed(f, root / "private%_", 100_001, "c.jpg", "d.jpg")
        val literalSibling = seed(f, root / "privateXY", 100_001, "e.jpg", "f.jpg")
        f.repository.addExclusion(root / "private%_")
        f.repository.allFiles().forEach { exif(f, it) }
        assertEquals((inside + literalSibling).sortedBy { it.path.toString() },
            f.repository.suspectedExifCandidates(root).single().files)
        assertEquals((inside + literalSibling).sortedBy { it.path.toString() },
            f.repository.suspectedDuplicates(root).single().files)
        assertTrue(f.repository.suspectedExifCandidates(root / "ins").isEmpty())
        val windows = "C:\\Photos%_".toPath()
        val win = seed(f, windows / "child", 100_001, "a.jpg", "b.jpg")
        seed(f, "C:\\Photos%_sibling".toPath(), 100_001, "a.jpg", "b.jpg")
        win.forEach { exif(f, it) }
        assertEquals(win.sortedBy { it.path.toString() }, f.repository.suspectedExifCandidates(windows).single().files)
        assertEquals(win.sortedBy { it.path.toString() }, f.repository.suspectedDuplicates(windows).single().files)
        f.repository.addExclusion(windows / "child")
        assertTrue(f.repository.suspectedExifCandidates(windows).isEmpty())
        assertTrue(f.repository.suspectedDuplicates(windows).isEmpty())
        assertTrue(f.repository.suspectedExifCandidates().single().files.size > inside.size)
    }

    @Test
    fun matchingUsesFullSecondSizeAndFinalMixedNameRuleInDeterministicOrder() = concurrencyTest { f ->
        val files = seed(f, f.root, 100_001, "z.jpg", "a.jpg", "seconds.jpg", "days.jpg", "missing.jpg")
        files.forEach { file ->
            when (file.name) {
                "seconds.jpg" -> exif(f, file, "2024:02:29 12:34:57")
                "days.jpg" -> exif(f, file, "2024:03:01 12:34:56")
                "missing.jpg" -> Unit
                else -> exif(f, file, "\t 2024:02:29 12:34:56 \n")
            }
        }
        val copies = seed(f, f.root / "copies", 100_001, "a.jpg")
        exif(f, copies.single())
        val sameA = seed(f, f.root / "same-a", 200_000, "same.jpg")
        val sameB = seed(f, f.root / "same-b", 200_000, "same.jpg")
        val other = seed(f, f.root / "other", 200_000, "other.jpg")
        (sameA + sameB).forEach { exif(f, it) }
        exif(f, other.single(), "2024:02:29 12:34:57")
        val large = seed(f, f.root / "larger", 300_000, "second.jpg", "first.jpg")
        large.forEach { exif(f, it, "2023:01:01 00:00:00") }
        val groups = f.repository.suspectedDuplicates()
        assertEquals(listOf(100_001L, 300_000L), groups.map { it.sizeBytes })
        assertEquals("2024:02:29 12:34:56", groups[0].capturedAt)
        assertEquals(listOf("a.jpg", "a.jpg", "z.jpg"), groups[0].files.map { it.name })
        assertEquals(groups, f.repository.suspectedDuplicates())
        assertTrue(f.repository.suspectedDuplicates(f.root / "copies").isEmpty())
        f.repository.addExclusion(f.root / "larger")
        assertEquals(1, f.repository.suspectedDuplicates().size)
    }

    @Test
    fun offsetsKeepUnknownSeparateAndRejectMalformedNonblankValues() = concurrencyTest { f ->
        val offsets = listOf(null, " \t", "+02:00", " +02:00\n", "-03:30", "-03:30",
            "+24:00", "+24:00", "+01:60", "+01:60", "Z", "Z", "+2:00", "+2:00", "+00:00", "+00:00")
        val files = seed(f, f.root, 100_001, *offsets.indices.map { "$it.jpg" }.toTypedArray())
        files.forEach { exif(f, it, offset = offsets[it.name.substringBefore('.').toInt()]) }
        val groups = f.repository.suspectedDuplicates()
        assertEquals(setOf(null, "+02:00", "-03:30", "+00:00"), groups.map { it.offsetOriginal }.toSet())
        assertTrue(groups.all { it.files.size == 2 })
    }

    @Test
    fun allExistingImageExtensionsParticipateAndDisplayMetadataAndSubsecondsDoNotAffectMatches() =
        concurrencyTest { f ->
            val extensions = DuplicateFileType.IMAGES.extensions
            val files = seed(f, f.root, 100_001,
                *extensions.map { "photo.$it" }.toTypedArray(), ".jpg")
            for ((i, file) in files.withIndex()) {
                assertTrue(f.repository.saveFileExif(ExifRecord(ExifInput.from(file),
                    ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(
                        capturedAt = "\u20032024:02:29 12:34:56\u00a0",
                        subsecondOriginal = "$i", make = "make$i", model = "model$i", width = i, height = i + 1,
                    )), 2)))
            }
            assertEquals(extensions.size + 1, f.repository.suspectedExifCandidates().single().files.size)
            assertEquals(files.sortedBy { it.path.toString() }, f.repository.suspectedDuplicates().single().files)
        }

    @Test
    fun equalSizeGroupsAreOrderedByCaptureTimeAndEachGroupsPathsAreSorted() = concurrencyTest { f ->
        val dates = listOf("2024:01:02 23:59:59", "2024:01:01 00:00:00", "2024:01:02 00:00:00")
        for ((i, date) in dates.withIndex()) {
            seed(f, f.root / "$i", 100_001, "z.jpg", "a.jpg").forEach { exif(f, it, date) }
        }
        val groups = f.repository.suspectedDuplicates()
        assertEquals(dates.sorted(), groups.map { it.capturedAt })
        assertTrue(groups.all { it.files.map { file -> file.name } == listOf("a.jpg", "z.jpg") })
    }

    @Test
    fun failedMissingUnreliableAndMalformedDatesAndOldReadersNeverMatch() = concurrencyTest { f ->
        val dates = listOf<String?>(null, "", "2024:02:30 12:00:00", "2023:02:29 12:00:00",
            "1970:01:01 00:00:00", "2100:01:01 00:00:00", "2024:01:01", "2024:01:01 24:00:00",
            "2024:01:01 00:60:00", "2024:01:01 00:00:60", "0000:00:00 00:00:00")
        for ((i, date) in dates.withIndex()) {
            seed(f, f.root / "invalid-$i", 100_001L + i, "a.jpg", "b.jpg").forEach { exif(f, it, date) }
        }
        for (status in ExifStatus.entries.filter { it != ExifStatus.EXTRACTED }) {
            seed(f, f.root / status.name, 200_000, "a.jpg", "b.jpg").forEach { exif(f, it, status = status) }
        }
        seed(f, f.root / "old-version", 300_000, "a.jpg", "b.jpg").forEach { exif(f, it, version = "old") }
        seed(f, f.root / "other-reader", 400_000, "a.jpg", "b.jpg").forEach { exif(f, it, reader = "other") }
        assertTrue(f.repository.suspectedDuplicates().isEmpty())
    }

    @Test
    fun fingerprintChecksAreNullSafeAndIgnoreLastScannedOnly() = concurrencyTest { f ->
        val directory = f.root / "current"
        val files = seed(f, directory, 100_001, "a.jpg", "b.jpg")
        files.forEach { exif(f, it) }
        f.repository.recordListing(directory, DirectoryListing(emptyList(), files.map {
            DirectoryListing.Entry(it.path, it.sizeBytes, null)
        }), 999)
        assertEquals(1, f.repository.suspectedDuplicates().size)
        for (changed in listOf(
            DirectoryListing.Entry(files[0].path, 100_002, null),
            DirectoryListing.Entry(files[0].path, 100_001, 42),
            DirectoryListing.Entry(files[0].path, 100_001, null, 42),
        )) {
            f.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
                changed, DirectoryListing.Entry(files[1].path, 100_001, null),
            )), 1000)
            assertTrue(f.repository.suspectedDuplicates().isEmpty())
        }
    }

    @Test
    fun mismatchedPersistedPathAndDirectoryFingerprintsAreNotCurrent() = concurrencyTest { f ->
        val files = seed(f, f.root, 100_001, "a.jpg", "b.jpg")
        files.forEach { exif(f, it) }
        f.lane.run("change EXIF directory fingerprint") {
            f.driver.execute(null, "UPDATE fileExif SET directoryPath = '/elsewhere'", 0).await()
        }
        assertTrue(f.repository.suspectedDuplicates().isEmpty())
        files.forEach { exif(f, it) }
        f.lane.run("change EXIF size fingerprint without changing eligible inventory buckets") {
            f.driver.execute(null, "UPDATE fileExif SET inputSizeBytes = 100002", 0).await()
        }
        assertTrue(f.repository.suspectedDuplicates().isEmpty())
        files.forEach { exif(f, it) }
        f.lane.run("orphan EXIF paths") {
            f.driver.execute(null, "UPDATE fileExif SET filePath = filePath || '.orphan'", 0).await()
        }
        assertTrue(f.repository.suspectedDuplicates().isEmpty())
    }
}
