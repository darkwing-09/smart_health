package com.fitagent.domain

import kotlin.math.roundToInt

/*
 * Heart-rate zones (docs/phase2/05-agents-and-algorithms.md §2).
 * HRmax = 208 − 0.7 × age (Tanaka et al., 2001). Zones 1–5 start at 50/60/70/80/90 % of HRmax.
 */

fun hrMax(ageYears: Int): Int = (208 - 0.7 * ageYears.coerceIn(10, 100)).roundToInt()

/** 0 = below zone 1 (resting/very light), 1–5 = zones. */
fun zoneOf(bpm: Int, max: Int): Int {
    val pct = bpm * 100.0 / max
    return when {
        pct >= 90 -> 5
        pct >= 80 -> 4
        pct >= 70 -> 3
        pct >= 60 -> 2
        pct >= 50 -> 1
        else -> 0
    }
}

/** Lower bpm bound of zones 1..5, for drawing zone bands. */
fun zoneFloors(max: Int): List<Int> = listOf(50, 60, 70, 80, 90).map { (max * it / 100.0).roundToInt() }

val ZONE_NAMES = listOf("Warm-up", "Fat burn", "Cardio", "Hard", "Max")

/**
 * Minutes per zone (index 0..4 = zones 1..5) from heart-rate buckets, using each bucket's average.
 * Buckets are [bucketMinutes] long; an estimate, labelled as such in the UI.
 */
fun zoneMinutes(buckets: List<HrBucket>, max: Int, bucketMinutes: Int = 10): List<Int> {
    val out = IntArray(5)
    for (b in buckets) {
        val z = zoneOf(b.avg, max)
        if (z > 0) out[z - 1] += bucketMinutes
    }
    return out.toList()
}

/** WHO-equivalent minutes: moderate (zones 2–3) + 2 × vigorous (zones 4–5). Target 150 a week. */
fun whoMinutes(zoneMinutes: List<Int>): Int =
    zoneMinutes.getOrElse(1) { 0 } + zoneMinutes.getOrElse(2) { 0 } + 2 * (zoneMinutes.getOrElse(3) { 0 } + zoneMinutes.getOrElse(4) { 0 })

const val WHO_WEEKLY_MINUTES = 150

/**
 * WHO-equivalent minutes from logged workouts when no heart-rate data exists: hard workout types count as
 * vigorous (×2), the rest as moderate.
 */
fun whoMinutesFromWorkouts(workouts: List<Workout>): Int = workouts.sumOf { if (it.type.isHard) it.minutes * 2 else it.minutes }


/** Heart-rate summary of a workout from live samples (time ms, bpm). */
data class HrSummary(val avg: Int, val max: Int, val zoneSeconds: List<Int>)

/**
 * Averages are time-weighted; each sample counts until the next one, capped at [maxGapMs] so a dropped sensor
 * doesn't stretch one reading over minutes. Null with fewer than 10 samples.
 */
fun hrSummary(samples: List<Pair<Long, Int>>, hrMax: Int, maxGapMs: Long = 5_000): HrSummary? {
    if (samples.size < 10) return null
    val zones = DoubleArray(5)
    var weighted = 0.0
    var total = 0.0
    samples.zipWithNext().forEach { (a, b) ->
        val dt = (b.first - a.first).coerceIn(0, maxGapMs) / 1000.0
        weighted += a.second * dt
        total += dt
        val z = zoneOf(a.second, hrMax)
        if (z > 0) zones[z - 1] += dt
    }
    if (total <= 0) return null
    return HrSummary((weighted / total).roundToInt(), samples.maxOf { it.second }, zones.map { it.roundToInt() })
}
