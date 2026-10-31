package com.fitagent.ui

import androidx.compose.foundation.layout.PaddingValues
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
import com.fitagent.domain.DeviceDay
import com.fitagent.domain.IntervalConfig
import com.fitagent.domain.Profile
import com.fitagent.domain.Workout
import com.fitagent.domain.WorkoutSource
import com.fitagent.domain.WorkoutType
import com.fitagent.domain.buildDays
import com.fitagent.domain.computeAchievements
import com.fitagent.domain.computeReadiness
import com.fitagent.domain.computeStreak
import com.fitagent.domain.suggestToday
import com.fitagent.domain.workoutKcal
import com.fitagent.domain.workoutsThisWeek
import com.fitagent.ui.screens.ActiveWorkoutScreen
import com.fitagent.ui.screens.CoachScreen
import com.fitagent.ui.screens.OnboardingScreen
import com.fitagent.ui.screens.SquadScreen
import com.fitagent.ui.screens.TodayScreen
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

    @Test fun today() = snap { TodayScreen(home, pad, {}, {}, {}) }
    @Test fun today_light() = snap(dark = false) { TodayScreen(home, pad, {}, {}, {}) }
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

    private val device: Map<LocalDate, DeviceDay> = (0L..29L).associate { ago ->
        val d = today.minusDays(ago)
        val steps = if (ago == 0L) 6_240L else listOf(9_800L, 7_200L, 11_400L, 5_600L, 8_900L, 10_300L, 6_700L)[(ago % 7).toInt()]
        d to DeviceDay(d, steps, steps * 0.74, steps * 0.045, restingHr = if (ago == 0L) 57 else 59 + (ago % 3).toInt(),
            avgHr = 74, sleepMinutes = if (ago == 0L) 462 else 410 + (ago % 4).toInt() * 20)
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
        )
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
