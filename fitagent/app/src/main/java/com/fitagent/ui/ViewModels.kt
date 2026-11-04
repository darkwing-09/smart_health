package com.fitagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fitagent.container
import com.fitagent.data.DataSource
import com.fitagent.data.ble.BleState
import com.fitagent.data.local.FoodEntity
import com.fitagent.data.local.MoodEntity
import com.fitagent.data.HealthStatus
import com.fitagent.data.WatchState
import com.fitagent.data.SyncEvent
import com.fitagent.data.health.CompatibilityRow
import com.fitagent.data.prefs.Account
import com.fitagent.data.remote.dto.CampusRes
import com.fitagent.data.remote.dto.OfficialChallengeDto
import com.fitagent.data.remote.dto.RewardsRes
import com.fitagent.data.remote.dto.ChallengeRes
import com.fitagent.data.remote.dto.PlanRes
import com.fitagent.data.remote.userMessage
import com.fitagent.domain.Achievement
import com.fitagent.domain.ActiveSession
import com.fitagent.domain.DayActivity
import com.fitagent.domain.HrBucket
import com.fitagent.domain.SleepSegment
import com.fitagent.domain.IntervalConfig
import com.fitagent.domain.Profile
import com.fitagent.domain.Readiness
import com.fitagent.domain.StreakInfo
import com.fitagent.domain.FoodEntry
import com.fitagent.domain.Meal
import com.fitagent.domain.MoodEntry
import com.fitagent.domain.RoutePoint
import com.fitagent.domain.hrMax
import com.fitagent.domain.hrSummary
import com.fitagent.domain.mealFor
import com.fitagent.domain.Routine
import com.fitagent.domain.Suggestion
import com.fitagent.domain.Timetable
import com.fitagent.domain.parseIcs
import com.fitagent.domain.Workout
import com.fitagent.domain.WorkoutType
import com.fitagent.domain.buildDays
import com.fitagent.domain.computeAchievements
import com.fitagent.domain.computeReadiness
import com.fitagent.domain.computeStreak
import com.fitagent.domain.suggestToday
import com.fitagent.domain.workoutsThisWeek
import com.fitagent.service.RouteService
import com.fitagent.service.Scheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class HomeState(
    val profile: Profile,
    val health: HealthStatus,
    val refreshing: Boolean,
    val today: LocalDate,
    /** Last 30 days, oldest first, today last. */
    val days: List<DayActivity>,
    val todayActivity: DayActivity,
    val readiness: Readiness,
    val suggestion: Suggestion,
    val streak: StreakInfo,
    val workouts: List<Workout>,
    val weekWorkouts: Int,
    val achievements: List<Achievement>,
    /** Up to 90 days, oldest first, today last — for trend charts. */
    val history: List<DayActivity> = days,
    /** Readiness as it was on each of the last 30 days (oldest first). */
    val readinessHistory: List<Pair<LocalDate, Readiness>> = emptyList(),
    /** When watch data was last read successfully (0 = never). */
    val lastWatchSync: Long = 0,
    /** Whether a real watch feeds the app; nothing body-related is shown without one. */
    val watch: WatchState = WatchState.None,
) {
    /** Readiness exists only when the watch measured sleep or the heart — never the "no data" default. */
    val hasReadiness: Boolean get() = watch.hasWatch && readiness.components.keys.any { it != "load" }
}

sealed interface FoodPhotoState {
    data object Idle : FoodPhotoState
    data object Analysing : FoodPhotoState
    /** Editable estimate; the user confirms before anything is saved. */
    data class Review(val items: List<FoodEntry>, val note: String) : FoodPhotoState
    data class Message(val text: String, val supportive: Boolean = false) : FoodPhotoState
}

sealed interface CompatibilityState {
    data object Idle : CompatibilityState
    data object Running : CompatibilityState
    /** Each row: a data type, with record counts per app and the apps' display names. */
    data class Done(val rows: List<Pair<CompatibilityRow, Map<String, String>>>) : CompatibilityState
    data class Failed(val message: String) : CompatibilityState
}

