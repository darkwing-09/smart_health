package com.fitagent.domain

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Readiness v2 — mirrors agents/src/fitagent_agents/metrics.py; both are checked against
 * fixtures/readiness_v2.json. See docs/phase2/05-agents-and-algorithms.md §1.
 *
 *   sleep          25%   min(sleep / 8h, 1) × 100
 *   sleep quality  10%   stage shares (deep, REM, awake) — only when the watch reports stages
 *   HRV            25%   75 + 25 × z of ln(HRV) vs the 14-day personal baseline (≥ 7 nights)
 *   resting HR     20%   85 − 10 × (today − 14-day baseline)
 *   nightly         5%   100 − 20 per abnormal signal (breathing rate, skin temperature, SpO2)
 *   load           15%   acute:chronic ratio; only counted when it signals risk (> 1.3) or nothing else exists
 *
 * Missing components are dropped and the remaining weights re-normalised; no data at all → neutral 60.
 * Safety caps: fever-like signals → ≤ 45, sleep < 4 h → ≤ 55, load spike → ≤ 66 (never "push").
 * Rounding is half-up (roundToInt) on both sides.
 */

enum class Band(val label: String) {
    PUSH("Ready to push"),
    STEADY("Steady day"),
    RECOVER("Recovery day"),
}

data class Readiness(
    val score: Int,
    val band: Band,
    val components: Map<String, Int>,
    val reasons: List<String>,
    val acwr: Double?,
)

private const val SLEEP_TARGET_MIN = 8 * 60
private const val BASELINE_WINDOW_DAYS = 14L
private const val MIN_BASELINE_DAYS = 3
private const val MIN_HRV_NIGHTS = 7
private const val HRV_MIN_SD = 0.05
private const val MIN_HISTORY_DAYS_FOR_LOAD = 14
/** Score when there is no data at all: a steady day, never "push" by default. */
private const val NEUTRAL_SCORE = 60
/** Percent; integers so Kotlin and Python compute the weighted average exactly. */
private val WEIGHTS = mapOf(
    "sleep" to 25, "sleep_quality" to 10, "hrv" to 25, "resting_hr" to 20, "load" to 15, "nightly" to 5,
)

fun bandFor(score: Int): Band = when {
    score >= 67 -> Band.PUSH
    score >= 34 -> Band.STEADY
    else -> Band.RECOVER
}

private fun minutesBetween(workouts: List<Workout>, start: LocalDate, end: LocalDate, zone: ZoneId): Int =
    workouts.filter {
        val d = it.startMs.toLocalDate(zone)
        !d.isBefore(start) && !d.isAfter(end)
    }.sumOf { it.minutes }

fun acuteChronicRatio(workouts: List<Workout>, today: LocalDate, historyDays: Int, zone: ZoneId): Double? {
    val span = minOf(historyDays, 28)
    if (span < MIN_HISTORY_DAYS_FOR_LOAD) return null
    val acute = minutesBetween(workouts, today.minusDays(6), today, zone) / 7.0
    val chronic = minutesBetween(workouts, today.minusDays(span - 1L), today, zone) / span.toDouble()
    if (chronic <= 0) return null
    return (acute / chronic * 100).roundToInt() / 100.0
}

/** 75 + 25 × z, where z compares tonight's ln(HRV) with the personal baseline. Higher HRV = better recovered. */
fun hrvScore(todayMs: Double, baseline: List<Double>): Int {
    val logs = baseline.filter { it > 0 }.map { ln(it) }
    val mean = logs.average()
    val sd = maxOf(sqrt(logs.sumOf { (it - mean) * (it - mean) } / logs.size), HRV_MIN_SD)
    val z = (ln(todayMs) - mean) / sd
    return (75 + 25 * z).coerceIn(0.0, 100.0).roundToInt()
}

