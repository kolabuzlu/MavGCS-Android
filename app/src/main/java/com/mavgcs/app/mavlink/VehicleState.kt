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
    /**
     * A mode asked for and not yet seen in a heartbeat, or null.
     *
     * On a link that drops a third of what it carries, a press that vanishes
     * looks exactly like a press that was never made. Holding the request
     * visible until the aircraft reports the mode means the pilot presses
     * once and waits, rather than pressing four more times.
     */
    val modePending: String? = null,
    /** What the link is carrying and losing. See [LinkStats]. */
    val link: LinkQuality = LinkQuality(),
    /**
     * Receiver signal strength as a percentage, or null when there is no RC
     * link to report. ArduPilot and PX4 expose only this over MAVLink -- no
     * link quality or signal-to-noise figure in any standard field -- so it is
     * the whole of what can be said about the radio from here.
     */
    val rssiPercent: Float? = null,
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
    /**
     * The bearing the navigation controller is steering, in degrees true.
     *
     * With the distance beside it this says where the vehicle is being taken
     * without downloading the mission: the current waypoint under AUTO, home
     * or the loiter point under RTL, whatever a guided command named.
     */
    val navBearingDeg: Float? = null,
    val windDirectionDeg: Float? = null,
    val windSpeedMs: Float? = null,
    val qnhHpa: Float? = null,
    val terrainAltM: Float? = null,
    val homeLat: Double? = null,
    val homeLon: Double? = null,
    /**
     * Home's height above the sea, in metres.
     *
     * Kept because every relative altitude the aircraft reports is measured
     * from it. Moving home across the map has to carry this figure along
     * unchanged, or the RTL height and the altitude readout move with it.
     */
    val homeAltM: Double? = null,
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
    /** Bumped when the aircraft confirms it has thrown its mission away. */
    val missionCleared: Int = 0,
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
) {
    /**
     * Whether a vehicle has ever identified itself on this link.
     *
     * Latches on the first heartbeat and stays on for the life of the
     * connection, because it answers "is there an aircraft at the other end of
     * this" and not "did one speak in the last three seconds". That is the
     * distinction the panel needs: the controls must not go dead every time a
     * radio stutters, and they must not be live before there is anything to
     * send to. [systemId] is zero until a heartbeat sets it and connect()
     * replaces the whole state, so it is already per-session.
     */
    val heard: Boolean get() = systemId != 0
}

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
/**
 * What a waypoint's altitude is measured from.
 *
 * Absolute is deliberately absent. Height above the sea is the one of the
 * three a pilot cannot read off the ground in front of them, and offering it
 * beside the other two invites picking it by mistake.
 */
enum class AltitudeFrame {
    /** Above the home the aircraft armed at, which is what the app shows. */
    RELATIVE,

    /** Above whatever ground is under the aircraft at the time. */
    TERRAIN,
}

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
