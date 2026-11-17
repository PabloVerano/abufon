package com.abufon.app

import java.util.Calendar
import java.util.TimeZone

/**
 * Pure time-window maths for the call schedule (no Android dependencies, so it is unit-tested).
 * Times of day are minutes after midnight (0..1439) on the tablet's LOCAL clock. A window runs
 * from `from` up to, but not including, `to`; it may cross midnight (22:00 to 07:00).
 * `from == to` means "all day".
 */
object Schedule {
    const val DAY = 24 * 60

    /** One calling window in local time. */
    data class Window(val from: Int, val to: Int) {
        val allDay: Boolean get() = from == to
        operator fun contains(minuteOfDay: Int): Boolean = inWindow(minuteOfDay, from, to)
    }

    /**
     * The parent's rules (a snapshot of [Settings]), v4:
     *  - [paused]: nobody calls or receives calls.
     *  - [window], when [windowOn]: the hours for calls in BOTH directions. Off = no limit.
     *  - [second], when [secondOn] (and the schedule itself is on): granny's incoming calls
     *    follow this window INSTEAD of [window]; the child's calls still follow [window].
     */
    data class Rules(
        val paused: Boolean = false,
        val windowOn: Boolean = false,
        val window: Window = Window(13 * 60, 60),
        val secondOn: Boolean = false,
        val second: Window = Window(13 * 60, 20 * 60),
    ) {
        /** The window that limits the child's calls now, or null when there is no limit. */
        val callWindow: Window? get() = if (windowOn && !window.allDay) window else null

        /** The window that limits granny's calls now, or null when there is no limit. */
        val incomingWindow: Window?
            get() = when {
                !windowOn -> null
                secondOn -> second.takeUnless { it.allDay }
                else -> callWindow
            }

        /** May the child call granny at [nowMs]? */
        fun canCall(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Boolean =
            !paused && isOpen(callWindow, nowMs, zone)

        /** Should a call from granny ring at [nowMs]? */
        fun acceptsIncoming(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Boolean =
            !paused && isOpen(incomingWindow, nowMs, zone)

        /** When calling granny opens again; null if it is open now, paused, or never limited. */
        fun nextCallOpening(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Long? =
            if (paused) null else nextOpening(callWindow, nowMs, zone)

        /** When the calling hours that are open now end; null if closed, paused or never limited. */
        fun currentWindowEnd(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Long? =
            if (paused) null else windowEnd(callWindow, nowMs, zone)

        /** The same two questions for granny's incoming calls. */
        fun nextIncomingOpening(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Long? =
            if (paused) null else nextOpening(incomingWindow, nowMs, zone)

        fun incomingWindowEnd(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Long? =
            if (paused) null else windowEnd(incomingWindow, nowMs, zone)

        /**
         * The next moment [canCall] changes (the window opens or closes), for the home screen to
         * switch at the exact boundary minute. Null while paused or when there is no limit.
         */
        fun nextCallChange(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Long? =
            nextCallOpening(nowMs, zone) ?: currentWindowEnd(nowMs, zone)
    }

    enum class Day { YESTERDAY, TODAY, TOMORROW, OTHER }

    fun inWindow(minuteOfDay: Int, from: Int, to: Int): Boolean = when {
        from == to -> true
        from < to -> minuteOfDay in from until to
        else -> minuteOfDay >= from || minuteOfDay < to
    }

    /** Minute of the day at [nowMs] on the local clock (follows daylight saving time). */
    fun minuteOfDayLocal(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Int =
        Math.floorMod(Math.floorDiv(nowMs + zone.getOffset(nowMs), 60_000L), DAY.toLong()).toInt()

    /** Hour of the day (0..23) at [ms] on the local clock: the quantity for R.plurals.time_article. */
    fun hourLocal(ms: Long, zone: TimeZone = TimeZone.getDefault()): Int = minuteOfDayLocal(ms, zone) / 60

    /** Minutes from [minuteOfDay] until the window next opens; 0 if it is open now. */
    fun minutesUntilOpen(minuteOfDay: Int, from: Int, to: Int): Int =
        if (inWindow(minuteOfDay, from, to)) 0 else Math.floorMod(from - minuteOfDay, DAY)

    private fun isOpen(window: Window?, nowMs: Long, zone: TimeZone): Boolean =
        window == null || minuteOfDayLocal(nowMs, zone) in window

    private fun nextOpening(window: Window?, nowMs: Long, zone: TimeZone): Long? {
        if (window == null || isOpen(window, nowMs, zone)) return null
        return nextChange(window, nowMs, zone, wantOpen = true)
    }

    private fun windowEnd(window: Window?, nowMs: Long, zone: TimeZone): Long? {
        if (window == null || !isOpen(window, nowMs, zone)) return null
        return nextChange(window, nowMs, zone, wantOpen = false)
    }

    /**
     * The first whole minute after [nowMs] at which the window is open (or closed). Normally that
     * is simply the next time the local clock shows `from` (or `to`). If the clocks change in
     * between (daylight saving: an hour is skipped or repeated, so a clock time may not exist or
     * may come twice), the minutes are checked one by one instead, as the clock will really read.
     */
    private fun nextChange(window: Window, nowMs: Long, zone: TimeZone, wantOpen: Boolean): Long {
        val start = nowMs - Math.floorMod(nowMs, 60_000L) + 60_000L
        fun matches(ms: Long) = (minuteOfDayLocal(ms, zone) in window) == wantOpen
        if (matches(start)) return start
        val target = if (wantOpen) window.from else window.to
        val jump = start + Math.floorMod(target - minuteOfDayLocal(start, zone), DAY) * 60_000L
        if (zone.getOffset(start) == zone.getOffset(jump)) return jump
        var ms = start
        while (!matches(ms) && ms < start + 3L * DAY * 60_000L) ms += 60_000L
        return ms
    }

    /** Is [thenMs] on the same local calendar day as [nowMs], the day before or the day after? */
    fun dayOf(thenMs: Long, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Day {
        val now = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        val then = Calendar.getInstance(zone).apply { timeInMillis = thenMs }
        fun same(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        if (same(now, then)) return Day.TODAY
        now.add(Calendar.DAY_OF_YEAR, -1)
        if (same(now, then)) return Day.YESTERDAY
        now.add(Calendar.DAY_OF_YEAR, 2)
        if (same(now, then)) return Day.TOMORROW
        return Day.OTHER
    }

    /** "09:05" for a minute of the day. */
    fun format(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    /** "09:05" for a moment, on the local clock. */
    fun formatLocal(ms: Long, zone: TimeZone = TimeZone.getDefault()): String = format(minuteOfDayLocal(ms, zone))
}
