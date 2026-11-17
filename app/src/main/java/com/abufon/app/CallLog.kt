package com.abufon.app

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.linphone.core.Call
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A small call-quality log kept on the Portal, so an audio dropout can be diagnosed afterwards
 * without a cable attached at the time. Once a second during a call it writes one line with the
 * audio and video rates, loss, jitter, round trip, levels, mute flags and volume; it also logs
 * call and registration changes. Lines where audio stops in one direction are flagged "!!".
 *
 * File: Android/data/com.abufon.app/files/calls.log (rolled to calls.1.log at 1 MB), readable
 * with `adb pull` on release builds too. No SIP addresses are written.
 */
object CallLog {
    private const val TAG = "Abufon"
    private const val MAX_BYTES = 1_000_000L
    /** Below this (kbit/s) a running audio stream is treated as silent in that direction. */
    private const val SILENT_KBPS = 5f

    private lateinit var file: File
    private lateinit var audio: AudioManager
    private val writer = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private var sampling = false
    private var ready = false

    fun init(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        file = File(dir, "calls.log")
        audio = context.getSystemService(AudioManager::class.java)
        ready = true
    }

    /** A one-off event: call state change, registration, start-up. */
    fun event(text: String) = write("EV $text")

    /** Called on every call state change; starts or stops the once-a-second sampling. */
    fun onCallState(call: Call, state: Call.State, message: String) {
        val who = if (LinphoneManager.isGranny(call.remoteAddress)) "granny" else "other"
        val reason = if (message.isBlank()) "" else " ($message)"
        event("call ${call.dir.name.lowercase()} $who -> $state$reason")
        if (LinphoneManager.activeCall() != null) startSampling() else stopSampling()
    }

    private fun startSampling() {
        if (sampling) return
        sampling = true
        ui.post(sample)
    }

    private fun stopSampling() {
        sampling = false
        ui.removeCallbacks(sample)
    }

    private val sample = object : Runnable {
        override fun run() {
            val call = LinphoneManager.activeCall()
            if (call == null) { sampling = false; return }
            try { write(describe(call)) } catch (t: Throwable) { Log.w(TAG, "Call log sample failed", t) }
            ui.postDelayed(this, 1000)
        }
    }

    private fun describe(call: Call): String {
        val a = call.audioStats
        val v = call.videoStats
        val running = call.state == Call.State.StreamsRunning
        val up = a?.uploadBandwidth ?: 0f
        val down = a?.downloadBandwidth ?: 0f
        val flags = buildString {
            if (running && up < SILENT_KBPS) append(" !!NO_AUDIO_OUT")
            if (running && down < SILENT_KBPS) append(" !!NO_AUDIO_IN")
            if (call.microphoneMuted) append(" !!MIC_MUTED")
            if (call.speakerMuted) append(" !!SPEAKER_MUTED")
            if (LinphoneManager.coreOrNull?.isMicEnabled == false) append(" !!CORE_MIC_OFF")
        }
        val vol = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        val volMax = audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        return "S ${call.state}" +
            " audio up=%.1f down=%.1f".format(up, down) +
            " loss r=%.1f%% s=%.1f%%".format(a?.receiverLossRate ?: 0f, a?.senderLossRate ?: 0f) +
            " jit=%.0fms rtt=%.0fms".format(a?.jitterBufferSizeMs ?: 0f, (a?.roundTripDelay ?: 0f) * 1000) +
            " lvl mic=%.0fdB her=%.0fdB".format(call.recordVolume, call.playVolume) +
            " q=%.1f".format(call.currentQuality) +
            " video up=%.0f down=%.0f".format(v?.uploadBandwidth ?: 0f, v?.downloadBandwidth ?: 0f) +
            " out=${call.outputAudioDevice?.type ?: "-"} in=${call.inputAudioDevice?.type ?: "-"}" +
            " callvol=$vol/$volMax mode=${audio.mode}" +
            flags
    }

    private fun write(line: String) {
        if (!ready) return
        val stamped = "${time.format(Date())} $line\n"
        writer.execute {
            try {
                if (file.length() > MAX_BYTES) {
                    val old = File(file.parentFile, "calls.1.log")
                    old.delete()
                    file.renameTo(old)
                }
                file.appendText(stamped)
            } catch (e: Exception) {
                Log.w(TAG, "Call log write failed", e)
            }
        }
    }
}
