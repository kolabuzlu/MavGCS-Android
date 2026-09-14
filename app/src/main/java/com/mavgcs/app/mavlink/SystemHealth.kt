package com.mavgcs.app.mavlink

import io.dronefleet.mavlink.common.MavSysStatusSensor
import io.dronefleet.mavlink.util.EnumValue
import java.util.Locale

/**
 * How a subsystem is doing.
 *
 * Four states rather than two. SYS_STATUS carries three bitmasks -- present,
 * enabled, healthy -- and they answer different questions: a sensor can be
 * fitted and switched off, or fitted and broken, and those are not the same
 * trouble. Folding them into one green light would throw away the distinction
 * that says which.
 */
enum class HealthState { ABSENT, OFF, OK, WARN, FAILED }

/** Mission Planner's three-way tint for its EKF and vibration indicators. */
enum class HealthTint { WHITE, YELLOW, RED }

/**
 * Mission Planner's three-way banding, shared by the EKF and vibration reads.
 */
fun tintFor(value: Float, warn: Float = 0.5f, bad: Float = 0.8f): HealthTint = when {
    value > bad -> HealthTint.RED
    value > warn -> HealthTint.YELLOW
    else -> HealthTint.WHITE
}

/**
 * Mission Planner's EKF score: the worst of the five variances, except that
 * three flag states force the top of the scale whatever the variances say.
 *
 * MP deliberately does not check EKF_GPS_GLITCHING or EKF_CONST_POS_MODE here,
 * despite their names suggesting otherwise.
 */
@Suppress("LongParameterList")
fun ekfScore(
    velocityVariance: Float,
    compassVariance: Float,
    posHorizVariance: Float,
    posVertVariance: Float,
    terrainVariance: Float,
    hasAttitude: Boolean,
    hasVelocityHoriz: Boolean,
    uninitialised: Boolean,
    haveGpsFix: Boolean,
): Float = when {
    !hasAttitude -> 1f
    !hasVelocityHoriz && haveGpsFix -> 1f
    uninitialised -> 1f
    else -> maxOf(
        velocityVariance,
        compassVariance,
        posHorizVariance,
        posVertVariance,
        terrainVariance,
    )
}

/** The airframe's own verdict, on the raw per-axis figures alone. */
fun vibeTint(x: Float, y: Float, z: Float): HealthTint =
    tintFor(maxOf(x, y, z), warn = 30f, bad = 60f)

/** One cell of the Systems strip. */
data class SystemStatus(
    val label: String,
    val state: HealthState,
    val detail: String,
)

/**
 * The autopilot's own view of every subsystem it reports on.
 *
 * Green is meant to mean nothing is wrong, not merely that the autopilot has
 * not declared the part broken. SYS_STATUS answers the narrower question --
 * is the hardware working -- and it goes on answering yes through a GPS with
 * no fix, a compass arguing with its neighbours, and an airframe shaking
 * itself apart. So where the aircraft sends something sharper, that decides
 * too; it can only ever make a cell worse.
 */
object SystemHealth {

    /**
     * Bit and label, roughly in the order they matter on a preflight: the IMU,
     * then what corrects it, then what it needs to navigate.
     */
    private val SENSORS = listOf(
        MavSysStatusSensor.MAV_SYS_STATUS_SENSOR_3D_GYRO to "GYRO",
        MavSysStatusSensor.MAV_SYS_STATUS_SENSOR_3D_ACCEL to "ACC",
        MavSysStatusSensor.MAV_SYS_STATUS_SENSOR_3D_MAG to "MAG",
        MavSysStatusSensor.MAV_SYS_STATUS_SENSOR_ABSOLUTE_PRESSURE to "BARO",
        MavSysStatusSensor.MAV_SYS_STATUS_SENSOR_GPS to "GPS",
        MavSysStatusSensor.MAV_SYS_STATUS_SENSOR_LASER_POSITION to "RNGFND",
        MavSysStatusSensor.MAV_SYS_STATUS_SENSOR_DIFFERENTIAL_PRESSURE to "PITOT",
        MavSysStatusSensor.MAV_SYS_STATUS_AHRS to "EKF",
    )

    /**
     * Mission Planner's own bands for an EKF variance: over 0.5 is worth a
     * look, over 0.8 means the filter is rejecting the measurement.
     */
    private const val VARIANCE_WARN = 0.5f
    private const val VARIANCE_BAD = 0.8f

    /**
     * What ArduPilot itself calls a good enough GPS to arm on: GPS_HDOP_GOOD
     * defaults to 1.4, and the arming check wants six satellites. A fix that
     * would not pass those is a fix, but it is not a healthy GPS.
     */
    private const val HDOP_GOOD = 1.4f
    private const val SATS_GOOD = 6

    private const val TIP_ABSENT = "not fitted, or not reported by this autopilot"
    private const val TIP_OFF = "fitted but not enabled"
    private const val TIP_OK = "present, enabled and healthy"
    private const val TIP_FAILED = "present and enabled, but reporting unhealthy"
    private const val TIP_NO_TELEMETRY = "no telemetry"

