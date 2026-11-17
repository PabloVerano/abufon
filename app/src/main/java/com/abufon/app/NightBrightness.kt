package com.abufon.app

import android.content.Context
import android.provider.Settings.System as Sys

/**
 * The Portal's own (system) brightness at night. Abufon's windows already ask for the lowest
 * backlight at night, but every time the screen wakes (a tap, or the Portal's presence sensor
 * noticing someone walk past) Android shows the system brightness for a moment before the
 * window's request applies: with the Portal at full brightness that is a visible flash.
 *
 * So outside the calling hours the system brightness itself is turned down, and the day's
 * value (and auto-brightness setting) is put back when the hours start. Needs the
 * WRITE_SETTINGS app-op, which deploy.sh grants; without it this does nothing.
 */
object NightBrightness {
    private const val PREFS = "night_brightness"
    private const val KEY_LEVEL = "day_level"
    private const val KEY_MODE = "day_mode"
    /** 0–255 system scale; matches the 1 % Abufon's windows ask for at night. */
    private const val NIGHT_LEVEL = 2

    fun apply(context: Context, night: Boolean) {
        if (!Sys.canWrite(context)) return
        val cr = context.contentResolver
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            if (night) {
                if (!prefs.contains(KEY_LEVEL)) {
                    prefs.edit()
                        .putInt(KEY_LEVEL, Sys.getInt(cr, Sys.SCREEN_BRIGHTNESS, 255))
                        .putInt(KEY_MODE, Sys.getInt(cr, Sys.SCREEN_BRIGHTNESS_MODE, Sys.SCREEN_BRIGHTNESS_MODE_MANUAL))
                        .apply()
                    CallLog.event("system brightness -> night")
                }
                Sys.putInt(cr, Sys.SCREEN_BRIGHTNESS_MODE, Sys.SCREEN_BRIGHTNESS_MODE_MANUAL)
                if (Sys.getInt(cr, Sys.SCREEN_BRIGHTNESS, -1) != NIGHT_LEVEL) Sys.putInt(cr, Sys.SCREEN_BRIGHTNESS, NIGHT_LEVEL)
            } else if (prefs.contains(KEY_LEVEL)) {
                Sys.putInt(cr, Sys.SCREEN_BRIGHTNESS, prefs.getInt(KEY_LEVEL, 255))
                Sys.putInt(cr, Sys.SCREEN_BRIGHTNESS_MODE, prefs.getInt(KEY_MODE, Sys.SCREEN_BRIGHTNESS_MODE_MANUAL))
                prefs.edit().clear().apply()
                CallLog.event("system brightness -> day")
            }
        } catch (e: Exception) {
            android.util.Log.w("Abufon", "System brightness change failed", e)
        }
    }
}
