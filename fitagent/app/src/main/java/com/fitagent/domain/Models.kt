package com.fitagent.domain

import java.time.LocalDate

/**
 * Workout categories with MET values (Compendium of Physical Activities) for calorie estimates.
 * Names match the backend's workout types.
 */
enum class WorkoutType(val label: String, val met: Double) {
    WALK("Walk", 3.8),
    RUN("Run", 9.8),
    CYCLE("Cycle", 7.5),
    STRENGTH("Strength", 5.0),
    HIIT("HIIT", 8.0),
    YOGA("Yoga", 2.5),
    SWIM("Swim", 7.0),
    SPORTS("Sports", 7.0),
    DANCE("Dance", 5.0),
    OTHER("Workout", 4.0);

    val isHard: Boolean get() = this == RUN || this == HIIT || this == SWIM || this == SPORTS

    companion object {
        fun fromName(name: String?): WorkoutType = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

enum class WorkoutSource { APP, HEALTH_CONNECT }

data class Workout(
    val id: Long,
    val uid: String,
    val type: WorkoutType,
    val title: String?,
    val startMs: Long,
    val endMs: Long,
    val activeSeconds: Long,
    val kcal: Double,
    val source: WorkoutSource,
) {
    val minutes: Int get() = (activeSeconds / 60).toInt()
}

data class Goals(
    val dailySteps: Int = 8_000,
    val dailyActiveKcal: Int = 400,
    val dailyWorkoutMinutes: Int = 30,
    val weeklyWorkouts: Int = 4,
)

data class Profile(
    val name: String = "",
    val ageYears: Int = 20,
    val heightCm: Int = 170,
    val weightKg: Double = 65.0,
    val goals: Goals = Goals(),
    val remindersEnabled: Boolean = true,
    val reminderHour: Int = 19,
    val onboarded: Boolean = false,
) {
    val bmi: Double
        get() = if (heightCm > 0) weightKg / ((heightCm / 100.0) * (heightCm / 100.0)) else 0.0
}

/** Raw per-day readings from Health Connect, before logged workouts are merged in. */
data class DeviceDay(
    val date: LocalDate,
    val steps: Long = 0,
    val distanceMeters: Double = 0.0,
    val activeKcal: Double? = null,
    val restingHr: Int? = null,
    val avgHr: Int? = null,
    val sleepMinutes: Int? = null,
)

/** Everything known about one calendar day. */
data class DayActivity(
    val date: LocalDate,
    val steps: Long = 0,
    val distanceMeters: Double = 0.0,
    val activeKcal: Int = 0,
    val kcalEstimated: Boolean = true,
    val workoutMinutes: Int = 0,
    val workoutCount: Int = 0,
    val restingHr: Int? = null,
    val avgHr: Int? = null,
    val sleepMinutes: Int? = null,
) {
    /** A day counts toward the streak when either the step goal or the workout goal is reached. */
    fun goalMet(goals: Goals): Boolean = steps >= goals.dailySteps || workoutMinutes >= goals.dailyWorkoutMinutes

    val hasData: Boolean
        get() = steps > 0 || workoutCount > 0 || sleepMinutes != null || restingHr != null
}