fun computeReadiness(days: List<DayActivity>, workouts: List<Workout>, today: LocalDate, zone: ZoneId): Readiness {
    val t = days.firstOrNull { it.date == today }
    val components = linkedMapOf<String, Int>()
    val reasons = mutableListOf<String>()
    val windowStart = today.minusDays(BASELINE_WINDOW_DAYS)
    fun <T : Number> baseline(pick: (DayActivity) -> T?): List<Double> =
        days.filter { !it.date.isBefore(windowStart) && it.date.isBefore(today) }.mapNotNull { pick(it)?.toDouble() }

    t?.sleepMinutes?.let { sleep ->
        components["sleep"] = (sleep.toDouble() / SLEEP_TARGET_MIN * 100).coerceIn(0.0, 100.0).roundToInt()
        reasons += "Slept ${formatHours(sleep)} last night"
        t.sleepStages?.let { components["sleep_quality"] = sleepQuality(it) }
    }

    val hrvBase = baseline { it.night.hrvMs }
    val hrv = t?.night?.hrvMs
    if (hrv != null && hrv > 0 && hrvBase.size >= MIN_HRV_NIGHTS) {
        components["hrv"] = hrvScore(hrv, hrvBase)
        val avg = hrvBase.average()
        reasons += "HRV ${hrv.roundToInt()} ms, ${if (hrv >= avg) "above" else "below"} your normal ${avg.roundToInt()} ms"
    }

    val rhrBase = baseline { it.restingHr }
    val rhr = t?.restingHr
    var rhrDelta: Double? = null
    if (rhr != null && rhrBase.size >= MIN_BASELINE_DAYS) {
        val delta = rhr - rhrBase.average()
        rhrDelta = delta
        components["resting_hr"] = (85 - 10 * delta).coerceIn(0.0, 100.0).roundToInt()
        reasons += "Resting HR $rhr bpm, ${abs(delta).roundToInt()} bpm ${if (delta > 0) "above" else "below"} your 14-day average"
    }

    if (t != null) {
        var nightly = 100
        var signals = 0
        val respBase = baseline { it.night.respiratoryRate }
        t.night.respiratoryRate?.let { resp ->
            signals++
            if (respBase.size >= MIN_BASELINE_DAYS && resp > respBase.average() + 2) {
                nightly -= 20
                reasons += "Breathing rate ${"%.1f".format(resp)}/min is higher than usual"
            }
        }
        t.night.skinTempDelta?.let { delta ->
            signals++
            if (delta > 0.5) {
                nightly -= 20
                reasons += "Skin temperature ${"%+.1f".format(delta)} °C vs your baseline"
            }
        }
        t.night.spo2Avg?.let { spo2 ->
            signals++
            if (spo2 < 94) {
                nightly -= 20
                reasons += "Blood oxygen averaged ${spo2.roundToInt()}% overnight"
            }
        }
        if (signals > 0) components["nightly"] = nightly
    }

    val firstData = listOfNotNull(
        days.filter { it.hasData }.minOfOrNull { it.date },
        workouts.minOfOrNull { it.startMs.toLocalDate(zone) },
    ).minOrNull() ?: today
    val historyDays = ChronoUnit.DAYS.between(firstData, today).toInt() + 1
    val acwr = acuteChronicRatio(workouts, today, historyDays, zone)
    // Training load is evidence of *risk*, not of recovery: a normal (or unknown) load only counts when there is
    // nothing else to go on, so it can't prop up a night of bad sleep.
    when {
        acwr == null -> if (components.isEmpty()) components["load"] = NEUTRAL_SCORE
        acwr <= 1.3 -> if (components.isEmpty()) components["load"] = 90
        acwr <= 1.5 -> { components["load"] = 60; reasons += "Training load rising fast (ratio $acwr)" }
        else -> { components["load"] = 30; reasons += "Training load spiked (ratio $acwr) — injury risk is higher" }
    }

    val totalWeight = components.keys.sumOf { WEIGHTS.getValue(it) }
    val weighted = components.entries.sumOf { WEIGHTS.getValue(it.key) * it.value }
    var score = (2 * weighted + totalWeight) / (2 * totalWeight) // exact half-up rounding of weighted / total

    // Safety caps: never suggest a hard day when the body shows strain, whatever the other numbers say.
    val skin = t?.night?.skinTempDelta
    if (skin != null && skin > 1.0 && rhrDelta != null && rhrDelta > 5) {
        score = minOf(score, 45)
        reasons.add(0, "Your body may be fighting something — take it easy today")
    }
    t?.sleepMinutes?.let { if (it < 240) score = minOf(score, 55) }
    if (acwr != null && acwr > 1.5) score = minOf(score, 66)
    return Readiness(score, bandFor(score), components, reasons, acwr)
}

fun formatHours(minutes: Int): String = "${minutes / 60}h ${minutes % 60}m"
