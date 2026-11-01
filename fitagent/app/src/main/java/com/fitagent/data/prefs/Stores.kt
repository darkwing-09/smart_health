package com.fitagent.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.fitagent.BuildConfig
import com.fitagent.domain.ActiveSession
import com.fitagent.domain.Goals
import com.fitagent.domain.IntervalConfig
import com.fitagent.domain.BusyBlock
import com.fitagent.domain.Profile
import com.fitagent.domain.Timetable
import com.fitagent.domain.WorkoutType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)
    private val _profile = MutableStateFlow(read())
    val profile: StateFlow<Profile> = _profile.asStateFlow()

    @Synchronized
    fun update(transform: (Profile) -> Profile) {
        val next = transform(_profile.value)
        prefs.edit()
            .putString("name", next.name)
            .putInt("age", next.ageYears)
            .putInt("height_cm", next.heightCm)
            .putFloat("weight_kg", next.weightKg.toFloat())
            .putInt("goal_steps", next.goals.dailySteps)
            .putInt("goal_kcal", next.goals.dailyActiveKcal)
            .putInt("goal_workout_min", next.goals.dailyWorkoutMinutes)
            .putInt("goal_weekly", next.goals.weeklyWorkouts)
            .putBoolean("reminders", next.remindersEnabled)
            .putInt("reminder_hour", next.reminderHour)
            .putBoolean("onboarded", next.onboarded)
            .apply()
        _profile.value = next
    }

    private fun read(): Profile {
        val d = Profile()
        return Profile(
            name = prefs.getString("name", d.name).orEmpty(),
            ageYears = prefs.getInt("age", d.ageYears),
            heightCm = prefs.getInt("height_cm", d.heightCm),
            weightKg = prefs.getFloat("weight_kg", d.weightKg.toFloat()).toDouble(),
            goals = Goals(
                dailySteps = prefs.getInt("goal_steps", d.goals.dailySteps),
                dailyActiveKcal = prefs.getInt("goal_kcal", d.goals.dailyActiveKcal),
                dailyWorkoutMinutes = prefs.getInt("goal_workout_min", d.goals.dailyWorkoutMinutes),
                weeklyWorkouts = prefs.getInt("goal_weekly", d.goals.weeklyWorkouts),
            ),
            remindersEnabled = prefs.getBoolean("reminders", d.remindersEnabled),
            reminderHour = prefs.getInt("reminder_hour", d.reminderHour),
            onboarded = prefs.getBoolean("onboarded", d.onboarded),
        )
    }
}

/** Persists the in-progress workout so a process kill never loses it. */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("active_session", Context.MODE_PRIVATE)
    private val _session = MutableStateFlow(read())
    val session: StateFlow<ActiveSession?> = _session.asStateFlow()

    @Synchronized
    fun set(session: ActiveSession?) {
        val e = prefs.edit().clear()
        if (session != null) {
            e.putString("type", session.type.name)
                .putLong("started_at", session.startedAtMs)
                .putLong("paused_total", session.pausedTotalMs)
            session.pausedAtMs?.let { e.putLong("paused_at", it) }
            session.interval?.let { e.putInt("work", it.workSeconds).putInt("rest", it.restSeconds).putInt("rounds", it.rounds) }
        }
        e.commit()
        _session.value = session
    }

    private fun read(): ActiveSession? {
        val type = prefs.getString("type", null) ?: return null
        return ActiveSession(
            type = WorkoutType.fromName(type),
            startedAtMs = prefs.getLong("started_at", System.currentTimeMillis()),
            pausedAtMs = if (prefs.contains("paused_at")) prefs.getLong("paused_at", 0) else null,
            pausedTotalMs = prefs.getLong("paused_total", 0),
            interval = if (prefs.contains("rounds")) IntervalConfig(
                prefs.getInt("work", 40), prefs.getInt("rest", 20), prefs.getInt("rounds", 8),
            ) else null,
        )
    }
}

data class Account(
    val email: String,
    val displayName: String,
)

/**
 * Cloud account state: server URL, tokens and sync bookkeeping. Stored in app-private storage,
 * which is sandboxed per app and excluded from backups (see data_extraction_rules.xml).
 */
class AuthStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val _account = MutableStateFlow(readAccount())
    val account: StateFlow<Account?> = _account.asStateFlow()
    private val _serverUrl = MutableStateFlow(prefs.getString("server_url", null) ?: BuildConfig.DEFAULT_API_URL)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    val accessToken: String? get() = prefs.getString("access", null)
    val refreshToken: String? get() = prefs.getString("refresh", null)
    val lastSyncMs: Long get() = prefs.getLong("last_sync", 0)

    @Synchronized
    fun signIn(email: String, displayName: String, access: String, refresh: String) {
        prefs.edit().putString("email", email).putString("name", displayName)
            .putString("access", access).putString("refresh", refresh).commit()
        _account.value = Account(email, displayName)
    }

    @Synchronized
    fun updateTokens(access: String, refresh: String) {
        prefs.edit().putString("access", access).putString("refresh", refresh).commit()
    }

    @Synchronized
    fun signOut() {
        prefs.edit().remove("email").remove("name").remove("access").remove("refresh")
            .remove("last_sync").remove("deleted").commit()
        _account.value = null
    }

    fun setServerUrl(url: String) {
        val normalized = url.trim().let { if (it.endsWith("/")) it else "$it/" }
        prefs.edit().putString("server_url", normalized).apply()
        _serverUrl.value = normalized
    }

    fun setLastSync(ms: Long) = prefs.edit().putLong("last_sync", ms).apply()

    /** Workout uids deleted locally that the server still needs to hear about. */
    @Synchronized
    fun queueDeletion(uid: String) {
        val set = pendingDeletions() + uid
        prefs.edit().putStringSet("deleted", set).apply()
    }

    fun pendingDeletions(): Set<String> = prefs.getStringSet("deleted", emptySet())!!.toSet()

    @Synchronized
    fun clearDeletions(uids: Set<String>) {
        prefs.edit().putStringSet("deleted", pendingDeletions() - uids).apply()
    }

    private fun readAccount(): Account? {
        val email = prefs.getString("email", null) ?: return null
        if (prefs.getString("refresh", null) == null) return null
        return Account(email, prefs.getString("name", "").orEmpty())
    }
}

/** Bookkeeping for watch → phone sync: Changes API token, last full read, phone step-counter baseline. */
class SyncStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("sync_state", Context.MODE_PRIVATE)
    private val _lastHealthSync = MutableStateFlow(prefs.getLong("last_health_sync", 0))
    /** When Health Connect was last read successfully (0 = never). */
    val lastHealthSync: StateFlow<Long> = _lastHealthSync.asStateFlow()

    var changesToken: String?
        get() = prefs.getString("changes_token", null)
        set(value) { prefs.edit().putString("changes_token", value).apply() }

    var lastFullReadMs: Long
        get() = prefs.getLong("last_full_read", 0)
        set(value) { prefs.edit().putLong("last_full_read", value).apply() }

    var historyImported: Boolean
        get() = prefs.getBoolean("history_imported", false)
        set(value) { prefs.edit().putBoolean("history_imported", value).apply() }

    /** Last raw TYPE_STEP_COUNTER value (counts since boot) and when it was read. */
    var phoneCounter: Pair<Float, Long>?
        get() = if (!prefs.contains("phone_counter")) null
        else prefs.getFloat("phone_counter", 0f) to prefs.getLong("phone_counter_at", 0)
        set(value) {
            val e = prefs.edit()
            if (value == null) e.remove("phone_counter").remove("phone_counter_at")
            else e.putFloat("phone_counter", value.first).putLong("phone_counter_at", value.second)
            e.apply()
        }

    fun markHealthSync(timeMs: Long) {
        prefs.edit().putLong("last_health_sync", timeMs).apply()
        _lastHealthSync.value = timeMs
    }
}

/** Exam mode timetable, kept on the phone (class names included). */
class TimetableStore(context: Context) {
    private val prefs = context.getSharedPreferences("timetable", Context.MODE_PRIVATE)
    private val _timetable = MutableStateFlow(read())
    val timetable: StateFlow<Timetable> = _timetable.asStateFlow()

    @Synchronized
    fun update(transform: (Timetable) -> Timetable) {
        val next = transform(_timetable.value)
        val json = com.google.gson.Gson().toJson(
            StoredTimetable(
                next.busy.map { StoredBlock(it.day.name, it.startMin, it.endMin, it.label) },
                next.examDates.map { it.toString() },
                next.bedtimeMin,
            )
        )
        prefs.edit().putString("json", json).apply()
        _timetable.value = next
    }

    private fun read(): Timetable {
        val json = prefs.getString("json", null) ?: return Timetable()
        return runCatching {
            val s = com.google.gson.Gson().fromJson(json, StoredTimetable::class.java)
            Timetable(
                busy = s.busy.orEmpty().map { BusyBlock(java.time.DayOfWeek.valueOf(it.day), it.start, it.end, it.label.orEmpty()) },
                examDates = s.exams.orEmpty().map { java.time.LocalDate.parse(it) },
                bedtimeMin = s.bedtime,
            )
        }.getOrDefault(Timetable())
    }

    private data class StoredBlock(val day: String, val start: Int, val end: Int, val label: String?)
    private data class StoredTimetable(val busy: List<StoredBlock>?, val exams: List<String>?, val bedtime: Int?)
}
