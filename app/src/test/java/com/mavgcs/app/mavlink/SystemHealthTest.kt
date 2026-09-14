package com.mavgcs.app.mavlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the Systems strip to the desktop's behaviour.
 *
 * The cases in health_cases.txt were produced by running MavGCS Desktop's own
 * SensorHealthPanel methods -- extracted from main.py, not paraphrased -- over
 * a matrix of inputs: every present/enabled/health combination for each of the
 * eight cells, boundary sweeps on the GPS, HDOP, satellite and variance
 * thresholds, and twelve hundred random combinations for breadth.
 *
 * SITL reports everything healthy, so the amber and red paths never run on a
 * live link. This is what covers them.
 */
class SystemHealthTest {

    @Test
    fun verdictsMatchTheDesktop() {
        val lines = readCases()
        // A silently empty resource would make every assertion below vacuous.
        assertTrue("expected a full case table, got ${lines.size}", lines.size > 1000)

        val mismatches = mutableListOf<String>()
        var checked = 0
        val seen = mutableSetOf<HealthState>()

        for (line in lines) {
            val (encoded, expected) = line.split(">", limit = 2)
            val field = encoded.split("|")
            val vehicle = VehicleState(
                sensorsPresent = field[0].intOrNull(),
                sensorsEnabled = field[1].intOrNull(),
                sensorsHealth = field[2].intOrNull(),
                gpsFixType = field[3].intOrNull(),
                satellites = field[4].intOrNull(),
                hdop = field[5].floatOrNull(),
                ekfCompassVariance = field[6].floatOrNull(),
                ekfPosHorizVariance = field[7].floatOrNull(),
                ekfPosVertVariance = field[8].floatOrNull(),
                ekfTerrainVariance = field[9].floatOrNull(),
                ekfTint = field[10].tintOrNull(),
                vibeTint = field[11].tintOrNull(),
            )

            val actual = SystemHealth.cells(vehicle)
            val wanted = expected.split("~").map { it.split("=", limit = 3) }
            assertEquals("cell count", wanted.size, actual.size)

            for (i in wanted.indices) {
                val (label, state, detail) = Triple(wanted[i][0], wanted[i][1], wanted[i][2])
                val got = actual[i]
                seen += got.state
                checked++
                if (got.label != label ||
                    got.state != state.asState() ||
                    got.detail != detail
                ) {
                    mismatches += "$encoded  $label: desktop=$state/'$detail' " +
                        "tablet=${got.state}/'${got.detail}'"
                }
            }
        }

        // Every verdict must actually occur, or a table that only ever said
        // "ok" would pass while proving nothing.
        assertEquals(
            "every verdict should be exercised",
            setOf(
                HealthState.ABSENT,
                HealthState.OFF,
                HealthState.OK,
                HealthState.WARN,
                HealthState.FAILED,
            ),
            seen,
        )
        assertTrue("expected thousands of assertions, got $checked", checked > 8000)
        assertEquals(
            "${mismatches.size} of $checked cells disagree:\n" +
                mismatches.take(20).joinToString("\n"),
            0,
            mismatches.size,
        )
    }

    @Test
    fun ekfScoreFollowsMissionPlanner() {
        // The worst variance decides when nothing is forced.
        assertEquals(0.4f, score(0.1f, 0.4f, 0.2f, 0.3f, 0.05f), 1e-6f)
        // Missing attitude forces the top of the scale outright.
        assertEquals(1f, score(0f, 0f, 0f, 0f, 0f, hasAttitude = false), 1e-6f)
        // Missing horizontal velocity only counts when there is a fix to lose.
        assertEquals(1f, score(0f, 0f, 0f, 0f, 0f, hasVelocityHoriz = false, fix = true), 1e-6f)
        assertEquals(0f, score(0f, 0f, 0f, 0f, 0f, hasVelocityHoriz = false, fix = false), 1e-6f)
        assertEquals(1f, score(0f, 0f, 0f, 0f, 0f, uninitialised = true), 1e-6f)

        // Mission Planner's bands, on the boundaries themselves.
        assertEquals(HealthTint.WHITE, tintFor(0.5f))
        assertEquals(HealthTint.YELLOW, tintFor(0.51f))
        assertEquals(HealthTint.YELLOW, tintFor(0.8f))
        assertEquals(HealthTint.RED, tintFor(0.81f))
    }

    @Test
    fun vibrationFollowsMissionPlanner() {
        assertEquals(HealthTint.WHITE, vibeTint(30f, 10f, 5f))
        assertEquals(HealthTint.YELLOW, vibeTint(30.1f, 10f, 5f))
        assertEquals(HealthTint.YELLOW, vibeTint(5f, 60f, 5f))
        assertEquals(HealthTint.RED, vibeTint(5f, 5f, 60.1f))
    }

    @Suppress("LongParameterList")
    private fun score(
        velocity: Float,
        compass: Float,
        posHoriz: Float,
        posVert: Float,
        terrain: Float,
        hasAttitude: Boolean = true,
        hasVelocityHoriz: Boolean = true,
        uninitialised: Boolean = false,
        fix: Boolean = true,
    ) = ekfScore(
        velocityVariance = velocity,
        compassVariance = compass,
        posHorizVariance = posHoriz,
        posVertVariance = posVert,
        terrainVariance = terrain,
        hasAttitude = hasAttitude,
        hasVelocityHoriz = hasVelocityHoriz,
        uninitialised = uninitialised,
        haveGpsFix = fix,
    )

    private fun readCases(): List<String> =
        checkNotNull(javaClass.getResourceAsStream("/health_cases.txt")) {
            "health_cases.txt missing from the test resources"
        }.bufferedReader().readLines().filter { it.isNotBlank() }

    private fun String.intOrNull(): Int? = if (this == "-") null else toInt()
    private fun String.floatOrNull(): Float? = if (this == "-") null else toFloat()
    private fun String.tintOrNull(): HealthTint? =
        if (this == "-") null else HealthTint.valueOf(uppercase())

    private fun String.asState(): HealthState = HealthState.valueOf(uppercase())
}
