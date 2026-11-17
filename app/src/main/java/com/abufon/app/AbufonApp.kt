package com.abufon.app

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

class AbufonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
        Settings.init(this)
        MissedCall.init(this)
        CallLog.init(this)
        CallRecorder.init(this)
        CallLog.event("app started (version ${BuildConfig.VERSION_NAME})")
        IncomingNotification.createChannels(this)
        Ringer.restoreLeftoverVolume(this)
        AbufonService.start(this)
        if (crashLooping()) {
            Log.e(TAG, "Linphone attempts keep dying early: safe mode, will retry later")
            safeMode = true
            CallLog.event("safe mode: Linphone not started")
            scheduleRetry()
        } else if (!startLinphone()) {
            scheduleRetry()
        }
    }

    companion object {
        private const val TAG = "Abufon"
        private const val KEY_ATTEMPTS = "attempts_without_surviving"
        private const val MAX_QUICK_DEATHS = 3
        private const val HEALTHY_AFTER_MS = 30_000L
        private val RETRY_DELAYS_MS = longArrayOf(60_000, 5 * 60_000, 15 * 60_000, 30 * 60_000)

        private lateinit var app: AbufonApp
        private val handler = Handler(Looper.getMainLooper())
        private var retries = 0
        private val prefs get() = app.getSharedPreferences("health", Context.MODE_PRIVATE)

        /** True when this process skipped Linphone at start because of repeated early deaths. */
        var safeMode = false
            private set

        /** Called on the main thread when Linphone comes up after a failed or skipped start. */
        var onLinphoneReady: (() -> Unit)? = null

        /**
         * Abufon is the HOME app: if it dies on every start (including native crashes inside the
         * Linphone library, which no exception handler sees), the Portal becomes unusable and
         * Android may reset the default home. So every attempt to start Linphone is counted
         * until the process has survived it for a while. Once [MAX_QUICK_DEATHS] attempts in a
         * row have died, a fresh process skips Linphone (safe mode: the launcher shows its
         * offline screen) and tries again later, on its own or when "Reintentar" is tapped.
         * The count stays one short of the limit until an attempt survives, so a still-broken
         * retry costs one restart, not three.
         */
        private fun crashLooping() = prefs.getInt(KEY_ATTEMPTS, 0) >= MAX_QUICK_DEATHS

        /** Try to start Linphone, counting the attempt. Returns true if the core is running. */
        fun startLinphone(): Boolean {
            if (LinphoneManager.isReady) return true
            val before = prefs.getInt(KEY_ATTEMPTS, 0)
            // commit(): must be on disk before native code gets a chance to kill the process.
            prefs.edit().putInt(KEY_ATTEMPTS, before + 1).commit()
            val ok = LinphoneManager.tryInit(app)
            if (ok) {
                handler.postDelayed({ prefs.edit().putInt(KEY_ATTEMPTS, 0).apply() }, HEALTHY_AFTER_MS)
                handler.removeCallbacks(retry)
                safeMode = false
                onLinphoneReady?.invoke()
            } else {
                // It failed cleanly (an exception, not a crash): that is not a crash loop.
                prefs.edit().putInt(KEY_ATTEMPTS, before).commit()
            }
            return ok
        }

        private val retry = Runnable {
            if (LinphoneManager.isReady) return@Runnable
            // Leave the counter one short of the limit: if this attempt kills the process, the
            // next start goes straight back to safe mode.
            prefs.edit().putInt(KEY_ATTEMPTS, MAX_QUICK_DEATHS - 1).commit()
            if (!startLinphone()) scheduleRetry()
        }

        private fun scheduleRetry() {
            val delay = RETRY_DELAYS_MS[minOf(retries, RETRY_DELAYS_MS.size - 1)]
            retries++
            handler.removeCallbacks(retry)
            handler.postDelayed(retry, delay)
        }

        /** "Reintentar" while Linphone is down: try now instead of waiting for the timer. */
        fun retryNow() {
            handler.removeCallbacks(retry)
            retry.run()
        }
    }
}
