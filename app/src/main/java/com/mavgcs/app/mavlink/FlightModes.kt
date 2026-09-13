package com.mavgcs.app.mavlink

/** A single ArduPlane flight mode offered by the Flight Mode panel. */
data class PlaneModeButton(val label: String, val customMode: Long)

object FlightModes {
    private val copter = mapOf(
        0L to "STABILIZE",
        1L to "ACRO",
        2L to "ALT_HOLD",
        3L to "AUTO",
        4L to "GUIDED",
        5L to "LOITER",
        6L to "RTL",
        7L to "CIRCLE",
        9L to "LAND",
        11L to "DRIFT",
        13L to "SPORT",
        14L to "FLIP",
        15L to "AUTOTUNE",
        16L to "POSHOLD",
        17L to "BRAKE",
        18L to "THROW",
        21L to "SMART_RTL",
        25L to "AUTO_RTL",
    )

    private val plane = mapOf(
        0L to "MANUAL",
        1L to "CIRCLE",
        2L to "STABILIZE",
        3L to "TRAINING",
        4L to "ACRO",
        5L to "FBWA",
        6L to "FBWB",
        7L to "CRUISE",
        8L to "AUTOTUNE",
        10L to "AUTO",
        11L to "RTL",
        12L to "LOITER",
        13L to "TAKEOFF",
        14L to "AVOID_ADSB",
        15L to "GUIDED",
        16L to "INITIALISING",
        17L to "QSTABILIZE",
        18L to "QHOVER",
        19L to "QLOITER",
        20L to "QLAND",
        21L to "QRTL",
        22L to "QAUTOTUNE",
        23L to "QACRO",
        24L to "THERMAL",
        25L to "LOITER_ALT_QLAND",
        26L to "AUTOLAND",
    )

    /** Rows of the Flight Mode panel, laid out three across. */
    val planeModeRows = listOf(
        listOf(
            PlaneModeButton("MANUAL", 0L),
            PlaneModeButton("FBWA", 5L),
            PlaneModeButton("CRUISE", 7L),
        ),
        listOf(
            PlaneModeButton("LOITER", 12L),
            PlaneModeButton("AUTO", 10L),
            PlaneModeButton("RTL", 11L),
        ),
        listOf(
            PlaneModeButton("TAKEOFF", 13L),
            PlaneModeButton("AUTOLAND", 26L),
            PlaneModeButton("AUTOTUNE", 8L),
        ),
    )

    val planeGuidedMode = PlaneModeButton("GUIDED", 15L)

    /** Quadplane modes, offered behind the VTOL dropdown. */
    val vtolModes = listOf(
        PlaneModeButton("QSTABILIZE", 17L),
        PlaneModeButton("QHOVER", 18L),
        PlaneModeButton("QLOITER", 19L),
        PlaneModeButton("QLAND", 20L),
        PlaneModeButton("QRTL", 21L),
        PlaneModeButton("QAUTOTUNE", 22L),
        PlaneModeButton("QACRO", 23L),
    )

    private val rover = mapOf(
        0L to "MANUAL",
        3L to "STEERING",
        4L to "HOLD",
        5L to "LOITER",
        10L to "AUTO",
        11L to "RTL",
        12L to "SMART_RTL",
        15L to "GUIDED",
    )

    fun ardupilotMode(vehicleType: String, customMode: Long): String {
        val table = when {
            vehicleType.contains("ROVER", ignoreCase = true) ||
                vehicleType.contains("SURFACE", ignoreCase = true) -> rover
            vehicleType.contains("PLANE", ignoreCase = true) ||
                vehicleType.contains("FIXED", ignoreCase = true) ||
                vehicleType.contains("VTOL", ignoreCase = true) -> plane
            else -> copter
        }
        return table[customMode] ?: "MODE $customMode"
    }

    fun ardupilotCustomMode(vehicleType: String, command: GcsCommand): Long? {
        val copterModes = mapOf(
            GcsCommand.STABILIZE to 0L,
            GcsCommand.AUTO to 3L,
            GcsCommand.GUIDED to 4L,
            GcsCommand.LOITER to 5L,
            GcsCommand.RTL to 6L,
            GcsCommand.LAND to 9L,
        )
        val planeModes = mapOf(
            GcsCommand.STABILIZE to 2L,
            GcsCommand.AUTO to 10L,
            GcsCommand.RTL to 11L,
            GcsCommand.LOITER to 12L,
            GcsCommand.GUIDED to 15L,
            GcsCommand.LAND to 20L,
        )
        return if (
            vehicleType.contains("PLANE", ignoreCase = true) ||
            vehicleType.contains("FIXED", ignoreCase = true)
        ) {
            planeModes[command]
        } else {
            copterModes[command]
        }
    }

    fun px4ModeName(customMode: Long): String {
        val main = (customMode shr 16) and 0xFF
        val sub = (customMode shr 24) and 0xFF
        return when (main) {
            1L -> "MANUAL"
            2L -> "ALTITUDE"
            3L -> "POSITION"
            4L -> when (sub) {
                1L -> "AUTO READY"
                2L -> "AUTO TAKEOFF"
                3L -> "AUTO LOITER"
                4L -> "AUTO MISSION"
                5L -> "AUTO RTL"
                6L -> "AUTO LAND"
                else -> "AUTO"
            }
            5L -> "ACRO"
            6L -> "OFFBOARD"
            7L -> "STABILIZED"
            else -> "PX4 $customMode"
        }
    }

    fun px4CustomMode(command: GcsCommand): Long? {
        fun pack(main: Long, sub: Long = 0L): Long = (main shl 16) or (sub shl 24)
        return when (command) {
            GcsCommand.STABILIZE -> pack(7)
            GcsCommand.LOITER -> pack(4, 3)
            GcsCommand.AUTO -> pack(4, 4)
            GcsCommand.RTL -> pack(4, 5)
            GcsCommand.LAND -> pack(4, 6)
            GcsCommand.TAKEOFF -> pack(4, 2)
            GcsCommand.GUIDED -> pack(6)
            else -> null
        }
    }
}
