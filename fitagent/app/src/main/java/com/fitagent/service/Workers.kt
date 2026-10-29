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
import com.fitagent.domain.computeStreak
import com.fitagent.domain.formatCount
import com.fitagent.domain.walkMinutesFor
import com.fitagent.ui.MainActivity
import java.io.IOException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object Notifications {
    const val CHANNEL_REMINDERS = "reminders"

    fun createChannels(context: Context) {
        val channel = NotificationChannel(CHANNEL_REMINDERS, "Goal reminders", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "A daily nudge when you're close to your goals" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun post(context: Context, id: Int, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val intent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call — skip this reminder.
        }
    }
}

object Scheduler {
    private const val REMINDER = "daily_reminder"
    private const val SYNC_PERIODIC = "sync_periodic"
    private const val SYNC_NOW = "sync_now"

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

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodicSync(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(online).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(SYNC_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
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
        c.fitness.refresh(daysBack = 7)
        val (days, _) = c.fitness.snapshot()
        val today = LocalDate.now()
        val day = days.lastOrNull { it.date == today } ?: return Result.success()
        if (day.goalMet(profile.goals)) return Result.success()

        val streak = computeStreak(days, profile.goals, today)
        val remainingSteps = (profile.goals.dailySteps - day.steps).coerceAtLeast(0)
        val remainingMinutes = (profile.goals.dailyWorkoutMinutes - day.workoutMinutes).coerceAtLeast(0)
        val walk = walkMinutesFor(remainingSteps)
        val title = if (streak.current > 1) "Keep your ${streak.current}-day streak alive 🔥" else "Close today's goal"
        val text = if (walk <= remainingMinutes || remainingMinutes == 0) {
            "${formatCount(remainingSteps)} steps to go — a $walk-minute brisk walk gets you there."
        } else {
            "$remainingMinutes workout minutes to go — a quick session in your room counts!"
        }
        Notifications.post(applicationContext, 1001, title, text)
        return Result.success()
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (c.auth.account.value == null) return Result.success()
        c.fitness.refresh()
        return c.cloud.sync().fold(
            onSuccess = { Result.success() },
            onFailure = { if (it is IOException && runAttemptCount < 5) Result.retry() else Result.failure() },
        )
    }
}
