package com.abufon.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleBarTest {
    private fun seg(from: Int, to: Int) = ScheduleBar.segments(Schedule.Window(from, to))
    private val eps = 1e-6f

    @Test fun windowInsideTheDayIsOneSegment() {
        val s = seg(9 * 60, 18 * 60)
        assertEquals(1, s.size)
        assertEquals(9 / 24f, s[0].start, eps)
        assertEquals(18 / 24f, s[0].end, eps)
    }

    @Test fun windowCrossingMidnightIsSplitThere() {
        val s = seg(13 * 60, 60)
        assertEquals(listOf(ScheduleBar.Segment(0f, 1 / 24f), ScheduleBar.Segment(13 / 24f, 1f)), s)
    }

    @Test fun windowEndingAtMidnightHasNoEmptyMorningPart() {
        val s = seg(20 * 60, 0)
        assertEquals(listOf(ScheduleBar.Segment(20 / 24f, 1f)), s)
    }

    @Test fun windowStartingAtMidnight() {
        val s = seg(0, 7 * 60)
        assertEquals(listOf(ScheduleBar.Segment(0f, 7 / 24f)), s)
    }

    @Test fun equalStartAndEndFillsTheWholeBar() {
        assertEquals(listOf(ScheduleBar.Segment(0f, 1f)), seg(13 * 60, 13 * 60))
        assertEquals(listOf(ScheduleBar.Segment(0f, 1f)), seg(0, 0))
    }

    @Test fun nowPosition() {
        assertEquals(0f, ScheduleBar.position(0), eps)
        assertEquals(0.5f, ScheduleBar.position(12 * 60), eps)
        assertEquals((16 * 60 + 5) / 1440f, ScheduleBar.position(16 * 60 + 5), eps)
    }

    @Test fun snapTo15Minutes() {
        assertEquals(13 * 60, ScheduleBar.snap15(13 * 60))
        assertEquals(13 * 60 + 15, ScheduleBar.snap15(13 * 60 + 8))
        assertEquals(13 * 60, ScheduleBar.snap15(13 * 60 + 7))
        assertEquals(0, ScheduleBar.snap15(23 * 60 + 55)) // wraps to midnight
    }
}
