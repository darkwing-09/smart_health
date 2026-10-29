package com.fitagent.domain

import java.time.LocalDate
import java.time.ZoneId

/** Today's on-device coaching suggestion. Mirrors `suggest_today` in agents/rules.py. */
data class Suggestion(
    val type: WorkoutType?,
    val minutes: Int,
    val headline: String,
    val detail: String,
    val stepsRemaining: Long,
    val walkMinutes: Int,
)

fun suggestToday(
    today: DayActivity,
    readiness: Readiness,
    workouts: List<Workout>,
    goals: Goals,
    todayDate: LocalDate,
    zone: ZoneId,
): Suggestion {
    val stepsRemaining = (goals.dailySteps - today.steps).coerceAtLeast(0)
    val walk = walkMinutesFor(stepsRemaining)
    val recent = workouts.filter { !it.startMs.toLocalDate(zone).isBefore(todayDate.minusDays(3)) }
        .map { it.type }.toSet()
    val left = (goals.dailyWorkoutMinutes - today.workoutMinutes).coerceAtLeast(10)

    if (today.workoutMinutes >= goals.dailyWorkoutMinutes) {
        val detail = if (stepsRemaining > 0) "$walk more minutes of brisk walking would also close your step goal."
        else "Every goal is closed for today. Rest well and hydrate."
        return Suggestion(null, 0, "Workout goal done — great work", detail, stepsRemaining, walk)
    }
    return when (readiness.band) {
        Band.RECOVER -> Suggestion(
            WorkoutType.YOGA, 20, "Go easy today",
            "Your body is still recovering. Gentle mobility or an easy walk keeps the habit without adding strain.",
            stepsRemaining, walk,
        )
        Band.PUSH -> if (WorkoutType.HIIT in recent) Suggestion(
            WorkoutType.RUN, maxOf(left, 25), "You're primed for a strong session",
            "Recovery looks good. A steady run builds your aerobic base.", stepsRemaining, walk,
        ) else Suggestion(
            WorkoutType.HIIT, 20, "You're primed for intensity",
            "Recovery looks good. Short intervals give the biggest fitness return for the time.",
            stepsRemaining, walk,
        )
        Band.STEADY -> if (WorkoutType.STRENGTH !in recent) Suggestion(
            WorkoutType.STRENGTH, maxOf(left, 25), "Time for some strength",
            "No strength work in the last few days. Two full-body sessions a week protect joints and build power.",
            stepsRemaining, walk,
        ) else Suggestion(
            WorkoutType.WALK, maxOf(left, 20), "Keep the momentum",
            "A moderate session keeps you consistent without overloading.", stepsRemaining, walk,
        )
    }
}
