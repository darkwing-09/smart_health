package com.fitagent.domain

import com.fitagent.data.sensors.PhoneStepCounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class WatchDataTest {
    private val zone = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 10, 30)
    private fun ms(date: LocalDate, h: Int, m: Int = 0) = date.atTime(h, m).toInstant(zone).toEpochMilli()

    @Test fun nightWithStagesBeatsLongerPhoneEstimate() {
        val phone = SleepSession("phone.app", ms(day.minusDays(1), 22), ms(day, 8), emptyList())
        val watchStages = listOf(
            SleepSegment(ms(day.minusDays(1), 23), ms(day, 1), SleepStage.LIGHT),
            SleepSegment(ms(day, 1), ms(day, 2), SleepStage.DEEP),
            SleepSegment(ms(day, 2), ms(day, 2, 10), SleepStage.AWAKE),
            SleepSegment(ms(day, 2, 10), ms(day, 6), SleepStage.REM),
        )
        val watch = SleepSession("com.noisefit", ms(day.minusDays(1), 23), ms(day, 6), watchStages)
        val night = pickNights(listOf(phone, watch), zone).getValue(day)
        assertEquals("com.noisefit", night.source)
        assertEquals(7 * 60 - 10, night.asleepMinutes) // awake time excluded, never summed with the phone
        assertEquals(SleepStages(deep = 60, rem = 230, light = 120, awake = 10), night.stages)
    }

    @Test fun nightWithoutStagesHasNoStageBreakdown() {
        val s = SleepSession("com.coveiot.android.boat", ms(day.minusDays(1), 23, 30), ms(day, 7), emptyList())
        val night = pickNights(listOf(s), zone).getValue(day)
        assertNull(night.stages)
        assertEquals(450, night.asleepMinutes)
    }

    @Test fun readingsBelongToTheNightTheyWereMeasuredIn() {
        val nights = mapOf(day to Night("x", emptyList(), ms(day.minusDays(1), 23), ms(day, 7)))
        assertEquals(day, nightOf(java.time.Instant.ofEpochMilli(ms(day, 3)), zone, nights))
        assertEquals(day.plusDays(1), nightOf(java.time.Instant.ofEpochMilli(ms(day, 22)), zone, emptyMap()))
        assertEquals(day, nightOf(java.time.Instant.ofEpochMilli(ms(day, 9)), zone, emptyMap()))
    }

    @Test fun sleepScoreAndConsistency() {
        assertNull(bedtimeConsistency(listOf(300, 310)))
        assertEquals(100, bedtimeConsistency(listOf(330, 330, 330)))
        assertTrue(bedtimeConsistency(listOf(240, 360, 480, 300))!! < 40)
        val good = sleepScore(480, SleepStages(90, 110, 260, 15), listOf(330, 335, 325))
        val poor = sleepScore(300, SleepStages(20, 40, 220, 60), listOf(240, 420, 330))
        assertTrue(good > 80 && poor < 50)
        assertEquals(330, bedtimeMinutes(ms(day, 23, 30), zone))
        assertEquals(390, bedtimeMinutes(ms(day, 0, 30), zone))
    }

    @Test fun phoneStepsFillOnlyDaysWithoutWatchSteps() {
        val device = mapOf(day to DeviceDay(day, steps = 8000, stepsSource = "com.noisefit"), day.minusDays(1) to DeviceDay(day.minusDays(1)))
        val merged = mergePhoneSteps(device, mapOf(day to 7000L, day.minusDays(1) to 5000L, day.minusDays(2) to 3000L))
        assertEquals(8000L, merged.getValue(day).steps)
        assertEquals("com.noisefit", merged.getValue(day).stepsSource)
        assertEquals(5000L, merged.getValue(day.minusDays(1)).steps)
        assertEquals(PHONE_SENSOR_SOURCE, merged.getValue(day.minusDays(2)).stepsSource)
    }

    @Test fun phoneCounterDeltaHandlesRebootAndGlitches() {
        val boot = 1_000_000L
        val hour = 3_600_000L
        assertEquals(0L, PhoneStepCounter.stepsSince(null, 500f, boot + hour, boot))
        assertEquals(400L, PhoneStepCounter.stepsSince(100f to boot + 10, 500f, boot + hour, boot))
        // Counter reset by a reboot: everything since boot is new.
        assertEquals(300L, PhoneStepCounter.stepsSince(9000f to boot - hour, 300f, boot + hour, boot))
        // Impossible jump (50k steps in an hour) is treated as a glitch.
        assertEquals(0L, PhoneStepCounter.stepsSince(100f to boot + 10, 50_100f, boot + hour, boot))
    }

    @Test fun zonesAndWhoMinutes() {
        val max = hrMax(20)
        assertEquals(194, max)
        assertEquals(0, zoneOf(80, max))
        assertEquals(2, zoneOf(120, max))
        assertEquals(5, zoneOf(180, max))
        val buckets = listOf(120, 125, 150, 160, 175, 60).mapIndexed { i, bpm -> HrBucket(i * 600_000L, bpm - 5, bpm, bpm + 5) }
        val minutes = zoneMinutes(buckets, max)
        assertEquals(listOf(0, 20, 10, 10, 10), minutes)
        assertEquals(20 + 10 + 2 * (10 + 10), whoMinutes(minutes))
    }

    @Test fun headlinesDescribeWithoutDiagnosing() {
        assertTrue(sleepHeadline(500, listOf(440, 450, 460)).contains("more than your average"))
        assertTrue(sleepHeadline(450, listOf(440, 450, 460)).contains("usual"))
        assertTrue(sleepHeadline(null, emptyList()).contains("wear your watch", ignoreCase = true))
        assertTrue(baselineHeadline("HRV", 40.0, listOf(60.0, 62.0, 58.0, 61.0, 59.0), true, "ms").contains("below"))
        assertTrue(baselineHeadline("Resting HR", 50.0, listOf(60.0, 62.0, 58.0, 61.0, 59.0), false, "bpm").contains("recovered"))
        assertTrue(activeHourHeadline(List(24) { if (it == 18) 900L else 10L }).contains("18:00"))
        val all = listOf(readinessHeadline(Readiness(40, Band.STEADY, mapOf("hrv" to 10), emptyList(), null)),
            readinessHeadline(Readiness(30, Band.RECOVER, mapOf("resting_hr" to 5), listOf("Your body may be fighting something"), null)))
        all.forEach { h -> listOf("infection", "fever", "illness", "covid").forEach { assertTrue(h, !h.contains(it, ignoreCase = true)) } }
    }

    @Test fun sleepReadinessSlope() {
        val points = (0 until 10).map { i -> (6f + i * 0.25f) to (50f + i * 0.25f * 8) }
        assertEquals("For you, each extra hour of sleep ≈ +8 readiness.", sleepReadinessHeadline(points))
        assertNull(sleepReadinessHeadline(points.take(3)))
    }
}
