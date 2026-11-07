package com.fitagent.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fitagent.R
import com.fitagent.container
import com.fitagent.domain.Profile
import com.fitagent.data.WatchState
import com.fitagent.domain.NudgeKind
import com.fitagent.domain.watchReminder
import com.fitagent.domain.bedtimeMessage
import com.fitagent.domain.bedtimeTarget
import com.fitagent.domain.canPost
import com.fitagent.domain.computeReadiness
import com.fitagent.domain.computeStreak
import com.fitagent.domain.eveningReminder
import com.fitagent.domain.morningMessage
import com.fitagent.domain.shouldNudgeStudyBreak
import com.fitagent.domain.suggestToday
import com.fitagent.ui.MainActivity
import java.io.IOException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object Notifications {
    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_MORNING = "morning"
    const val CHANNEL_STUDY = "study_breaks"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_REMINDERS, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "An evening nudge when today's goal is within reach" },
            NotificationChannel(CHANNEL_MORNING, context.getString(R.string.channel_morning), NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Your readiness score and today's suggestion" },
            NotificationChannel(CHANNEL_STUDY, context.getString(R.string.channel_study), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "A gentle stretch reminder after long study sessions" },
        ))
    }

    /** Posts only if today's cap for [kind] allows it (see Nudges.kt), and records it. */
    fun post(context: Context, kind: NudgeKind, id: Int, title: String, text: String): Boolean {
        val log = NudgeLog(context)
        if (!canPost(kind, log.today())) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        val channel = when (kind) {
            NudgeKind.MORNING -> CHANNEL_MORNING
            NudgeKind.STUDY_BREAK -> CHANNEL_STUDY
            else -> CHANNEL_REMINDERS
        }
        val intent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(id, n)
            log.record(kind)
            true
        } catch (_: SecurityException) {
            false // permission revoked between the check and the call
        }
    }
}

/** Per-day notification counts, for the caps in Nudges.kt. */
class NudgeLog(context: Context) {
    private val prefs = context.getSharedPreferences("nudges", Context.MODE_PRIVATE)

    fun today(): Map<NudgeKind, Int> {
        if (prefs.getLong("day", -1) != LocalDate.now().toEpochDay()) return emptyMap()
        return NudgeKind.entries.associateWith { prefs.getInt(it.name, 0) }.filterValues { it > 0 }
    }

    fun record(kind: NudgeKind) {
        val day = LocalDate.now().toEpochDay()
        val e = prefs.edit()
        if (prefs.getLong("day", -1) != day) { e.clear(); e.putLong("day", day) }
        e.putInt(kind.name, today()[kind]?.plus(1) ?: 1).apply()
    }
}

object Scheduler {
    private const val REMINDER = "daily_reminder"
    private const val SYNC_PERIODIC = "sync_periodic"
    private const val SYNC_NOW = "sync_now"
    private const val WATCH_SYNC = "watch_sync"
    private const val MORNING = "morning_card"
    private const val STUDY_BREAKS = "study_breaks"
    private const val BEDTIME = "bedtime"

