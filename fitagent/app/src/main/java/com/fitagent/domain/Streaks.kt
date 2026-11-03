package com.fitagent.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

data class StreakInfo(
    val current: Int = 0,
    val best: Int = 0,
    val todayDone: Boolean = false,
    /** True when this week's rest-day freeze has already been spent. */
    val freezeUsedThisWeek: Boolean = false,
) {
    val atRisk: Boolean get() = current > 0 && !todayDone
}

private fun weekKey(d: LocalDate) = d.get(IsoFields.WEEK_BASED_YEAR) * 100 + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

/**
 * Consecutive goal days. One missed day per Mon–Sun week is absorbed by a *freeze* so a single rest
 * day never wipes out weeks of effort (streak anxiety is a top reason students quit habit apps).
 * An unfinished today never breaks the streak.
 */
fun computeStreak(days: List<DayActivity>, goals: Goals, today: LocalDate): StreakInfo {
    val met = days.filter { it.goalMet(goals) }.map { it.date }.toSet()
    val first = met.minOrNull() ?: return StreakInfo()
    val frozenWeeks = mutableSetOf<Int>()
    var run = 0
    var best = 0
    var date = first
    while (!date.isAfter(today)) {
        when {
            date in met -> { run++; best = maxOf(best, run) }
            date == today -> Unit
            run > 0 && weekKey(date) !in frozenWeeks -> frozenWeeks += weekKey(date)
            else -> run = 0
        }
        date = date.plusDays(1)
    }
    return StreakInfo(
        current = run,
        best = best,
        todayDone = today in met,
        freezeUsedThisWeek = weekKey(today) in frozenWeeks,
    )
}

fun startOfWeek(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

fun workoutsThisWeek(workouts: List<Workout>, today: LocalDate, zone: ZoneId): Int {
    val monday = startOfWeek(today)
    return workouts.count { !it.startMs.toLocalDate(zone).isBefore(monday) }
}

/** Days of the current Mon–Sun week still remaining, including [today]. */
fun daysLeftInWeek(today: LocalDate): Int = 8 - today.dayOfWeek.value
