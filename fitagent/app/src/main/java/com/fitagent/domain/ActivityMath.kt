package com.fitagent.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Brisk walking cadence used to turn a step deficit into "minutes of walking". */
const val STEPS_PER_BRISK_MINUTE = 100

private const val KCAL_PER_STEP_70KG = 0.04

fun workoutKcal(type: WorkoutType, weightKg: Double, activeSeconds: Long): Double =
    type.met * weightKg * (activeSeconds / 3600.0)

fun stepKcal(steps: Long, weightKg: Double): Double = steps * KCAL_PER_STEP_70KG * (weightKg / 70.0)

fun walkMinutesFor(remainingSteps: Long): Int =
    if (remainingSteps <= 0) 0 else ceil(remainingSteps / STEPS_PER_BRISK_MINUTE.toDouble()).toInt()

fun Long.toLocalDate(zone: ZoneId): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

/**
 * Merges device readings and logged workouts into one [DayActivity] per date in [from]..[to].
 *
 * Active calories: in-app workouts are not written to Health Connect as calories, so we take the
 * larger of the device value and our own estimate (steps + in-app workouts). This avoids both
 * under-counting (no watch) and double-counting (watch already measured the workout).
 */
fun buildDays(
    from: LocalDate,
    to: LocalDate,
    deviceDays: Map<LocalDate, DeviceDay>,
    workouts: List<Workout>,
    weightKg: Double,
    zone: ZoneId,
): List<DayActivity> {
    val workoutsByDay = workouts.groupBy { it.startMs.toLocalDate(zone) }
    val result = ArrayList<DayActivity>()
    var date = from
    while (!date.isAfter(to)) {
        val device = deviceDays[date]
        val dayWorkouts = workoutsByDay[date].orEmpty()
        val steps = device?.steps ?: 0L
        val appKcal = dayWorkouts.filter { it.source == WorkoutSource.APP }.sumOf { it.kcal }
        val estimate = stepKcal(steps, weightKg) + appKcal
        val measured = device?.activeKcal ?: 0.0
        result += DayActivity(
            date = date,
            steps = steps,
            distanceMeters = device?.distanceMeters ?: 0.0,
            activeKcal = max(measured, estimate).roundToInt(),
            kcalEstimated = estimate > measured,
            workoutMinutes = (dayWorkouts.sumOf { it.activeSeconds } / 60).toInt(),
            workoutCount = dayWorkouts.size,
            restingHr = device?.restingHr,
            avgHr = device?.avgHr,
            sleepMinutes = device?.sleepMinutes,
        )
        date = date.plusDays(1)
    }
    return result
}
