package com.fitagent.domain

import java.text.NumberFormat
import java.util.Locale

fun formatCount(value: Long): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)

fun formatCount(value: Int): String = formatCount(value.toLong())

fun formatKm(meters: Double): String = String.format(Locale.getDefault(), "%.1f", meters / 1000.0)

/** 0:42 · 12:05 · 1:02:33 */
fun formatClock(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatMinutes(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "$minutes min"
