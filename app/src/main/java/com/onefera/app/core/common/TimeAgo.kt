package com.onefera.app.core.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Compact relative time: "now", "5m", "3h", "2d", "3w", then "12 Mar". */
fun timeAgo(millis: Long, now: Long = System.currentTimeMillis()): String {
    val seconds = ((now - millis) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "now"
        seconds < 3_600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3_600}h"
        seconds < 7 * 86_400 -> "${seconds / 86_400}d"
        seconds < 5 * 7 * 86_400 -> "${seconds / (7 * 86_400)}w"
        else -> DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))
    }
}

/** 1234 → "1.2K", 2_500_000 → "2.5M". */
fun compactCount(value: Int): String {
    fun trim(v: Double): String {
        val rounded = (v * 10).toInt() / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    }
    return when {
        value >= 1_000_000 -> trim(value / 1_000_000.0) + "M"
        value >= 1_000 -> trim(value / 1_000.0) + "K"
        else -> value.toString()
    }
}
