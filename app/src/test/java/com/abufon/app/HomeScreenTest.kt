package com.abufon.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeScreenTest {
    private fun pick(offline: Boolean = false, paused: Boolean = false, canCall: Boolean = true,
                     failed: Boolean = false, missed: Boolean = false) =
        HomeScreen.pick(offline, paused, canCall, failed, missed)

    @Test fun plainHome() = assertEquals(HomeScreen.HOME, pick())

    @Test fun offlineWinsOverEverything() {
        assertEquals(HomeScreen.OFFLINE, pick(offline = true, paused = true, canCall = false, failed = true, missed = true))
        assertEquals(HomeScreen.OFFLINE, pick(offline = true, canCall = false))
    }

    @Test fun pausedWinsOverResting() {
        // Schedule.Rules.canCall is already false while paused; either way the screen says "en pausa".
        assertEquals(HomeScreen.PAUSED, pick(paused = true, canCall = false))
        assertEquals(HomeScreen.PAUSED, pick(paused = true, canCall = true, missed = true))
    }

    @Test fun restingHidesFailedAndMissed() {
        assertEquals(HomeScreen.RESTING, pick(canCall = false, failed = true, missed = true))
    }

    @Test fun missedShowsOnceRestingEnds() {
        assertEquals(HomeScreen.RESTING, pick(canCall = false, missed = true))
        assertEquals(HomeScreen.MISSED, pick(canCall = true, missed = true))
    }

    @Test fun noAnswerWinsOverAnOlderMissedCall() {
        assertEquals(HomeScreen.FAILED, pick(failed = true, missed = true))
        assertEquals(HomeScreen.FAILED, pick(failed = true))
    }

    @Test fun onlyTheDayScreensCanDial() {
        for (s in HomeScreen.values()) {
            val day = s == HomeScreen.HOME || s == HomeScreen.MISSED || s == HomeScreen.FAILED
            assertEquals(s.name, day, s.canDial)
            if (s.resting) assertFalse(s.name, s.canDial)
        }
        assertTrue(HomeScreen.RESTING.resting && HomeScreen.PAUSED.resting)
        assertFalse(HomeScreen.OFFLINE.resting)
    }
}
