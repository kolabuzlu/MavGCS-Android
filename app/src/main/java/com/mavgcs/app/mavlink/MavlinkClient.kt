package com.mavgcs.app.mavlink

import io.dronefleet.mavlink.MavlinkConnection
import io.dronefleet.mavlink.MavlinkMessage
import io.dronefleet.mavlink.common.Attitude
import io.dronefleet.mavlink.common.BatteryStatus
import io.dronefleet.mavlink.common.CommandLong
import io.dronefleet.mavlink.common.GlobalPositionInt
import io.dronefleet.mavlink.common.GpsRawInt
import io.dronefleet.mavlink.common.MavCmd
import io.dronefleet.mavlink.common.RequestDataStream
import io.dronefleet.mavlink.common.SetMode
import io.dronefleet.mavlink.common.Statustext
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
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

    /** Commands an explicit ArduPilot custom mode, used by the Flight Mode panel. */
    fun setFlightMode(label: String, customMode: Long) {
        val connection = connectionRef.get() ?: return
        val (sys, comp) = target.get() ?: (1 to 1)
        try {
            sendModeChange(connection, sys, comp, customMode)
            appendStatus("Sent mode $label")
        } catch (error: Exception) {
            appendStatus("Mode change failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun runUdp(config: LinkConfig) {
        val bindHost = if (config.host.isBlank()) "0.0.0.0" else config.host
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = 1000
            bind(InetSocketAddress(InetAddress.getByName(bindHost), config.port))
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
                runCatching { sendHeartbeat(connection) }
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
                    yawDeg = Math.toDegrees(payload.yaw().toDouble()).toFloat(),
                )
            }
            is GlobalPositionInt -> _state.update {
                it.copy(
                    lat = payload.lat() / 1e7,
                    lon = payload.lon() / 1e7,
                    altMslM = payload.alt() / 1000f,
                    altRelM = payload.relativeAlt() / 1000f,
                    headingDeg = payload.hdg().takeIf { hdg -> hdg < 36000 }?.div(100f),
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
        connection.send2(GCS_SYSTEM_ID, GCS_COMPONENT_ID, request)
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
    }
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
