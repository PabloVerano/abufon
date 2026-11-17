package com.abufon.app

import kotlin.math.exp

/**
 * Pure maths for the v4 voice rings (no Android imports, unit-tested): turns the loudness Linphone
 * reports for what granny is saying into 0..1, smooths it, and gives the two ring scales.
 */
object VoiceLevel {
    /** Loudness (dBm0, as returned by Call.getPlayVolume()) at or below which the rings rest. */
    const val QUIET_DB = -40f
    /** Loudness at or above which the rings are at their largest. */
    const val LOUD_DB = -10f
    /** JSON animations.voice: "smoothed over ~120 ms". */
    const val SMOOTH_MS = 120f

    /** 0 (silence) .. 1 (loud speech), linear in dB. Anything that is not a number counts as silence. */
    fun fromDb(db: Float): Float {
        if (db.isNaN()) return 0f
        return ((db - QUIET_DB) / (LOUD_DB - QUIET_DB)).coerceIn(0f, 1f)
    }

    /** One step of exponential smoothing with time constant [tauMs]: moves [current] towards [target]. */
    fun smooth(current: Float, target: Float, dtMs: Float, tauMs: Float = SMOOTH_MS): Float {
        if (dtMs <= 0f) return current
        val k = 1f - exp(-dtMs / tauMs)
        return current + (target - current) * k
    }

    /** JSON incallNoVideo.voiceRings: inner 1.02-1.07, outer 1.06-1.13. */
    fun innerScale(level: Float) = 1.02f + 0.05f * level.coerceIn(0f, 1f)
    fun outerScale(level: Float) = 1.06f + 0.07f * level.coerceIn(0f, 1f)
}
