package com.fitagent.domain

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Readiness score — mirrors agents/src/fitagent_agents/metrics.py. Keep both in sync.
 *
 *   sleep       40%   min(sleep / 8h, 1) × 100
 *   resting HR  35%   85 − 10 × (today − 14-day personal baseline), clamped 0..100
 *   load        25%   acute:chronic workload ratio ≤1.3 → 90, ≤1.5 → 60, else 30
 *
 * Missing components are dropped and the remaining weights re-normalised. With no data at all the
 * score is a neutral 60 (steady).
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
private const val MIN_HISTORY_DAYS_FOR_LOAD = 14
/** Score when there is no data at all: a steady day, never "push" by default. */
private const val NEUTRAL_SCORE = 60
private val WEIGHTS = mapOf("sleep" to 0.40, "resting_hr" to 0.35, "load" to 0.25)

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

fun computeReadiness(days: List<DayActivity>, workouts: List<Workout>, today: LocalDate, zone: ZoneId): Readiness {
    val todayDay = days.firstOrNull { it.date == today }
    val components = linkedMapOf<String, Int>()
    val reasons = mutableListOf<String>()

    todayDay?.sleepMinutes?.let { sleep ->
        components["sleep"] = (sleep.toDouble() / SLEEP_TARGET_MIN * 100).coerceIn(0.0, 100.0).roundToInt()
        reasons += "Slept ${formatHours(sleep)} last night"
    }

    val windowStart = today.minusDays(BASELINE_WINDOW_DAYS)
    val baseline = days.filter { !it.date.isBefore(windowStart) && it.date.isBefore(today) }.mapNotNull { it.restingHr }
    val rhr = todayDay?.restingHr
    if (rhr != null && baseline.size >= MIN_BASELINE_DAYS) {
        val delta = rhr - baseline.average()
        components["resting_hr"] = (85 - 10 * delta).coerceIn(0.0, 100.0).roundToInt()
        val direction = if (delta > 0) "above" else "below"
        reasons += "Resting HR $rhr bpm, ${abs(delta).roundToInt()} bpm $direction your 14-day average"
    }

    val firstData = listOfNotNull(
        days.filter { it.hasData }.minOfOrNull { it.date },
        workouts.minOfOrNull { it.startMs.toLocalDate(zone) },
    ).minOrNull() ?: today
    val historyDays = ChronoUnit.DAYS.between(firstData, today).toInt() + 1
    val acwr = acuteChronicRatio(workouts, today, historyDays, zone)
    when {
        // Unknown load says nothing about recovery: leave it out, unless it's all we have.
        acwr == null -> if (components.isEmpty()) components["load"] = NEUTRAL_SCORE
        else -> components["load"] = loadScore(acwr, reasons)
    }

    val totalWeight = components.keys.sumOf { WEIGHTS.getValue(it) }
    val score = (components.entries.sumOf { WEIGHTS.getValue(it.key) * it.value } / totalWeight).roundToInt()
    return Readiness(score, bandFor(score), components, reasons, acwr)
}

private fun loadScore(acwr: Double, reasons: MutableList<String>): Int = when {
    acwr <= 1.3 -> 90
    acwr <= 1.5 -> { reasons += "Training load rising fast (ratio $acwr)"; 60 }
    else -> { reasons += "Training load spiked (ratio $acwr) — injury risk is higher"; 30 }
}

fun formatHours(minutes: Int): String = "${minutes / 60}h ${minutes % 60}m"