/** On-device fitness: Health Connect data, goals, streaks, workout tracking. Works fully offline. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val refreshing = MutableStateFlow(false)
    private val today = MutableStateFlow(LocalDate.now())

    val session: StateFlow<ActiveSession?> = c.sessions.session
    private val _finished = MutableStateFlow<Workout?>(null)
    val finished: StateFlow<Workout?> = _finished.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val meta = combine(c.fitness.status, refreshing, today, c.fitness.lastHealthSync, c.fitness.watchState) { s, r, t, sync, watch ->
        Meta(s, r, t, sync, watch)
    }

    private data class Meta(val health: HealthStatus, val refreshing: Boolean, val today: LocalDate, val lastSync: Long, val watch: WatchState)

    val state: StateFlow<HomeState?> = combine(
        c.profiles.profile, c.fitness.deviceDays, c.fitness.workouts, meta,
    ) { profile, device, workouts, (health, isRefreshing, day, lastSync, watch) ->
        val zone = zone
        val first = listOfNotNull(device.keys.minOrNull(), day.minusDays(29)).min()
        val all = buildDays(first, day, device, workouts, profile.weightKg, zone)
        val history = all.takeLast(90)
        val todayActivity = all.last()
        val readiness = computeReadiness(all, workouts, day, zone)
        val streak = computeStreak(all, profile.goals, day)
        HomeState(
            profile = profile,
            health = health,
            refreshing = isRefreshing,
            today = day,
            days = all.takeLast(30),
            todayActivity = todayActivity,
            readiness = readiness,
            suggestion = suggestToday(todayActivity, readiness, workouts, profile.goals, day, zone),
            streak = streak,
            workouts = workouts,
            weekWorkouts = workoutsThisWeek(workouts, day, zone),
            achievements = computeAchievements(all, workouts, streak, zone),
            history = history,
            readinessHistory = (29 downTo 0).map { ago ->
                val d = day.minusDays(ago.toLong())
                d to computeReadiness(all.filter { !it.date.isAfter(d) }, workouts, d, zone)
            },
            lastWatchSync = lastSync,
            watch = watch,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val profile: StateFlow<Profile> = c.profiles.profile
    val healthSource get() = c.health

    // ── Detail data for charts (watch data stays on the phone) ───────────────
    @OptIn(ExperimentalCoroutinesApi::class)
    val hourlySteps: StateFlow<List<Long>> = today.flatMapLatest { c.fitness.hourlySteps(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), List(24) { 0L })

    @OptIn(ExperimentalCoroutinesApi::class)
    val heartToday: StateFlow<List<HrBucket>> = today.flatMapLatest { c.fitness.heartRate(it, zone) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Heart-rate buckets for the last 7 days, for weekly zone minutes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val heartWeek: StateFlow<List<HrBucket>> = today.flatMapLatest { d ->
        c.fitness.heartRateRange(d.minusDays(6), d, zone)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val lastNight: StateFlow<List<SleepSegment>> = today.flatMapLatest { c.fitness.sleepSegments(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sources: StateFlow<List<DataSource>> = c.fitness.sources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val syncLog: StateFlow<List<SyncEvent>> = c.fitness.syncLog()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _compatibility = MutableStateFlow<CompatibilityState>(CompatibilityState.Idle)
    val compatibility: StateFlow<CompatibilityState> = _compatibility.asStateFlow()

    fun runCompatibilityCheck() {
        if (_compatibility.value is CompatibilityState.Running) return
        viewModelScope.launch {
            _compatibility.value = CompatibilityState.Running
            _compatibility.value = runCatching { c.fitness.compatibility() }.fold(
                onSuccess = { CompatibilityState.Done(it) },
                onFailure = { CompatibilityState.Failed(it.message ?: "Couldn't read Health Connect") },
            )
        }
    }

    val lastCloudSync: Long get() = c.auth.lastSyncMs

    // ── Mood (stays on the phone) ────────────────────────────────────────────
    val moods: StateFlow<List<MoodEntry>> = c.db.mood().observe()
        .map { rows -> rows.map { MoodEntry(LocalDate.ofEpochDay(it.epochDay), it.score, it.tags.split(',').filter(String::isNotBlank).toSet()) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveMood(score: Int, tags: Set<String>) {
        viewModelScope.launch {
            c.db.mood().upsert(MoodEntity(LocalDate.now().toEpochDay(), score.coerceIn(1, 5), tags.joinToString(",")))
        }
    }

    // ── Food log (stays on the phone) ────────────────────────────────────────
    @OptIn(ExperimentalCoroutinesApi::class)
    val foods: StateFlow<List<FoodEntry>> = today.flatMapLatest { d -> c.db.food().observeSince(d.minusDays(6).toEpochDay()) }
        .map { rows -> rows.map { FoodEntry(it.id, LocalDate.ofEpochDay(it.epochDay), Meal.valueOf(it.meal), it.name, it.portion, it.kcal, it.proteinG, it.carbsG, it.fatG, it.fromPhoto) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _foodPhoto = MutableStateFlow<FoodPhotoState>(FoodPhotoState.Idle)
    val foodPhoto: StateFlow<FoodPhotoState> = _foodPhoto.asStateFlow()

    fun addFoods(entries: List<FoodEntry>) = viewModelScope.launch {
        c.db.food().insert(entries.map {
            FoodEntity(0, it.date.toEpochDay(), it.meal.name, it.name.take(60), it.portion.take(40), it.kcal.coerceIn(0, 3000),
                it.proteinG.coerceIn(0.0, 300.0), it.carbsG.coerceIn(0.0, 600.0), it.fatG.coerceIn(0.0, 300.0), it.fromPhoto)
        })
        _foodPhoto.value = FoodPhotoState.Idle
    }

    fun deleteFood(id: Long) = viewModelScope.launch { c.db.food().delete(id) }

    /** Sends the photo for an estimate. The file is deleted right after it's read. */
    fun estimateFoodPhoto(file: java.io.File, note: String) {
        _foodPhoto.value = FoodPhotoState.Analysing
        viewModelScope.launch {
            val jpeg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { com.fitagent.ui.screens.downscaleJpeg(file) }.also { file.delete() }.getOrNull()
            }
            if (jpeg == null) { _foodPhoto.value = FoodPhotoState.Message("Couldn't read the photo — try again."); return@launch }
            if (c.auth.account.value == null) { _foodPhoto.value = FoodPhotoState.Message("Sign in to estimate meals from photos, or add items from the list."); return@launch }
            c.cloud.estimateFood(jpeg, note).fold(
                onSuccess = { r ->
                    val now = java.time.LocalDateTime.now()
                    _foodPhoto.value = when {
                        r.safety != null -> FoodPhotoState.Message(r.safetyMessage ?: "", supportive = true)
                        r.items.isEmpty() && !r.aiPowered -> FoodPhotoState.Message("The AI coach is offline right now — add items from the list instead.")
                        r.items.isEmpty() -> FoodPhotoState.Message("No food found in that photo. Try again with the plate in view.")
                        else -> FoodPhotoState.Review(r.items.map {
                            FoodEntry(0, now.toLocalDate(), mealFor(now.hour), it.name, it.portion, it.kcal, it.proteinG, it.carbsG, it.fatG, fromPhoto = true)
                        }, r.note)
                    }
                },
                onFailure = { _foodPhoto.value = FoodPhotoState.Message(it.userMessage()) },
            )
        }
    }

    fun dismissFoodPhoto() { _foodPhoto.value = FoodPhotoState.Idle }

    // ── Exam mode ────────────────────────────────────────────────────────────
    val timetable: StateFlow<Timetable> = c.timetable.timetable

    fun updateTimetable(transform: (Timetable) -> Timetable) {
        c.timetable.update(transform)
        if (c.auth.account.value != null) viewModelScope.launch { c.cloud.pushSchedule() }
    }

    /** Merges classes and exams from a calendar export (.ics) into the timetable. */
    fun importIcs(text: String) {
        val r = parseIcs(text, zone)
        if (r.busy.isEmpty() && r.exams.isEmpty()) {
            _events.tryEmit("No weekly classes or exams found in that file.")
            return
        }
        updateTimetable { t -> t.copy(busy = (t.busy + r.busy).distinct(), examDates = (t.examDates + r.exams).distinct()) }
        _events.tryEmit("Imported ${r.busy.size} class slots and ${r.exams.size} exams.")
    }

    /** Full re-read of the last 30 days from the watch app, plus a cloud sync when signed in. */
    fun syncNow() {
        if (refreshing.value) return
        today.value = LocalDate.now()
        viewModelScope.launch {
            refreshing.value = true
            c.fitness.refresh(full = true).onFailure { _events.tryEmit("Couldn't read watch data: ${it.message}") }
            refreshing.value = false
            if (c.auth.account.value != null) Scheduler.syncSoon(getApplication())
        }
    }

    fun refresh() {
        if (refreshing.value) return
        today.value = LocalDate.now()
        viewModelScope.launch {
            refreshing.value = true
            c.fitness.refresh().onFailure { _events.tryEmit("Couldn't read Health Connect: ${it.message}") }
            refreshing.value = false
            if (c.auth.account.value != null) Scheduler.syncSoon(getApplication())
        }
    }

    fun updateProfile(transform: (Profile) -> Profile) {
        c.profiles.update(transform)
        Scheduler.scheduleReminder(getApplication(), c.profiles.profile.value)
        Scheduler.scheduleNudges(getApplication(), c.profiles.profile.value)
        if (c.auth.account.value != null) viewModelScope.launch { c.cloud.pushProfile() }
    }

    fun completeOnboarding(profile: Profile) {
        updateProfile { profile.copy(onboarded = true) }
        refresh()
    }

    // ── Workout tracking ────────────────────────────────────────────────────

    fun startWorkout(type: WorkoutType, interval: IntervalConfig? = null) {
        if (session.value != null) return
        val s = ActiveSession(type, System.currentTimeMillis(), interval = interval)
        c.sessions.set(s)
        startRoute()
    }

    /** Starts GPS recording for outdoor sessions when location is allowed (called again after the permission prompt). */
    fun startRoute() {
        val s = session.value ?: return
        if (s.type.usesGps) RouteService.start(getApplication(), s.startedAtMs)
    }

    /** Route of the just-finished workout, for the summary. */
    private val _finishedRoute = MutableStateFlow<List<RoutePoint>>(emptyList())
    val finishedRoute: StateFlow<List<RoutePoint>> = _finishedRoute.asStateFlow()

    fun togglePause() {
        val s = session.value ?: return
        val now = System.currentTimeMillis()
        c.sessions.set(if (s.isPaused) s.resume(now) else s.pause(now))
    }

    // ── Live heart rate (BLE) ────────────────────────────────────────────────
    val heartRate: StateFlow<BleState> = c.heartRate.state
    /** (time ms, bpm) samples for the current session; kept in memory while the player runs. */
    private val hrSamples = mutableListOf<Pair<Long, Int>>()
    private val _liveHr = MutableStateFlow<List<Int>>(emptyList())
    val liveHr: StateFlow<List<Int>> = _liveHr.asStateFlow()

    init {
        viewModelScope.launch {
            c.heartRate.state.collect { st ->
                val bpm = (st as? BleState.Connected)?.bpm ?: return@collect
                val s = session.value ?: return@collect
                if (s.isPaused) return@collect
                hrSamples += System.currentTimeMillis() to bpm
                _liveHr.value = (_liveHr.value + bpm).takeLast(120)
            }
        }
    }

    fun scanHeartRate() = c.heartRate.scan()
    fun connectHeartRate(address: String) = c.heartRate.connect(address)
    fun disconnectHeartRate() = c.heartRate.disconnect()

    fun finishWorkout() {
        val s = session.value ?: return
        val now = System.currentTimeMillis()
        val activeSeconds = s.activeMs(now) / 1000
        c.sessions.set(null)
        val hr = hrSummary(hrSamples.filter { it.first >= s.startedAtMs }, hrMax(c.profiles.profile.value.ageYears))
        RouteService.stop(getApplication())
        hrSamples.clear(); _liveHr.value = emptyList()
        if (activeSeconds < 60) {
            _events.tryEmit("Workouts under a minute aren't saved.")
            return
        }
        viewModelScope.launch {
            _finishedRoute.value = c.db.route().points(s.startedAtMs).map { RoutePoint(it.timeMs, it.lat, it.lon, it.accuracy) }
            _finished.value = c.fitness.saveWorkout(s.type, s.startedAtMs, now, activeSeconds, hr)
            if (c.auth.account.value != null) Scheduler.syncSoon(getApplication())
        }
    }

    fun discardWorkout() {
        val s = session.value
        c.sessions.set(null); hrSamples.clear(); _liveHr.value = emptyList()
        RouteService.stop(getApplication())
        if (s != null) viewModelScope.launch { c.db.route().delete(s.startedAtMs) }
    }

    /** Saves a camera-counted set as a strength (or HIIT for jumping jacks) workout. */
    fun saveReps(exercise: com.fitagent.domain.RepExercise, reps: Int, seconds: Long) {
        if (reps <= 0) return
        val now = System.currentTimeMillis()
        val type = if (exercise == com.fitagent.domain.RepExercise.JUMPING_JACK) WorkoutType.HIIT else WorkoutType.STRENGTH
        val secs = seconds.coerceAtLeast(60)
        viewModelScope.launch {
            _finished.value = c.fitness.saveWorkout(type, now - secs * 1000, now, secs, title = "$reps ${exercise.label.lowercase()}")
        }
    }

    /** Saves a guided library routine as a workout of the routine's type. */
    fun saveRoutine(routine: Routine, activeSeconds: Long) {
        if (activeSeconds < 60) return
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            _finished.value = c.fitness.saveWorkout(routine.type, now - activeSeconds * 1000, now, activeSeconds)
            if (c.auth.account.value != null) Scheduler.syncSoon(getApplication())
        }
    }

    fun dismissSummary() { _finished.value = null }

    fun logPastWorkout(type: WorkoutType, minutes: Int, daysAgo: Int) {
        val end = java.time.LocalDateTime.now().minusDays(daysAgo.toLong()).atZone(zone).toInstant().toEpochMilli()
        val start = end - minutes * 60_000L
        viewModelScope.launch {
            c.fitness.saveWorkout(type, start, end, minutes * 60L)
            _events.tryEmit("${type.label} logged — nice work!")
            if (c.auth.account.value != null) Scheduler.syncSoon(getApplication())
        }
    }

    fun deleteWorkout(id: Long) = viewModelScope.launch {
        c.fitness.deleteWorkout(id)
        if (c.auth.account.value != null) Scheduler.syncSoon(getApplication())
    }
}

