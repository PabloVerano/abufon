package com.abufon.app

/**
 * What the home screen shows, v4. Pure (no Android imports) so the priority is unit-tested.
 *
 * Priority, highest first:
 *  OFFLINE  no connection; wins over everything (the offline screen is unchanged from v3)
 *  PAUSED   "Pausar llamadas" is on
 *  RESTING  outside the calling hours
 *  FAILED   "No contestó", right after a call she did not answer
 *  MISSED   normal home plus the letter cue: granny called and nobody answered
 *  HOME     normal home
 *
 * A missed call is only remembered, not shown, while a higher screen is up; it appears as soon as
 * that one ends (e.g. when the resting hours are over), until the next call that connects.
 */
enum class HomeScreen {
    HOME, MISSED, FAILED, RESTING, PAUSED, OFFLINE;

    /** Dusk scene with the plaque: nothing on it can place a call. */
    val resting: Boolean get() = this == RESTING || this == PAUSED

    /** The green call button and the photo place a call. */
    val canDial: Boolean get() = this == HOME || this == MISSED || this == FAILED

    companion object {
        fun pick(offline: Boolean, paused: Boolean, canCall: Boolean, failed: Boolean, missed: Boolean): HomeScreen = when {
            offline -> OFFLINE
            paused -> PAUSED
            !canCall -> RESTING
            failed -> FAILED
            missed -> MISSED
            else -> HOME
        }
    }
}
