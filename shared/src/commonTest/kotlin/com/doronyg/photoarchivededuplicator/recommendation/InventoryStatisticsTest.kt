package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.scan.DuplicateFileType
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InventoryStatisticsTest {
    private fun file(path: String): FileRecord {
        val p = path.toPath()
        return FileRecord(p, p.parent!!, p.name, p.name.substringAfterLast('.', "").lowercase(), 120_000, 1, 2)
    }

    @Test
    fun everyApplicationFileTypeIsRegisteredForStatisticsUsingExistingExtensions() {
        for (type in DuplicateFileType.entries.filter { it != DuplicateFileType.ALL }) {
            for (extension in type.extensions) {
                assertEquals(type.name, StatisticsFileType.forFilename("file.${extension.uppercase()}").name)
            }
        }
    }

    @Test
    fun countsAllTypesAndEachPossibleDuplicatePathOnceAcrossBothCohorts() = runTest {
        val a = file("/a/IMG.JPG")
        val b = file("/b/IMG.JPG")
        val renamed = file("/c/renamed.jpg")
        val docs = listOf(file("/a/report.pdf"), file("/b/report.pdf"))
        val report = aggregateInventoryStatistics(
            mapOf("jpg" to 4L, "pdf" to 3L, "mp4" to 2L, "zip" to 1L, null to 1L),
            listOf(listOf(a, b), docs), listOf(listOf(a, b, renamed)))
        assertEquals(InventoryFileCounts(11, 5), report.all)
        assertEquals(InventoryFileCounts(4, 3), report.byFileType.getValue(StatisticsFileType.IMAGES))
        assertEquals(InventoryFileCounts(3, 2), report.byFileType.getValue(StatisticsFileType.DOCUMENTS))
        assertEquals(InventoryFileCounts(2, 0), report.byFileType.getValue(StatisticsFileType.VIDEOS))
        assertEquals(InventoryFileCounts(2, 0), report.byFileType.getValue(StatisticsFileType.OTHER))
        assertEquals(StatisticsFileType.entries.toSet(), report.byFileType.keys)
    }

    @Test
    fun unknownExtensionsAndDotFilesUseTheSameExistingClassificationAsRecommendations() = runTest {
        val dot = file("/a/.JPG")
        val result = aggregateInventoryStatistics(
            mapOf("JPG" to 1L, "txt" to 1L, null to 2L), listOf(listOf(dot)), emptyList())
        assertEquals(InventoryFileCounts(1, 1), result.byFileType.getValue(StatisticsFileType.IMAGES))
        assertEquals(InventoryFileCounts(1, 0), result.byFileType.getValue(StatisticsFileType.DOCUMENTS))
        assertEquals(InventoryFileCounts(2, 0), result.byFileType.getValue(StatisticsFileType.OTHER))
        assertEquals(InventoryFileCounts(0, 0), result.byFileType.getValue(StatisticsFileType.VIDEOS))
    }

    @Test
    fun emptyInventoryIncludesEveryRegisteredTypeWithZeroCounts() = runTest {
        val empty = aggregateInventoryStatistics(emptyMap(), emptyList(), emptyList())
        assertEquals(InventoryFileCounts(), empty.all)
        assertEquals(StatisticsFileType.entries.toSet(), empty.byFileType.keys)
        assertTrue(empty.byFileType.values.all { it == InventoryFileCounts() })
    }

    @Test
    fun inconsistentSnapshotsAndOverflowAreSurfacedRatherThanClamped() = runTest {
        val file = file("/a/IMG.jpg")
        assertFailsWith<IllegalArgumentException> {
            aggregateInventoryStatistics(mapOf("jpg" to 0L), listOf(listOf(file)), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            aggregateInventoryStatistics(mapOf("jpg" to 2L), listOf(listOf(file)),
                listOf(listOf(file.copy(sizeBytes = 99))))
        }
        assertFailsWith<IllegalArgumentException> { InventoryFileCounts(1, 2) }
        assertFailsWith<IllegalArgumentException> { InventoryFileCounts(-1, 0) }
        assertFailsWith<ArithmeticException> {
            aggregateInventoryStatistics(mapOf("jpg" to Long.MAX_VALUE, "png" to 1L), emptyList(), emptyList())
        }
    }
}
