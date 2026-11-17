package com.abufon.app

/**
 * Pure maths for the 24-hour schedule bar in the settings (no Android imports, unit-tested).
 * The bar runs from 00:00 at 0.0 to 24:00 at 1.0.
 */
object ScheduleBar {
    /** One filled stretch of the bar, as fractions of the day: [start] < [end], both 0..1. */
    data class Segment(val start: Float, val end: Float)

    /**
     * The stretches to fill for [window]: one for a window inside the day, two for one that
     * crosses midnight (split there), the whole bar for an all-day window (from == to).
     */
    fun segments(window: Schedule.Window): List<Segment> {
        val day = Schedule.DAY.toFloat()
        val from = window.from / day
        val to = window.to / day
        return when {
            window.allDay -> listOf(Segment(0f, 1f))
            window.from < window.to -> listOf(Segment(from, to))
            else -> listOfNotNull(
                Segment(0f, to).takeIf { window.to > 0 },
                Segment(from, 1f),
            )
        }
    }

    /** Where "now" sits on the bar for a minute of the day. */
    fun position(minuteOfDay: Int): Float = minuteOfDay.coerceIn(0, Schedule.DAY) / Schedule.DAY.toFloat()

    /** A stored minute snapped to the time picker's 15-minute steps (the old picker allowed any minute). */
    fun snap15(minuteOfDay: Int): Int =
        Math.floorMod(Math.round(minuteOfDay / 15f) * 15, Schedule.DAY)
}
