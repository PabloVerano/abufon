package com.abufon.app

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * The parent's settings (changed in [SettingsActivity], kept in SharedPreferences), v4:
 *  - pause: nobody calls or receives calls until it is switched off
 *  - one calling window, in the tablet's LOCAL time, for calls in both directions (on/off)
 *  - an optional second window: when on, granny's incoming calls follow it instead
 *  - auto-answer on/off and its countdown
 *  - the chirp when the calling hours begin (on by default)
 * The auto-answer defaults come from abufon.properties; the schedule starts switched off.
 * The rules themselves live in [Schedule.Rules] (pure and unit-tested); this object stores them.
 */
object Settings {
    private lateinit var prefs: SharedPreferences

    // Keys of the earlier design (two windows, one on granny's clock). The new keys have other
    // names, so an old value is never read with a new meaning; the old ones are just removed.
    private val OLD_KEYS = listOf(
        "call_window", "granny_offset", "call_from", "call_to",
        "incoming_window", "incoming_from", "incoming_to",
    )

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (OLD_KEYS.any { prefs.contains(it) }) {
            prefs.edit().apply { OLD_KEYS.forEach { remove(it) } }.apply()
        }
    }

    // A stored value of an unexpected type (an older build) counts as "not set".
    private fun bool(key: String, default: Boolean): Boolean =
        try { prefs.getBoolean(key, default) } catch (_: ClassCastException) { default }
    private fun int(key: String, default: Int): Int =
        try { prefs.getInt(key, default) } catch (_: ClassCastException) { default }
    private fun minute(key: String, default: Int): Int = int(key, default).coerceIn(0, Schedule.DAY - 1)
    private fun put(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()
    private fun put(key: String, v: Int) = prefs.edit().putInt(key, v).apply()

    var autoAnswer: Boolean
        get() = bool("auto_answer", BuildConfig.AUTO_ANSWER)
        set(v) = put("auto_answer", v)

    /** Countdown before a call from granny is answered by itself, 1..60 s. */
    var autoAnswerSeconds: Int
        get() = int("auto_answer_seconds", BuildConfig.AUTO_ANSWER_SECONDS).coerceIn(1, 60)
        set(v) = put("auto_answer_seconds", v.coerceIn(1, 60))

    /** "Pausar llamadas": no calls in or out until switched off. */
    var paused: Boolean
        get() = bool("paused", false)
        set(v) = put("paused", v)

    /** "Horario de llamadas" on/off. Off: calls are allowed all day, in both directions. */
    var scheduleEnabled: Boolean
        get() = bool("schedule_on", DEFAULTS.windowOn)
        set(v) = put("schedule_on", v)
    /** The calling window, minutes of the day in local time. */
    var scheduleFrom: Int
        get() = minute("schedule_from", DEFAULTS.window.from)
        set(v) = put("schedule_from", v.coerceIn(0, Schedule.DAY - 1))
    var scheduleTo: Int
        get() = minute("schedule_to", DEFAULTS.window.to)
        set(v) = put("schedule_to", v.coerceIn(0, Schedule.DAY - 1))

    /** "Otro horario para sus llamadas" on/off. Only has an effect while the schedule is on. */
    var secondWindowEnabled: Boolean
        get() = bool("second_on", DEFAULTS.secondOn)
        set(v) = put("second_on", v)
    var secondFrom: Int
        get() = minute("second_from", DEFAULTS.second.from)
        set(v) = put("second_from", v.coerceIn(0, Schedule.DAY - 1))
    var secondTo: Int
        get() = minute("second_to", DEFAULTS.second.to)
        set(v) = put("second_to", v.coerceIn(0, Schedule.DAY - 1))

    /** "Pío al poder llamar": one chirp when the calling hours begin. */
    var chirp: Boolean
        get() = bool("chirp", true)
        set(v) = put("chirp", v)

    private val DEFAULTS = Schedule.Rules()

    /** The current rules as one consistent snapshot. */
    fun rules(): Schedule.Rules = Schedule.Rules(
        paused = paused,
        windowOn = scheduleEnabled,
        window = Schedule.Window(scheduleFrom, scheduleTo),
        secondOn = secondWindowEnabled,
        second = Schedule.Window(secondFrom, secondTo),
    )

    /** May the child call granny right now? */
    fun canCallNow(nowMs: Long = System.currentTimeMillis()): Boolean = rules().canCall(nowMs)

    /** Should a call from granny ring right now? (Otherwise it is declined as busy and logged.) */
    fun acceptsIncomingNow(nowMs: Long = System.currentTimeMillis()): Boolean = rules().acceptsIncoming(nowMs)

    /** When calling granny opens again (epoch ms); null if it is open now, paused or unlimited. */
    fun nextCallOpening(nowMs: Long = System.currentTimeMillis()): Long? = rules().nextCallOpening(nowMs)

    /** When the calling hours that are open now end (epoch ms), for "Hasta ..."; null if closed, paused or unlimited. */
    fun currentWindowEnd(nowMs: Long = System.currentTimeMillis()): Long? = rules().currentWindowEnd(nowMs)

    /** The next moment [canCallNow] flips (epoch ms), to re-check at the exact boundary minute. */
    fun nextCallChange(nowMs: Long = System.currentTimeMillis()): Long? = rules().nextCallChange(nowMs)
}

