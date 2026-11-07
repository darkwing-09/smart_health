package com.fitagent.data

import com.fitagent.data.prefs.AuthStore
import com.fitagent.data.prefs.ProfileStore
import com.fitagent.data.remote.ApiClient
import com.fitagent.data.remote.userMessage
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
import com.fitagent.data.remote.dto.FreeSlotDto
import com.fitagent.data.remote.dto.CampusRes
import com.fitagent.data.remote.dto.OfficialChallengeDto
import com.fitagent.data.remote.dto.RedeemReq
import com.fitagent.data.remote.dto.RedemptionDto
import com.fitagent.data.remote.dto.RewardsRes
import com.fitagent.data.remote.dto.FoodEstimateRes
import com.fitagent.data.remote.dto.FoodPhotoReq
import com.fitagent.data.remote.dto.KudosReq
import com.fitagent.data.remote.dto.ScheduleDto
import com.fitagent.data.prefs.TimetableStore
import com.fitagent.domain.code
import com.fitagent.domain.freeSlots
import com.fitagent.domain.hhmm
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
    private val timetable: TimetableStore,
) {
    private val api get() = client.api

    private val _server = kotlinx.coroutines.flow.MutableStateFlow<ServerStatus>(ServerStatus.Checking)
    /** Whether the FitAgent server answers /health. Coach, squad, cloud sync and WhatsApp reports need it. */
    val server: kotlinx.coroutines.flow.StateFlow<ServerStatus> = _server

    suspend fun checkServer(): ServerStatus {
        val s = runCatching { api.health() }.fold(
            { if (it.status == "ok") ServerStatus.Online else ServerStatus.Offline("The server is up but not healthy (${it.status}).") },
            { ServerStatus.Offline(it.userMessage()) },
        )
        _server.value = s
        return s
    }

    /** Asks the server to send today's PDF report to the user's WhatsApp now. */
    suspend fun sendReportNow(): Result<com.fitagent.data.remote.dto.SendReportRes> = runCatching {
        pushProfile().getOrThrow() // the server needs the current number first
        api.sendReport()
    }

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
                    city = it.city.ifBlank { me.city.orEmpty() },
                    whatsappNumber = it.whatsappNumber.ifBlank { me.whatsappNumber.orEmpty() },
                    whatsappReports = it.whatsappReports || me.whatsappOptIn == true,
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
        college = p.college.trim(),
        hostel = p.hostel.trim(),
        batch = p.batch.trim(),
        showOnCampus = p.showOnCampus,
        language = p.language,
        city = p.city.trim(),
        gender = p.gender.ifBlank { null },
        whatsappNumber = p.whatsappNumber,
        whatsappOptIn = p.whatsappReports && p.whatsappNumber.isNotBlank(),
        reportHour = p.reportHour.coerceIn(0, 23),
    )

    fun signOut() = auth.signOut()

    suspend fun deleteAccount(): Result<Unit> = runCatching {
        val r = api.deleteMe()
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
        auth.signOut()
    }

    /** Uploads the last 30 days and recent workouts. Idempotent on the server. */
    suspend fun sync(): Result<Unit> {
        if (auth.account.value == null) return Result.success(Unit)
        val started = System.currentTimeMillis()
        val result = upload()
        fitness.log(
            FitnessRepository.KIND_CLOUD, started, result.isSuccess, result.getOrDefault(0),
            result.exceptionOrNull()?.let { it.message?.take(160) ?: it::class.java.simpleName },
        )
        return result.map { }
    }

    /** Returns the number of days + workouts sent. */
    private suspend fun upload(): Result<Int> = runCatching {
        val (days, workouts) = fitness.snapshot()
        val zone = ZoneId.systemDefault()
        val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val deletions = auth.pendingDeletions()
        val res = api.sync(
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
                        hrMin = it.hrMin?.takeIf { hr -> hr in 25..230 },
                        hrMax = it.hrMax?.takeIf { hr -> hr in 25..250 },
                        floors = it.floors?.coerceIn(0, 1000),
                        vo2max = it.vo2max?.takeIf { v -> v in 10.0..95.0 },
                        sleepDeepMin = it.sleepStages?.deep,
                        sleepRemMin = it.sleepStages?.rem,
                        sleepLightMin = it.sleepStages?.light,
                        sleepAwakeMin = it.sleepStages?.awake,
                        bedTime = it.bedMs?.let { ms -> iso.format(Instant.ofEpochMilli(ms).atZone(zone)) },
                        wakeTime = it.wakeMs?.let { ms -> iso.format(Instant.ofEpochMilli(ms).atZone(zone)) },
                        hrvMs = it.night.hrvMs?.takeIf { v -> v in 1.0..300.0 },
                        spo2Avg = it.night.spo2Avg?.takeIf { v -> v in 50.0..100.0 },
                        spo2Min = it.night.spo2Min?.takeIf { v -> v in 50.0..100.0 },
                        respRate = it.night.respiratoryRate?.takeIf { v -> v in 4.0..60.0 },
                        skinTempDelta = it.night.skinTempDelta?.takeIf { v -> v in -5.0..5.0 },
                        stepsSource = it.stepsSource?.take(120),
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
                        avgHr = it.avgHr?.takeIf { hr -> hr in 30..230 },
                        maxHr = it.maxHr?.takeIf { hr -> hr in 30..250 },
                        zoneSeconds = it.zoneSeconds,
                    )
                },
                deletedWorkoutIds = deletions.toList(),
            )
        )
        auth.clearDeletions(deletions)
        auth.setLastSync(System.currentTimeMillis())
        res.daysUpserted + res.workoutsUpserted
    }

    suspend fun chat(message: String): Result<ChatRes> = runCatching { api.chat(ChatReq(message)) }
    suspend fun messages(): Result<List<MessageRes>> = runCatching { api.messages() }
    suspend fun clearMessages(): Result<Unit> = runCatching { api.clearMessages(); Unit }
    suspend fun generatePlan(): Result<PlanRes> = runCatching { sync(); pushSchedule(); api.generatePlan() }

    /** Sends Exam mode free slots, exam dates and bedtime — never class names. */
    suspend fun pushSchedule(): Result<Unit> = runCatching {
        if (auth.account.value == null) return@runCatching
        val t = timetable.timetable.value
        api.putSchedule(
            ScheduleDto(
                freeSlots = if (t.busy.isEmpty()) emptyList() else freeSlots(t).map { FreeSlotDto(it.day.code, hhmm(it.startMin), hhmm(it.endMin)) },
                examDates = t.examDates.map { it.toString() },
                bedtime = t.bedtimeMin?.let(::hhmm),
            )
        )
    }
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
    suspend fun kudos(challengeId: String, toUserId: String, emoji: String, remove: Boolean): Result<ChallengeRes> = runCatching {
        if (remove) api.removeKudos(challengeId, toUserId, emoji) else api.giveKudos(challengeId, KudosReq(toUserId, emoji))
    }

    /** Sends one JPEG for an estimate; the server never stores it. */
    suspend fun estimateFood(jpeg: ByteArray, note: String): Result<FoodEstimateRes> = runCatching {
        api.estimateFood(FoodPhotoReq(android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP), "image/jpeg", note))
    }

    suspend fun rewards(): Result<RewardsRes> = runCatching { sync(); api.rewards() }
    suspend fun redeem(perkId: String): Result<RedemptionDto> = runCatching { api.redeem(RedeemReq(perkId)) }
    suspend fun officialChallenges(): Result<List<OfficialChallengeDto>> = runCatching { api.officialChallenges() }

    suspend fun campus(scope: String): Result<CampusRes> = runCatching { api.campus(scope) }

    /** Public invite link for a challenge (opens the app via App Links, or a web page with the code). */
    fun inviteLink(code: String): String = auth.serverUrl.value.trimEnd('/') + "/j/" + code

    suspend fun leaveChallenge(id: String): Result<Unit> = runCatching {
        val r = api.leave(id)
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
    }
}

sealed interface ServerStatus {
    data object Checking : ServerStatus
    data object Online : ServerStatus
    data class Offline(val message: String) : ServerStatus
}
