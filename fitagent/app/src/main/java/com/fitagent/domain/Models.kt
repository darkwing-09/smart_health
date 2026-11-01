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
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    /** Seconds in heart-rate zones 1–5, when recorded with a sensor. */
    val zoneSeconds: List<Int>? = null,
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
    val voiceCues: Boolean = true,
    /** Campus leaderboard groups (optional; only group totals are ever shown). */
    val college: String = "",
    val hostel: String = "",
    val batch: String = "",
    val showOnCampus: Boolean = true,
    /** Coach reply language (ISO 639-1). Defaults to the phone's language when supported. */
    val language: String = defaultLanguage(),
) {
    val bmi: Double
        get() = if (heightCm > 0) weightKg / ((heightCm / 100.0) * (heightCm / 100.0)) else 0.0
}

/** Minutes per sleep stage for one night. Stages a watch doesn't report stay 0. */
data class SleepStages(val deep: Int = 0, val rem: Int = 0, val light: Int = 0, val awake: Int = 0) {
    val asleep: Int get() = deep + rem + light
    val total: Int get() = asleep + awake
}

/** Overnight body signals — only some watches report them; every field is optional. */
data class NightSignals(
    val hrvMs: Double? = null,
    val spo2Avg: Double? = null,
    val spo2Min: Double? = null,
    val respiratoryRate: Double? = null,
    val skinTempDelta: Double? = null,
) {
    val isEmpty: Boolean get() = listOf(hrvMs, spo2Avg, spo2Min, respiratoryRate, skinTempDelta).all { it == null }
}

/**
 * Raw per-day readings from the watch (via Health Connect) or the phone's step sensor, before logged
 * workouts are merged in. [stepsSource] / [sleepSource] name the app or sensor each value came from.
 */
data class DeviceDay(
    val date: LocalDate,
    val steps: Long = 0,
    val distanceMeters: Double = 0.0,
    val activeKcal: Double? = null,
    val restingHr: Int? = null,
    val avgHr: Int? = null,
    val sleepMinutes: Int? = null,
    val hrMin: Int? = null,
    val hrMax: Int? = null,
    val floors: Int? = null,
    val vo2max: Double? = null,
    val sleepStages: SleepStages? = null,
    val bedMs: Long? = null,
    val wakeMs: Long? = null,
    val night: NightSignals = NightSignals(),
    val stepsSource: String? = null,
    val sleepSource: String? = null,
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
    val hrMin: Int? = null,
    val hrMax: Int? = null,
    val floors: Int? = null,
    val vo2max: Double? = null,
    val sleepStages: SleepStages? = null,
    val bedMs: Long? = null,
    val wakeMs: Long? = null,
    val night: NightSignals = NightSignals(),
    val stepsSource: String? = null,
) {
    /** A day counts toward the streak when either the step goal or the workout goal is reached. */
    fun goalMet(goals: Goals): Boolean = steps >= goals.dailySteps || workoutMinutes >= goals.dailyWorkoutMinutes

    val hasData: Boolean
        get() = steps > 0 || workoutCount > 0 || sleepMinutes != null || restingHr != null || !night.isEmpty
}

/** Hypnogram stages. [ASLEEP] = asleep but the watch didn't say which stage. */
enum class SleepStage { AWAKE, LIGHT, DEEP, REM, ASLEEP }

data class SleepSegment(val startMs: Long, val endMs: Long, val stage: SleepStage) {
    val minutes: Int get() = ((endMs - startMs) / 60_000).toInt()
}

data class HrBucket(val startMs: Long, val min: Int, val avg: Int, val max: Int)

/** Everything read from Health Connect for one sync window. */
data class HealthReadout(
    val days: Map<java.time.LocalDate, DeviceDay>,
    val hourly: Map<java.time.LocalDate, List<Pair<Int, Long>>>,
    val hourlyKcal: Map<java.time.LocalDate, Map<Int, Double>>,
    val hr: List<HrBucket>,
    val sleepNights: Map<java.time.LocalDate, List<SleepSegment>>,
    /** App package → the data types it supplied in this window. */
    val origins: Map<String, Set<String>>,
)

val COACH_LANGUAGES = listOf("en" to "English", "hi" to "हिन्दी", "ta" to "தமிழ்", "te" to "తెలుగు", "bn" to "বাংলা", "mr" to "मराठी")

fun defaultLanguage(): String = java.util.Locale.getDefault().language.takeIf { code -> COACH_LANGUAGES.any { it.first == code } } ?: "en"
