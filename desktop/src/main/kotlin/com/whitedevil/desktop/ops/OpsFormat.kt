package com.whitedevil.desktop.ops

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Display helpers. Pure, so they can be tested; every "unknown" is spelled out, never rendered as 0. */

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

fun formatClock(epochMillis: Long): String = CLOCK.format(Instant.ofEpochMilli(epochMillis))

/** "45s", "3m 05s", "2h 10m". Negative input (clock skew) reads as "0s". */
fun formatAge(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${"%02d".format(Locale.ROOT, s % 60)}s"
        else -> "${s / 3600}h ${"%02d".format(Locale.ROOT, (s % 3600) / 60)}m"
    }
}

/** Age of an epoch-seconds stamp relative to [nowMillis]; null when the stamp is missing or zero ("never"). */
fun ageOfEpochSec(epochSec: Double?, nowMillis: Long = System.currentTimeMillis()): Long? {
    if (epochSec == null || epochSec <= 0.0) return null
    return (nowMillis / 1000.0 - epochSec).toLong()
}

fun money(v: Double?, digits: Int = 2): String =
    if (v == null) "unknown" else "$" + "%.${digits}f".format(Locale.ROOT, v)

fun plainNumber(v: Double?, digits: Int = 2): String =
    if (v == null) "unknown" else "%.${digits}f".format(Locale.ROOT, v)

fun orUnknown(v: Any?): String = v?.toString() ?: "unknown"

fun yesNoUnknown(v: Boolean?): String = when (v) {
    true -> "yes"
    false -> "no"
    null -> "unknown"
}
