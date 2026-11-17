package com.abufon.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.PowerManager
import android.util.Log
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Granny's ring. The melody is synthesised here, so it never depends on the Portal having a
 * default ringtone set. It plays on the media stream (the one the Portal's volume buttons
 * control) and, while ringing, lifts that volume to at least RING_MIN_VOLUME_PERCENT so a
 * turned-down tablet still rings; the previous volume is restored afterwards.
 * It also wakes the screen, as a fallback for the incoming activity's own turnScreenOn.
 */
object Ringer {
    private const val TAG = "Abufon"
    private const val RATE = 44_100
    private const val MAX_RING_MS = 90_000L

    private var track: AudioTrack? = null
    private var savedVolume: Int? = null
    private var raisedTo = 0
    private const val PREFS = "ringer"
    private const val KEY_SAVED = "volume_before_ring"
    private const val KEY_RAISED = "volume_raised_to"
    private var wakeLock: PowerManager.WakeLock? = null

    val isRinging get() = track != null

    fun start(context: Context) {
        if (track != null) return
        wakeScreen(context)
        raiseVolume(context)
        try {
            val pcm = melody()
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
                .apply {
                    write(pcm, 0, pcm.size)
                    setLoopPoints(0, pcm.size, -1)
                    play()
                }
        } catch (e: Exception) {
            Log.w(TAG, "Could not play ring", e)
            track = null
        }
    }

    fun stop(context: Context) {
        track?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        track = null
        savedVolume?.let { restoreVolume(context, it, raisedTo) }
        savedVolume = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun raiseVolume(context: Context) {
        val percent = BuildConfig.RING_MIN_VOLUME_PERCENT
        if (percent <= 0) return
        try {
            val am = context.getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val floor = (max * percent + 99) / 100
            val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (now < floor) {
                savedVolume = now
                raisedTo = floor
                // Also on disk, so a process death mid-ring can't leave the volume raised for good.
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(KEY_SAVED, now).putInt(KEY_RAISED, floor).commit()
                am.setStreamVolume(AudioManager.STREAM_MUSIC, floor, 0)
            }
        } catch (e: Exception) { // e.g. Do Not Disturb refusing the change
            Log.w(TAG, "Could not raise ring volume", e)
        }
    }

    /** Put the volume back, unless someone changed it during the ring: then their choice stands. */
    private fun restoreVolume(context: Context, saved: Int, raised: Int) {
        try {
            val am = context.getSystemService(AudioManager::class.java)
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == raised) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore volume", e)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** At process start: undo a volume raise left behind by a process that died while ringing. */
    fun restoreLeftoverVolume(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_SAVED)) restoreVolume(context, prefs.getInt(KEY_SAVED, 0), prefs.getInt(KEY_RAISED, -1))
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen(context: Context) {
        try {
            val pm = context.getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Abufon::ring"
            ).apply { setReferenceCounted(false); acquire(MAX_RING_MS) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not wake the screen", e)
        }
    }

    /** A soft, bell-like four-note phrase played twice, then a pause: about 3 s per loop. */
    private fun melody(): ShortArray {
        val notes = doubleArrayOf(783.99, 1046.50, 1318.51, 1046.50) // G5 C6 E6 C6
        val noteMs = 190
        val phrase = notes.size * noteMs
        val totalMs = phrase * 2 + 1500
        val out = DoubleArray(RATE * totalMs / 1000)
        for (rep in 0 until 2) {
            for ((i, hz) in notes.withIndex()) {
                val start = RATE * (rep * phrase + i * noteMs) / 1000
                val len = RATE * 600 / 1000 // notes ring on over the next ones
                for (n in 0 until len) {
                    val idx = start + n
                    if (idx >= out.size) break
                    val t = n.toDouble() / RATE
                    val attack = minOf(1.0, n / (RATE * 0.005))
                    val env = attack * exp(-6.0 * t)
                    out[idx] += env * (sin(2 * PI * hz * t) + 0.3 * sin(2 * PI * 2 * hz * t))
                }
            }
        }
        val peak = out.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
        return ShortArray(out.size) { (out[it] / peak * 0.8 * Short.MAX_VALUE).toInt().toShort() }
    }
}
