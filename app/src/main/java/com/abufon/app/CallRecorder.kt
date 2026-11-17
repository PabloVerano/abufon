package com.abufon.app

import android.content.Context
import android.util.Log
import org.linphone.core.Call
import org.linphone.core.CallParams
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Records calls on the Portal so an audio problem can be listened to afterwards: did the
 * Portal actually send our voices, or did they get lost on granny's side?
 *
 * Linphone's recorder mixes both sides into one file: what the Portal sends (taken after its
 * echo canceller, so as granny should hear it) and what granny's phone sends back.
 *
 * Files: Android/data/com.abufon.app/files/recordings/call_<date>_<time>.wav (about 6 MB a
 * minute). Nothing is uploaded. Files older than RECORD_KEEP_DAYS are deleted, and the oldest
 * go first if the folder passes 1 GB. Turned off with RECORD_CALLS=false.
 */
object CallRecorder {
    private const val TAG = "Abufon"
    private const val MAX_BYTES = 1_000_000_000L

    private var dir: File? = null
    private val io = Executors.newSingleThreadExecutor()
    private val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun init(context: Context) {
        if (!BuildConfig.RECORD_CALLS) return
        dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings").apply { mkdirs() }
        io.execute(::prune)
    }

    /** Gives a new call its recording file. Call before inviting or accepting. */
    fun prepare(params: CallParams) {
        val d = dir ?: return
        params.recordFile = File(d, "call_${name.format(Date())}.wav").absolutePath
        io.execute(::prune)
    }

    /** Starts recording once the call's audio is running (repeat calls are ignored). */
    fun start(call: Call) {
        val file = call.params.recordFile ?: return
        if (call.isRecording) return
        try {
            call.startRecording()
            CallLog.event("recording -> ${File(file).name}")
        } catch (t: Throwable) {
            Log.w(TAG, "Call recording failed", t)
            CallLog.event("recording failed: ${t.message}")
        }
    }

    private fun prune() {
        val d = dir ?: return
        try {
            val cutoff = System.currentTimeMillis() - BuildConfig.RECORD_KEEP_DAYS * 86_400_000L
            val files = d.listFiles()?.sortedBy { it.lastModified() }?.toMutableList() ?: return
            files.filter { it.lastModified() < cutoff }.forEach { it.delete(); files.remove(it) }
            var total = files.sumOf { it.length() }
            for (f in files) {
                if (total <= MAX_BYTES) break
                total -= f.length()
                f.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Recording cleanup failed", e)
        }
    }
}
