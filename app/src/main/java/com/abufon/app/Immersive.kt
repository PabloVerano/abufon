package com.abufon.app

import android.app.Activity
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

fun Activity.goImmersive(keepScreenOn: Boolean = false) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowCompat.getInsetsController(window, window.decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

fun Activity.showOverLockScreen() {
    setShowWhenLocked(true)
    setTurnScreenOn(true)
}

/** Debug builds only: lock orientation for screenshots (`--es orient land|port`). */
fun Activity.debugOrientation(orient: String?) {
    requestedOrientation = when (orient) {
        "land" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        "port" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else -> return
    }
}


/** Lowest backlight a window may ask for (0 can switch the backlight off on some panels). */
private const val NIGHT_BRIGHTNESS = 0.01f

/**
 * Outside the calling hours (or while paused) the window asks for the lowest backlight; during
 * them it leaves brightness to the system. Every Abufon window that can be on screen at night
 * uses this, so going from one to another never flashes up to full brightness.
 */
fun android.view.Window.applyNightBrightness() {
    val brightness = if (Settings.canCallNow()) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else NIGHT_BRIGHTNESS
    if (attributes.screenBrightness != brightness) attributes = attributes.apply { screenBrightness = brightness }
}
