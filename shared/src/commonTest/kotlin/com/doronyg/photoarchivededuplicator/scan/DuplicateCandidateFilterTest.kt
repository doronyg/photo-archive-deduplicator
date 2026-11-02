package com.doronyg.photoarchivededuplicator.scan

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DuplicateCandidateFilterTest {
    @Test
    fun defaultFilterIncludesEveryFilename() {
        val filter = DuplicateCandidateFilter()
        for (name in listOf("photo.jpg", "movie.mp4", "notes.pdf", "archive.zip", "README", ".hidden")) {
            assertTrue(filter.matches(name), name)
        }
    }

    @Test
    fun categoriesUseCaseInsensitiveFinalExtensions() {
        val examples = mapOf(
            DuplicateFileType.IMAGES to listOf("photo.JPG", "photo.jpeg", "photo.HEIC", "photo.dng", "photo.png"),
            DuplicateFileType.VIDEOS to listOf("movie.MP4", "movie.mov", "movie.mkv", "movie.MTS"),
            DuplicateFileType.DOCUMENTS to listOf("notes.PDF", "notes.docx", "notes.xlsx", "notes.txt", "notes.odt"),
        )
        for ((type, names) in examples) {
            val filter = DuplicateCandidateFilter(type)
            for (name in names) assertTrue(filter.matches(name), "$type: $name")
            for ((other, otherNames) in examples) {
                if (other != type) {
                    for (name in otherNames) assertFalse(filter.matches(name), "$type: $name")
                }
            }
            for (name in listOf("README", "archive.zip", "photo.jpg.bak", "jpg", "photo.")) {
                assertFalse(filter.matches(name), "$type: $name")
            }
        }
    }

    @Test
    fun starMatchesZeroOrMoreCharactersAndDotIsLiteral() {
        val filter = DuplicateCandidateFilter(filenamePattern = "IMG*.jpg")
        for (name in listOf("IMG.jpg", "IMG_123.jpg", "img_123.JPG", "IMG.family.trip.jpg")) {
            assertTrue(filter.matches(name), name)
        }
        for (name in listOf("XIMG_123.jpg", "IMG_123Xjpg", "IMG_123.jpg.bak", "IMG_123.png")) {
            assertFalse(filter.matches(name), name)
        }
    }

    @Test
    fun questionMarkMatchesExactlyOneCharacter() {
        val filter = DuplicateCandidateFilter(filenamePattern = "IMG_?.jpg")
        assertTrue(filter.matches("img_1.JPG"))
        assertFalse(filter.matches("IMG_.jpg"))
        assertFalse(filter.matches("IMG_12.jpg"))
    }

    @Test
    fun regexMetacharactersAndBackslashesAreLiteral() {
        val name = "IMG[1](copy)+{a}^$|\\.jpg"
        assertTrue(DuplicateCandidateFilter(filenamePattern = name).matches(name))
        assertFalse(DuplicateCandidateFilter(filenamePattern = "IMG[1].jpg").matches("IMG1.jpg"))
        assertFalse(DuplicateCandidateFilter(filenamePattern = "IMG+.jpg").matches("IMGG.jpg"))
    }

    @Test
    fun blankPatternDoesNotRestrictResultsAndSurroundingWhitespaceIsIgnored() {
        assertTrue(DuplicateCandidateFilter(filenamePattern = " \t ").matches("README"))
        assertTrue(DuplicateCandidateFilter(filenamePattern = " IMG*.jpg ").matches("img_1.JPG"))
        assertTrue(DuplicateCandidateFilter(filenamePattern = "*").matches("README"))
    }

    @Test
    fun categoryAndFilenamePatternMustBothMatch() {
        val filter = DuplicateCandidateFilter(DuplicateFileType.IMAGES, "holiday*")
        assertTrue(filter.matches("Holiday.JPG"))
        assertFalse(filter.matches("Holiday.mp4"))
        assertFalse(filter.matches("Other.jpg"))
    }

    @Test
    fun multipleStarsBacktrackWithoutTreatingPatternsAsRegex() {
        val filter = DuplicateCandidateFilter(filenamePattern = "*ab*ab?.**jpg")
        assertTrue(filter.matches("first_ab_second_ab1.JPG"))
        assertFalse(filter.matches("first_ab_second_ab.JPG"))
        assertTrue(DuplicateCandidateFilter(filenamePattern = "**IMG**.jpg**").matches("IMG.jpg"))
        assertFalse(DuplicateCandidateFilter(filenamePattern = "*a*a*a*b").matches("a".repeat(1_000)))
    }
}
