package com.fitagent.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class DomainTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 28) // Wednesday
    private val goals = Goals(dailySteps = 8000, dailyWorkoutMinutes = 30)

    private fun day(offset: Long, steps: Long = 0, workoutMin: Int = 0, rhr: Int? = null, sleep: Int? = null) =
        DayActivity(today.minusDays(offset), steps = steps, workoutMinutes = workoutMin, restingHr = rhr, sleepMinutes = sleep,
            workoutCount = if (workoutMin > 0) 1 else 0)

    private fun workout(daysAgo: Long, type: WorkoutType, minutes: Int): Workout {
        val start = today.minusDays(daysAgo).atTime(18, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        return Workout(daysAgo, "w$daysAgo", type, null, start, start + minutes * 60_000L, minutes * 60L, 0.0, WorkoutSource.APP)
    }

    // ── Calories & merging ───────────────────────────────────────────────────

    @Test fun `workout kcal follows MET formula`() {
        assertEquals(9.8 * 70 * 0.5, workoutKcal(WorkoutType.RUN, 70.0, 1800), 0.001)
    }

    @Test fun `walk minutes round up`() {
        assertEquals(0, walkMinutesFor(0))
        assertEquals(1, walkMinutesFor(1))
        assertEquals(23, walkMinutesFor(2250))
    }

    @Test fun `buildDays uses larger of device and estimated calories`() {
        val device = mapOf(today to DeviceDay(today, steps = 10_000, activeKcal = 150.0))
        val days = buildDays(today, today, device, emptyList(), 70.0, zone)
        assertEquals(400, days.single().activeKcal) // 10k steps × 0.04
        assertTrue(days.single().kcalEstimated)

        val watch = mapOf(today to DeviceDay(today, steps = 10_000, activeKcal = 650.0))
        assertEquals(650, buildDays(today, today, watch, emptyList(), 70.0, zone).single().activeKcal)
    }

    @Test fun `buildDays fills gaps and attributes workouts to their start day`() {
        val days = buildDays(today.minusDays(2), today, emptyMap(), listOf(workout(1, WorkoutType.RUN, 40)), 70.0, zone)
        assertEquals(3, days.size)
        assertEquals(listOf(0, 40, 0), days.map { it.workoutMinutes })
    }

    // ── Streaks ──────────────────────────────────────────────────────────────

    @Test fun `unfinished today keeps the streak`() {
        val days = (1L..5L).map { day(it, steps = 9000) } + day(0, steps = 100)
        val s = computeStreak(days, goals, today)
        assertEquals(5, s.current)
        assertFalse(s.todayDone)
        assertTrue(s.atRisk)
    }

    @Test fun `one missed day per week is frozen, two break the streak`() {
        // Mon(2) met, Tue(1) missed -> frozen, today met
        val frozen = listOf(day(2, steps = 9000), day(1), day(0, workoutMin = 30))
        assertEquals(2, computeStreak(frozen, goals, today).current)
        assertTrue(computeStreak(frozen, goals, today).freezeUsedThisWeek)

        // Two misses in the same week reset it
        val broken = listOf(day(3, steps = 9000), day(2), day(1), day(0, steps = 9000))
        assertEquals(1, computeStreak(broken, goals, today).current)
    }

    @Test fun `best streak is tracked`() {
        val days = (10L downTo 6L).map { day(it, steps = 9000) } + day(5) + day(4) + day(3, steps = 9000)
        val s = computeStreak(days, goals, today)
        assertEquals(5, s.best)
    }

    // ── Readiness ────────────────────────────────────────────────────────────

    @Test fun `rested user is ready to push`() {
        val days = (1L..14L).map { day(it, steps = 8000, rhr = 58, sleep = 450) } + day(0, rhr = 57, sleep = 480)
        val r = computeReadiness(days, emptyList(), today, zone)
        assertEquals(Band.PUSH, r.band)
        assertEquals(setOf("sleep", "resting_hr"), r.components.keys) // no workouts: load unknown
    }

    @Test fun `short sleep and elevated resting HR means recover`() {
        val days = (1L..14L).map { day(it, rhr = 58, sleep = 450) } + day(0, rhr = 67, sleep = 250)
        assertEquals(Band.RECOVER, computeReadiness(days, emptyList(), today, zone).band)
    }

    @Test fun `new user never gets a false load spike`() {
        val days = listOf(day(2, steps = 5000), day(1, steps = 5000), day(0))
        val workouts = listOf(workout(0, WorkoutType.HIIT, 60), workout(1, WorkoutType.RUN, 60))
        val r = computeReadiness(days, workouts, today, zone)
        assertNull(r.acwr)
        assertEquals(60, r.score)
        assertEquals(Band.STEADY, r.band)
    }

    // ── Coach suggestions ────────────────────────────────────────────────────

    @Test fun `suggestion follows readiness band`() {
        val t = day(0, steps = 2000)
        val recover = Readiness(20, Band.RECOVER, emptyMap(), emptyList(), null)
        assertEquals(WorkoutType.YOGA, suggestToday(t, recover, emptyList(), goals, today, zone).type)
        val push = Readiness(85, Band.PUSH, emptyMap(), emptyList(), null)
        assertEquals(WorkoutType.HIIT, suggestToday(t, push, emptyList(), goals, today, zone).type)
        val pushAfterHiit = suggestToday(t, push, listOf(workout(1, WorkoutType.HIIT, 20)), goals, today, zone)
        assertEquals(WorkoutType.RUN, pushAfterHiit.type)
        assertEquals(60, pushAfterHiit.walkMinutes)
    }

    @Test fun `no workout suggested once the goal is done`() {
        val t = day(0, steps = 9000, workoutMin = 45)
        val s = suggestToday(t, Readiness(70, Band.PUSH, emptyMap(), emptyList(), null), emptyList(), goals, today, zone)
        assertNull(s.type)
        assertEquals(0L, s.stepsRemaining)
    }

    // ── Intervals & sessions ─────────────────────────────────────────────────

    @Test fun `interval phases`() {
        val c = IntervalConfig(40, 20, 3)
        assertEquals(IntervalPhase(PhaseKind.WORK, 1, 40, 40), intervalPhaseAt(c, 0))
        assertEquals(IntervalPhase(PhaseKind.REST, 1, 20, 20), intervalPhaseAt(c, 40))
        assertEquals(IntervalPhase(PhaseKind.WORK, 2, 1, 40), intervalPhaseAt(c, 99))
        assertEquals(160, c.totalSeconds)
        assertEquals(PhaseKind.DONE, intervalPhaseAt(c, 160).kind)
    }

    @Test fun `paused time is excluded and survives serialisation`() {
        var s = ActiveSession(WorkoutType.RUN, startedAtMs = 0)
        s = s.pause(60_000)
        assertEquals(60_000, s.activeMs(500_000))
        s = s.resume(120_000)
        assertEquals(120_000, s.activeMs(180_000))
    }

    @Test fun `achievements unlock`() {
        val days = listOf(day(0, steps = 12_000))
        val a = computeAchievements(days, listOf(workout(0, WorkoutType.HIIT, 20)), StreakInfo(best = 3), zone)
            .associateBy { it.id }
        assertTrue(a.getValue("first_workout").unlocked)
        assertTrue(a.getValue("steps_10k").unlocked)
        assertTrue(a.getValue("streak_3").unlocked)
        assertFalse(a.getValue("streak_7").unlocked)
        assertEquals(0.2f, a.getValue("hiit_5").progress, 0.001f)
    }

    @Test fun `clock formatting`() {
        assertEquals("0:05", formatClock(5))
        assertEquals("12:05", formatClock(725))
        assertEquals("1:02:33", formatClock(3753))
    }
}
