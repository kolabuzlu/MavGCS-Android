package com.mavgcs.app.mavlink

data class VehicleState(
    val linkUp: Boolean = false,
    val lastHeartbeatMs: Long = 0L,
    val packetsIn: Long = 0L,
    val systemId: Int = 0,
    val componentId: Int = 1,
    val autopilot: String = "—",
    val vehicleType: String = "—",
    val firmware: Firmware = Firmware.UNKNOWN,
    val mode: String = "UNKNOWN",
    val customMode: Long = 0L,
    val armed: Boolean = false,
    val systemStatus: String = "—",
    val rollDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val yawDeg: Float = 0f,
    val lat: Double? = null,
    val lon: Double? = null,
    val altMslM: Float? = null,
    val altRelM: Float? = null,
    val groundSpeedMs: Float? = null,
    val airSpeedMs: Float? = null,
    val headingDeg: Float? = null,
    val throttlePct: Int? = null,
    val climbMs: Float? = null,
    val gpsFix: String = "NO GPS",
    val satellites: Int = 0,
    val hdop: Float? = null,
    val batteryV: Float? = null,
    val batteryA: Float? = null,
    val batteryRemainingPct: Int? = null,
    val statusLog: List<String> = emptyList(),
)

enum class Firmware {
    UNKNOWN,
    ARDUPILOT,
    PX4,
}

enum class LinkType {
    UDP,
    TCP,
}

data class LinkConfig(
    val type: LinkType = LinkType.UDP,
    val host: String = "0.0.0.0",
    val port: Int = 14550,
)

enum class GcsCommand {
    ARM,
    DISARM,
    RTL,
    LAND,
    LOITER,
    AUTO,
    STABILIZE,
    GUIDED,
    TAKEOFF,
}
