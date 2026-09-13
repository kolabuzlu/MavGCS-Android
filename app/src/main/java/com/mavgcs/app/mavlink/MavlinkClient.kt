package com.mavgcs.app.mavlink

import io.dronefleet.mavlink.MavlinkConnection
import io.dronefleet.mavlink.MavlinkMessage
import io.dronefleet.mavlink.ardupilotmega.Rangefinder
import io.dronefleet.mavlink.ardupilotmega.Wind
import io.dronefleet.mavlink.common.Attitude
import io.dronefleet.mavlink.common.BatteryStatus
import io.dronefleet.mavlink.common.CommandInt
import io.dronefleet.mavlink.common.CommandLong
import io.dronefleet.mavlink.common.GlobalPositionInt
import io.dronefleet.mavlink.common.GpsRawInt
import io.dronefleet.mavlink.common.HomePosition
import io.dronefleet.mavlink.common.NavControllerOutput
import io.dronefleet.mavlink.common.ParamSet
import io.dronefleet.mavlink.common.MavCmd
import io.dronefleet.mavlink.common.MavFrame
import io.dronefleet.mavlink.common.MavParamType
import io.dronefleet.mavlink.common.RequestDataStream
import io.dronefleet.mavlink.common.ScaledPressure
import io.dronefleet.mavlink.common.SetMode
import io.dronefleet.mavlink.common.Statustext
import io.dronefleet.mavlink.common.TerrainReport
import io.dronefleet.mavlink.common.SysStatus
import io.dronefleet.mavlink.common.VfrHud
import io.dronefleet.mavlink.minimal.Heartbeat
import io.dronefleet.mavlink.minimal.MavAutopilot
import io.dronefleet.mavlink.minimal.MavModeFlag
import io.dronefleet.mavlink.minimal.MavState
import io.dronefleet.mavlink.minimal.MavType
import io.dronefleet.mavlink.util.EnumValue
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MavlinkClient {
    private val _state = MutableStateFlow(VehicleState())
    val state: StateFlow<VehicleState> = _state.asStateFlow()

    private val running = AtomicBoolean(false)
    private val connectionRef = AtomicReference<MavlinkConnection?>(null)
    private val target = AtomicReference<Pair<Int, Int>?>(null)
    private var worker: Thread? = null
    private var heartbeat: Thread? = null
    private var datagramSocket: DatagramSocket? = null
    private var tcpSocket: Socket? = null

    /**
     * Every outbound frame goes through this one thread. It keeps socket writes
     * off the caller's thread -- button handlers run on the main thread, which
     * Android forbids from touching a socket -- and serialises commands against
     * the 1 Hz heartbeat so two frames cannot interleave mid-write on one stream.
     */
    private val tx: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mavlink-tx").apply { isDaemon = true }
    }

    fun connect(config: LinkConfig) {
        disconnect()
        running.set(true)
        _state.value = VehicleState()
        worker = thread(name = "mavlink-rx", isDaemon = true) {
            try {
                when (config.type) {
                    LinkType.UDP -> runUdp(config)
                    LinkType.TCP -> runTcp(config)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                appendStatus("Link error: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                running.set(false)
                _state.update { it.copy(linkUp = false) }
            }
        }
    }

    fun disconnect() {
        running.set(false)
        worker?.interrupt()
        heartbeat?.interrupt()
        runCatching { datagramSocket?.close() }
        runCatching { tcpSocket?.close() }
        connectionRef.set(null)
        worker = null
        heartbeat = null
        datagramSocket = null
        tcpSocket = null
        _state.update { it.copy(linkUp = false) }
    }

    fun send(command: GcsCommand) {
        val connection = connectionRef.get() ?: return
        val (sys, comp) = target.get() ?: (1 to 1)
        val snapshot = _state.value
        tx.execute {
            try {
                when (command) {
                    GcsCommand.ARM -> sendCommand(connection, sys, comp, MavCmd.MAV_CMD_COMPONENT_ARM_DISARM, 1f)
                    GcsCommand.DISARM -> sendCommand(connection, sys, comp, MavCmd.MAV_CMD_COMPONENT_ARM_DISARM, 0f)
                    GcsCommand.RTL -> sendCommand(connection, sys, comp, MavCmd.MAV_CMD_NAV_RETURN_TO_LAUNCH)
                    GcsCommand.LAND -> sendCommand(connection, sys, comp, MavCmd.MAV_CMD_NAV_LAND)
                    GcsCommand.TAKEOFF -> sendCommand(
                        connection,
                        sys,
                        comp,
                        MavCmd.MAV_CMD_NAV_TAKEOFF,
                        param7 = 10f,
                    )
                    GcsCommand.LOITER,
                    GcsCommand.AUTO,
                    GcsCommand.STABILIZE,
                    GcsCommand.GUIDED,
                    -> setMode(connection, sys, snapshot, command)
                }
                appendStatus("Sent ${command.name}")
            } catch (error: Exception) {
                appendStatus("Send failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    /** Commands an explicit ArduPilot custom mode, used by the Flight Mode panel. */
    fun setFlightMode(label: String, customMode: Long) {
        val connection = connectionRef.get() ?: return
        val (sys, comp) = target.get() ?: (1 to 1)
        tx.execute {
            try {
                sendModeChange(connection, sys, comp, customMode)
                appendStatus("Sent mode $label")
            } catch (error: Exception) {
                appendStatus("Mode change failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    /** Target airspeed in m/s, leaving the throttle to the autopilot. */
    fun changeSpeed(metersPerSecond: Float) = guided("speed $metersPerSecond m/s") { connection, sys, comp ->
        sendCommand(
            connection,
            sys,
            comp,
            MavCmd.MAV_CMD_DO_CHANGE_SPEED,
            param1 = SPEED_TYPE_AIRSPEED.toFloat(),
            param2 = metersPerSecond,
            param3 = -1f,
        )
    }

    /** Target altitude in metres above home. */
    fun changeAltitude(meters: Float) = guided("altitude $meters m") { connection, sys, comp ->
        sendCommand(
            connection,
            sys,
            comp,
            MavCmd.MAV_CMD_DO_CHANGE_ALTITUDE,
            param1 = meters,
            param2 = MAV_FRAME_GLOBAL_RELATIVE_ALT.toFloat(),
        )
    }

    /**
     * Loiter radius is a parameter rather than a command, so this is a PARAM_SET
     * of WP_LOITER_RAD. ArduPilot takes every parameter as REAL32 over the wire.
     */
    fun setLoiterRadius(meters: Float) = guided("loiter radius $meters m") { connection, sys, comp ->
        val request = ParamSet.builder()
            .targetSystem(sys)
            .targetComponent(comp)
            .paramId(LOITER_RADIUS_PARAM)
            .paramValue(meters)
            .paramType(MavParamType.MAV_PARAM_TYPE_REAL32)
            .build()
        connection.send2(GCS_SYSTEM_ID, GCS_COMPONENT_ID, request)
    }

    /**
     * Guided goto. Sent as COMMAND_INT rather than COMMAND_LONG: that message
     * carries its parameters as float32, which cannot hold a 1e7-scaled
     * latitude without losing metres of precision. COMMAND_INT has int32 x/y.
     */
    fun flyTo(lat: Double, lon: Double, altitudeM: Float) =
        guided("fly to %.6f, %.6f at %.0f m".format(lat, lon, altitudeM)) { connection, sys, comp ->
            val payload = CommandInt.builder()
                .targetSystem(sys)
                .targetComponent(comp)
                .frame(MavFrame.MAV_FRAME_GLOBAL_RELATIVE_ALT_INT)
                .command(MavCmd.MAV_CMD_DO_REPOSITION)
                .current(0)
                .autocontinue(0)
                .param1(-1f)
                .param2(REPOSITION_CHANGE_MODE.toFloat())
                .param3(0f)
                .param4(Float.NaN)
                .x((lat * 1e7).roundToInt())
                .y((lon * 1e7).roundToInt())
                .z(altitudeM)
                .build()
            connection.send2(GCS_SYSTEM_ID, GCS_COMPONENT_ID, payload)
        }

    private fun guided(description: String, block: (MavlinkConnection, Int, Int) -> Unit) {
        val connection = connectionRef.get() ?: return
        val (sys, comp) = target.get() ?: (1 to 1)
        tx.execute {
            try {
                block(connection, sys, comp)
                appendStatus("Sent $description")
            } catch (error: Exception) {
                appendStatus("Send failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun runUdp(config: LinkConfig) {
        val bindHost = if (config.host.isBlank()) "0.0.0.0" else config.host
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = 1000
        }
        try {
            socket.bind(InetSocketAddress(InetAddress.getByName(bindHost), config.port))
        } catch (error: SocketException) {
            // For UDP this field is a local bind address, but the same field holds a
            // remote host in TCP mode and carries over when the type is switched. A
            // remote address is not assignable here and fails with EADDRNOTAVAIL, so
            // fall back to every interface rather than dead-ending on it.
            if (bindHost == "0.0.0.0") {
                throw error
            }
            appendStatus("Cannot bind $bindHost here, listening on 0.0.0.0 instead")
            socket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), config.port))
        }
        datagramSocket = socket
        val remote = AtomicReference<InetSocketAddress?>(null)
        val input = PacketInputStream()
        val output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()))
            override fun write(b: ByteArray, off: Int, len: Int) {
                val dest = remote.get() ?: return
                socket.send(DatagramPacket(b, off, len, dest.address, dest.port))
            }
        }
        startConnection(input, output)
        val buffer = ByteArray(2048)
        while (running.get() && !Thread.currentThread().isInterrupted) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                remote.set(InetSocketAddress(packet.address, packet.port))
                input.offer(buffer.copyOf(packet.length))
            } catch (_: java.net.SocketTimeoutException) {
                checkStale()
            }
        }
        input.close()
    }

    private fun runTcp(config: LinkConfig) {
        val socket = Socket()
        socket.connect(InetSocketAddress(config.host, config.port), 5000)
        socket.tcpNoDelay = true
        tcpSocket = socket
        startConnection(socket.getInputStream(), socket.getOutputStream())
        while (running.get() && !socket.isClosed) {
            Thread.sleep(500)
            checkStale()
        }
    }

    private fun startConnection(input: InputStream, output: OutputStream) {
        val connection = MavlinkConnection.create(input, output)
        connectionRef.set(connection)
        heartbeat = thread(name = "mavlink-hb", isDaemon = true) {
            while (running.get() && !Thread.currentThread().isInterrupted) {
                runCatching { tx.execute { runCatching { sendHeartbeat(connection) } } }
                Thread.sleep(1000)
            }
        }
        thread(name = "mavlink-parse", isDaemon = true) {
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val message = runCatching { connection.next() }.getOrNull() ?: continue
                onMessage(connection, message)
            }
        }
    }

    private fun onMessage(connection: MavlinkConnection, message: MavlinkMessage<*>) {
        val payload = message.payload
        _state.update { it.copy(packetsIn = it.packetsIn + 1) }
        when (payload) {
            is Heartbeat -> onHeartbeat(
                connection,
                message,
                typeName = payload.type().entry()?.name.orEmpty(),
                typeValue = payload.type().value(),
                autopilotName = payload.autopilot().entry()?.name.orEmpty(),
                custom = payload.customMode().toLong(),
                armed = payload.baseMode().flagsEnabled(MavModeFlag.MAV_MODE_FLAG_SAFETY_ARMED),
                statusName = payload.systemStatus().entry()?.name ?: "UNINIT",
            )
            is Attitude -> _state.update {
                it.copy(
                    rollDeg = Math.toDegrees(payload.roll().toDouble()).toFloat(),
                    pitchDeg = Math.toDegrees(payload.pitch().toDouble()).toFloat(),
                    // Yaw arrives as radians in -pi..pi, so it converts to
                    // -180..180. Wrapped to a compass bearing like the heading
                    // and wind fields; roll and pitch stay signed, as they should.
                    yawDeg = normaliseBearing(Math.toDegrees(payload.yaw().toDouble()).toFloat()),
                )
            }
            is GlobalPositionInt -> _state.update {
                val lat = payload.lat() / 1e7
                val lon = payload.lon() / 1e7
                it.copy(
                    lat = lat,
                    lon = lon,
                    altMslM = payload.alt() / 1000f,
                    altRelM = payload.relativeAlt() / 1000f,
                    headingDeg = payload.hdg().takeIf { hdg -> hdg < 36000 }?.div(100f),
                    distToHomeM = distanceMeters(lat, lon, it.homeLat, it.homeLon),
                )
            }
            is GpsRawInt -> _state.update {
                it.copy(
                    gpsFix = gpsFixName(payload.fixType().value()),
                    satellites = payload.satellitesVisible().toInt() and 0xFF,
                    hdop = payload.eph().takeIf { eph -> eph < 65535 }?.div(100f),
                    lat = if (it.lat == null && payload.lat() != 0) payload.lat() / 1e7 else it.lat,
                    lon = if (it.lon == null && payload.lon() != 0) payload.lon() / 1e7 else it.lon,
                )
            }
            is VfrHud -> _state.update {
                it.copy(
                    airSpeedMs = payload.airspeed(),
                    groundSpeedMs = payload.groundspeed(),
                    headingDeg = payload.heading().toFloat(),
                    throttlePct = payload.throttle(),
                    altMslM = payload.alt(),
                    climbMs = payload.climb(),
                )
            }
            is SysStatus -> _state.update {
                it.copy(
                    batteryV = payload.voltageBattery().takeIf { mv -> mv in 1..65534 }?.div(1000f),
                    batteryA = payload.currentBattery().takeIf { c -> c != -1 }?.div(100f),
                    batteryRemainingPct = payload.batteryRemaining().takeIf { pct -> pct in 0..100 },
                )
            }
            is BatteryStatus -> _state.update { current ->
                val millivolts = payload.voltages().firstOrNull { cell -> cell.toInt() != 65535 && cell.toInt() > 0 }?.toInt() ?: 0
                current.copy(
                    batteryRemainingPct = payload.batteryRemaining().takeIf { pct -> pct in 0..100 }
                        ?: current.batteryRemainingPct,
                    batteryA = payload.currentBattery().takeIf { c -> c != -1 }?.div(100f) ?: current.batteryA,
                    batteryV = if (millivolts > 0 && millivolts < 65535) millivolts / 1000f else current.batteryV,
                )
            }
            is NavControllerOutput -> _state.update {
                it.copy(distToWpM = payload.wpDist().toFloat())
            }
            is HomePosition -> _state.update {
                val homeLat = payload.latitude() / 1e7
                val homeLon = payload.longitude() / 1e7
                it.copy(
                    homeLat = homeLat,
                    homeLon = homeLon,
                    distToHomeM = distanceMeters(it.lat, it.lon, homeLat, homeLon),
                )
            }
            is Wind -> _state.update {
                it.copy(
                    // ArduPilot wraps this to -180..180, so a westerly reads as
                    // -13 rather than 347. Normalised here so the stored bearing
                    // is a compass value, as headingDeg already is.
                    windDirectionDeg = normaliseBearing(payload.direction()),
                    windSpeedMs = payload.speed(),
                )
            }
            is Rangefinder -> _state.update {
                it.copy(rangefinderM = payload.distance())
            }
            is ScaledPressure -> _state.update {
                it.copy(qnhHpa = qnhFrom(payload.pressAbs(), it.altMslM))
            }
            is TerrainReport -> _state.update {
                it.copy(terrainAltM = payload.terrainHeight())
            }
            is Statustext -> appendStatus(payload.text())
        }
    }

    private fun onHeartbeat(
        connection: MavlinkConnection,
        message: MavlinkMessage<*>,
        typeName: String,
        typeValue: Int,
        autopilotName: String,
        custom: Long,
        armed: Boolean,
        statusName: String,
    ) {
        if (typeName.endsWith("GCS") || typeValue == 6) return
        val now = System.currentTimeMillis()
        val first = target.get() == null
        target.set(message.originSystemId to message.originComponentId)
        val firmware = when {
            autopilotName.contains("ARDUPILOT") -> Firmware.ARDUPILOT
            autopilotName.contains("PX4") -> Firmware.PX4
            else -> Firmware.UNKNOWN
        }
        val vehicleType = typeName.removePrefix("MAV_TYPE_").ifBlank { "TYPE $typeValue" }
        val mode = when (firmware) {
            Firmware.ARDUPILOT -> FlightModes.ardupilotMode(vehicleType, custom)
            Firmware.PX4 -> FlightModes.px4ModeName(custom)
            Firmware.UNKNOWN -> "MODE $custom"
        }
        _state.update {
            it.copy(
                linkUp = true,
                lastHeartbeatMs = now,
                systemId = message.originSystemId,
                componentId = message.originComponentId,
                autopilot = autopilotName.removePrefix("MAV_AUTOPILOT_").ifBlank { "—" },
                vehicleType = vehicleType,
                firmware = firmware,
                mode = mode,
                customMode = custom,
                armed = armed,
                systemStatus = statusName.removePrefix("MAV_STATE_"),
            )
        }
        if (first) {
            requestStreams(connection, message.originSystemId, message.originComponentId)
            appendStatus("Vehicle ${message.originSystemId} online")
        }
    }

    private fun requestStreams(connection: MavlinkConnection, sys: Int, comp: Int) {
        val request = RequestDataStream.builder()
            .targetSystem(sys)
            .targetComponent(comp)
            .reqStreamId(0)
            .reqMessageRate(4)
            .startStop(1)
            .build()
        tx.execute { runCatching { connection.send2(GCS_SYSTEM_ID, GCS_COMPONENT_ID, request) } }
    }

    private fun sendHeartbeat(connection: MavlinkConnection) {
        val heartbeat = Heartbeat.builder()
            .type(MavType.MAV_TYPE_GCS)
            .autopilot(MavAutopilot.MAV_AUTOPILOT_INVALID)
            .baseMode(EnumValue.create(0))
            .customMode(0)
            .systemStatus(MavState.MAV_STATE_ACTIVE)
            .mavlinkVersion(3)
            .build()
        connection.send2(GCS_SYSTEM_ID, GCS_COMPONENT_ID, heartbeat)
    }

    private fun sendCommand(
        connection: MavlinkConnection,
        sys: Int,
        comp: Int,
        command: MavCmd,
        param1: Float = 0f,
        param2: Float = 0f,
        param3: Float = 0f,
        param4: Float = 0f,
        param5: Float = 0f,
        param6: Float = 0f,
        param7: Float = 0f,
    ) {
        val payload = CommandLong.builder()
            .targetSystem(sys)
            .targetComponent(comp)
            .command(command)
            .confirmation(0)
            .param1(param1)
            .param2(param2)
            .param3(param3)
            .param4(param4)
            .param5(param5)
            .param6(param6)
            .param7(param7)
            .build()
        connection.send2(GCS_SYSTEM_ID, GCS_COMPONENT_ID, payload)
    }

    private fun setMode(
        connection: MavlinkConnection,
        sys: Int,
        snapshot: VehicleState,
        command: GcsCommand,
    ) {
        val custom = when (snapshot.firmware) {
            Firmware.PX4 -> FlightModes.px4CustomMode(command)
            else -> FlightModes.ardupilotCustomMode(snapshot.vehicleType, command)
        } ?: return
        sendModeChange(connection, sys, snapshot.componentId, custom)
    }

    private fun sendModeChange(
        connection: MavlinkConnection,
        sys: Int,
        comp: Int,
        custom: Long,
    ) {
        val mode = SetMode.builder()
            .targetSystem(sys)
            .baseMode(EnumValue.create(MAV_MODE_FLAG_CUSTOM_MODE_ENABLED))
            .customMode(custom)
            .build()
        connection.send2(GCS_SYSTEM_ID, GCS_COMPONENT_ID, mode)
        sendCommand(
            connection,
            sys,
            comp,
            MavCmd.MAV_CMD_DO_SET_MODE,
            param1 = MAV_MODE_FLAG_CUSTOM_MODE_ENABLED.toFloat(),
            param2 = custom.toFloat(),
        )
    }

    private fun checkStale() {
        val last = _state.value.lastHeartbeatMs
        if (last != 0L && System.currentTimeMillis() - last > 3_000L) {
            _state.update { it.copy(linkUp = false) }
        }
    }

    private fun gpsFixName(fix: Int): String = when (fix) {
        0, 1 -> "NO FIX"
        2 -> "2D"
        3 -> "3D"
        4 -> "DGPS"
        5 -> "RTK FLOAT"
        6 -> "RTK FIXED"
        else -> "FIX $fix"
    }

    private fun appendStatus(text: String) {
        val line = text.trim().ifBlank { return }
        _state.update { current ->
            current.copy(statusLog = (current.statusLog + line).takeLast(12))
        }
    }

    companion object {
        const val GCS_SYSTEM_ID = 255
        const val GCS_COMPONENT_ID = 190
        private const val MAV_MODE_FLAG_CUSTOM_MODE_ENABLED = 1
        private const val MAV_FRAME_GLOBAL_RELATIVE_ALT = 3
        private const val SPEED_TYPE_AIRSPEED = 0
        private const val LOITER_RADIUS_PARAM = "WP_LOITER_RAD"
        private const val REPOSITION_CHANGE_MODE = 1
    }
}

/** Wraps a bearing into 0..360, the convention the rest of the state uses. */
private fun normaliseBearing(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f

/** Great-circle distance in metres, or null unless both points are known. */
private fun distanceMeters(lat1: Double?, lon1: Double?, lat2: Double?, lon2: Double?): Float? {
    if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) return null
    val earthRadiusM = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    // min() guards asin against a domain error from floating point drift above 1.
    return (2 * earthRadiusM * asin(min(1.0, sqrt(a)))).toFloat()
}

/**
 * Sea level pressure from the absolute reading, via the standard atmosphere.
 * With no altitude to correct for, the absolute reading is already the answer.
 */
private fun qnhFrom(pressAbsHpa: Float, altMslM: Float?): Float {
    val altitude = altMslM ?: return pressAbsHpa
    return (pressAbsHpa * (1.0 - 0.0065 * altitude / 288.15).pow(-5.257)).toFloat()
}

private class PacketInputStream : InputStream() {
    private val queue = LinkedBlockingQueue<ByteArray>(256)
    private var current: ByteArray? = null
    private var index = 0
    private val closed = AtomicBoolean(false)

    fun offer(bytes: ByteArray) {
        if (!closed.get()) {
            queue.offer(bytes)
        }
    }

    override fun read(): Int {
        while (true) {
            if (closed.get() && queue.isEmpty() && (current == null || index >= (current?.size ?: 0))) {
                return -1
            }
            val slice = current
            if (slice == null || index >= slice.size) {
                val next = queue.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                current = next
                index = 0
            } else {
                val value = slice[index]
                index += 1
                return value.toInt() and 0xFF
            }
        }
    }

    override fun close() {
        closed.set(true)
        queue.clear()
    }
}
