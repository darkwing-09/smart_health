package com.fitagent.wear

import kotlin.math.roundToInt

/*
 * Small, pure pieces of training maths the watch needs offline. They intentionally mirror the phone's
 * domain/WorkoutSession.kt and domain/Zones.kt (same formulas, unit-tested on both sides).
 */

enum class WatchWorkout(val label: String, val phoneType: String) {
    RUN("Run", "RUN"), WALK("Walk", "WALK"), CYCLE("Cycle", "CYCLE"), HIIT("HIIT", "HIIT"),
    STRENGTH("Strength", "STRENGTH"), YOGA("Yoga", "YOGA"),
}

data class Intervals(val workSeconds: Int, val restSeconds: Int, val rounds: Int) {
    val totalSeconds: Int get() = rounds * workSeconds + (rounds - 1) * restSeconds

    companion object {
        val TABATA = Intervals(20, 10, 8)
        val CLASSIC = Intervals(40, 20, 10)
    }
}

enum class Phase { WORK, REST, DONE }

data class PhaseState(val phase: Phase, val round: Int, val secondsLeft: Int)

fun phaseAt(c: Intervals, elapsed: Long): PhaseState {
    if (elapsed >= c.totalSeconds) return PhaseState(Phase.DONE, c.rounds, 0)
    val cycle = c.workSeconds + c.restSeconds
    val round = (elapsed / cycle).toInt() + 1
    val into = (elapsed % cycle).toInt()
    return if (into < c.workSeconds) PhaseState(Phase.WORK, round, c.workSeconds - into)
    else PhaseState(Phase.REST, round, cycle - into)
}

fun hrMax(age: Int): Int = (208 - 0.7 * age.coerceIn(10, 100)).roundToInt()

fun zoneOf(bpm: Int, max: Int): Int {
    val pct = bpm * 100.0 / max
    return when { pct >= 90 -> 5; pct >= 80 -> 4; pct >= 70 -> 3; pct >= 60 -> 2; pct >= 50 -> 1; else -> 0 }
}

/** Accumulates heart-rate samples into avg/max and seconds per zone (gaps capped at 5 s). */
class HrAccumulator(private val max: Int) {
    private var lastMs = 0L
    private var lastBpm = 0
    private var weighted = 0.0
    private var total = 0.0
    var peak = 0
        private set
    val zones = DoubleArray(5)

    fun add(timeMs: Long, bpm: Int) {
        if (bpm !in 30..250) return
        if (lastMs > 0) {
            val dt = ((timeMs - lastMs).coerceIn(0, 5_000)) / 1000.0
            weighted += lastBpm * dt
            total += dt
            val z = zoneOf(lastBpm, max)
            if (z > 0) zones[z - 1] += dt
        }
        lastMs = timeMs
        lastBpm = bpm
        peak = maxOf(peak, bpm)
    }

    val average: Int? get() = if (total < 10) null else (weighted / total).roundToInt()
}
