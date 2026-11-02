package com.doronyg.photoarchivededuplicator.scan

enum class DuplicateFileType(private val extensions: Set<String>) {
    ALL(emptySet()),
    IMAGES(setOf(
        "jpg", "jpeg", "jpe", "png", "gif", "webp", "bmp", "tif", "tiff", "heic", "heif", "avif",
        "svg", "ico", "jxl", "raw", "dng", "cr2", "cr3", "nef", "nrw", "arw", "orf", "rw2", "raf", "pef", "srw",
    )),
    VIDEOS(setOf(
        "mp4", "m4v", "mov", "avi", "mkv", "webm", "mpg", "mpeg", "m2v", "3gp", "3g2",
        "mts", "m2ts", "ts", "vob", "wmv", "flv", "ogv", "asf",
    )),
    DOCUMENTS(setOf(
        "pdf", "txt", "md", "rtf", "doc", "docx", "odt", "xls", "xlsx", "ods", "csv",
        "ppt", "pptx", "odp", "pages", "numbers", "key", "epub", "mobi", "html", "htm",
    ));

    internal fun matches(name: String): Boolean =
        this == ALL || name.substringAfterLast('.', "").lowercase() in extensions
}

/** Extension category AND a case-insensitive whole-filename wildcard, not a regular expression. */
data class DuplicateCandidateFilter(
    val fileType: DuplicateFileType = DuplicateFileType.ALL,
    val filenamePattern: String = "",
) {
    private val pattern = filenamePattern.trim().ifEmpty { "*" }

    fun matches(name: String): Boolean = fileType.matches(name) && matchesPattern(name)

    private fun matchesPattern(name: String): Boolean {
        var nameIndex = 0
        var patternIndex = 0
        var lastStar = -1
        var starEnd = 0
        // Retry only the last star's span: no recursion or regex backtracking.
        while (nameIndex < name.length) {
            when {
                patternIndex < pattern.length && pattern[patternIndex] == '*' -> {
                    lastStar = patternIndex++
                    starEnd = nameIndex
                }
                patternIndex < pattern.length &&
                    (pattern[patternIndex] == '?' || pattern[patternIndex].equals(name[nameIndex], ignoreCase = true)) -> {
                    patternIndex++
                    nameIndex++
                }
                lastStar >= 0 -> {
                    patternIndex = lastStar + 1
                    nameIndex = ++starEnd
                }
                else -> return false
            }
        }
        while (patternIndex < pattern.length && pattern[patternIndex] == '*') patternIndex++
        return patternIndex == pattern.length
    }
}
