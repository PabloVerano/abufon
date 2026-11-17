package com.abufon.app

import com.abufon.app.Schedule.Day
import com.abufon.app.Schedule.Rules
import com.abufon.app.Schedule.Window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ScheduleTest {
    private val paris = TimeZone.getTimeZone("Europe/Paris")

    private fun at(zone: String, y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
            clear(); set(y, mo - 1, d, h, mi)
        }.timeInMillis

    private fun paris(y: Int, mo: Int, d: Int, h: Int, mi: Int) = at("Europe/Paris", y, mo, d, h, mi)
    private fun t(h: Int, m: Int = 0) = h * 60 + m
    private val min = 60_000L
    private val hour = 60 * min

    // ---- windows

    @Test fun daytimeWindow() {
        assertFalse(Schedule.inWindow(8 * 60 + 59, 9 * 60, 21 * 60))
        assertTrue(Schedule.inWindow(9 * 60, 9 * 60, 21 * 60))
        assertTrue(Schedule.inWindow(20 * 60 + 59, 9 * 60, 21 * 60))
        assertFalse(Schedule.inWindow(21 * 60, 9 * 60, 21 * 60)) // end is exclusive
    }

    @Test fun windowAcrossMidnight() {
        assertTrue(Schedule.inWindow(23 * 60, 22 * 60, 7 * 60))
        assertTrue(Schedule.inWindow(0, 22 * 60, 7 * 60))
        assertTrue(Schedule.inWindow(6 * 60 + 59, 22 * 60, 7 * 60))
        assertFalse(Schedule.inWindow(7 * 60, 22 * 60, 7 * 60))
        assertFalse(Schedule.inWindow(12 * 60, 22 * 60, 7 * 60))
    }

    @Test fun sameStartAndEndMeansAllDay() {
        assertTrue(Schedule.inWindow(0, 600, 600))
        assertTrue(Schedule.inWindow(1439, 600, 600))
        assertTrue(Window(600, 600).allDay)
        val rules = Rules(windowOn = true, window = Window(600, 600))
        val now = paris(2026, 11, 10, 3, 0)
        assertTrue(rules.canCall(now, paris))
        assertTrue(rules.acceptsIncoming(now, paris))
        assertNull(rules.nextCallOpening(now, paris))
        assertNull(rules.currentWindowEnd(now, paris))
        assertNull(rules.nextCallChange(now, paris))
    }

    @Test fun localClockFollowsDaylightSaving() {
        assertEquals(12 * 60 + 30, Schedule.minuteOfDayLocal(paris(2026, 7, 10, 12, 30), paris))
        assertEquals(12 * 60 + 30, Schedule.minuteOfDayLocal(paris(2026, 11, 10, 12, 30), paris))
        assertEquals(12, Schedule.hourLocal(paris(2026, 7, 10, 12, 30), paris))
    }

    @Test fun minutesUntilOpen() {
        assertEquals(0, Schedule.minutesUntilOpen(10 * 60, 9 * 60, 21 * 60))
        assertEquals(30, Schedule.minutesUntilOpen(8 * 60 + 30, 9 * 60, 21 * 60))
        assertEquals(12 * 60, Schedule.minutesUntilOpen(21 * 60, 9 * 60, 21 * 60)) // closed at 21:00, opens 09:00
        assertEquals(10 * 60, Schedule.minutesUntilOpen(12 * 60, 22 * 60, 7 * 60))
    }

    @Test fun labels() {
        assertEquals("09:05", Schedule.format(9 * 60 + 5))
        assertEquals("00:00", Schedule.format(0))
        assertEquals("13:00", Schedule.formatLocal(paris(2026, 11, 10, 13, 0), paris))
    }

    // ---- the rules

    @Test fun scheduleOffMeansNoLimit() {
        val rules = Rules() // defaults: nothing switched on
        val night = paris(2026, 11, 10, 3, 0)
        assertTrue(rules.canCall(night, paris))
        assertTrue(rules.acceptsIncoming(night, paris))
        assertNull(rules.nextCallOpening(night, paris))
        assertNull(rules.currentWindowEnd(night, paris))
        assertNull(rules.callWindow)
        assertNull(rules.incomingWindow)
    }

    @Test fun oneWindowGovernsBothDirections() {
        val rules = Rules(windowOn = true, window = Window(t(13), t(1)))
        // Boundary minutes: opens at 13:00 sharp, closes at 01:00 sharp.
        for ((h, m, open) in listOf(
            Triple(12, 59, false), Triple(13, 0, true), Triple(23, 59, true),
            Triple(0, 0, true), Triple(0, 59, true), Triple(1, 0, false), Triple(8, 0, false),
        )) {
            val now = paris(2026, 11, 10, h, m)
            assertEquals("call at $h:$m", open, rules.canCall(now, paris))
            assertEquals("incoming at $h:$m", open, rules.acceptsIncoming(now, paris))
        }
        // Seconds inside a minute don't matter.
        assertFalse(rules.canCall(paris(2026, 11, 10, 12, 59) + 59_999, paris))
        assertTrue(rules.canCall(paris(2026, 11, 10, 13, 0) + 1, paris))
    }

    @Test fun pauseStopsEverything() {
        val rules = Rules(paused = true, windowOn = true, window = Window(t(9), t(21)))
        val noon = paris(2026, 11, 10, 12, 0)
        assertFalse(rules.canCall(noon, paris))
        assertFalse(rules.acceptsIncoming(noon, paris))
        assertNull(rules.nextCallOpening(noon, paris))
        assertNull(rules.currentWindowEnd(noon, paris))
        assertNull(rules.nextCallChange(noon, paris))
        assertFalse(Rules(paused = true).canCall(noon, paris))
        assertFalse(Rules(paused = true).acceptsIncoming(noon, paris))
    }

    @Test fun secondWindowGovernsOnlyHerCalls() {
        val rules = Rules(
            windowOn = true, window = Window(t(13), t(1)),
            secondOn = true, second = Window(t(15), t(19, 30)),
        )
        // 14:00: the child may call, her calls don't ring yet.
        assertTrue(rules.canCall(paris(2026, 11, 10, 14, 0), paris))
        assertFalse(rules.acceptsIncoming(paris(2026, 11, 10, 14, 0), paris))
        // 15:00 to 19:29: both.
        assertTrue(rules.acceptsIncoming(paris(2026, 11, 10, 15, 0), paris))
        assertTrue(rules.acceptsIncoming(paris(2026, 11, 10, 19, 29), paris))
        assertFalse(rules.acceptsIncoming(paris(2026, 11, 10, 19, 30), paris))
        assertTrue(rules.canCall(paris(2026, 11, 10, 19, 30), paris))
        // The second window REPLACES the first for her calls: it may reach outside it.
        val wide = rules.copy(second = Window(t(8), t(20)))
        assertTrue(wide.acceptsIncoming(paris(2026, 11, 10, 9, 0), paris))
        assertFalse(wide.canCall(paris(2026, 11, 10, 9, 0), paris))
        // Her own opening and closing times.
        assertEquals(paris(2026, 11, 10, 15, 0), rules.nextIncomingOpening(paris(2026, 11, 10, 14, 0), paris))
        assertEquals(paris(2026, 11, 10, 19, 30), rules.incomingWindowEnd(paris(2026, 11, 10, 16, 0), paris))
        // Equal start and end in the second window: her calls ring all day.
        assertTrue(rules.copy(second = Window(600, 600)).acceptsIncoming(paris(2026, 11, 10, 4, 0), paris))
    }

    @Test fun secondWindowNeedsItsSwitchAndTheSchedule() {
        val morning = paris(2026, 11, 10, 9, 0)
        // Switch off: her calls follow the main window.
        val off = Rules(windowOn = true, window = Window(t(13), t(1)), secondOn = false, second = Window(t(8), t(20)))
        assertFalse(off.acceptsIncoming(morning, paris))
        // Schedule off: no limits at all, whatever the second window says.
        val noSchedule = Rules(windowOn = false, secondOn = true, second = Window(t(15), t(16)))
        assertTrue(noSchedule.acceptsIncoming(morning, paris))
        assertTrue(noSchedule.canCall(morning, paris))
    }

    // ---- next opening / end of the window

    @Test fun nextOpeningTodayAndTomorrow() {
        val rules = Rules(windowOn = true, window = Window(t(13), t(21)))
        val morning = paris(2026, 11, 10, 8, 30)
        assertEquals(paris(2026, 11, 10, 13, 0), rules.nextCallOpening(morning, paris))
        assertEquals(Day.TODAY, Schedule.dayOf(rules.nextCallOpening(morning, paris)!!, morning, paris))
        val evening = paris(2026, 11, 10, 21, 0) // closed exactly at 21:00
        assertEquals(paris(2026, 11, 11, 13, 0), rules.nextCallOpening(evening, paris))
        assertEquals(Day.TOMORROW, Schedule.dayOf(rules.nextCallOpening(evening, paris)!!, evening, paris))
        // Open now: nothing to wait for.
        assertNull(rules.nextCallOpening(paris(2026, 11, 10, 13, 0), paris))
        // One minute before opening, with seconds on the clock.
        assertEquals(paris(2026, 11, 10, 13, 0), rules.nextCallOpening(paris(2026, 11, 10, 12, 59) + 30_000, paris))
    }

    @Test fun nextOpeningAfterMidnightIsToday() {
        // A window that opens at 01:00: at 23:00 that is tomorrow, at 00:30 it is today.
        val rules = Rules(windowOn = true, window = Window(t(1), t(5)))
        val late = paris(2026, 11, 10, 23, 0)
        assertEquals(paris(2026, 11, 11, 1, 0), rules.nextCallOpening(late, paris))
        assertEquals(Day.TOMORROW, Schedule.dayOf(rules.nextCallOpening(late, paris)!!, late, paris))
        val early = paris(2026, 11, 11, 0, 30)
        assertEquals(Day.TODAY, Schedule.dayOf(rules.nextCallOpening(early, paris)!!, early, paris))
        assertEquals(1, Schedule.hourLocal(rules.nextCallOpening(early, paris)!!, paris)) // "a la 01:00"
    }

    @Test fun currentWindowEnd() {
        val rules = Rules(windowOn = true, window = Window(t(13), t(1)))
        // 16:05 (the designer's example): open until 01:00 tomorrow.
        assertEquals(paris(2026, 11, 11, 1, 0), rules.currentWindowEnd(paris(2026, 11, 10, 16, 5), paris))
        // 00:30: it ends today.
        assertEquals(paris(2026, 11, 11, 1, 0), rules.currentWindowEnd(paris(2026, 11, 11, 0, 30), paris))
        assertEquals(paris(2026, 11, 11, 1, 0), rules.currentWindowEnd(paris(2026, 11, 11, 0, 59), paris))
        // Closed: no end to show.
        assertNull(rules.currentWindowEnd(paris(2026, 11, 11, 1, 0), paris))
        // The next change is whichever comes: the end while open, the opening while closed.
        assertEquals(paris(2026, 11, 11, 1, 0), rules.nextCallChange(paris(2026, 11, 10, 16, 5), paris))
        assertEquals(paris(2026, 11, 11, 13, 0), rules.nextCallChange(paris(2026, 11, 11, 1, 0), paris))
    }

    // ---- daylight saving in Europe/Paris (2026: forward on 29 March, back on 25 October)

    @Test fun openingFollowsTheLocalClockAcrossDaylightSaving() {
        val rules = Rules(windowOn = true, window = Window(t(9), t(21)))
        // Evening before the clocks go forward: 09:00 tomorrow is 11 real hours away, not 12.
        val springEve = paris(2026, 3, 28, 21, 0)
        assertEquals(paris(2026, 3, 29, 9, 0), rules.nextCallOpening(springEve, paris))
        assertEquals(11 * hour, rules.nextCallOpening(springEve, paris)!! - springEve)
        // Evening before the clocks go back: 13 real hours.
        val autumnEve = paris(2026, 10, 24, 21, 0)
        assertEquals(paris(2026, 10, 25, 9, 0), rules.nextCallOpening(autumnEve, paris))
        assertEquals(13 * hour, rules.nextCallOpening(autumnEve, paris)!! - autumnEve)
        // And the end of a window that crosses the change.
        val night = Rules(windowOn = true, window = Window(t(22), t(7)))
        assertEquals(paris(2026, 3, 29, 7, 0), night.currentWindowEnd(paris(2026, 3, 28, 23, 0), paris))
        assertEquals(paris(2026, 10, 25, 7, 0), night.currentWindowEnd(paris(2026, 10, 24, 23, 0), paris))
    }

    @Test fun windowBoundaryInsideTheSkippedHour() {
        // 02:30 does not exist on 29 March 2026 in Paris (02:00 jumps to 03:00).
        val rules = Rules(windowOn = true, window = Window(t(2, 30), t(6)))
        val before = paris(2026, 3, 29, 1, 0)
        val opens = rules.nextCallOpening(before, paris)!!
        assertEquals(paris(2026, 3, 29, 3, 0), opens) // the first minute that really is inside the window
        assertEquals(1 * hour, opens - before)
        assertTrue(rules.canCall(opens, paris))
        assertFalse(rules.canCall(opens - min, paris))
    }

    /** Every minute of both change-over days (and their neighbours) against a plain search. */
    @Test fun everyMinuteOfTheDaylightSavingDaysMatchesAPlainSearch() {
        val windows = listOf(
            Window(t(9), t(21)), Window(t(13), t(1)), Window(t(22), t(7)), Window(t(2, 30), t(6)),
            Window(t(1), t(2, 15)), Window(t(2), t(3)), Window(t(3), t(2)), Window(t(2, 45), t(2, 15)),
            Window(t(0), t(2, 30)), Window(t(23, 45), t(0, 15)),
        )
        for (start in listOf(paris(2026, 3, 28, 12, 0), paris(2026, 10, 24, 12, 0))) {
            for (w in windows) {
                val rules = Rules(windowOn = true, window = w)
                // Open/closed for every minute of the next three days, worked out once.
                val n = 3 * Schedule.DAY
                val open = BooleanArray(n + 2 * Schedule.DAY) { Schedule.minuteOfDayLocal(start + it * min, paris) in w }
                for (i in 0 until n step 7) {
                    val now = start + i * min + 20_000 // 20 s into the minute
                    var j = i + 1
                    while (open[j] == open[i]) j++
                    val change = start + j * min
                    val what = "$w at ${java.util.Date(now)}"
                    assertEquals(what, open[i], rules.canCall(now, paris))
                    assertEquals(what, if (open[i]) null else change, rules.nextCallOpening(now, paris))
                    assertEquals(what, if (open[i]) change else null, rules.currentWindowEnd(now, paris))
                    assertEquals(what, change, rules.nextCallChange(now, paris))
                }
            }
        }
    }

    // ---- today / tomorrow / yesterday

    @Test fun dayOf() {
        val now = paris(2026, 11, 10, 16, 5)
        assertEquals(Day.TODAY, Schedule.dayOf(paris(2026, 11, 10, 0, 0), now, paris))
        assertEquals(Day.TODAY, Schedule.dayOf(paris(2026, 11, 10, 23, 59), now, paris))
        assertEquals(Day.TOMORROW, Schedule.dayOf(paris(2026, 11, 11, 0, 0), now, paris))
        assertEquals(Day.YESTERDAY, Schedule.dayOf(paris(2026, 11, 9, 23, 59), now, paris))
        assertEquals(Day.OTHER, Schedule.dayOf(paris(2026, 11, 8, 23, 59), now, paris))
        assertEquals(Day.OTHER, Schedule.dayOf(paris(2026, 11, 12, 0, 0), now, paris))
        // Across New Year, and across the 23-hour and 25-hour days.
        assertEquals(Day.YESTERDAY, Schedule.dayOf(paris(2025, 12, 31, 22, 0), paris(2026, 1, 1, 8, 0), paris))
        assertEquals(Day.TOMORROW, Schedule.dayOf(paris(2026, 1, 1, 0, 30), paris(2025, 12, 31, 23, 0), paris))
        assertEquals(Day.YESTERDAY, Schedule.dayOf(paris(2026, 3, 28, 23, 30), paris(2026, 3, 29, 23, 30), paris))
        assertEquals(Day.TOMORROW, Schedule.dayOf(paris(2026, 10, 26, 0, 10), paris(2026, 10, 25, 0, 10), paris))
        // It is the LOCAL day that counts: 23:30 UTC on the 10th is already the 11th in Paris.
        assertEquals(Day.TOMORROW, Schedule.dayOf(at("UTC", 2026, 11, 10, 23, 30), now, paris))
    }
}
