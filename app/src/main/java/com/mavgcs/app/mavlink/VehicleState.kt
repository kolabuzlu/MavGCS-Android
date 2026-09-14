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
    // Null until an ATTITUDE arrives. Zero is a perfectly good roll, so a
    // default of 0f would have the panel state the aircraft is level when it
    // is really saying nothing at all.
    val rollDeg: Float? = null,
    val pitchDeg: Float? = null,
    val yawDeg: Float? = null,
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
    val satellites: Int? = null,
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
    /**
     * The mission item the vehicle is currently flying to. Item 0 is the
     * home placeholder, so the pilot's first point is 1.
     */
    val currentWaypointSeq: Int? = null,
    /**
     * Bumped each time the vehicle accepts a mission. Altitudes are only
     * counted as sent when this moves, not when the upload is pressed, so a
     * failed or lost upload keeps showing as pending.
     */
    val missionAccepted: Int = 0,
    /** SYS_STATUS's three sensor bitmasks, carried whole. */
    val sensorsPresent: Int? = null,
    val sensorsEnabled: Int? = null,
    val sensorsHealth: Int? = null,
    /** GPS_RAW_INT's fix type, which the GPS health bit does not cover. */
    val gpsFixType: Int? = null,
    /** EKF variances, for the cells that have one. */
    val ekfCompassVariance: Float? = null,
    val ekfPosHorizVariance: Float? = null,
    val ekfPosVertVariance: Float? = null,
    val ekfTerrainVariance: Float? = null,
    /** Mission Planner's verdicts on the filter and on the airframe. */
    val ekfTint: HealthTint? = null,
    val vibeTint: HealthTint? = null,
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

/**
 * Which way round a UDP link is set up.
 *
 * LISTEN binds a port and waits for the vehicle to stream to it, which is what
 * SITL and most telemetry do. CONNECT dials out to a named peer, which is what
 * a WiFi bridge needs: they stay silent until they have heard from the ground
 * station.
 */
enum class UdpMode { LISTEN, CONNECT }

data class LinkConfig(
    val type: LinkType = LinkType.UDP,
    val host: String = "0.0.0.0",
    val port: Int = 14550,
    val udpMode: UdpMode = UdpMode.LISTEN,
)

/** Guided-mode adjustments, each driven by a single value typed by the pilot. */
enum class GuidedAction(val label: String, val unit: String) {
    SPEED("Change Speed", "m/s"),
    ALTITUDE("Change Altitude", "m"),
    LOITER_RADIUS("Change Loiter Radius", "m"),
}

/**
 * One point of a queued mission. A null altitude means the point has not
 * been given one of its own and takes the mission's.
 */
data class MissionWaypoint(
    val lat: Double,
    val lon: Double,
    val altitudeM: Float? = null,
)

enum class GcsCommand {
    ARM,

    /** Arms with the pre-arm checks bypassed. */
    FORCE_ARM,
    DISARM,

    /** Ground pressure and airspeed calibration, on the ground before flight. */
    PREFLIGHT_CALIBRATION,
    RTL,
    LAND,
    LOITER,
    AUTO,
    STABILIZE,
    GUIDED,
    TAKEOFF,
}
