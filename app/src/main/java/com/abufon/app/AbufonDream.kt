package com.abufon.app

import android.service.dreams.DreamService

/**
 * Stands in for the Portal's own screensaver. The Portal runs "the screensaver" every time the
 * screen sleeps or wakes, whatever the screensaver switch says, and its own one opens the Portal
 * home screen with its photo slideshow on top of Abufon. This one instead brings Abufon to the
 * front and ends at once: going to sleep, the screen then switches off; waking, Abufon shows.
 *
 * Installed as the screensaver by deploy.sh (secure setting screensaver_components).
 */
class AbufonDream : DreamService() {
    override fun onCreate() {
        super.onCreate()
        // Windowless, like the Portal's own screensaver: a window of ours would cover Abufon's
        // dimmed screen for a moment on every wake, flashing it to full brightness at night.
        // The switch is hidden API; if the Portal refuses it, the window below is the fallback.
        windowless = try {
            DreamService::class.java.getMethod("setWindowless", Boolean::class.javaPrimitiveType).invoke(this, true)
            true
        } catch (t: Throwable) {
            android.util.Log.w("Abufon", "Windowless screensaver refused", t)
            false
        }
    }

    private var windowless = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        window?.let {
            it.decorView.setBackgroundColor(android.graphics.Color.BLACK)
            it.applyNightBrightness()
        }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        // Never disturb a call (the call screens hold the screen on, so this is a safety net).
        if (LinphoneManager.activeCall() == null) {
            try { HomeActivity.open(this) } catch (e: Exception) { android.util.Log.w("Abufon", "Dream could not open home", e) }
        }
        CallLog.event("screensaver -> Abufon" + if (windowless) "" else " (with window)")
        finish()
    }
}