/**
 * Time wording, v4: "a la 01:00" but "a las 13:00" (R.plurals.time_article, quantity = the hour),
 * with today / tomorrow / yesterday. Every time is the tablet's local time.
 */
object TimeText {
    /** "la 01:00" / "las 13:00" for a moment (epoch ms). */
    fun withArticle(context: Context, ms: Long): String =
        article(context, Schedule.hourLocal(ms), Schedule.formatLocal(ms))

    /** "la 01:00" / "las 13:00" for a minute of the day. */
    fun withArticle(context: Context, minuteOfDay: Int): String =
        article(context, minuteOfDay / 60, Schedule.format(minuteOfDay))

    // The texts are Spanish whatever the tablet's language is, so the plural rule must be the
    // Spanish one too (only 1 is "one"; French, for one, would also say "la 00:30").
    private fun article(context: Context, hour: Int, time: String): String =
        spanish(context).getQuantityString(R.plurals.time_article, hour, time)

    private fun spanish(context: Context): android.content.res.Resources {
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(Locale("es"))
        return context.createConfigurationContext(config).resources
    }

    /** Resting subtitle: "Podrás llamar hoy a las 13:00" / "Podrás llamar mañana a la 01:00". */
    fun restingSubtitle(context: Context, opensMs: Long, nowMs: Long = System.currentTimeMillis()): String =
        context.getString(
            if (Schedule.dayOf(opensMs, nowMs) == Schedule.Day.TODAY) R.string.resting_subtitle_today
            else R.string.resting_subtitle_tomorrow,
            withArticle(context, opensMs)
        )

    /** Missed-call subtitle: "Hoy a las 15:20" / "Ayer a la 01:05" / "El 3 de noviembre a las 15:20". */
    fun missedSubtitle(context: Context, atMs: Long, nowMs: Long = System.currentTimeMillis()): String =
        when (Schedule.dayOf(atMs, nowMs)) {
            Schedule.Day.TODAY, Schedule.Day.TOMORROW -> context.getString(R.string.missed_today, withArticle(context, atMs))
            Schedule.Day.YESTERDAY -> context.getString(R.string.missed_yesterday, withArticle(context, atMs))
            Schedule.Day.OTHER -> context.getString(
                R.string.missed_earlier,
                java.text.SimpleDateFormat("d 'de' MMMM", Locale("es")).format(java.util.Date(atMs)),
                withArticle(context, atMs)
            )
        }

    /** Status card, first line's detail: "Hasta la 01:00" / "Desde las 13:00". */
    fun until(context: Context, ms: Long): String = context.getString(R.string.status_until, withArticle(context, ms))
    fun from(context: Context, ms: Long): String = context.getString(R.string.status_from, withArticle(context, ms))
}
