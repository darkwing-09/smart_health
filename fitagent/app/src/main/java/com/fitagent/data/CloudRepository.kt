package com.fitagent.data

import com.fitagent.data.prefs.AuthStore
import com.fitagent.data.prefs.ProfileStore
import com.fitagent.data.remote.ApiClient
import com.fitagent.data.remote.dto.ChallengeCreateReq
import com.fitagent.data.remote.dto.ChallengeRes
import com.fitagent.data.remote.dto.ChatReq
import com.fitagent.data.remote.dto.ChatRes
import com.fitagent.data.remote.dto.DayDto
import com.fitagent.data.remote.dto.GoalsDto
import com.fitagent.data.remote.dto.JoinReq
import com.fitagent.data.remote.dto.LoginReq
import com.fitagent.data.remote.dto.MessageRes
import com.fitagent.data.remote.dto.PlanRes
import com.fitagent.data.remote.dto.ProfileUpdateReq
import com.fitagent.data.remote.dto.RegisterReq
import com.fitagent.data.remote.dto.SyncReq
import com.fitagent.data.remote.dto.TokenRes
import com.fitagent.data.remote.dto.WorkoutDto
import com.fitagent.domain.Profile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Everything that needs the FitAgent backend: account, sync, AI coach and challenges. */
class CloudRepository(
    private val client: ApiClient,
    private val auth: AuthStore,
    private val profiles: ProfileStore,
    private val fitness: FitnessRepository,
) {
    private val api get() = client.api

    suspend fun register(email: String, password: String, name: String): Result<Unit> = runCatching {
        val tokens = api.register(RegisterReq(email.trim(), password, name.trim(), ZoneId.systemDefault().id))
        completeSignIn(tokens, pushProfile = true)
    }

    suspend fun login(email: String, password: String): Result<Unit> = runCatching {
        completeSignIn(api.login(LoginReq(email.trim(), password)), pushProfile = false)
    }

    private suspend fun completeSignIn(tokens: TokenRes, pushProfile: Boolean) {
        auth.updateTokens(tokens.accessToken, tokens.refreshToken)
        val me = if (pushProfile) api.updateMe(profileUpdate(profiles.profile.value)) else api.me()
        auth.signIn(me.email, me.displayName, tokens.accessToken, tokens.refreshToken)
        if (!pushProfile) {
            // Signing in on a new phone: adopt the goals stored in the cloud.
            profiles.update {
                it.copy(
                    name = it.name.ifBlank { me.displayName },
                    goals = it.goals.copy(
                        dailySteps = me.goals.dailySteps,
                        dailyActiveKcal = me.goals.dailyActiveKcal,
                        dailyWorkoutMinutes = me.goals.dailyWorkoutMinutes,
                        weeklyWorkouts = me.goals.weeklyWorkouts,
                    ),
                )
            }
        }
    }

    suspend fun pushProfile(): Result<Unit> = runCatching {
        if (auth.account.value != null) api.updateMe(profileUpdate(profiles.profile.value))
    }

    private fun profileUpdate(p: Profile) = ProfileUpdateReq(
        displayName = p.name.ifBlank { null },
        timezone = ZoneId.systemDefault().id,
        age = p.ageYears.takeIf { it in 10..100 },
        heightCm = p.heightCm.takeIf { it in 100..250 },
        weightKg = p.weightKg.takeIf { it in 25.0..300.0 },
        goals = GoalsDto(p.goals.dailySteps, p.goals.dailyActiveKcal, p.goals.dailyWorkoutMinutes, p.goals.weeklyWorkouts),
    )

    fun signOut() = auth.signOut()

    suspend fun deleteAccount(): Result<Unit> = runCatching {
        val r = api.deleteMe()
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
        auth.signOut()
    }

    /** Uploads the last 30 days and recent workouts. Idempotent on the server. */
    suspend fun sync(): Result<Unit> = runCatching {
        if (auth.account.value == null) return@runCatching
        val (days, workouts) = fitness.snapshot()
        val zone = ZoneId.systemDefault()
        val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val deletions = auth.pendingDeletions()
        api.sync(
            SyncReq(
                timezone = zone.id,
                days = days.takeLast(30).filter { it.hasData }.map {
                    DayDto(
                        date = it.date.toString(),
                        steps = it.steps.coerceAtMost(200_000),
                        distanceM = it.distanceMeters.coerceAtMost(300_000.0),
                        activeKcal = it.activeKcal.coerceAtMost(15_000),
                        workoutMinutes = it.workoutMinutes.coerceAtMost(1440),
                        restingHr = it.restingHr?.takeIf { hr -> hr in 25..220 },
                        avgHr = it.avgHr?.takeIf { hr -> hr in 25..230 },
                        sleepMinutes = it.sleepMinutes?.coerceAtMost(1440),
                    )
                },
                workouts = workouts.take(500).map {
                    WorkoutDto(
                        clientId = it.uid,
                        type = it.type.name,
                        title = it.title?.take(120),
                        start = iso.format(Instant.ofEpochMilli(it.startMs).atZone(zone)),
                        end = iso.format(Instant.ofEpochMilli(it.endMs).atZone(zone)),
                        activeSeconds = it.activeSeconds.coerceIn(0, 86_400),
                        kcal = (it.kcal * 10).roundToInt() / 10.0,
                        source = it.source.name,
                    )
                },
                deletedWorkoutIds = deletions.toList(),
            )
        )
        auth.clearDeletions(deletions)
        auth.setLastSync(System.currentTimeMillis())
    }

    suspend fun chat(message: String): Result<ChatRes> = runCatching { api.chat(ChatReq(message)) }
    suspend fun messages(): Result<List<MessageRes>> = runCatching { api.messages() }
    suspend fun clearMessages(): Result<Unit> = runCatching { api.clearMessages(); Unit }
    suspend fun generatePlan(): Result<PlanRes> = runCatching { sync(); api.generatePlan() }
    suspend fun latestPlan(): Result<PlanRes?> = runCatching {
        val r = api.latestPlan()
        when {
            r.isSuccessful -> r.body()
            r.code() == 404 -> null
            else -> throw retrofit2.HttpException(r)
        }
    }

    suspend fun challenges(): Result<List<ChallengeRes>> = runCatching { api.challenges() }
    suspend fun createChallenge(name: String, metric: String, days: Int): Result<ChallengeRes> =
        runCatching { api.createChallenge(ChallengeCreateReq(name.trim(), metric, days)) }
    suspend fun joinChallenge(code: String): Result<ChallengeRes> = runCatching { api.join(JoinReq(code.trim())) }
    suspend fun leaveChallenge(id: String): Result<Unit> = runCatching {
        val r = api.leave(id)
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
    }
}
