package com.fitagent.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/*
 * Exam mode timetable (docs/phase2/04-features.md S1/S2). Class names stay on the phone; only free slots,
 * exam dates and the target bedtime are shared with the coach.
 */

/** A recurring busy block, e.g. a lecture every Monday 09:00–11:00. [label] never leaves the phone. */
data class BusyBlock(val day: DayOfWeek, val startMin: Int, val endMin: Int, val label: String = "")

data class FreeSlot(val day: DayOfWeek, val startMin: Int, val endMin: Int) {
    val minutes: Int get() = endMin - startMin
}

data class Timetable(
    val busy: List<BusyBlock> = emptyList(),
    val examDates: List<LocalDate> = emptyList(),
    /** Minutes after midnight, e.g. 23:00 → 1380; values < 12:00 mean after midnight. */
    val bedtimeMin: Int? = null,
) {
    val isEmpty: Boolean get() = busy.isEmpty() && examDates.isEmpty() && bedtimeMin == null

    fun upcomingExams(today: LocalDate): List<LocalDate> = examDates.filter { !it.isBefore(today) }.sorted()

    fun examWeek(today: LocalDate): Boolean = examDates.any { !it.isBefore(today) && it.isBefore(today.plusDays(7)) }
}

const val DAY_START_MIN = 7 * 60
const val DAY_END_MIN = 22 * 60
const val MIN_FREE_SLOT = 15
/** Default meal breaks kept free of training suggestions. */
val MEALS = listOf(13 * 60 to 14 * 60, 20 * 60 to 21 * 60)

/** Free time between 07:00 and 22:00 on each day: gaps ≥ 15 min between busy blocks and meals. */
fun freeSlots(timetable: Timetable): List<FreeSlot> = DayOfWeek.entries.flatMap { day ->
    val blocked = (timetable.busy.filter { it.day == day }.map { it.startMin to it.endMin } + MEALS).sortedBy { it.first }
    val out = mutableListOf<FreeSlot>()
    var cursor = DAY_START_MIN
    for ((s, e) in blocked) {
        if (s - cursor >= MIN_FREE_SLOT) out += FreeSlot(day, cursor, minOf(s, DAY_END_MIN))
        cursor = maxOf(cursor, e)
        if (cursor >= DAY_END_MIN) break
    }
    if (DAY_END_MIN - cursor >= MIN_FREE_SLOT) out += FreeSlot(day, cursor, DAY_END_MIN)
    out.filter { it.minutes >= MIN_FREE_SLOT }
}

fun hhmm(minutes: Int): String = "%02d:%02d".format((minutes / 60) % 24, minutes % 60)

fun parseHhmm(text: String): Int? {
    val m = Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(text.trim()) ?: return null
    val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
    return if (h in 0..23 && min in 0..59) h * 60 + min else null
}

val DayOfWeek.code: String get() = name.take(3)

/** Result of importing a calendar file. */
data class IcsImport(val busy: List<BusyBlock>, val exams: List<LocalDate>, val skipped: Int)

/**
 * Minimal iCalendar (.ics) import for university timetables: weekly recurring events become busy blocks; events
 * whose title mentions an exam/test/quiz become exam dates. One-off non-exam events are skipped. Times are
 * converted to the phone's zone. Unfolds folded lines (RFC 5545 §3.1).
 */
fun parseIcs(text: String, zone: ZoneId): IcsImport {
    val lines = text.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "").split("\n")
    val busy = mutableListOf<BusyBlock>()
    val exams = mutableListOf<LocalDate>()
    var skipped = 0
    var event: MutableMap<String, String>? = null
    for (raw in lines) {
        val line = raw.trimEnd()
        when {
            line == "BEGIN:VEVENT" -> event = mutableMapOf()
            line == "END:VEVENT" -> {
                val e = event ?: continue
                event = null
                val start = icsTime(e["DTSTART"], e["DTSTART_TZID"], zone) ?: run { skipped++; null } ?: continue
                val end = icsTime(e["DTEND"], e["DTEND_TZID"], zone) ?: start.plusHours(1)
                val summary = e["SUMMARY"].orEmpty()
                val rrule = e["RRULE"].orEmpty()
                when {
                    Regex("""\b(exam|exams|test|quiz|midterm|mid-sem|end-sem|viva)\b""", RegexOption.IGNORE_CASE).containsMatchIn(summary) ->
                        exams += start.toLocalDate()
                    rrule.contains("FREQ=WEEKLY") -> {
                        val days = Regex("""BYDAY=([A-Z,]+)""").find(rrule)?.groupValues?.get(1)?.split(',')
                            ?.mapNotNull { code -> DayOfWeek.entries.firstOrNull { it.name.startsWith(code.take(2)) } }
                            ?: listOf(start.dayOfWeek)
                        val s = start.hour * 60 + start.minute
                        val en = (end.hour * 60 + end.minute).let { if (it <= s) s + 60 else it }
                        days.forEach { busy += BusyBlock(it, s, en, summary.take(60)) }
                    }
                    else -> skipped++
                }
            }
            event != null -> {
                val (keyPart, value) = line.split(":", limit = 2).let { if (it.size == 2) it[0] to it[1] else return@let null }
                    ?: continue
                val key = keyPart.substringBefore(';')
                event[key] = value
                Regex("""TZID=([^;:]+)""").find(keyPart)?.let { event["${key}_TZID"] = it.groupValues[1] }
            }
        }
    }
    return IcsImport(busy.distinct(), exams.distinct().sorted(), skipped)
}

private fun icsTime(value: String?, tzid: String?, zone: ZoneId): LocalDateTime? {
    if (value == null) return null
    return runCatching {
        when {
            value.endsWith("Z") -> ZonedDateTime.of(LocalDateTime.parse(value.dropLast(1), ICS_FORMAT), ZoneId.of("UTC"))
                .withZoneSameInstant(zone).toLocalDateTime()
            value.length == 8 -> LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE).atTime(LocalTime.MIDNIGHT)
            tzid != null -> ZonedDateTime.of(LocalDateTime.parse(value, ICS_FORMAT), ZoneId.of(tzid)).withZoneSameInstant(zone).toLocalDateTime()
            else -> LocalDateTime.parse(value, ICS_FORMAT)
        }
    }.getOrNull()
}

private val ICS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
