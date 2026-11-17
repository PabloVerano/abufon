package com.abufon.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLevelTest {
    @Test fun silenceAndNonsenseAreZero() {
        assertEquals(0f, VoiceLevel.fromDb(-120f), 0f)
        assertEquals(0f, VoiceLevel.fromDb(VoiceLevel.QUIET_DB), 0f)
        assertEquals(0f, VoiceLevel.fromDb(Float.NaN), 0f)
        assertEquals(0f, VoiceLevel.fromDb(Float.NEGATIVE_INFINITY), 0f)
    }

    @Test fun loudIsOneAndNeverMore() {
        assertEquals(1f, VoiceLevel.fromDb(VoiceLevel.LOUD_DB), 0f)
        assertEquals(1f, VoiceLevel.fromDb(0f), 0f)
        assertEquals(1f, VoiceLevel.fromDb(30f), 0f)
        assertEquals(1f, VoiceLevel.fromDb(Float.POSITIVE_INFINITY), 0f)
    }

    @Test fun linearInBetween() {
        assertEquals(0.5f, VoiceLevel.fromDb(-25f), 1e-6f)
        assertTrue(VoiceLevel.fromDb(-20f) > VoiceLevel.fromDb(-30f))
    }

    @Test fun ringScalesFollowTheDesign() {
        assertEquals(1.02f, VoiceLevel.innerScale(0f), 1e-6f)
        assertEquals(1.07f, VoiceLevel.innerScale(1f), 1e-6f)
        assertEquals(1.06f, VoiceLevel.outerScale(0f), 1e-6f)
        assertEquals(1.13f, VoiceLevel.outerScale(1f), 1e-6f)
        assertEquals(1.07f, VoiceLevel.innerScale(5f), 1e-6f)
        assertEquals(1.06f, VoiceLevel.outerScale(-1f), 1e-6f)
    }

    @Test fun smoothingReachesAboutTwoThirdsAfterOneTimeConstant() {
        assertEquals(0.632f, VoiceLevel.smooth(0f, 1f, 120f), 0.001f)
        assertEquals(0f, VoiceLevel.smooth(0f, 1f, 0f), 0f)
    }

    @Test fun smoothingIsTheSameInOneStepOrMany() {
        var v = 0f
        repeat(15) { v = VoiceLevel.smooth(v, 1f, 16f) }
        assertEquals(VoiceLevel.smooth(0f, 1f, 240f), v, 0.001f)
        // and it never overshoots, however long the frame took
        assertTrue(VoiceLevel.smooth(0.2f, 1f, 100_000f) <= 1f)
        assertTrue(VoiceLevel.smooth(0.9f, 0f, 100_000f) >= 0f)
    }
}