    fun scheduleReminder(context: Context, profile: Profile) {
        val wm = WorkManager.getInstance(context)
        if (!profile.remindersEnabled) {
            wm.cancelUniqueWork(REMINDER)
            return
        }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(profile.reminderHour, 0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(REMINDER, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Morning readiness card at 08:00 and study-break checks every 30 min (both respect the daily caps). */
    fun scheduleNudges(context: Context, profile: Profile) {
        val wm = WorkManager.getInstance(context)
        if (!profile.remindersEnabled) {
            wm.cancelUniqueWork(MORNING); wm.cancelUniqueWork(STUDY_BREAKS); return
        }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(8, 0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        wm.enqueueUniquePeriodicWork(MORNING, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<MorningWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES).build())
        wm.enqueueUniquePeriodicWork(STUDY_BREAKS, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<StudyBreakWorker>(30, TimeUnit.MINUTES).build())
        scheduleBedtime(context)
    }

    /** One-off reminder 30 minutes before tonight's target bedtime (re-planned every evening by the worker). */
    fun scheduleBedtime(context: Context) {
        val now = LocalDateTime.now()
        val target = bedtimeTarget(context.container.timetable.timetable.value, now.toLocalDate().plusDays(1).dayOfWeek)
        val bedDate = if (target < 12 * 60) now.toLocalDate().plusDays(1) else now.toLocalDate()
        var at = bedDate.atStartOfDay().plusMinutes(target - 30L)
        if (!at.isAfter(now)) at = at.plusDays(1)
        WorkManager.getInstance(context).enqueueUniqueWork(BEDTIME, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BedtimeWorker>().setInitialDelay(Duration.between(now, at).toMinutes(), TimeUnit.MINUTES).build())
    }

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodicSync(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(online).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(SYNC_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * Pulls watch data every 15 minutes (WorkManager's minimum) without the app being open. Health Connect only
     * allows this with the background-read permission; without it the phone step sensor is still sampled and
     * watch data is read whenever the app is opened.
     */
    fun scheduleWatchSync(context: Context) {
        val request = PeriodicWorkRequestBuilder<WatchSyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WATCH_SYNC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun syncSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(online)
            .setInitialDelay(5, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(SYNC_NOW, ExistingWorkPolicy.REPLACE, request)
    }
}

/** Evening nudge: tells the student exactly how far they are from today's goal. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val profile = c.profiles.profile.value
        if (!profile.remindersEnabled || !profile.onboarded) return Result.success()
        c.fitness.refresh()
        if (c.fitness.watchStateNow() !is WatchState.Connected && !profile.usePhoneSteps) return Result.success()
        val (days, _) = c.fitness.snapshot()
        val today = LocalDate.now()
        val day = days.lastOrNull { it.date == today } ?: return Result.success()
        if (day.goalMet(profile.goals)) return Result.success()

        val text = eveningReminder(day, profile.goals) ?: return Result.success()
        val streak = computeStreak(days, profile.goals, today)
        val title = if (streak.current > 1) "Keep your ${streak.current}-day streak alive 🔥" else "Close today's goal"
        Notifications.post(applicationContext, NudgeKind.EVENING, 1001, title, text)
        return Result.success()
    }
}

/** 08:00 readiness card with a concrete suggestion (and a time from the timetable when known). */
class MorningWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val profile = c.profiles.profile.value
        if (!profile.remindersEnabled || !profile.onboarded) return Result.success()
        c.fitness.refresh()
        val watch = c.fitness.watchStateNow()
        if (watch !is WatchState.Connected) {
            // No made-up readiness: remind (once a day) to connect or sync the watch instead.
            val (label, last) = when (watch) { is WatchState.Stale -> watch.label to watch.lastDataMs; else -> null to null }
            watchReminder(label, last, System.currentTimeMillis(), WatchState.STALE_AFTER_HOURS)?.let { (title, text) ->
                Notifications.post(applicationContext, NudgeKind.WATCH, 1005, title, text)
            }
            return Result.success()
        }
        val (days, workouts) = c.fitness.snapshot()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val day = days.lastOrNull { it.date == today } ?: return Result.success()
        if (day.sleepMinutes == null && day.restingHr == null) return Result.success() // nothing new to say
        val r = computeReadiness(days, workouts, today, zone)
        val s = suggestToday(day, r, workouts, profile.goals, today, zone)
        val (title, text) = morningMessage(r, s, c.timetable.timetable.value, LocalDateTime.now(zone))
        Notifications.post(applicationContext, NudgeKind.MORNING, 1002, title, text)
        return Result.success()
    }
}

/** Bedtime reminder (D2), then schedules tomorrow's. */
class BedtimeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (c.profiles.profile.value.remindersEnabled) {
            val timetable = c.timetable.timetable.value
            val tomorrow = LocalDate.now().plusDays(1).dayOfWeek
            val target = bedtimeTarget(timetable, tomorrow)
            val first = timetable.busy.filter { it.day == tomorrow }.minOfOrNull { it.startMin }
            Notifications.post(applicationContext, NudgeKind.BEDTIME, 1004, "Wind down in 30 minutes", bedtimeMessage(target, first))
        }
        Scheduler.scheduleBedtime(applicationContext)
        return Result.success()
    }
}

/** Every 30 min: after ~90 min of sitting in free time on a study day, suggest a 3-minute stretch. */
class StudyBreakWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (!c.profiles.profile.value.remindersEnabled) return Result.success()
        val timetable = c.timetable.timetable.value
        if (timetable.busy.isEmpty()) return Result.success()
        // Without live step data "no steps" just means "no data" — never nudge on that.
        if (c.fitness.watchStateNow() !is WatchState.Connected && !c.profiles.profile.value.usePhoneSteps) return Result.success()
        c.fitness.samplePhoneOnly()
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val hourly = c.fitness.hourlyStepsNow(now.toLocalDate())
        // Approximate "last 90 min" with this hour and the previous one.
        val recent = hourly.getOrElse(now.hour) { 0 } + if (now.hour > 0) hourly.getOrElse(now.hour - 1) { 0 } else 0
        if (shouldNudgeStudyBreak(now, timetable, recent)) {
            Notifications.post(applicationContext, NudgeKind.STUDY_BREAK, 1003, applicationContext.getString(R.string.study_break_title),
                applicationContext.getString(R.string.study_break_text))
        }
        return Result.success()
    }
}

/** Watch → phone: reads Health Connect (when background reads are allowed) and the phone's step sensor. */
class WatchSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val status = c.fitness.refreshStatus()
        if (status.connected && !status.backgroundRead) {
            // Reading Health Connect from the background would throw; still count phone steps.
            c.fitness.samplePhoneOnly()
            return Result.success()
        }
        c.fitness.refresh()
        return Result.success()
    }
}

/** Phone → cloud. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (c.auth.account.value == null) return Result.success()
        if (c.fitness.refreshStatus().backgroundRead) c.fitness.refresh()
        return c.cloud.sync().fold(
            onSuccess = { Result.success() },
            onFailure = { if (it is IOException && runAttemptCount < 5) Result.retry() else Result.failure() },
        )
    }
}
