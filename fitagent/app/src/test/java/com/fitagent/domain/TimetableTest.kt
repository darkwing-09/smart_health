package com.fitagent.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

class TimetableTest {
    @Test fun freeSlotsAreGapsBetweenClassesAndMeals() {
        val t = Timetable(busy = listOf(
            BusyBlock(DayOfWeek.MONDAY, 9 * 60, 11 * 60), BusyBlock(DayOfWeek.MONDAY, 11 * 60, 12 * 60 + 50),
            BusyBlock(DayOfWeek.MONDAY, 15 * 60, 17 * 60),
        ))
        val monday = freeSlots(t).filter { it.day == DayOfWeek.MONDAY }.map { hhmm(it.startMin) to hhmm(it.endMin) }
        assertEquals(listOf("07:00" to "09:00", "14:00" to "15:00", "17:00" to "20:00", "21:00" to "22:00"), monday)
        // 12:50–13:00 is under 15 minutes, so it isn't offered.
    }

    @Test fun emptyDayIsFreeAroundMeals() {
        val slots = freeSlots(Timetable()).filter { it.day == DayOfWeek.SUNDAY }
        assertEquals(3, slots.size)
        assertEquals(7 * 60, slots.first().startMin)
    }

    @Test fun examWeekDetection() {
        val today = LocalDate.of(2026, 11, 2)
        assertTrue(Timetable(examDates = listOf(today.plusDays(6))).examWeek(today))
        assertFalse(Timetable(examDates = listOf(today.plusDays(7), today.minusDays(1))).examWeek(today))
    }

    @Test fun parsesHhmm() {
        assertEquals(23 * 60, parseHhmm("23:00"))
        assertEquals(null, parseHhmm("24:00"))
        assertEquals(null, parseHhmm("noon"))
    }

    @Test fun importsWeeklyClassesAndExamsFromIcs() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:Data Structures Lecture
            DTSTART;TZID=Asia/Kolkata:20261102T090000
            DTEND;TZID=Asia/Kolkata:20261102T103000
            RRULE:FREQ=WEEKLY;BYDAY=MO,WE
            END:VEVENT
            BEGIN:VEVENT
            SUMMARY:Physics Lab
            DTSTART:20261103T083000Z
            DTEND:20261103T103000Z
            RRULE:FREQ=WEEKLY
            END:VEVENT
            BEGIN:VEVENT
            SUMMARY:Mid-sem Exam — Maths
            DTSTART;TZID=Asia/Kolkata:20261110T100000
            DTEND;TZID=Asia/Kolkata:20261110T130000
            END:VEVENT
            BEGIN:VEVENT
            SUMMARY:Club meeting
            DTSTART;TZID=Asia/Kolkata:20261105T180000
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val r = parseIcs(ics, ZoneId.of("Asia/Kolkata"))
        assertEquals(listOf(LocalDate.of(2026, 11, 10)), r.exams)
        assertEquals(1, r.skipped)
        val mon = r.busy.first { it.day == DayOfWeek.MONDAY }
        assertEquals(9 * 60 to 10 * 60 + 30, mon.startMin to mon.endMin)
        assertTrue(r.busy.any { it.day == DayOfWeek.WEDNESDAY })
        val lab = r.busy.first { it.label == "Physics Lab" }
        assertEquals(DayOfWeek.TUESDAY to 14 * 60, lab.day to lab.startMin) // 08:30Z = 14:00 IST
    }
}
