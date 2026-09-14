package com.mavgcs.app.terrain

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Elevations over a forward polar fan, row-major as `[a * radCells + b]` with
 * NaN where the ground height is not known. Cell `a` runs left to right across
 * the fan and `b` runs outwards from the aircraft.
 */
class TerrainFan(
    val elevations: FloatArray,
    val rangeM: Double,
    val angCells: Int,
    val radCells: Int,
) {
    /** The elevation in the cell, or NaN. */
    fun elevation(angular: Int, radial: Int): Float = elevations[angular * radCells + radial]

    /** How far out the centre of radial ring [radial] sits, in metres. */
    fun distanceOf(radial: Int): Double = rangeM * (radial + 0.5) / radCells

    /** True once at least one cell carries a real height. */
    val hasData: Boolean get() = elevations.any { !it.isNaN() }
}

/**
 * Samples the terrain ahead of the aircraft, matching the desktop's
 * TerrainRadarWorker: the same fan shape, the same speed-driven range steps,
 * and the same rule for when a new sample is worth taking. That last part
 * matters because a fan can mean a fresh block download, which is slow.
 */
object TerrainSampler {

    /** Half-width of the forward fan; the full sweep is twice this. */
    const val HALF_ANGLE_DEG = 60.0
    const val ANG_CELLS = 32
    const val RAD_CELLS = 16

    /** Range steps in metres, picked from ground speed. */
    val RANGE_STEPS = doubleArrayOf(300.0, 900.0, 1800.0, 3600.0)

    /** The smallest step covering this many seconds of flight is chosen. */
    private const val LOOKAHEAD_S = 120.0

    /** Speed must fall this far below a step before dropping back down to it. */
    private const val STEP_DOWN_HYST = 0.7

    private const val EARTH_RADIUS_M = 6_371_000.0

    /**
     * The range to draw at for [speedMs], given the range already in use.
     * Stepping up is immediate, stepping down needs the speed to fall clear of
     * the boundary, so the picture does not flip back and forth in a climb.
     */
    fun nextRange(current: Double, speedMs: Double): Double {
        val need = speedMs * LOOKAHEAD_S
        val index = RANGE_STEPS.indexOfFirst { it == current }.coerceAtLeast(0)
        val target = RANGE_STEPS.firstOrNull { it >= need } ?: RANGE_STEPS.last()
        if (target > current) {
            return target
        }
        if (target < current && index > 0 && need < RANGE_STEPS[index - 1] * STEP_DOWN_HYST) {
            return target
        }
        return current
    }

    /**
     * Elevations over the fan ahead of [lat]/[lon] on [headingDeg], out to
     * [rangeM], sampled at every cell centre. Blocks on the network.
     */
    fun sample(lat: Double, lon: Double, headingDeg: Double, rangeM: Double): TerrainFan {
        val elevations = FloatArray(ANG_CELLS * RAD_CELLS)
        for (a in 0 until ANG_CELLS) {
            val fraction = (a + 0.5) / ANG_CELLS
            val bearing = headingDeg - HALF_ANGLE_DEG + 2 * HALF_ANGLE_DEG * fraction
            for (b in 0 until RAD_CELLS) {
                val distance = rangeM * (b + 0.5) / RAD_CELLS
                val (cellLat, cellLon) = destination(lat, lon, bearing, distance)
                elevations[a * RAD_CELLS + b] =
                    TerrainProvider.elevation(cellLat, cellLon) ?: Float.NaN
            }
        }
        return TerrainFan(elevations, rangeM, ANG_CELLS, RAD_CELLS)
    }

    /** Great-circle destination from a start point, bearing and distance. */
    private fun destination(
        lat: Double,
        lon: Double,
        bearingDeg: Double,
        distanceM: Double,
    ): Pair<Double, Double> {
        val angular = distanceM / EARTH_RADIUS_M
        val bearing = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lon)
        val lat2 = asin(
            sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing),
        )
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(angular) * cos(lat1),
            cos(angular) - sin(lat1) * sin(lat2),
        )
        return Math.toDegrees(lat2) to Math.toDegrees(lon2)
    }

    /** Great-circle distance in metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    /** Smallest turn between two headings, in degrees. */
    fun angleDiff(a: Double, b: Double): Double = abs(((a - b + 540) % 360) - 180)
}
