package com.fitagent

import android.app.Application
import android.content.Context
import com.fitagent.data.CloudRepository
import com.fitagent.data.FitnessRepository
import com.fitagent.data.health.HealthConnectSource
import com.fitagent.data.local.AppDatabase
import com.fitagent.data.prefs.AuthStore
import com.fitagent.data.prefs.ProfileStore
import com.fitagent.data.ble.BleHeartRateMonitor
import com.fitagent.data.prefs.SessionStore
import com.fitagent.data.prefs.SyncStateStore
import com.fitagent.data.prefs.TimetableStore
import com.fitagent.data.sensors.PhoneStepCounter
import com.fitagent.data.remote.ApiClient
import com.fitagent.service.Notifications
import com.fitagent.service.Scheduler
import com.fitagent.widget.FitAgentWidget

/** Manual dependency container — small enough that a DI framework would add more than it saves. */
class AppContainer(context: Context) {
    val profiles = ProfileStore(context)
    val sessions = SessionStore(context)
    val auth = AuthStore(context)
    val health = HealthConnectSource(context)
    val syncState = SyncStateStore(context)
    val phoneSteps = PhoneStepCounter(context)
    val db = AppDatabase.create(context)
    val fitness = FitnessRepository(context.applicationContext, db, health, phoneSteps, profiles, auth, syncState).apply {
        onDataChanged = {
            FitAgentWidget.refresh(context.applicationContext)
            publishToWatch(context.applicationContext, this)
        }
    }
    val timetable = TimetableStore(context)
    val heartRate = BleHeartRateMonitor(context.applicationContext)
    val cloud = CloudRepository(ApiClient(auth), auth, profiles, fitness, timetable)
}

class FitAgentApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        Scheduler.scheduleReminder(this, container.profiles.profile.value)
        Scheduler.schedulePeriodicSync(this)
        Scheduler.scheduleWatchSync(this)
        Scheduler.scheduleNudges(this, container.profiles.profile.value)
    }
}

val Context.container: AppContainer get() = (applicationContext as FitAgentApp).container


/** Sends today's readiness and steps to a paired Wear OS watch (tile, complication, watch app). */
private suspend fun publishToWatch(context: Context, fitness: FitnessRepository) {
    val (days, workouts) = fitness.snapshot()
    val today = java.time.LocalDate.now()
    val day = days.lastOrNull { it.date == today } ?: return
    val profile = context.container.profiles.profile.value
    val zone = java.time.ZoneId.systemDefault()
    val r = com.fitagent.domain.computeReadiness(days, workouts, today, zone)
    val streak = com.fitagent.domain.computeStreak(days, profile.goals, today)
    com.fitagent.wearsync.WearSyncService.publishSummary(context, r.score, r.band, day.steps, profile.goals.dailySteps, streak.current)
}
