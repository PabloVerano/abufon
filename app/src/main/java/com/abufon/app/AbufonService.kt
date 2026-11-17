package com.abufon.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat

/** Keeps the process (and so the Linphone core) alive and registered at all times. */
class AbufonService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(
            IncomingNotification.SERVICE_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        )
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // In safe mode (or after a failed start) there is no core; the service still keeps the
        // process alive so "Reintentar" can bring Linphone up later.
        LinphoneManager.coreOrNull?.ensureRegistered()
        return START_STICKY
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        // The Portal is always plugged in; keep the CPU and Wi-Fi up so SIP stays registered.
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Abufon::sip").apply {
            setReferenceCounted(false); acquire()
        }
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Abufon::wifi").apply {
            setReferenceCounted(false); acquire()
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, HomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, IncomingNotification.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_notification))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        wakeLock?.release()
        wifiLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, AbufonService::class.java))
            } catch (e: Exception) {
                Log.w("Abufon", "Could not start service", e)
            }
        }
    }
}
