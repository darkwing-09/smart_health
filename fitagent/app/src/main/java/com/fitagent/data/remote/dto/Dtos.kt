package com.fitagent.data.remote.dto

// Wire models for the FitAgent API. Gson maps snake_case JSON to these camelCase fields.

data class RegisterReq(val email: String, val password: String, val displayName: String, val timezone: String)
data class LoginReq(val email: String, val password: String)
data class RefreshReq(val refreshToken: String)
data class TokenRes(val accessToken: String, val refreshToken: String, val expiresIn: Int)

data class GoalsDto(
    val dailySteps: Int,
    val dailyActiveKcal: Int,
    val dailyWorkoutMinutes: Int,
    val weeklyWorkouts: Int,
)

data class ProfileRes(
    val id: String,
    val email: String,
    val displayName: String,
    val timezone: String,
    val age: Int?,
    val heightCm: Int?,
    val weightKg: Double?,
    val goals: GoalsDto,
)

data class ProfileUpdateReq(
    val displayName: String?,
    val timezone: String?,
    val age: Int?,
    val heightCm: Int?,
    val weightKg: Double?,
    val goals: GoalsDto?,
)

/** One day's summary for the cloud. Intraday heart rate, sleep segments and routes never leave the phone. */
data class DayDto(
    val date: String,
    val steps: Long,
    val distanceM: Double,
    val activeKcal: Int,
    val workoutMinutes: Int,
    val restingHr: Int?,
    val avgHr: Int?,
    val sleepMinutes: Int?,
    val hrMin: Int? = null,
    val hrMax: Int? = null,
    val floors: Int? = null,
    val vo2max: Double? = null,
    val sleepDeepMin: Int? = null,
    val sleepRemMin: Int? = null,
    val sleepLightMin: Int? = null,
    val sleepAwakeMin: Int? = null,
    val bedTime: String? = null,
    val wakeTime: String? = null,
    val hrvMs: Double? = null,
    val spo2Avg: Double? = null,
    val spo2Min: Double? = null,
    val respRate: Double? = null,
    val skinTempDelta: Double? = null,
    val stepsSource: String? = null,
)

data class WorkoutDto(
    val clientId: String,
    val type: String,
    val title: String?,
    val start: String,
    val end: String,
    val activeSeconds: Long,
    val kcal: Double,
    val source: String,
)

data class SyncReq(
    val timezone: String,
    val days: List<DayDto>,
    val workouts: List<WorkoutDto>,
    val deletedWorkoutIds: List<String>,
)

data class SyncRes(val daysUpserted: Int, val workoutsUpserted: Int, val workoutsDeleted: Int)

data class ChatReq(val message: String)
data class ChatRes(val reply: String, val safety: String?, val toolsUsed: List<String>, val aiPowered: Boolean)
data class MessageRes(val id: Long, val role: String, val content: String, val safety: String?, val createdAt: String)

data class ReadinessDto(val score: Int, val band: String, val components: Map<String, Int>, val reasons: List<String>)
data class SessionDto(val day: String, val type: String, val minutes: Int, val intensity: String, val focus: String)
data class WeeklyPlanDto(val summary: String, val sessions: List<SessionDto>)
data class PlanRes(
    val id: String,
    val startsOn: String,
    val createdAt: String,
    val source: String,
    val readiness: ReadinessDto,
    val plan: WeeklyPlanDto,
)

data class ChallengeCreateReq(val name: String, val metric: String, val days: Int)
data class JoinReq(val code: String)
data class LeaderboardRowDto(val rank: Int, val userId: String, val displayName: String, val value: Double, val isMe: Boolean)
data class ChallengeRes(
    val id: String,
    val code: String,
    val name: String,
    val metric: String,
    val startDate: String,
    val endDate: String,
    val isOwner: Boolean,
    val status: String,
    val participants: Int,
    val myRank: Int?,
    val leaderboard: List<LeaderboardRowDto>,
)

data class ErrorRes(val detail: Any?)
