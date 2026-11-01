package com.fitagent.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

class NudgesTest {
    private val monday = LocalDate.of(2026, 11, 2)
    private val classes = Timetable(busy = listOf(BusyBlock(DayOfWeek.MONDAY, 9 * 60, 11 * 60)), bedtimeMin = 23 * 60)

    @Test fun dailyCaps() {
        assertTrue(canPost(NudgeKind.MORNING, emptyMap()))
        assertFalse(canPost(NudgeKind.MORNING, mapOf(NudgeKind.MORNING to 1))) // one of each kind
        assertFalse(canPost(NudgeKind.CHALLENGE, mapOf(NudgeKind.MORNING to 1, NudgeKind.EVENING to 1, NudgeKind.CHALLENGE to 0) + mapOf(NudgeKind.STUDY_BREAK to 3, NudgeKind.CHALLENGE to 1)))
        assertTrue(canPost(NudgeKind.EVENING, mapOf(NudgeKind.MORNING to 1, NudgeKind.STUDY_BREAK to 2)))
        assertFalse(canPost(NudgeKind.STUDY_BREAK, mapOf(NudgeKind.STUDY_BREAK to 3)))
    }

    @Test fun quietDuringClassEarlyMorningAndBeforeBed() {
        assertTrue(isQuietTime(monday.atTime(9, 30), classes))
        assertTrue(isQuietTime(monday.atTime(7, 30), classes))
        assertTrue(isQuietTime(monday.atTime(22, 15), classes))
        assertFalse(isQuietTime(monday.atTime(15, 0), classes))
    }

    @Test fun studyBreakNeedsTimetableStillnessAndStudyDay() {
        val at3pm = monday.atTime(15, 0)
        assertTrue(shouldNudgeStudyBreak(at3pm, classes, 40))
        assertFalse(shouldNudgeStudyBreak(at3pm, classes, 900))
        assertFalse(shouldNudgeStudyBreak(at3pm, Timetable(), 40))
        val saturday = LocalDateTime.of(2026, 11, 7, 15, 0)
        assertFalse(shouldNudgeStudyBreak(saturday, classes, 40))
        assertTrue(shouldNudgeStudyBreak(saturday, classes.copy(examDates = listOf(LocalDate.of(2026, 11, 9))), 40))
    }

    @Test fun eveningReminderOnlyWhenReachable() {
        val goals = Goals(dailySteps = 8000, dailyWorkoutMinutes = 30)
        assertTrue(eveningReminder(DayActivity(monday, steps = 6500), goals)!!.contains("15-minute walk"))
        assertNull(eveningReminder(DayActivity(monday, steps = 1000), goals)) // 70 min away: no guilt trip
        assertTrue(eveningReminder(DayActivity(monday, steps = 1000, workoutMinutes = 15), goals)!!.contains("15 workout minutes"))
        assertNull(eveningReminder(DayActivity(monday, steps = 9000), goals))
    }

    @Test fun morningMessageUsesNextFreeSlot() {
        val r = Readiness(74, Band.PUSH, mapOf("sleep" to 90), emptyList(), null)
        val s = Suggestion(WorkoutType.RUN, 25, "Good day for a 25-min run", "", 0, 0)
        val (title, text) = morningMessage(r, s, classes, monday.atTime(8, 0))
        assertEquals("Readiness 74 · Ready to push", title)
        assertEquals("Good day for a 25-min run — fits at 08:00.", text)
        val (_, later) = morningMessage(r, s, classes, monday.atTime(9, 0))
        assertTrue(later.endsWith("fits at 11:00."))
    }
}
