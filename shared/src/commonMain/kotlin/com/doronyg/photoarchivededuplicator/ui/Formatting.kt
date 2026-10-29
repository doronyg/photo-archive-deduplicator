package com.doronyg.photoarchivededuplicator.ui

import kotlin.math.roundToLong
import kotlin.time.Instant

private const val BYTES_PER_UNIT = 1024.0
private val BYTE_UNITS = listOf("B", "KB", "MB", "GB", "TB")
private const val DECIMAL_SCALE = 10

/** e.g. 1536 -> "1.5 KB". */
fun formatBytes(bytes: Long): String {
    var value = bytes.toDouble()
    var unit = 0
    while (value >= BYTES_PER_UNIT && unit < BYTE_UNITS.lastIndex) {
        value /= BYTES_PER_UNIT
        unit++
    }
    if (unit == 0) return "$bytes B"
    val tenths = (value * DECIMAL_SCALE).roundToLong()
    return "${tenths / DECIMAL_SCALE}.${tenths % DECIMAL_SCALE} ${BYTE_UNITS[unit]}"
}

/** UTC, to the second. */
fun formatEpochMillis(millis: Long?): String =
    millis?.let { Instant.fromEpochMilliseconds(it).toString().substringBefore('.').removeSuffix("Z").replace('T', ' ') }
        ?: "-"

private const val MILLIS_PER_SECOND = 1000
private const val SECONDS_PER_MINUTE = 60

fun formatElapsed(millis: Long): String {
    val seconds = millis / MILLIS_PER_SECOND
    return "${seconds / SECONDS_PER_MINUTE}m ${seconds % SECONDS_PER_MINUTE}s"
}
