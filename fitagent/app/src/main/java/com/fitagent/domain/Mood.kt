package com.fitagent.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/*
 * Mood check-in, stress estimate and breathing (docs/phase2/04-features.md S5, D4).
 * Mood stays on the phone. Stress is an *estimate* from heart rate at rest — never a diagnosis.
 */

data class MoodEntry(val date: LocalDate, val score: Int, val tags: Set<String> = emptySet())

val MOOD_LABELS = listOf("Rough", "Low", "Okay", "Good", "Great")
val MOOD_EMOJI = listOf("😣", "😕", "😐", "🙂", "😄")
val MOOD_TAGS = listOf("Exams", "Sleep", "Friends", "Family", "Health", "Workload", "Money")

/** Consecutive days up to [today] (or yesterday, if today has no entry) with mood ≤ 2. */
fun lowMoodStreak(entries: List<MoodEntry>, today: LocalDate): Int {
    val byDate = entries.associateBy { it.date }
    var cursor = if (byDate.containsKey(today)) today else today.minusDays(1)
    var n = 0
    while (true) {
        val e = byDate[cursor] ?: break
        if (e.score > 2) break
        n++
        cursor = cursor.minusDays(1)
    }
    return n
}

const val LOW_MOOD_SUPPORT_DAYS = 5

/** Ordinary awake, seated heart rate sits ~10 bpm above sleeping resting HR; only the excess counts as stress. */
const val AWAKE_ALLOWANCE_BPM = 10

/**
 * Stress estimate 0–100 per heart-rate bucket: how far heart rate sits above resting (+ an awake allowance) while
 * not moving. Buckets in hours with more than [movingSteps] steps are excluded (exercise, walking), as are buckets
 * without a resting baseline. 0 ≈ relaxed, 100 ≈ 40+ bpm above resting while still.
 */
fun stressTimeline(
    buckets: List<HrBucket>,
    restingHr: Int?,
    hourlySteps: List<Long>,
    zone: ZoneId,
    movingSteps: Long = 500,
): List<Int?> {
    if (restingHr == null) return buckets.map { null }
    return buckets.map { b ->
        val hour = Instant.ofEpochMilli(b.startMs).atZone(zone).hour
        if ((hourlySteps.getOrNull(hour) ?: 0) > movingSteps) null
        else ((b.avg - restingHr - AWAKE_ALLOWANCE_BPM) / 30.0 * 100).coerceIn(0.0, 100.0).roundToInt()
    }
}

fun stressHeadline(values: List<Int?>): String {
    val known = values.filterNotNull()
    if (known.size < 6) return "Wear your watch through the day to see a stress estimate."
    val high = known.count { it >= 60 }
    return when {
        high == 0 -> "Calm day so far — heart rate stayed close to resting when you were still."
        high * 10 < 60 -> "A few tense moments (about ${high * 10} min). A 3-minute breathing break can help."
        else -> "About ${high * 10} min of elevated heart rate while still — try a breathing break."
    }
}

/** Breathing patterns: phase lengths in seconds (in, hold, out, hold). */
enum class BreathPattern(val label: String, val description: String, val phases: List<Pair<String, Int>>) {
    BOX("Box 4-4-4-4", "Calm focus before study or an exam", listOf("Breathe in" to 4, "Hold" to 4, "Breathe out" to 4, "Hold" to 4)),
    RELAX("4-7-8", "Wind down before sleep", listOf("Breathe in" to 4, "Hold" to 7, "Breathe out" to 8)),
    COHERENT("Coherent 5-5", "Steady, even breathing", listOf("Breathe in" to 5, "Breathe out" to 5));

    val cycleSeconds: Int get() = phases.sumOf { it.second }
}

/** The phase (label, progress 0..1 within the phase, phase index) at [elapsedMs] into a session. */
fun breathPhase(pattern: BreathPattern, elapsedMs: Long): Triple<String, Float, Int> {
    val cycleMs = pattern.cycleSeconds * 1000L
    var t = elapsedMs % cycleMs
    pattern.phases.forEachIndexed { i, (label, sec) ->
        val len = sec * 1000L
        if (t < len) return Triple(label, t / len.toFloat(), i)
        t -= len
    }
    return Triple(pattern.phases.last().first, 1f, pattern.phases.lastIndex)
}
