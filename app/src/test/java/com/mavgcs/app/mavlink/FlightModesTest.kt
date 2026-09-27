package com.mavgcs.app.mavlink

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the ArduPilot mode numbers the Flight Mode readout depends on.
 *
 * A wrong label here is worse than no label: "MODE 27" tells a pilot to go and
 * look it up, where a confident AUTO_RTL against mode 25 tells them the
 * aircraft is doing something it is not. Both had happened -- 25 was labelled
 * AUTO_RTL, which is SYSTEMID, and AUTO_RTL at 27 was missing entirely and
 * came out as "MODE 27".
 *
 * The numbers are ArduCopter's Mode::Number enum, checked against pymavlink's
 * mode_mapping_acm rather than written from memory. Copter 8 (POSITION) and 10
 * (OF_LOITER) are deliberately absent: they were removed from the firmware
 * years ago and a modern aircraft never reports them.
 */
class FlightModesTest {

    @Test
    fun copterModesAreNamedCorrectly() {
        val expected = mapOf(
            0L to "STABILIZE", 1L to "ACRO", 2L to "ALT_HOLD", 3L to "AUTO",
            4L to "GUIDED", 5L to "LOITER", 6L to "RTL", 7L to "CIRCLE",
            9L to "LAND", 11L to "DRIFT", 13L to "SPORT", 14L to "FLIP",
            15L to "AUTOTUNE", 16L to "POSHOLD", 17L to "BRAKE", 18L to "THROW",
            19L to "AVOID_ADSB", 20L to "GUIDED_NOGPS", 21L to "SMART_RTL",
            22L to "FLOWHOLD", 23L to "FOLLOW", 24L to "ZIGZAG",
            25L to "SYSTEMID", 26L to "AUTOROTATE", 27L to "AUTO_RTL",
        )
        expected.forEach { (number, name) ->
            assertEquals(
                "ArduCopter mode $number",
                name,
                FlightModes.ardupilotMode("QUADROTOR", number),
            )
        }
    }

    /** The two that were wrong, stated on their own so a regression is loud. */
    @Test
    fun theTwoThatWereWrong() {
        assertEquals("SYSTEMID", FlightModes.ardupilotMode("QUADROTOR", 25L))
        assertEquals("AUTO_RTL", FlightModes.ardupilotMode("QUADROTOR", 27L))
    }

    @Test
    fun planeModesAreNamedCorrectly() {
        val expected = mapOf(
            0L to "MANUAL", 5L to "FBWA", 7L to "CRUISE", 10L to "AUTO",
            11L to "RTL", 12L to "LOITER", 13L to "TAKEOFF", 15L to "GUIDED",
            17L to "QSTABILIZE", 21L to "QRTL", 26L to "AUTOLAND",
        )
        expected.forEach { (number, name) ->
            assertEquals(
                "ArduPlane mode $number",
                name,
                FlightModes.ardupilotMode("FIXED_WING", number),
            )
        }
    }

    /**
     * The buttons have to send what the readout reads back, or a mode press
     * would light up as something else.
     */
    @Test
    fun planeButtonsAgreeWithTheReadout() {
        val buttons = FlightModes.planeModeRows.flatten() + FlightModes.planeGuidedMode
        buttons.forEach { button ->
            assertEquals(
                "the ${button.label} button sends ${button.customMode}",
                button.label,
                FlightModes.ardupilotMode("FIXED_WING", button.customMode),
            )
        }
    }

    @Test
    fun roverModesAreNamedCorrectly() {
        val expected = mapOf(
            0L to "MANUAL", 1L to "ACRO", 2L to "LEARNING", 3L to "STEERING",
            4L to "HOLD", 5L to "LOITER", 6L to "FOLLOW", 7L to "SIMPLE",
            8L to "DOCK", 9L to "CIRCLE", 10L to "AUTO", 11L to "RTL",
            12L to "SMART_RTL", 15L to "GUIDED", 16L to "INITIALISING",
        )
        expected.forEach { (number, name) ->
            assertEquals(
                "ArduRover mode $number",
                name,
                FlightModes.ardupilotMode("GROUND_ROVER", number),
            )
        }
    }

    /**
     * A rover must not be read off the copter table. They disagree on most
     * numbers -- 5 is LOITER to both, but 10 is AUTO to a rover and nothing at
     * all to a copter, and 11 is RTL against DRIFT.
     */
    @Test
    fun theVehicleTypePicksTheRightTable() {
        assertEquals("RTL", FlightModes.ardupilotMode("GROUND_ROVER", 11L))
        assertEquals("DRIFT", FlightModes.ardupilotMode("QUADROTOR", 11L))
        assertEquals("RTL", FlightModes.ardupilotMode("FIXED_WING", 11L))
        assertEquals("SMART_RTL", FlightModes.ardupilotMode("SURFACE_BOAT", 12L))
    }

    /** An unknown number is shown as itself rather than guessed at. */
    @Test
    fun unknownModesFallBackToTheirNumber() {
        assertEquals("MODE 99", FlightModes.ardupilotMode("QUADROTOR", 99L))
        assertEquals("MODE 99", FlightModes.ardupilotMode("FIXED_WING", 99L))
    }
}
