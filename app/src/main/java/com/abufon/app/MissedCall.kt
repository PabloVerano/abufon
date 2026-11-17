package com.abufon.app

import android.content.Context
import android.content.SharedPreferences

/**
 * The missed-call cue, v4: the time of the latest call from granny that was not answered (it rang
 * out or she gave up) or that was declined because it came outside the calling hours or while
 * paused. Kept on disk so it survives restarts; cleared by the next call that connects, in either
 * direction. Only the latest one is kept. Calls from anyone else never get here.
 */
object MissedCall {
    private const val KEY = "latest_at"
    private var prefs: SharedPreferences? = null

    /** Called on the main thread whenever [latest] changes (the home screen listens while visible). */
    var onChange: (() -> Unit)? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("missed_call", Context.MODE_PRIVATE)
    }

    /** When granny's latest missed call arrived (epoch ms), or null if there is none to show. */
    val latest: Long?
        get() = try {
            prefs?.getLong(KEY, 0L)?.takeIf { it > 0L }
        } catch (_: ClassCastException) { null }

    fun record(atMs: Long = System.currentTimeMillis()) {
        prefs?.edit()?.putLong(KEY, atMs)?.apply()
        onChange?.invoke()
    }

    fun clear() {
        if (latest == null) return
        prefs?.edit()?.remove(KEY)?.apply()
        onChange?.invoke()
    }
}
