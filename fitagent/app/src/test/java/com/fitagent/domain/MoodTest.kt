package com.fitagent.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class MoodTest {
    private val today = LocalDate.of(2026, 11, 2)

    @Test fun lowMoodStreakCountsConsecutiveLowDays() {
        val entries = (0..5).map { MoodEntry(today.minusDays(it.toLong()), if (it < 5) 2 else 4) }
        assertEquals(5, lowMoodStreak(entries, today))
        assertEquals(4, lowMoodStreak(entries.drop(1), today)) // today not logged yet → counts from yesterday
        assertEquals(0, lowMoodStreak(listOf(MoodEntry(today, 3)), today))
    }

    @Test fun stressExcludesMovingHoursAndNeedsBaseline() {
        val zone = ZoneOffset.UTC
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val buckets = listOf(HrBucket(start + 9 * 3_600_000, 60, 72, 80), HrBucket(start + 10 * 3_600_000, 90, 110, 130))
        val steps = List(24) { if (it == 10) 2000L else 50L }
        assertEquals(listOf(7, null), stressTimeline(buckets, 60, steps, zone)) // (72 − 60 − 10) / 30
        assertEquals(listOf(null, null), stressTimeline(buckets, null, steps, zone))
        assertTrue(stressHeadline(List(10) { 10 }).startsWith("Calm"))
    }

    @Test fun breathPhasesCycle() {
        val (label, progress, index) = breathPhase(BreathPattern.BOX, 6_000)
        assertEquals("Hold", label); assertEquals(1, index); assertEquals(0.5f, progress, 0.001f)
        assertEquals("Breathe in", breathPhase(BreathPattern.RELAX, 19_000).first) // 4+7+8 = 19 s cycle
    }
}

class VoiceCueTest {
    @Test fun intervalCues() {
        val c = IntervalConfig(20, 10, 4)
        assertEquals("Work. Round 1 of 4", voiceCue(c, -1, 0))
        assertEquals("Three, two, one", voiceCue(c, 16, 17))
        assertEquals("Rest", voiceCue(c, 19, 20))
        assertEquals("Work. Round 2 of 4", voiceCue(c, 29, 30))
        assertEquals("Rest. Halfway there", voiceCue(c, 49, 50))
        assertEquals("All rounds done. Great work — cool down.", voiceCue(c, c.totalSeconds - 1L, c.totalSeconds.toLong()))
        assertEquals(null, voiceCue(c, 5, 6))
        assertEquals(null, voiceCue(c, 6, 6))
    }

    @Test fun openWorkoutTimeChecks() {
        assertEquals("5 minutes", voiceCue(null, 299, 300))
        assertEquals(null, voiceCue(null, 300, 301))
        assertEquals("10 minutes", voiceCue(null, 595, 605)) // a skipped tick still announces once
    }
}
