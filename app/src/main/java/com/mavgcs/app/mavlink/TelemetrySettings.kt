package com.mavgcs.app.mavlink

import android.content.Context

/**
 * How much telemetry to ask the vehicle for.
 *
 * A telemetry radio has a fixed budget. ArduPilot's default streams spend most
 * of it on messages this app never reads, and the radio drops whatever
 * overflows without regard for which mattered. Asking for less means what is
 * asked for actually arrives.
 */
object TelemetrySettings {

    private const val FILE = "mavgcs"
    private const val KEY_ATTITUDE = "telemetry_attitude_hz"
    private const val KEY_POSITION = "telemetry_position_hz"
    private const val KEY_FULL = "telemetry_full"

    const val DEFAULT_ATTITUDE_HZ = 5f
    const val DEFAULT_POSITION_HZ = 2f

    /** The rates offered, in Hz. */
    val CHOICES = listOf(1f, 2f, 3f, 5f)

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // A stored value outside the offered set is treated as absent: this is read
    // on every connection, and a stray setting must not be why a link misbehaves.
    fun attitudeHz(context: Context): Float =
        prefs(context).getFloat(KEY_ATTITUDE, DEFAULT_ATTITUDE_HZ)
            .takeIf { it in CHOICES } ?: DEFAULT_ATTITUDE_HZ

    fun positionHz(context: Context): Float =
        prefs(context).getFloat(KEY_POSITION, DEFAULT_POSITION_HZ)
            .takeIf { it in CHOICES } ?: DEFAULT_POSITION_HZ

    fun fullTelemetry(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FULL, false)

    fun setAttitudeHz(context: Context, hz: Float) {
        prefs(context).edit().putFloat(KEY_ATTITUDE, hz).apply()
    }

    fun setPositionHz(context: Context, hz: Float) {
        prefs(context).edit().putFloat(KEY_POSITION, hz).apply()
    }

    fun setFullTelemetry(context: Context, full: Boolean) {
        prefs(context).edit().putBoolean(KEY_FULL, full).apply()
    }

    /** What the client should currently be asking for. */
    fun current(context: Context) = StreamRates(
        attitudeHz = attitudeHz(context),
        positionHz = positionHz(context),
        full = fullTelemetry(context),
    )
}

/** The rates one connection was opened with. */
data class StreamRates(
    val attitudeHz: Float = TelemetrySettings.DEFAULT_ATTITUDE_HZ,
    val positionHz: Float = TelemetrySettings.DEFAULT_POSITION_HZ,
    val full: Boolean = false,
)
