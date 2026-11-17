package com.abufon.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object IncomingNotification {
    const val CHANNEL_SERVICE = "service"
    const val CHANNEL_INCOMING = "incoming"
    const val SERVICE_ID = 1
    private const val INCOMING_ID = 2

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Abufon", NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_INCOMING, "Llamadas", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // we play the ringtone ourselves
                enableVibration(false)
            }
        )
    }

    fun show(context: Context) {
        val intent = Intent(context, IncomingCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        val pi = PendingIntent.getActivity(
            context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle(context.getString(R.string.incoming_notification, BuildConfig.GRANNY_NAME))
            .setCategory(Notification.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(INCOMING_ID, n)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(INCOMING_ID)
    }
}