    /** Every cell, in strip order. */
    fun cells(vehicle: VehicleState): List<SystemStatus> {
        val present = vehicle.sensorsPresent
        val enabled = vehicle.sensorsEnabled
        val health = vehicle.sensorsHealth
        // Nothing to ask: leave every cell dim rather than guessing.
        if (present == null || enabled == null || health == null) {
            return SENSORS.map { (_, label) ->
                SystemStatus(label, HealthState.ABSENT, TIP_NO_TELEMETRY)
            }
        }
        return SENSORS.map { (sensor, label) ->
            val base = baseState(sensor, present, enabled, health)
            if (base != HealthState.OK) {
                return@map SystemStatus(label, base, tipFor(base))
            }
            // A good sensor can still be argued down by a sharper source.
            val extra = secondOpinion(label, vehicle)
            if (extra == null) {
                SystemStatus(label, HealthState.OK, TIP_OK)
            } else {
                SystemStatus(label, extra.first, extra.second)
            }
        }
    }

    private fun tipFor(state: HealthState): String = when (state) {
        HealthState.ABSENT -> TIP_ABSENT
        HealthState.OFF -> TIP_OFF
        HealthState.OK -> TIP_OK
        else -> TIP_FAILED
    }

    private fun baseState(
        sensor: MavSysStatusSensor,
        present: Int,
        enabled: Int,
        health: Int,
    ): HealthState = when {
        !sensor.isSetIn(present) -> HealthState.ABSENT
        !sensor.isSetIn(enabled) -> HealthState.OFF
        sensor.isSetIn(health) -> HealthState.OK
        else -> HealthState.FAILED
    }

    /** Tested through the library's own bit mapping rather than a copied constant. */
    private fun MavSysStatusSensor.isSetIn(mask: Int): Boolean =
        runCatching {
            EnumValue.create(MavSysStatusSensor::class.java, mask).flagsEnabled(this)
        }.getOrDefault(false)

    /**
     * A second opinion, for the cells that have one. GYRO and PITOT reach here
     * with nothing: neither the EKF nor any other message carries a figure for
     * them, so those two are only ever as good as the autopilot's health bit.
     */
    private fun secondOpinion(
        label: String,
        vehicle: VehicleState,
    ): Pair<HealthState, String>? {
        var worst: Pair<HealthState, String>? = null

        fun worse(candidate: Pair<HealthState, String>?) {
            if (candidate == null) {
                return
            }
            val rank = { state: HealthState -> if (state == HealthState.FAILED) 2 else 1 }
            if (worst == null || rank(candidate.first) > rank(worst!!.first)) {
                worst = candidate
            }
        }

        when (label) {
            "GPS" -> {
                vehicle.gpsFixType?.let { fix ->
                    if (fix <= 1) {
                        worse(HealthState.FAILED to "no fix")
                    } else if (fix == 2) {
                        worse(HealthState.WARN to "2D fix only")
                    }
                }
                vehicle.satellites?.let { sats ->
                    if (sats < SATS_GOOD) {
                        worse(HealthState.WARN to "only $sats satellites")
                    }
                }
                vehicle.hdop?.let { hdop ->
                    if (hdop > HDOP_GOOD) {
                        worse(HealthState.WARN to "HDOP %.1f".format(Locale.ROOT, hdop))
                    }
                }
                worse(variance("GPS", vehicle.ekfPosHorizVariance))
            }

            "MAG" -> worse(variance("MAG", vehicle.ekfCompassVariance))
            "BARO" -> worse(variance("BARO", vehicle.ekfPosVertVariance))
            "RNGFND" -> worse(variance("RNGFND", vehicle.ekfTerrainVariance))

            "EKF" -> when (vehicle.ekfTint) {
                HealthTint.RED -> worse(HealthState.FAILED to "EKF variances high")
                HealthTint.YELLOW -> worse(HealthState.WARN to "EKF variances raised")
                else -> Unit
            }

            // Vibration is measured off the accelerometers, so it belongs to
            // this cell: the sensor is healthy but what it is being asked to
            // measure through is not.
            "ACC" -> when (vehicle.vibeTint) {
                HealthTint.RED -> worse(HealthState.FAILED to "vibration above 60")
                HealthTint.YELLOW -> worse(HealthState.WARN to "vibration above 30")
                else -> Unit
            }
        }
        return worst
    }

    /** A cell's own EKF variance, if it has one, as a state. */
    private fun variance(label: String, value: Float?): Pair<HealthState, String>? {
        if (value == null || value < 0f) {
            return null
        }
        return when {
            value > VARIANCE_BAD -> HealthState.FAILED to "$label variance high"
            value > VARIANCE_WARN -> HealthState.WARN to "$label variance raised"
            else -> null
        }
    }
}
