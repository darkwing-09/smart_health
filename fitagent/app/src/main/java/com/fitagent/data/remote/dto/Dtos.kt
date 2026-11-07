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
    val college: String? = null,
    val hostel: String? = null,
    val batch: String? = null,
    val showOnCampus: Boolean? = null,
    val language: String? = null,
    val city: String? = null,
    val gender: String? = null,
    val whatsappNumber: String? = null,
    val whatsappOptIn: Boolean? = null,
    val reportHour: Int? = null,
)

data class ProfileUpdateReq(
    val displayName: String?,
    val timezone: String?,
    val age: Int?,
    val heightCm: Int?,
    val weightKg: Double?,
    val goals: GoalsDto?,
    val college: String? = null,
    val hostel: String? = null,
    val batch: String? = null,
    val showOnCampus: Boolean? = null,
    val language: String? = null,
    val city: String? = null,
    val gender: String? = null,
    val whatsappNumber: String? = null,
    val whatsappOptIn: Boolean? = null,
    val reportHour: Int? = null,
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
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val zoneSeconds: List<Int>? = null,
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
data class SessionDto(
    val day: String,
    val type: String,
    val minutes: Int,
    val intensity: String,
    val focus: String,
    /** "HH:MM" inside a free slot when Exam mode has a timetable. */
    val startTime: String? = null,
)
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
data class LeaderboardRowDto(
    val rank: Int,
    val userId: String,
    val displayName: String,
    val value: Double,
    val isMe: Boolean,
    /** Today's reactions received, by emoji (null from older servers). */
    val kudos: Map<String, Int>? = null,
    /** Emoji I sent this member today. */
    val myKudos: List<String>? = null,
)

data class KudosReq(val toUserId: String, val emoji: String)

data class CampusRowDto(val rank: Int, val name: String, val members: Int, val value: Double, val isMine: Boolean)
data class CampusRes(val scope: String, val days: Int, val myGroup: String?, val rows: List<CampusRowDto>, val minGroupSize: Int)
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

/** Exam mode timetable as the server stores it: free time and exam dates only — class names stay on the phone. */
data class FreeSlotDto(val day: String, val start: String, val end: String)
data class ScheduleDto(val freeSlots: List<FreeSlotDto>, val examDates: List<String>, val bedtime: String?)

data class FoodPhotoReq(val imageBase64: String, val mimeType: String, val note: String)
data class FoodItemDto(val name: String, val portion: String, val kcal: Int, val proteinG: Double, val carbsG: Double, val fatG: Double, val confidence: String)
data class FoodEstimateRes(
    val items: List<FoodItemDto>,
    val note: String,
    val totalKcal: Int,
    val aiPowered: Boolean,
    val safety: String?,
    val safetyMessage: String?,
)

data class WeekPointsDto(val weekStart: String, val points: Int)
data class PerkDto(val id: String, val title: String, val description: String, val partner: String, val cost: Int, val stock: Int)
data class RedemptionDto(val code: String, val perkTitle: String, val points: Int, val createdAt: String)
data class RewardsRes(
    val balance: Int,
    val earnedTotal: Int,
    val weeks: List<WeekPointsDto>,
    val perks: List<PerkDto>,
    val redemptions: List<RedemptionDto>,
)
data class RedeemReq(val perkId: String)
data class OfficialChallengeDto(
    val id: String, val code: String, val name: String, val metric: String, val startDate: String, val endDate: String,
    val participants: Int, val joined: Boolean,
)

data class HealthRes(val status: String, val version: String? = null, val aiCoach: String? = null)
data class SendReportRes(val status: String, val detail: String)
data class ReportStatusRes(
    val whatsappConfigured: Boolean,
    val whatsappNumber: String?,
    val optedIn: Boolean,
    val reportHour: Int,
    val lastDay: String? = null,
    val lastStatus: String? = null,
)
