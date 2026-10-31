package com.fitagent.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** One sleep session as an app recorded it. [stages] is empty when the app only reports "asleep". */
data class SleepSession(val source: String, val startMs: Long, val endMs: Long, val stages: List<SleepSegment>)

/** The night chosen for one date: one app's sessions, merged. */
data class Night(val source: String, val segments: List<SleepSegment>, val bedMs: Long, val wakeMs: Long) {
    val stages: SleepStages? get() = sleepStagesOf(segments)
    val asleepMinutes: Int get() = segments.filter { it.stage != SleepStage.AWAKE }.sumOf { it.minutes }
}

/** Stage totals, or null when the watch didn't report stages. Unspecified "asleep" time counts as light. */
fun sleepStagesOf(segments: List<SleepSegment>): SleepStages? {
    if (segments.none { it.stage == SleepStage.DEEP || it.stage == SleepStage.REM || it.stage == SleepStage.LIGHT }) return null
    fun sum(vararg s: SleepStage) = segments.filter { it.stage in s }.sumOf { it.minutes }
    return SleepStages(
        deep = sum(SleepStage.DEEP),
        rem = sum(SleepStage.REM),
        light = sum(SleepStage.LIGHT, SleepStage.ASLEEP),
        awake = sum(SleepStage.AWAKE),
    )
}

/**
 * Groups sleep sessions by the date they end on. When several apps recorded the same night (phone + watch),
 * the app with stage data wins, then the one with the most sleep — never a sum, which would double-count.
 */
fun pickNights(sessions: List<SleepSession>, zone: ZoneId): Map<LocalDate, Night> =
    sessions.groupBy { Instant.ofEpochMilli(it.endMs).atZone(zone).toLocalDate() }.mapValues { (_, list) ->
        val (app, chosen) = list.groupBy { it.source }.entries.maxWith(
            compareBy<Map.Entry<String, List<SleepSession>>>(
                { e -> e.value.any { it.stages.isNotEmpty() } },
                { e -> e.value.sumOf { it.endMs - it.startMs } },
            )
        )
        val segments = chosen.sortedBy { it.startMs }.flatMap { s ->
            s.stages.ifEmpty { listOf(SleepSegment(s.startMs, s.endMs, SleepStage.ASLEEP)) }
        }
        Night(app, segments, chosen.minOf { it.startMs }, chosen.maxOf { it.endMs })
    }

/**
 * Which night a reading belongs to: inside a recorded sleep window → that night; otherwise readings from noon on
 * count toward the next morning and readings before noon toward that morning.
 */
fun nightOf(time: Instant, zone: ZoneId, nights: Map<LocalDate, Night>): LocalDate {
    val ms = time.toEpochMilli()
    nights.entries.firstOrNull { ms in it.value.bedMs..it.value.wakeMs }?.let { return it.key }
    val local = time.atZone(zone)
    return if (local.hour >= 12) local.toLocalDate().plusDays(1) else local.toLocalDate()
}

/** Sleep score 0–100: duration (50 %), stage quality (30 %), bedtime consistency over the last week (20 %). */
fun sleepScore(asleepMinutes: Int, stages: SleepStages?, recentBedtimes: List<Int>): Int {
    val duration = (asleepMinutes / 480.0 * 100).coerceIn(0.0, 100.0)
    val quality = stages?.let { sleepQuality(it) }
    val consistency = bedtimeConsistency(recentBedtimes)
    val parts = listOfNotNull(duration to 0.5, quality?.let { it.toDouble() to 0.3 }, consistency?.let { it.toDouble() to 0.2 })
    return (parts.sumOf { it.first * it.second } / parts.sumOf { it.second }).toInt().coerceIn(0, 100)
}

/** 0–100 from stage shares: rewards deep ≥ 15 % and REM ≥ 20 %, penalises awake > 5 %. */
fun sleepQuality(s: SleepStages): Int {
    val total = s.total.takeIf { it > 0 } ?: return 50
    fun pct(m: Int) = m * 100.0 / total
    return (50 + 2.5 * (pct(s.deep) - 15) + 2.5 * (pct(s.rem) - 20) - 3 * (pct(s.awake) - 5)).coerceIn(0.0, 100.0).roundToInt()
}

/**
 * 0–100 from bedtimes (minutes after 18:00, so midnight is 360). Standard deviation 0 → 100, ≥ 120 min → 0.
 * Null with fewer than 3 nights.
 */
fun bedtimeConsistency(bedtimes: List<Int>): Int? {
    if (bedtimes.size < 3) return null
    val mean = bedtimes.average()
    val sd = kotlin.math.sqrt(bedtimes.sumOf { (it - mean) * (it - mean) } / bedtimes.size)
    return (100 - sd / 120 * 100).toInt().coerceIn(0, 100)
}

/** Minutes after 18:00 for a bedtime (so 23:30 → 330 and 00:30 → 390), as used by [bedtimeConsistency]. */
fun bedtimeMinutes(bedMs: Long, zone: ZoneId): Int {
    val t = Instant.ofEpochMilli(bedMs).atZone(zone).toLocalTime()
    val m = t.hour * 60 + t.minute - 18 * 60
    return if (m < 0) m + 24 * 60 else m
}
