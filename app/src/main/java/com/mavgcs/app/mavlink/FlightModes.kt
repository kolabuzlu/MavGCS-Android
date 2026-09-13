package com.mavgcs.app.mavlink

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
        14L to "AVOID_ADSB",
        15L to "GUIDED",
        17L to "QSTABILIZE",
        20L to "QLAND",
        21L to "QRTL",
        22L to "QLOITER",
        25L to "TAKEOFF",
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