data class ChatMessage(val role: String, val text: String, val safety: String? = null, val pending: Boolean = false)

data class CloudState(
    val account: Account? = null,
    val server: com.fitagent.data.ServerStatus = com.fitagent.data.ServerStatus.Checking,
    val serverUrl: String = "",
    val authBusy: Boolean = false,
    val authError: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val sending: Boolean = false,
    val coachError: String? = null,
    val coachLoaded: Boolean = false,
    val plan: PlanRes? = null,
    val planBusy: Boolean = false,
    val challenges: List<ChallengeRes> = emptyList(),
    val challengesLoading: Boolean = false,
    val challengesError: String? = null,
    val campus: CampusRes? = null,
    val campusScope: String = "hostel",
    val campusLoading: Boolean = false,
    /** Join code from an invite link, waiting to be confirmed on the Squad tab. */
    val pendingJoinCode: String? = null,
    val rewards: RewardsRes? = null,
    val rewardsLoading: Boolean = false,
    val official: List<OfficialChallengeDto> = emptyList(),
)

/** Features that need the backend: account, AI coach and challenges. */
class CloudViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val _state = MutableStateFlow(CloudState())
    val state: StateFlow<CloudState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            combine(c.auth.account, c.auth.serverUrl) { a, u -> a to u }.collect { (a, u) ->
                val signedOut = a == null && _state.value.account != null
                _state.update {
                    if (signedOut) CloudState(account = null, serverUrl = u, server = it.server) else it.copy(account = a, serverUrl = u)
                }
            }
        }
        viewModelScope.launch { c.cloud.server.collect { st -> _state.update { it.copy(server = st) } } }
        // Keep checking the server: every minute while online, every 15 s while it can't be reached.
        viewModelScope.launch {
            c.auth.serverUrl.collectLatest {
                while (true) {
                    val st = c.cloud.checkServer()
                    kotlinx.coroutines.delay(if (st is com.fitagent.data.ServerStatus.Online) 60_000 else 15_000)
                }
            }
        }
    }

    fun retryServer() { viewModelScope.launch { c.cloud.checkServer() } }

    fun sendReportNow() {
        viewModelScope.launch {
            c.cloud.sendReportNow().fold(
                onSuccess = { r ->
                    _events.tryEmit(when (r.status) {
                        "sent" -> "Report sent to your WhatsApp"
                        "dry_run" -> "Server made the report, but WhatsApp isn't set up on the server yet"
                        else -> "WhatsApp couldn't deliver it: ${r.detail}"
                    })
                },
                onFailure = { e -> _events.tryEmit(e.userMessage()) },
            )
        }
    }

    fun signIn(email: String, password: String) = auth { c.cloud.login(email, password) }

    fun register(email: String, password: String, name: String) = auth { c.cloud.register(email, password, name) }

    private fun auth(block: suspend () -> Result<Unit>) {
        _state.update { it.copy(authBusy = true, authError = null) }
        viewModelScope.launch {
            block().fold(
                onSuccess = {
                    _state.update { it.copy(authBusy = false) }
                    _events.tryEmit("Signed in — syncing your activity")
                    c.fitness.refresh()
                    c.cloud.sync()
                },
                onFailure = { e -> _state.update { it.copy(authBusy = false, authError = e.userMessage()) } },
            )
        }
    }

    fun clearAuthError() = _state.update { it.copy(authError = null) }

    fun signOut() = c.cloud.signOut()

    fun deleteAccount() = viewModelScope.launch {
        c.cloud.deleteAccount().fold(
            onSuccess = { _events.tryEmit("Your cloud account and data were deleted.") },
            onFailure = { _events.tryEmit(it.userMessage()) },
        )
    }

    fun setServerUrl(url: String) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            _events.tryEmit("Server address must start with http:// or https://")
            return
        }
        c.auth.setServerUrl(trimmed)
        _events.tryEmit("Server updated")
    }

    // ── Coach ───────────────────────────────────────────────────────────────

    fun loadCoach() {
        if (_state.value.account == null) return
        viewModelScope.launch {
            c.cloud.messages().onSuccess { list ->
                _state.update { s ->
                    s.copy(messages = list.map { ChatMessage(it.role, it.content, it.safety) }, coachLoaded = true,
                        coachError = null)
                }
            }.onFailure { e -> _state.update { it.copy(coachError = e.userMessage(), coachLoaded = true) } }
            c.cloud.latestPlan().onSuccess { p -> _state.update { it.copy(plan = p) } }
        }
    }

    fun send(text: String) {
        val msg = text.trim()
        if (msg.isEmpty() || _state.value.sending) return
        _state.update {
            it.copy(sending = true, coachError = null,
                messages = it.messages + ChatMessage("user", msg) + ChatMessage("assistant", "", pending = true))
        }
        viewModelScope.launch {
            c.cloud.sync() // make sure the coach sees today's numbers
            c.cloud.chat(msg).fold(
                onSuccess = { r ->
                    _state.update { s ->
                        s.copy(sending = false, messages = s.messages.dropLast(1) + ChatMessage("assistant", r.reply, r.safety))
                    }
                },
                onFailure = { e ->
                    _state.update { s -> s.copy(sending = false, coachError = e.userMessage(), messages = s.messages.dropLast(2)) }
                },
            )
        }
    }

    fun clearChat() = viewModelScope.launch {
        c.cloud.clearMessages().onSuccess { _state.update { it.copy(messages = emptyList()) } }
            .onFailure { _events.tryEmit(it.userMessage()) }
    }

    fun generatePlan() {
        if (_state.value.planBusy) return
        _state.update { it.copy(planBusy = true) }
        viewModelScope.launch {
            c.cloud.generatePlan().fold(
                onSuccess = { p -> _state.update { it.copy(plan = p, planBusy = false) } },
                onFailure = { e -> _state.update { it.copy(planBusy = false) }; _events.tryEmit(e.userMessage()) },
            )
        }
    }

    // ── Squad / challenges ──────────────────────────────────────────────────

    fun loadChallenges() {
        if (_state.value.account == null) return
        _state.update { it.copy(challengesLoading = true) }
        viewModelScope.launch {
            c.cloud.sync()
            c.cloud.challenges().fold(
                onSuccess = { list -> _state.update { it.copy(challenges = list, challengesLoading = false, challengesError = null) } },
                onFailure = { e -> _state.update { it.copy(challengesLoading = false, challengesError = e.userMessage()) } },
            )
        }
    }

    fun createChallenge(name: String, metric: String, days: Int, onDone: (ChallengeRes) -> Unit) = viewModelScope.launch {
        c.cloud.createChallenge(name, metric, days).fold(
            onSuccess = { onDone(it); loadChallenges() },
            onFailure = { _events.tryEmit(it.userMessage()) },
        )
    }

    fun joinChallenge(code: String, onDone: () -> Unit) = viewModelScope.launch {
        c.cloud.joinChallenge(code).fold(
            onSuccess = { _events.tryEmit("Joined “${it.name}”"); onDone(); loadChallenges() },
            onFailure = { _events.tryEmit(it.userMessage()) },
        )
    }

    fun toggleKudos(challengeId: String, toUserId: String, emoji: String, remove: Boolean) = viewModelScope.launch {
        c.cloud.kudos(challengeId, toUserId, emoji, remove).fold(
            onSuccess = { updated -> _state.update { st -> st.copy(challenges = st.challenges.map { if (it.id == updated.id) updated else it }) } },
            onFailure = { _events.tryEmit(it.userMessage()) },
        )
    }

    fun loadCampus(scope: String = _state.value.campusScope) {
        if (_state.value.account == null) return
        _state.update { it.copy(campusScope = scope, campusLoading = true) }
        viewModelScope.launch {
            val r = c.cloud.campus(scope)
            _state.update { it.copy(campus = r.getOrNull() ?: it.campus.takeIf { c -> c?.scope == scope }, campusLoading = false) }
        }
    }

    fun loadRewards() {
        if (_state.value.account == null) return
        _state.update { it.copy(rewardsLoading = true) }
        viewModelScope.launch {
            val r = c.cloud.rewards()
            _state.update { it.copy(rewards = r.getOrNull() ?: it.rewards, rewardsLoading = false) }
            r.exceptionOrNull()?.let { _events.tryEmit(it.userMessage()) }
        }
    }

    fun redeem(perkId: String) = viewModelScope.launch {
        c.cloud.redeem(perkId).fold(
            onSuccess = { _events.tryEmit("Redeemed! Your code: ${it.code}"); loadRewards() },
            onFailure = { _events.tryEmit(it.userMessage()) },
        )
    }

    fun loadOfficial() = viewModelScope.launch {
        c.cloud.officialChallenges().onSuccess { list -> _state.update { it.copy(official = list) } }
    }

    fun inviteLink(code: String): String = c.cloud.inviteLink(code)

    fun openInvite(code: String) = _state.update { it.copy(pendingJoinCode = code.uppercase().filter(Char::isLetterOrDigit).take(8)) }
    fun clearInvite() = _state.update { it.copy(pendingJoinCode = null) }

    fun leaveChallenge(id: String) = viewModelScope.launch {
        c.cloud.leaveChallenge(id).fold(
            onSuccess = { loadChallenges() },
            onFailure = { _events.tryEmit(it.userMessage()) },
        )
    }
}
