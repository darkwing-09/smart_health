package com.healthos.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.healthos.ui.MainActivity

object HealthOSNotificationManager {

    const val CHANNEL_URGENT = "healthos_urgent"
    const val CHANNEL_IMPORTANT = "healthos_important"

    fun initChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val urgentChannel = NotificationChannel(
                CHANNEL_URGENT,
                "Urgent Health Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical physiological alerts overriding quiet hours and bedtime"
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            }

            val importantChannel = NotificationChannel(
                CHANNEL_IMPORTANT,
                "Important Health Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Significant baseline variations and trend deviations"
                enableVibration(true)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(urgentChannel)
            notificationManager.createNotificationChannel(importantChannel)
        }
    }

    fun showNotification(
        context: Context,
        notificationId: Int,
        title: String,
        body: String,
        isUrgent: Boolean,
        findingId: String? = null
    ) {
        initChannels(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("finding_id", findingId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = if (isUrgent) CHANNEL_URGENT else CHANNEL_IMPORTANT
        val priority = if (isUrgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(priority)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notificationId, notification)
    }
}
