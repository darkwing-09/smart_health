package com.fitagent.domain

import java.time.DayOfWeek
import java.time.LocalDateTime

/*
 * Smart notifications (X4) and study-break nudges (W5): fewer, better, never during class or sleep.
 * Pure decisions; the workers in service/ only post what these functions allow.
 */

enum class NudgeKind { MORNING, EVENING, STUDY_BREAK, CHALLENGE }

/** At most this many notifications of all kinds per day; study breaks have their own smaller cap. */
const val DAILY_NOTIFICATION_CAP = 3
const val DAILY_STUDY_BREAK_CAP = 3
/** A study-break nudge only after this long with almost no steps. */
const val SITTING_MINUTES = 90
const val SITTING_MAX_STEPS = 150

/** Today's counts by kind. */
fun canPost(kind: NudgeKind, sentToday: Map<NudgeKind, Int>): Boolean {
    val total = sentToday.values.sum()
    return when (kind) {
        NudgeKind.STUDY_BREAK -> (sentToday[kind] ?: 0) < DAILY_STUDY_BREAK_CAP
        else -> total - (sentToday[NudgeKind.STUDY_BREAK] ?: 0) < DAILY_NOTIFICATION_CAP && (sentToday[kind] ?: 0) == 0
    }
}

/** True when [now] is in class, before 08:00, or within an hour of bedtime (default 23:00). */
fun isQuietTime(now: LocalDateTime, timetable: Timetable): Boolean {
    val min = now.hour * 60 + now.minute
    if (min < 8 * 60) return true
    val bed = timetable.bedtimeMin?.let { if (it < 12 * 60) it + 24 * 60 else it } ?: (23 * 60)
    if (min >= bed - 60) return true
    return timetable.busy.any { it.day == now.dayOfWeek && min in it.startMin until it.endMin }
}

/**
 * Study-break nudge: only when the student has a timetable (so we know it's a study day), it's a weekday or exam
 * week, not quiet time, and the last [SITTING_MINUTES] had fewer than [SITTING_MAX_STEPS] steps.
 */
fun shouldNudgeStudyBreak(now: LocalDateTime, timetable: Timetable, stepsLast90Min: Long): Boolean {
    if (timetable.busy.isEmpty()) return false
    val studyDay = now.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) || timetable.examWeek(now.toLocalDate())
    return studyDay && !isQuietTime(now, timetable) && stepsLast90Min < SITTING_MAX_STEPS
}

/** Evening ring reminder only when the goal is genuinely reachable tonight. */
fun eveningReminder(day: DayActivity, goals: Goals): String? {
    if (day.goalMet(goals)) return null
    val walk = walkMinutesFor((goals.dailySteps - day.steps).coerceAtLeast(0))
    val workoutLeft = (goals.dailyWorkoutMinutes - day.workoutMinutes).coerceAtLeast(0)
    return when {
        walk in 1..25 -> "${formatCount(goals.dailySteps - day.steps)} steps to go — a $walk-minute walk closes your ring."
        workoutLeft in 1..20 -> "$workoutLeft workout minutes to go — a quick session in your room counts."
        else -> null // too far off tonight: no guilt-trip notification
    }
}

/** Morning card text: readiness + a concrete suggestion, with a time from today's free slots when known. */
fun morningMessage(r: Readiness, s: Suggestion, timetable: Timetable, now: LocalDateTime): Pair<String, String> {
    val title = "Readiness ${r.score} · ${r.band.label}"
    val minutes = s.minutes.coerceAtLeast(10)
    val nowMin = now.hour * 60 + now.minute
    // A slot that has already started still counts if enough of it is left.
    val start = freeSlots(timetable).filter { it.day == now.dayOfWeek }
        .map { maxOf(it.startMin, nowMin) to it.endMin }
        .firstOrNull { (from, to) -> to - from >= minutes }?.first
    val text = buildString {
        append(s.headline)
        if (timetable.busy.isNotEmpty() && start != null && s.type != null) append(" — fits at ${hhmm(start)}")
        append(".")
    }
    return title to text
}
