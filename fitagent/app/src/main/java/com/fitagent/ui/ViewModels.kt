package com.fitagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fitagent.container
import com.fitagent.data.HealthStatus
import com.fitagent.data.prefs.Account
import com.fitagent.data.remote.dto.ChallengeRes
import com.fitagent.data.remote.dto.PlanRes
import com.fitagent.data.remote.userMessage
import com.fitagent.domain.Achievement
import com.fitagent.domain.ActiveSession
import com.fitagent.domain.DayActivity
import com.fitagent.domain.IntervalConfig
import com.fitagent.domain.Profile
import com.fitagent.domain.Readiness
import com.fitagent.domain.StreakInfo
import com.fitagent.domain.Suggestion
import com.fitagent.domain.Workout
import com.fitagent.domain.WorkoutType
import com.fitagent.domain.buildDays
import com.fitagent.domain.computeAchievements
import com.fitagent.domain.computeReadiness
import com.fitagent.domain.computeStreak
import com.fitagent.domain.suggestToday
import com.fitagent.domain.workoutsThisWeek
import com.fitagent.service.Scheduler
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

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

    private val meta = combine(c.fitness.status, refreshing, today) { s, r, t -> Triple(s, r, t) }

    val state: StateFlow<HomeState?> = combine(
        c.profiles.profile, c.fitness.deviceDays, c.fitness.workouts, meta,
    ) { profile, device, workouts, (health, isRefreshing, day) ->
        val zone = zone
        val first = listOfNotNull(device.keys.minOrNull(), day.minusDays(29)).min()
        val all = buildDays(first, day, device, workouts, profile.weightKg, zone)
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
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val profile: StateFlow<Profile> = c.profiles.profile
    val healthSource get() = c.health

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
        if (c.auth.account.value != null) viewModelScope.launch { c.cloud.pushProfile() }
    }

    fun completeOnboarding(profile: Profile) {
        updateProfile { profile.copy(onboarded = true) }
        refresh()
    }

    // ── Workout tracking ────────────────────────────────────────────────────

    fun startWorkout(type: WorkoutType, interval: IntervalConfig? = null) {
        if (session.value != null) return
        c.sessions.set(ActiveSession(type, System.currentTimeMillis(), interval = interval))
    }

    fun togglePause() {
        val s = session.value ?: return
        val now = System.currentTimeMillis()
        c.sessions.set(if (s.isPaused) s.resume(now) else s.pause(now))
    }

    fun finishWorkout() {
        val s = session.value ?: return
        val now = System.currentTimeMillis()
        val activeSeconds = s.activeMs(now) / 1000
        c.sessions.set(null)
        if (activeSeconds < 60) {
            _events.tryEmit("Workouts under a minute aren't saved.")
            return
        }
        viewModelScope.launch {
            _finished.value = c.fitness.saveWorkout(s.type, s.startedAtMs, now, activeSeconds)
            if (c.auth.account.value != null) Scheduler.syncSoon(getApplication())
        }
    }

    fun discardWorkout() = c.sessions.set(null)

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
                    if (signedOut) CloudState(account = null, serverUrl = u) else it.copy(account = a, serverUrl = u)
                }
            }
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

    fun leaveChallenge(id: String) = viewModelScope.launch {
        c.cloud.leaveChallenge(id).fold(
            onSuccess = { loadChallenges() },
            onFailure = { _events.tryEmit(it.userMessage()) },
        )
    }
}
