package com.doronyg.photoarchivededuplicator.exif

private val exifDateTime = Regex("([0-9]{4}):([0-9]{2}):([0-9]{2}) ([0-9]{2}):([0-9]{2}):([0-9]{2})")
private val exifOffset = Regex("[+-]([0-9]{2}):([0-9]{2})")

// SQLite's default trim removes only ASCII spaces; use exactly Kotlin's whitespace set.
internal val EXIF_SURROUNDING_WHITESPACE: String =
    (Char.MIN_VALUE..Char.MAX_VALUE).filter { it.isWhitespace() }.joinToString("")

/** Reliable, complete camera-local DateTimeOriginal, without timezone conversion. */
internal fun validatedExifTimestamp(raw: String?): String? {
    val value = raw?.trim() ?: return null
    val match = exifDateTime.matchEntire(value) ?: return null
    val (year, month, day, hour, minute, second) = match.destructured
    val y = year.toInt()
    val m = month.toInt()
    if (y !in 1971..2099 || m !in 1..12 ||
        hour.toInt() !in 0..23 || minute.toInt() !in 0..59 || second.toInt() !in 0..59) return null
    val days = when (m) {
        2 -> if (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
    return value.takeIf { day.toInt() in 1..days }
}

internal fun isValidExifOffset(value: String): Boolean {
    val match = exifOffset.matchEntire(value) ?: return false
    return match.groupValues[1].toInt() in 0..23 && match.groupValues[2].toInt() in 0..59
}
