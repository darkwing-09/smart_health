package com.fitagent.domain

import java.time.DayOfWeek
import java.time.LocalDateTime

/*
 * Smart notifications (X4) and study-break nudges (W5): fewer, better, never during class or sleep.
 * Pure decisions; the workers in service/ only post what these functions allow.
 */

enum class NudgeKind { MORNING, EVENING, STUDY_BREAK, CHALLENGE, BEDTIME, WATCH }

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


/** Default first commitment when the timetable has no class tomorrow. */
const val DEFAULT_WAKE_COMMITMENT_MIN = 9 * 60
const val GET_READY_MIN = 45
const val SLEEP_NEED_MIN = 8 * 60

/**
 * Target bedtime for tonight (D2): tomorrow's first class (or 09:00) − 45 min to get ready − 8 h of sleep,
 * rounded down to 10 minutes. Returned as minutes after midnight, wrapping (e.g. 22:30 → 1350, 00:15 → 15).
 */
fun bedtimeTarget(timetable: Timetable, tomorrow: java.time.DayOfWeek, sleepNeedMin: Int = SLEEP_NEED_MIN): Int {
    val first = timetable.busy.filter { it.day == tomorrow }.minOfOrNull { it.startMin } ?: DEFAULT_WAKE_COMMITMENT_MIN
    val raw = first - GET_READY_MIN - sleepNeedMin
    val wrapped = ((raw % 1440) + 1440) % 1440
    return wrapped / 10 * 10
}

fun bedtimeMessage(targetMin: Int, firstClassMin: Int?): String =
    "Aim to be asleep by ${hhmm(targetMin)}" + (firstClassMin?.let { " — your first class is at ${hhmm(it)}." } ?: " for a full 8 hours.")

/**
 * Once-a-day reminder while no watch data arrives. [label] is the watch app that last delivered data (null = never).
 * Returns null when the watch is fine.
 */
fun watchReminder(label: String?, lastDataMs: Long?, nowMs: Long, staleAfterHours: Long = 24): Pair<String, String>? {
    if (label == null || lastDataMs == null) {
        return "Connect your watch" to
            "FitAgent shows only what your watch measures. Turn on Health Connect sharing in NoiseFit, boAt Crest or your watch app, then sync."
    }
    val hours = (nowMs - lastDataMs) / 3_600_000
    if (hours < staleAfterHours) return null
    val ago = if (hours < 48) "yesterday" else "${hours / 24} days ago"
    return "$label hasn't synced since $ago" to
        "Open $label so your watch syncs. Readiness and goals pause until new data arrives."
}
