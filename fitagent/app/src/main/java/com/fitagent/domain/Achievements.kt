package com.fitagent.domain

import java.time.Instant
import java.time.ZoneId

data class Achievement(
    val id: String,
    val title: String,
    val description: String,
    val progress: Float,
) {
    val unlocked: Boolean get() = progress >= 1f
}

private fun ratio(value: Number, target: Number): Float =
    (value.toDouble() / target.toDouble()).toFloat().coerceIn(0f, 1f)

fun computeAchievements(
    days: List<DayActivity>,
    workouts: List<Workout>,
    streak: StreakInfo,
    zone: ZoneId,
): List<Achievement> {
    val maxSteps = days.maxOfOrNull { it.steps } ?: 0L
    val totalKm = days.sumOf { it.distanceMeters } / 1000.0
    val hiit = workouts.count { it.type == WorkoutType.HIIT }
    val earlyBird = workouts.any { Instant.ofEpochMilli(it.startMs).atZone(zone).hour < 7 }
    val variety = workouts.map { it.type }.toSet().size

    return listOf(
        Achievement("first_workout", "First step", "Log your first workout", ratio(workouts.size, 1)),
        Achievement("streak_3", "On a roll", "3-day goal streak", ratio(streak.best, 3)),
        Achievement("streak_7", "Week warrior", "7-day goal streak", ratio(streak.best, 7)),
        Achievement("streak_30", "Unstoppable", "30-day goal streak", ratio(streak.best, 30)),
        Achievement("steps_10k", "10K club", "10,000 steps in a day", ratio(maxSteps, 10_000)),
        Achievement("steps_20k", "Trailblazer", "20,000 steps in a day", ratio(maxSteps, 20_000)),
        Achievement("workouts_10", "Regular", "Complete 10 workouts", ratio(workouts.size, 10)),
        Achievement("workouts_50", "Dedicated", "Complete 50 workouts", ratio(workouts.size, 50)),
        Achievement("hiit_5", "Interval hero", "Finish 5 HIIT sessions", ratio(hiit, 5)),
        Achievement("marathon", "Marathoner", "Cover 42.2 km in total", ratio(totalKm, 42.2)),
        Achievement("variety", "All-rounder", "Try 4 workout types", ratio(variety, 4)),
        Achievement("early_bird", "Early bird", "Work out before 7 am", if (earlyBird) 1f else 0f),
    )
}
