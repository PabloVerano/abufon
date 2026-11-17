package com.abufon.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.HapticFeedbackConstants
import android.view.View
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Tap feedback: a short haptic plus a soft "pop". The Portal has system touch sounds off,
 * so the pop is synthesised here (no audio asset needed).
 */
object Feedback {
    private const val RATE = 44_100

    private val pop: AudioTrack? by lazy { tone(startHz = 700.0, endHz = 520.0, ms = 70, volume = 0.22) }
    private val confirmTone: AudioTrack? by lazy { tone(startHz = 520.0, endHz = 360.0, ms = 140, volume = 0.25) }

    fun tap(view: View) {
        view.performHapticFeedback(
            HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
        play(pop)
    }

    /** Hold-to-confirm completed (hang up / decline). */
    fun confirm(view: View) {
        view.performHapticFeedback(
            HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
        play(confirmTone)
    }

    // v4 sounds, synthesised like the rest (no audio assets).
    private val chimeTone: AudioTrack? by lazy { track(chimePcm()) }
    private val chirpTone: AudioTrack? by lazy { track(chirpPcm()) }

    /** Tap while resting or paused: a short haptic and a soft chime instead of the pop. */
    fun chime(view: View) {
        view.performHapticFeedback(
            HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
        play(chimeTone)
    }

    /** One chirp when the calling hours begin (only if the parent left "Pío al poder llamar" on). */
    fun chirp() = play(chirpTone)

    /** A soft bell: one note (E6) with a quiet octave and a slow decay. */
    private fun chimePcm(): ShortArray {
        val n = RATE * 650 / 1000
        return ShortArray(n) { i ->
            val t = i.toDouble() / RATE
            val attack = minOf(1.0, i / (RATE * 0.006))
            val env = attack * exp(-6.5 * t)
            val v = sin(2 * PI * 1318.5 * t) + 0.25 * sin(2 * PI * 2637.0 * t) * exp(-6.0 * t)
            (v * env * 0.16 * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /** One bird chirp ("pí-o"): two quick rising whistles. */
    private fun chirpPcm(): ShortArray {
        val syllables = listOf(Triple(0, 80, 2900.0 to 4300.0), Triple(120, 110, 3300.0 to 4700.0)) // start ms, length ms, Hz
        val n = RATE * 260 / 1000
        val pcm = ShortArray(n)
        for ((startMs, lenMs, hz) in syllables) {
            val first = RATE * startMs / 1000
            val len = RATE * lenMs / 1000
            var phase = 0.0
            for (i in 0 until len) {
                val t = i.toDouble() / len
                phase += 2 * PI * (hz.first + (hz.second - hz.first) * t) / RATE
                val env = sin(PI * t) // rounded on and off, no click
                pcm[first + i] = (sin(phase) * env * 0.20 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return pcm
    }

    private fun play(track: AudioTrack?) {
        track ?: return
        try {
            track.stop()
            track.reloadStaticData()
            track.play()
        } catch (_: IllegalStateException) {
        }
    }

    /** A short sine sweep with a soft attack and exponential decay. */
    private fun tone(startHz: Double, endHz: Double, ms: Int, volume: Double): AudioTrack? {
        val n = RATE * ms / 1000
        val pcm = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / n
            val hz = startHz + (endHz - startHz) * t
            phase += 2 * PI * hz / RATE
            val attack = minOf(1.0, i / (RATE * 0.004))
            val env = attack * exp(-5.0 * t)
            pcm[i] = (sin(phase) * env * volume * Short.MAX_VALUE).toInt().toShort()
        }
        return track(pcm)
    }

    private fun track(pcm: ShortArray): AudioTrack? = try {
        val n = pcm.size
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
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
            .setBufferSizeInBytes(n * 2)
            .build()
            .apply { write(pcm, 0, n) }
    } catch (_: Exception) {
        null
    }
}
