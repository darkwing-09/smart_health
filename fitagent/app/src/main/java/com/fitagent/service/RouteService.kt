package com.fitagent.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fitagent.R
import com.fitagent.container
import com.fitagent.data.local.RoutePointEntity
import com.fitagent.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Records the GPS route of a walk, run or ride while the screen is off (D7). Points go straight to Room, keyed by
 * the session's start time, so a killed process loses nothing. Stops with the workout.
 */
class RouteService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionStart = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sessionStart = intent?.getLongExtra(EXTRA_SESSION, 0L) ?: 0L
        if (sessionStart == 0L || !hasPermission(this)) { stopSelf(); return START_NOT_STICKY }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        val lm = getSystemService(LocationManager::class.java)
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 3f, this, Looper.getMainLooper())
        } catch (_: SecurityException) {
            stopSelf()
        } catch (_: IllegalArgumentException) { // no GPS provider on this device
            stopSelf()
        }
        return START_REDELIVER_INTENT
    }

    override fun onLocationChanged(location: Location) {
        val row = RoutePointEntity(sessionStart, location.time, location.latitude, location.longitude, location.accuracy)
        scope.launch { container.db.route().insert(row) }
    }

    override fun onDestroy() {
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(this) }
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Workout GPS", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Recording your route")
            .setContentText("GPS stays on until you finish the workout.")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "workout_gps"
        private const val NOTIFICATION_ID = 2001
        private const val EXTRA_SESSION = "session_start"

        fun hasPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context, sessionStartMs: Long) {
            if (!hasPermission(context)) return
            ContextCompat.startForegroundService(context, Intent(context, RouteService::class.java).putExtra(EXTRA_SESSION, sessionStartMs))
        }

        fun stop(context: Context) = context.stopService(Intent(context, RouteService::class.java))
    }
}
