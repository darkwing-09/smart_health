package com.fitagent.domain

import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * One-sentence headlines shown above each chart. Pure functions, computed on the phone (instant, offline, no
 * LLM) and unit-tested. Wording rule: describe and encourage, never diagnose.
 */

private fun minutesText(m: Int): String = if (m >= 60) "${m / 60}h ${m % 60}m" else "$m min"

/** The component that moved the readiness score most, in plain words. */
fun readinessHeadline(r: Readiness): String {
    val c = r.components
    val worst = c.filterKeys { it != "load" || c.size == 1 }.minByOrNull { it.value }
    return when {
        r.reasons.firstOrNull()?.startsWith("Your body may be fighting") == true -> "Your body may be fighting something — keep today easy."
        r.acwr != null && r.acwr > 1.5 -> "Training load jumped this week — a lighter day lowers injury risk."
        worst == null -> "Wear your watch tonight to get a personal readiness score."
        worst.value >= 80 -> "Everything looks recovered — a good day for a harder session."
        worst.key == "sleep" -> "Short sleep is holding your score back."
        worst.key == "sleep_quality" -> "Light, broken sleep last night — keep intensity moderate."
        worst.key == "hrv" -> "HRV is below your normal — your body is still recovering."
        worst.key == "resting_hr" -> "Resting heart rate is up — take it a bit easier."
        worst.key == "nightly" -> "Some overnight signals were off — listen to your body today."
        worst.key == "load" -> "Training load is rising fast — mix in an easy day."
        else -> "Moderate effort today keeps you moving without overreaching."
    }
}

/** "You slept 42 min more than your average" style comparison of last night with the previous nights. */
fun sleepHeadline(lastNight: Int?, previous: List<Int>): String {
    if (lastNight == null) return "No sleep recorded last night — wear your watch to bed to see stages."
    if (previous.size < 3) return "You slept ${minutesText(lastNight)} last night."
    val avg = previous.average().roundToInt()
    val diff = lastNight - avg
    return when {
        abs(diff) < 15 -> "You slept ${minutesText(lastNight)} — right on your usual."
        diff > 0 -> "You slept ${minutesText(diff)} more than your average."
        else -> "You slept ${minutesText(-diff)} less than your average."
    }
}

/** Compares today's value with the personal normal range (mean ± 1 SD of the previous values). */
fun baselineHeadline(label: String, today: Double?, previous: List<Double>, higherIsBetter: Boolean, unit: String): String {
    if (today == null) return "No $label today yet."
    if (previous.size < 5) return "$label ${today.roundToInt()} $unit — a personal range appears after a week of data."
    val mean = previous.average()
    val sd = kotlin.math.sqrt(previous.sumOf { (it - mean) * (it - mean) } / previous.size).coerceAtLeast(1.0)
    return when {
        today > mean + sd -> if (higherIsBetter) "$label is above your normal range — nicely recovered." else "$label is above your normal range."
        today < mean - sd -> if (higherIsBetter) "$label is below your normal range." else "$label is below your normal range — nicely recovered."
        else -> "$label is within your normal range."
    }
}

/** Normal range (mean ± 1 SD) for chart shading, or null with too little data. */
fun normalRange(values: List<Double>): ClosedFloatingPointRange<Double>? {
    if (values.size < 5) return null
    val mean = values.average()
    val sd = kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    return (mean - sd)..(mean + sd)
}

/** The busiest hour of the day, e.g. "Most active around 18:00". */
fun activeHourHeadline(hourly: List<Long>): String {
    val total = hourly.sum()
    if (total <= 0) return "No steps recorded yet today."
    val peak = hourly.indices.maxBy { hourly[it] }
    return "Most active around %02d:00 — ${formatCount(hourly[peak])} steps that hour.".format(peak)
}

fun stepsTrendHeadline(days: List<DayActivity>, goal: Int): String {
    if (days.isEmpty()) return ""
    val met = days.count { it.steps >= goal }
    val avg = days.map { it.steps }.average().roundToInt()
    return "Average ${formatCount(avg.toLong())} a day · goal reached on $met of ${days.size} days."
}

fun whoHeadline(minutes: Int): String = when {
    minutes >= WHO_WEEKLY_MINUTES -> "WHO's 150 active minutes reached this week — great consistency."
    else -> "${WHO_WEEKLY_MINUTES - minutes} more active minutes reach the WHO's 150 this week."
}

/** Pearson-style "each extra hour of sleep ≈ +N readiness" from paired points (hours slept, readiness). */
fun sleepReadinessHeadline(points: List<Pair<Float, Float>>): String? {
    if (points.size < 7) return null
    val n = points.size
    val mx = points.sumOf { it.first.toDouble() } / n
    val my = points.sumOf { it.second.toDouble() } / n
    val sxx = points.sumOf { (it.first - mx) * (it.first - mx) }
    if (sxx < 0.25) return null
    val slope = points.sumOf { (it.first - mx) * (it.second - my) } / sxx
    val rounded = slope.roundToInt()
    return when {
        rounded >= 2 -> "For you, each extra hour of sleep ≈ +$rounded readiness."
        rounded <= -2 -> "More sleep hasn't lifted your readiness lately — other factors matter more right now."
        else -> "Your readiness hasn't tracked sleep closely yet."
    }
}
