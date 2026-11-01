package com.fitagent.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.fitagent.data.HealthStatus
import com.fitagent.data.health.HealthAvailability
import com.fitagent.data.prefs.Account
import com.fitagent.data.remote.dto.ChallengeRes
import com.fitagent.data.remote.dto.LeaderboardRowDto
import com.fitagent.data.remote.dto.PlanRes
import com.fitagent.data.remote.dto.ReadinessDto
import com.fitagent.data.remote.dto.SessionDto
import com.fitagent.data.remote.dto.WeeklyPlanDto
import com.fitagent.domain.ActiveSession
import com.fitagent.data.DataSource
import com.fitagent.data.SyncEvent
import com.fitagent.domain.DeviceDay
import com.fitagent.domain.HrBucket
import com.fitagent.domain.NightSignals
import com.fitagent.domain.SleepSegment
import com.fitagent.domain.SleepStage
import com.fitagent.domain.SleepStages
import com.fitagent.domain.IntervalConfig
import com.fitagent.domain.Profile
import com.fitagent.domain.Workout
import com.fitagent.domain.WorkoutSource
import com.fitagent.domain.WorkoutType
import com.fitagent.domain.buildDays
import com.fitagent.domain.computeAchievements
import com.fitagent.domain.computeReadiness
import com.fitagent.domain.computeStreak
import com.fitagent.domain.sleepStagesOf
import com.fitagent.domain.suggestToday
import com.fitagent.domain.workoutKcal
import com.fitagent.domain.workoutsThisWeek
import com.fitagent.ui.screens.ActiveWorkoutScreen
import com.fitagent.ui.screens.CoachScreen
import com.fitagent.ui.screens.OnboardingScreen
import com.fitagent.ui.screens.SquadScreen
import com.fitagent.ui.screens.DevicesScreen
import com.fitagent.ui.screens.HeartScreen
import com.fitagent.ui.screens.ReadinessScreen
import com.fitagent.ui.screens.SleepScreen
import com.fitagent.ui.screens.TimetableScreen
import com.fitagent.ui.screens.BreatheScreen
import com.fitagent.ui.screens.MoodCheckInCard
import com.fitagent.ui.screens.MoodScreen
import com.fitagent.ui.screens.LibraryScreen
import com.fitagent.ui.screens.RoutinePlayer
import com.fitagent.ui.screens.TodayScreen
import com.fitagent.ui.screens.TrainingInsights
import com.fitagent.ui.screens.TrainScreen
import com.fitagent.ui.screens.YouScreen
import com.fitagent.ui.theme.FitAgentTheme
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Renders every main screen with realistic data. Record with `./gradlew recordPaparazziDebug`,
 * check for visual regressions with `./gradlew verifyPaparazziDebug`.
 */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, theme = "android:Theme.Material.NoActionBar")

    private val pad = PaddingValues(top = 28.dp, bottom = 16.dp)

    @Test fun today() = snap { TodayScreen(home, pad, {}, {}, {}, hourly, lastNight) }
    @Test fun today_light() = snap(dark = false) { TodayScreen(home, pad, {}, {}, {}, hourly, lastNight) }
    @Test fun training_insights() = snap {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(16.dp, 28.dp)) { TrainingInsights(home) }
    }
    @Test fun exam_mode() = snap {
        val classes = listOf(
            java.time.DayOfWeek.MONDAY to (9 * 60 to 11 * 60), java.time.DayOfWeek.MONDAY to (14 * 60 to 16 * 60),
            java.time.DayOfWeek.TUESDAY to (10 * 60 to 13 * 60), java.time.DayOfWeek.WEDNESDAY to (9 * 60 to 12 * 60),
            java.time.DayOfWeek.WEDNESDAY to (15 * 60 to 17 * 60), java.time.DayOfWeek.THURSDAY to (11 * 60 to 13 * 60),
            java.time.DayOfWeek.FRIDAY to (9 * 60 to 12 * 60 + 30),
        ).map { (d, t) -> com.fitagent.domain.BusyBlock(d, t.first, t.second, "Lecture") }
        val timetable = com.fitagent.domain.Timetable(classes, listOf(today.plusDays(3)), 23 * 60)
        val examPlan = cloud.plan!!.copy(plan = WeeklyPlanDto("Exam week: short sessions in free slots.", listOf(
            SessionDto("MON", "WALK", 20, "easy", "Brisk walk", "17:00"), SessionDto("TUE", "STRENGTH", 25, "moderate", "Circuit", "07:30"),
            SessionDto("WED", "YOGA", 20, "easy", "Mobility", "18:00"), SessionDto("THU", "REST", 0, "rest", "Rest"),
            SessionDto("FRI", "WALK", 15, "easy", "Exam-day walk", "17:30"), SessionDto("SAT", "RUN", 25, "moderate", "Easy run", "08:00"),
            SessionDto("SUN", "REST", 0, "rest", "Rest"),
        )))
        TimetableScreen(timetable, today, examPlan, {}, {}, {})
    }
    @Test fun mood_today_card() = snap {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(16.dp, 28.dp)) {
            MoodCheckInCard(com.fitagent.domain.MoodEntry(today, 4, setOf("Exams")), { _, _ -> }, {})
        }
    }
    @Test fun stress_and_mood() = snap {
        val moods = (0L..40L).map { com.fitagent.domain.MoodEntry(today.minusDays(it), listOf(4, 3, 4, 5, 2, 3, 4)[(it % 7).toInt()],
            setOf(listOf("Exams", "Sleep", "Friends")[(it % 3).toInt()])) }
        MoodScreen(home, moods, heartToday, hourly, {}, {})
    }
    @Test fun breathe() = snap { BreatheScreen {} }
    @Test fun library() = snap { LibraryScreen({}, {}) }
    @Test fun routine_player() = snap { RoutinePlayer(com.fitagent.domain.Routines.byId("quiet_full_body"), true, {}, {}, {}) }
    /** Every exercise's keyframes side by side, to review the drawn poses. */
    @Test fun exercise_poses() = paparazzi.snapshot {
        FitAgentTheme(dark = true) {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(4.dp)) {
                        com.fitagent.domain.ExerciseLibrary.all.chunked(3).forEach { row ->
                            androidx.compose.foundation.layout.Row {
                                row.forEach { e ->
                                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f)) {
                                        androidx.compose.foundation.layout.Row {
                                            e.keyframes.take(2).forEachIndexed { i, _ ->
                                                com.fitagent.ui.components.ExerciseAnimation(e, androidx.compose.ui.graphics.Color(0xFFC6F432),
                                                    androidx.compose.ui.Modifier.weight(1f).height(64.dp), phase = i / e.keyframes.size.toFloat())
                                            }
                                        }
                                        androidx.compose.material3.Text(e.name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    @Test fun readiness_detail() = snap { ReadinessScreen(home) {} }
    @Test fun sleep_detail() = snap { SleepScreen(home, lastNight) {} }
    @Test fun heart_detail() = snap { HeartScreen(home, heartToday, heartToday) {} }
    @Test fun devices() = snap {
        DevicesScreen(home.health.copy(backgroundRead = true), home.lastWatchSync, clock.millis() - 50 * 60_000, true, sources,
            syncLog, CompatibilityState.Idle, false, {}, {}, {}, {})
    }
    @Test fun today_not_connected() = snap {
        TodayScreen(home.copy(health = HealthStatus(HealthAvailability.NOT_INSTALLED)), pad, {}, {}, {})
    }
    @Test fun train() = snap { TrainScreen(home, pad, { _, _ -> }, { _, _, _ -> }, {}) }
    @Test fun coach_chat() = snap { CoachScreen(home, cloud, pad, {}, {}, {}, {}, {}, {}) }
    @Test fun coach_signed_out() = snap { CoachScreen(home, CloudState(), pad, {}, {}, {}, {}, {}, {}) }
    @Test fun squad() = snap { SquadScreen(cloud, pad, {}, {}, { _, _, _, _ -> }, { _, _ -> }, {}) }
    @Test fun you() = snap { YouScreen(home, cloud, pad, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun workout_player_intervals() = snap {
        ActiveWorkoutScreen(
            ActiveSession(WorkoutType.HIIT, clock.millis() - 75_000, interval = IntervalConfig.CLASSIC),
            62.0, {}, {}, {}, {},
        )
    }
    @Test fun workout_player_run() = snap {
        ActiveWorkoutScreen(ActiveSession(WorkoutType.RUN, clock.millis() - 1_523_000), 62.0, {}, {}, {}, {})
    }
    @Test fun onboarding() = snap { OnboardingScreen(Profile(), {}, {}) }

    private fun snap(dark: Boolean = true, content: @Composable () -> Unit) =
        paparazzi.snapshot {
            FitAgentTheme(dark = dark) {
                // Mirrors the Scaffold in MainActivity, which supplies background and content colour.
                CompositionLocalProvider(LocalInspectionMode provides true, LocalClock provides clock) {
                    Surface(color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }

    // ── Sample data ──────────────────────────────────────────────────────────

    private val zone = ZoneId.of("Asia/Kolkata")
    // Fixed "now" (Friday evening) so snapshots never change with the calendar.
    private val clock = Clock.fixed(Instant.parse("2026-10-30T13:00:00Z"), zone)
    private val today = LocalDate.now(clock)
    private val profile = Profile(name = "Asha Verma", ageYears = 20, heightCm = 165, weightKg = 58.0, onboarded = true)

    private val workouts: List<Workout> = listOf(
        0L to WorkoutType.STRENGTH, 2L to WorkoutType.RUN, 3L to WorkoutType.YOGA, 5L to WorkoutType.HIIT,
        7L to WorkoutType.CYCLE, 9L to WorkoutType.STRENGTH, 12L to WorkoutType.RUN, 15L to WorkoutType.WALK,
        18L to WorkoutType.HIIT, 21L to WorkoutType.STRENGTH,
    ).mapIndexed { i, (ago, type) ->
        val minutes = if (type == WorkoutType.HIIT) 20 else 35
        val start = today.minusDays(ago).atTime(if (ago == 0L) 7 else 18, 15).atZone(zone).toInstant().toEpochMilli()
        Workout(i.toLong(), "w$i", type, null, start, start + minutes * 60_000L, minutes * 60L,
            workoutKcal(type, 58.0, minutes * 60L), if (ago == 7L) WorkoutSource.HEALTH_CONNECT else WorkoutSource.APP)
    }

    private val lastNight: List<SleepSegment> = run {
        var t = today.minusDays(1).atTime(23, 41).atZone(zone).toInstant().toEpochMilli()
        listOf(SleepStage.LIGHT to 25, SleepStage.DEEP to 50, SleepStage.LIGHT to 40, SleepStage.REM to 22, SleepStage.LIGHT to 55,
            SleepStage.DEEP to 32, SleepStage.AWAKE to 6, SleepStage.LIGHT to 48, SleepStage.REM to 35, SleepStage.LIGHT to 52,
            SleepStage.REM to 38, SleepStage.AWAKE to 5, SleepStage.LIGHT to 40, SleepStage.REM to 20, SleepStage.AWAKE to 13)
            .map { (stage, min) -> SleepSegment(t, t + min * 60_000L, stage).also { t += min * 60_000L } }
    }

    private val device: Map<LocalDate, DeviceDay> = (0L..29L).associate { ago ->
        val d = today.minusDays(ago)
        val steps = if (ago == 0L) 6_240L else listOf(9_800L, 7_200L, 11_400L, 5_600L, 8_900L, 10_300L, 6_700L)[(ago % 7).toInt()]
        val tonight = ago == 0L
        val sleep = if (tonight) lastNight.filter { it.stage != SleepStage.AWAKE }.sumOf { it.minutes } else 410 + (ago % 4).toInt() * 20
        val bed = if (tonight) lastNight.first().startMs else d.minusDays(1).atTime(23, 10 + (ago % 5).toInt() * 9).atZone(zone).toInstant().toEpochMilli()
        d to DeviceDay(
            d, steps, steps * 0.74, steps * 0.045, restingHr = if (ago == 0L) 57 else 59 + (ago % 3).toInt(),
            avgHr = 74, sleepMinutes = sleep, hrMin = 52 + (ago % 3).toInt(), hrMax = 128 + (ago % 6).toInt() * 9,
            floors = 6 + (ago % 4).toInt() * 3, vo2max = if (ago % 6 == 0L) 43.5 - ago * 0.05 else null,
            sleepStages = if (tonight) sleepStagesOf(lastNight)
            else SleepStages(deep = sleep * 18 / 100, rem = sleep * 22 / 100, light = sleep - sleep * 40 / 100, awake = 24),
            bedMs = bed, wakeMs = if (tonight) lastNight.last().endMs else bed + (sleep + 24) * 60_000L,
            night = NightSignals(hrvMs = if (ago == 0L) 68.0 else 58.0 + (ago % 5) * 2.5, spo2Avg = 96.5 - (ago % 3) * 0.4,
                spo2Min = 92.0 + (ago % 3), respiratoryRate = 14.2, skinTempDelta = listOf(0.1, -0.2, 0.3, 0.0, -0.1)[(ago % 5).toInt()]),
            stepsSource = "com.noisefit", sleepSource = "com.noisefit",
        )
    }

    private val hourly: List<Long> = listOf(0, 0, 0, 0, 0, 0, 120, 640, 980, 310, 220, 450, 610, 380, 290, 260, 420, 540, 760, 0, 0, 0, 0, 0)
        .map { it.toLong() }

    private val heartToday: List<HrBucket> = run {
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        (0 until 6 * 18).map { i ->
            val hour = i / 6
            val base = when (hour) { in 0..6 -> 54; 7 -> 112; 8 -> 96; in 17..17 -> 138; else -> 78 }
            val bpm = base + (i * 7 % 11) - 5
            HrBucket(start + i * 600_000L, bpm - 6, bpm, bpm + 9)
        }
    }

    private val home: HomeState by lazy {
        val all = buildDays(today.minusDays(29), today, device, workouts, profile.weightKg, zone)
        val readiness = computeReadiness(all, workouts, today, zone)
        val streak = computeStreak(all, profile.goals, today)
        HomeState(
            profile = profile,
            health = HealthStatus(HealthAvailability.AVAILABLE, setOf("a", "b"), setOf("a", "b")),
            refreshing = false,
            today = today,
            days = all,
            todayActivity = all.last(),
            readiness = readiness,
            suggestion = suggestToday(all.last(), readiness, workouts, profile.goals, today, zone),
            streak = streak,
            workouts = workouts,
            weekWorkouts = workoutsThisWeek(workouts, today, zone),
            achievements = computeAchievements(all, workouts, streak, zone),
            history = all,
            readinessHistory = (29 downTo 0).map { ago ->
                val d = today.minusDays(ago.toLong())
                d to computeReadiness(all.filter { !it.date.isAfter(d) }, workouts, d, zone)
            },
            lastWatchSync = clock.millis() - 4 * 60_000,
        )
    }

    private val sources = listOf(
        DataSource("com.noisefit", "NoiseFit", "Noise ColorFit Pro 5", clock.millis() - 4 * 60_000, 18_432,
            listOf("Heart rate", "Sleep", "Steps", "Blood oxygen")),
        DataSource("phone_sensor", "This phone's step sensor", null, clock.millis() - 20 * 60_000, 214, listOf("Steps")),
    )

    private val syncLog: List<SyncEvent> = (0 until 40).map { i ->
        SyncEvent(clock.millis() - i * 35 * 60_000L, if (i % 6 == 0) "cloud" else "watch", ok = i != 9, records = 20 + i % 7 * 15,
            message = if (i == 9) "Health Connect is busy or rate-limited — will retry" else null)
    }

    private val cloud = CloudState(
        account = Account("asha@uni.edu", "Asha"),
        serverUrl = "https://api.fitagent.app/",
        coachLoaded = true,
        messages = listOf(
            ChatMessage("user", "I have exams all week. How do I stay active?"),
            ChatMessage("assistant", "Your readiness is 82 today, so you can still train — just shorter. Try three " +
                "15-minute sessions this week: two dorm-room circuits (squats, push-ups, lunges, plank) and one brisk " +
                "campus walk between study blocks. A 10-minute walk after each 90-minute study session also covers " +
                "~3,000 of your 8,000 steps.\n\nNext step: start a 15-min strength session tonight before dinner."),
            ChatMessage("user", "How am I doing in the hostel challenge?"),
            ChatMessage("assistant", "", pending = true),
        ),
        plan = PlanRes(
            "p1", today.toString(), today.toString(), "llm",
            ReadinessDto(82, "PUSH", mapOf("sleep" to 96, "resting_hr" to 95, "load" to 90), emptyList()),
            WeeklyPlanDto("Four sessions with two hard days spaced out.", listOf(
                SessionDto("MON", "HIIT", 20, "hard", "40s on / 20s off × 10"),
                SessionDto("TUE", "YOGA", 25, "easy", "Hips and hamstrings flow"),
            )),
        ),
        challenges = listOf(
            ChallengeRes("c1", "K7QM2P", "Hostel Step-Off", "steps", today.minusDays(3).toString(),
                today.plusDays(3).toString(), true, "active", 18, 3, listOf(
                    LeaderboardRowDto(1, "u1", "Rohan", 74_820.0, false),
                    LeaderboardRowDto(2, "u2", "Meera", 66_100.0, false),
                    LeaderboardRowDto(3, "me", "Asha", 61_240.0, true),
                )),
            ChallengeRes("c2", "TX4W9A", "CSE Batch '27 — Consistency", "active_days", today.minusDays(10).toString(),
                today.plusDays(20).toString(), false, "active", 64, 1, listOf(
                    LeaderboardRowDto(1, "me", "Asha", 9.0, true),
                    LeaderboardRowDto(1, "u5", "Kabir", 9.0, false),
                    LeaderboardRowDto(3, "u6", "Zoya", 8.0, false),
                )),
        ),
    )
}
