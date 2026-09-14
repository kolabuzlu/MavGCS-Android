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
    /** Course over ground, which parts from heading in any crosswind. */
    val groundCourseDeg: Float? = null,
    /** Yaw rate, positive to the right; the arc of the predicted track. */
    val yawRateDegSec: Float = 0f,
    val throttlePct: Int? = null,
    val climbMs: Float? = null,
    val gpsFix: String = "NO GPS",
    val satellites: Int = 0,
    val hdop: Float? = null,
    val batteryV: Float? = null,
    val batteryA: Float? = null,
    val batteryRemainingPct: Int? = null,
    val rangefinderM: Float? = null,
    val distToHomeM: Float? = null,
    val distToWpM: Float? = null,
    val windDirectionDeg: Float? = null,
    val windSpeedMs: Float? = null,
    val qnhHpa: Float? = null,
    val terrainAltM: Float? = null,
    val homeLat: Double? = null,
    val homeLon: Double? = null,
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

/** Guided-mode adjustments, each driven by a single value typed by the pilot. */
enum class GuidedAction(val label: String, val unit: String) {
    SPEED("Change Speed", "m/s"),
    ALTITUDE("Change Altitude", "m"),
    LOITER_RADIUS("Change Loiter Radius", "m"),
}

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
