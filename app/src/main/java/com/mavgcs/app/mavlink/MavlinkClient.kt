package com.mavgcs.app.mavlink

import android.util.Log
import io.dronefleet.mavlink.MavlinkConnection
import io.dronefleet.mavlink.MavlinkMessage
import io.dronefleet.mavlink.ardupilotmega.Rangefinder
import io.dronefleet.mavlink.ardupilotmega.Wind
import io.dronefleet.mavlink.common.Attitude
import io.dronefleet.mavlink.common.BatteryStatus
import io.dronefleet.mavlink.common.CommandInt
import io.dronefleet.mavlink.common.CommandLong
import io.dronefleet.mavlink.common.DistanceSensor
import io.dronefleet.mavlink.common.GlobalPositionInt
import io.dronefleet.mavlink.ardupilotmega.EkfStatusFlags
import io.dronefleet.mavlink.ardupilotmega.EkfStatusReport
import io.dronefleet.mavlink.common.GpsRawInt
import io.dronefleet.mavlink.common.MavMissionResult
import io.dronefleet.mavlink.common.MavMissionType
import io.dronefleet.mavlink.common.MissionAck
import io.dronefleet.mavlink.common.MissionClearAll
import io.dronefleet.mavlink.common.MissionCount
import io.dronefleet.mavlink.common.MissionCurrent
import io.dronefleet.mavlink.common.MissionItemInt
import io.dronefleet.mavlink.common.MissionRequest
import io.dronefleet.mavlink.common.MissionRequestInt
import io.dronefleet.mavlink.common.MissionSetCurrent
import io.dronefleet.mavlink.common.HomePosition
import io.dronefleet.mavlink.common.NavControllerOutput
import io.dronefleet.mavlink.common.ParamSet
import io.dronefleet.mavlink.common.MavCmd
import io.dronefleet.mavlink.common.MavFrame
import io.dronefleet.mavlink.common.MavParamType
import io.dronefleet.mavlink.common.MavSensorOrientation
import io.dronefleet.mavlink.common.RequestDataStream
import io.dronefleet.mavlink.common.ScaledPressure
import io.dronefleet.mavlink.common.Statustext
import io.dronefleet.mavlink.common.TerrainReport
import io.dronefleet.mavlink.annotations.MavlinkMessageInfo
import io.dronefleet.mavlink.ardupilotmega.Ahrs
import io.dronefleet.mavlink.ardupilotmega.Ahrs2
import io.dronefleet.mavlink.ardupilotmega.AoaSsa
import io.dronefleet.mavlink.ardupilotmega.Meminfo
import io.dronefleet.mavlink.ardupilotmega.Simstate
import io.dronefleet.mavlink.common.LocalPositionNed
import io.dronefleet.mavlink.common.PositionTargetGlobalInt
import io.dronefleet.mavlink.common.PowerStatus
import io.dronefleet.mavlink.common.RawImu
import io.dronefleet.mavlink.common.RcChannels
import io.dronefleet.mavlink.common.ScaledImu2
import io.dronefleet.mavlink.common.ScaledImu3
import io.dronefleet.mavlink.common.ScaledPressure2
import io.dronefleet.mavlink.common.ServoOutputRaw
import io.dronefleet.mavlink.common.SystemTime
import io.dronefleet.mavlink.common.SysStatus
import io.dronefleet.mavlink.common.Vibration
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

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
    /** Guards the upload state machine, which the parse and tx threads share. */
    private val missionLock = Any()
    private var missionPending: List<MissionPoint>? = null
    private var missionState: MissionState? = null
    private var missionRestart = true

    /** The rates this connection was opened with. */
    @Volatile
    private var streamRates = StreamRates()

    private var chunkId = 0
    private var chunkSequence = -1

    /**
     * Every outbound frame goes through this one thread. It keeps socket writes
     * off the caller's thread -- button handlers run on the main thread, which
     * Android forbids from touching a socket -- and serialises commands against
     * the 1 Hz heartbeat so two frames cannot interleave mid-write on one stream.
     */
    private val tx: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mavlink-tx").apply { isDaemon = true }
    }

    fun connect(config: LinkConfig, rates: StreamRates = StreamRates()) {
        streamRates = rates
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
                Log.w(TAG, "Link error: ${error.message ?: error.javaClass.simpleName}")
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
                    GcsCommand.FORCE_ARM -> sendCommand(
                        connection,
                        sys,
                        comp,
                        MavCmd.MAV_CMD_COMPONENT_ARM_DISARM,
                        param1 = 1f,
                        param2 = FORCE_ARM_MAGIC,
                    )
                    GcsCommand.DISARM -> sendCommand(connection, sys, comp, MavCmd.MAV_CMD_COMPONENT_ARM_DISARM, 0f)
                    // Baro then airspeed, which is the combination Mission
                    // Planner's own Preflight Calibration sends and the order
                    // ArduPilot logs them in. The desktop notes that asking for
                    // gyro here instead of airspeed produced no calibration at
                    // all, so the parameters are not interchangeable.
                    GcsCommand.PREFLIGHT_CALIBRATION -> sendCommand(
                        connection,
                        sys,
                        comp,
                        MavCmd.MAV_CMD_PREFLIGHT_CALIBRATION,
                        param1 = 0f, // gyro
                        param2 = 0f, // magnetometer
                        param3 = 1f, // ground pressure
                        param4 = 0f, // RC
                        param5 = 0f, // accelerometer
                        param6 = 2f, // airspeed
                        param7 = 0f, // ESC
                    )
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
                Log.i(TAG, "Sent ${command.name}")
            } catch (error: Exception) {
                Log.w(TAG, "Send failed: ${error.message ?: error.javaClass.simpleName}")
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
                Log.i(TAG, "Sent mode $label")
            } catch (error: Exception) {
                Log.w(TAG, "Mode change failed: ${error.message ?: error.javaClass.simpleName}")
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
        guided("fly to %.6f, %.6f at %.0f m".format(Locale.ROOT, lat, lon, altitudeM)) { connection, sys, comp ->
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
                Log.i(TAG, "Sent $description")
            } catch (error: Exception) {
                Log.w(TAG, "Send failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun runUdp(config: LinkConfig) {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = 1000
        }
        val remote = AtomicReference<InetSocketAddress?>(null)
        if (config.udpMode == UdpMode.CONNECT) {
            // Dialling out. The local port does not matter, but the socket still
            // has to be bound before it can be read from, and the peer is known
            // up front rather than learned -- which is what lets the heartbeat
            // go out immediately. A WiFi bridge stays silent until it has heard
            // from us, so that first frame is what starts the stream.
            socket.bind(InetSocketAddress(0))
            remote.set(InetSocketAddress(InetAddress.getByName(config.host), config.port))
        } else {
            // Listening on every interface. SITL and most telemetry stream to a
            // port rather than accepting a connection, so being bound to that
            // port is the only way to hear anything.
            socket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), config.port))
        }
        datagramSocket = socket
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
                if (config.udpMode == UdpMode.LISTEN) {
                    // Whoever just spoke is who to answer. When dialling out the
                    // peer is the one that was asked for, whatever port it
                    // happens to reply from.
                    remote.set(InetSocketAddress(packet.address, packet.port))
                }
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
            // disconnect() interrupts this thread, which makes the sleep throw.
            // An uncaught exception on any thread takes the whole process down
            // with it, so the normal way out has to be caught here.
            try {
                while (running.get() && !Thread.currentThread().isInterrupted) {
                    runCatching { tx.execute { runCatching { sendHeartbeat(connection) } } }
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread(name = "mavlink-parse", isDaemon = true) {
            try {
                while (running.get() && !Thread.currentThread().isInterrupted) {
                    val message = runCatching { connection.next() }.getOrNull() ?: continue
                    // A malformed message must not be fatal either.
                    runCatching { onMessage(connection, message) }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
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
                    yawRateDegSec = Math.toDegrees(payload.yawspeed().toDouble()).toFloat(),
                )
            }
            is GlobalPositionInt -> _state.update {
                val lat = payload.lat() / 1e7
                val lon = payload.lon() / 1e7
                // Course comes from the velocity, not the heading: in a crosswind
                // the aircraft points one way and travels another.
                val northMs = payload.vx() / 100f
                val eastMs = payload.vy() / 100f
                val overGround = hypot(northMs, eastMs)
                it.copy(
                    groundCourseDeg = if (overGround >= MIN_COURSE_SPEED_MS) {
                        normaliseBearing(
                            Math.toDegrees(atan2(eastMs.toDouble(), northMs.toDouble())).toFloat(),
                        )
                    } else {
                        // Below walking pace the direction is noise; keep the last.
                        it.groundCourseDeg
                    },
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
                    gpsFixType = payload.fixType().value(),
                    // 255 is the receiver's "did not say", not a count.
                    satellites = (payload.satellitesVisible().toInt() and 0xFF)
                        .takeIf { count -> count != 255 },
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
                    sensorsPresent = payload.onboardControlSensorsPresent().value(),
                    sensorsEnabled = payload.onboardControlSensorsEnabled().value(),
                    sensorsHealth = payload.onboardControlSensorsHealth().value(),
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
            is MissionCurrent -> _state.update {
                it.copy(currentWaypointSeq = payload.seq())
            }
            is MissionAck -> onMissionAck(payload)
            is MissionRequestInt -> onMissionRequest(payload.seq())
            is MissionRequest -> onMissionRequest(payload.seq())
            is NavControllerOutput -> _state.update {
                it.copy(distToWpM = payload.wpDist().toFloat())
            }
            // Ported from Mission Planner's own HUD.cs so the verdicts match
            // it exactly: the worst of the five variances decides, except that
            // three flag states force the top of the scale outright.
            is EkfStatusReport -> _state.update { current ->
                val flags = payload.flags()
                val haveFix = (current.gpsFixType ?: 0) > 0
                val score = ekfScore(
                    velocityVariance = payload.velocityVariance(),
                    compassVariance = payload.compassVariance(),
                    posHorizVariance = payload.posHorizVariance(),
                    posVertVariance = payload.posVertVariance(),
                    terrainVariance = payload.terrainAltVariance(),
                    hasAttitude = flags.flagsEnabled(EkfStatusFlags.EKF_ATTITUDE),
                    hasVelocityHoriz = flags.flagsEnabled(EkfStatusFlags.EKF_VELOCITY_HORIZ),
                    uninitialised = flags.flagsEnabled(EkfStatusFlags.EKF_UNINITIALIZED),
                    haveGpsFix = haveFix,
                )
                current.copy(
                    ekfCompassVariance = payload.compassVariance(),
                    ekfPosHorizVariance = payload.posHorizVariance(),
                    ekfPosVertVariance = payload.posVertVariance(),
                    ekfTerrainVariance = payload.terrainAltVariance(),
                    ekfTint = tintFor(score),
                )
            }
            // Also from HUD.cs: the raw per-axis figures alone. The clipping
            // counters are deliberately not folded in -- they start climbing
            // well below 30 on plenty of boards, which would force red and
            // skip the amber range entirely.
            is Vibration -> _state.update {
                it.copy(
                    vibeTint = vibeTint(
                        payload.vibrationX(),
                        payload.vibrationY(),
                        payload.vibrationZ(),
                    ),
                )
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
            // ardupilotmega RANGEFINDER, kept for firmware that still sends it.
            is Rangefinder -> _state.update {
                it.copy(rangefinderM = payload.distance())
            }
            // The message ArduPilot actually sends today. Only the downward
            // sensor is the altitude rangefinder; a proximity ring reports on
            // other orientations and would otherwise overwrite it.
            is DistanceSensor -> _state.update { current ->
                if (payload.orientation().entry() != MavSensorOrientation.MAV_SENSOR_ROTATION_PITCH_270) {
                    current
                } else {
                    // Only zero means no reading. ArduPilot reports distances well
                    // past max_distance -- that field is the configured range of
                    // the sensor, not a cap on what it will send -- and discarding
                    // those left the field blank for the whole flight.
                    val centimetres = payload.currentDistance()
                    current.copy(rangefinderM = if (centimetres > 0) centimetres / 100f else null)
                }
            }
            is ScaledPressure -> _state.update {
                it.copy(qnhHpa = qnhFrom(payload.pressAbs(), it.altMslM))
            }
            is TerrainReport -> _state.update {
                it.copy(terrainAltM = payload.terrainHeight())
            }
            is Statustext -> appendVehicleMessage(payload)
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
        val firstContact = target.getAndSet(
            message.originSystemId to message.originComponentId,
        ) == null
        if (firstContact) {
            // Only once the vehicle has said who it is: every request has to be
            // addressed to it.
            applyStreamRates(streamRates)
        }
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
            Log.i(TAG, "Vehicle ${message.originSystemId} online")
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
        requestHomePosition(connection, sys, comp)
    }

    /**
     * HOME_POSITION is only emitted when home is set, so connecting to a vehicle
     * that is already flying never sees it and distance-to-home stays blank.
     * Ask for it explicitly instead of waiting for one that will not come.
     */
    private fun requestHomePosition(connection: MavlinkConnection, sys: Int, comp: Int) {
        tx.execute {
            runCatching {
                sendCommand(
                    connection,
                    sys,
                    comp,
                    MavCmd.MAV_CMD_REQUEST_MESSAGE,
                    param1 = HOME_POSITION_MESSAGE_ID.toFloat(),
                )
            }
        }
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

    /**
     * Upload [waypoints] as a real onboard AUTO mission and, unless this is an
     * update, start flying it.
     *
     * Unlike a guided fly-to this does not depend on the tablet staying
     * connected: once uploaded, ArduPilot owns the leg-to-leg progression
     * itself. The upload is a conversation -- clear, announce the count, then
     * answer each item the vehicle asks for -- so it runs as a small state
     * machine driven by the replies rather than as a single send.
     */
    fun uploadMission(
        waypoints: List<MissionWaypoint>,
        altitudeM: Float,
        restart: Boolean = true,
    ) {
        val connection = connectionRef.get() ?: return
        val (sys, comp) = target.get() ?: (1 to 1)
        if (waypoints.isEmpty()) {
            return
        }
        tx.execute {
            synchronized(missionLock) {
                if (missionState != null) {
                    Log.w(TAG, "A mission upload is already in progress")
                    return@execute
                }
                val snapshot = _state.value
                // ArduPilot treats item 0 as home and begins AUTO at item 1, so
                // a placeholder has to go first. Without it the aircraft
                // silently skips the first point the pilot actually clicked.
                val placeholder = if (snapshot.homeLat != null && snapshot.homeLon != null) {
                    MissionPoint(snapshot.homeLat, snapshot.homeLon, 0f)
                } else {
                    MissionPoint(waypoints.first().lat, waypoints.first().lon, 0f)
                }
                // Each point flies at its own altitude where it has been
                // given one; the rest take the mission's.
                missionPending = listOf(placeholder) +
                    waypoints.map {
                        MissionPoint(it.lat, it.lon, it.altitudeM ?: altitudeM)
                    }
                missionRestart = restart
                missionState = MissionState.AWAITING_CLEAR_ACK
            }
            val sent = runCatching { sendMissionClear(connection, sys, comp) }
            if (sent.isFailure) {
                resetMission()
                Log.w(TAG, "Could not start the upload: " + sent.exceptionOrNull()?.message)
            } else {
                Log.i(TAG, "Mission upload started (" + waypoints.size + " waypoints)")
            }
        }
    }

    /**
     * Erase the mission stored on the vehicle.
     *
     * ArduPilot silently refuses to clear a mission it is actively flying in
     * AUTO -- the identical clear works at once in LOITER -- so the mode change
     * goes first and the clear follows once it has had time to take effect
     * onboard. Any upload in flight is abandoned too, or its stale
     * acknowledgement would arrive later and be read as this clear's result.
     */
    fun clearMission() {
        val connection = connectionRef.get() ?: return
        val (sys, comp) = target.get() ?: (1 to 1)
        tx.execute {
            resetMission()
            runCatching {
                setMode(connection, sys, _state.value, GcsCommand.LOITER)
                Thread.sleep(MISSION_CLEAR_SETTLE_MS)
                sendMissionClear(connection, sys, comp)
                Log.i(TAG, "Sent mission clear")
            }.onFailure { error ->
                Log.w(TAG, "Could not clear the mission: " + error.message)
            }
        }
    }

    private fun sendMissionClear(connection: MavlinkConnection, sys: Int, comp: Int) {
        connection.send2(
            GCS_SYSTEM_ID,
            GCS_COMPONENT_ID,
            MissionClearAll.builder()
                .targetSystem(sys)
                .targetComponent(comp)
                .missionType(MavMissionType.MAV_MISSION_TYPE_MISSION)
                .build(),
        )
    }

    private fun resetMission() {
        synchronized(missionLock) {
            missionState = null
            missionPending = null
        }
    }

    /** The vehicle answering one step of the upload conversation. */
    private fun onMissionAck(payload: MissionAck) {
        val connection = connectionRef.get() ?: return
        val (sys, comp) = target.get() ?: (1 to 1)
        val state = synchronized(missionLock) { missionState } ?: return
        when (state) {
            MissionState.AWAITING_CLEAR_ACK -> {
                // Whatever the clear said. Clearing an already-empty mission
                // acknowledges oddly on some firmware and must not block a
                // fresh upload.
                val count = synchronized(missionLock) {
                    missionState = MissionState.UPLOADING
                    missionPending?.size
                } ?: return
                tx.execute {
                    runCatching {
                        connection.send2(
                            GCS_SYSTEM_ID,
                            GCS_COMPONENT_ID,
                            MissionCount.builder()
                                .targetSystem(sys)
                                .targetComponent(comp)
                                .count(count)
                                .missionType(MavMissionType.MAV_MISSION_TYPE_MISSION)
                                .build(),
                        )
                    }
                }
            }

            MissionState.UPLOADING -> {
                val accepted = payload.type().entry() == MavMissionResult.MAV_MISSION_ACCEPTED
                val points = synchronized(missionLock) { missionPending?.size ?: 0 }
                val restart = synchronized(missionLock) { missionRestart }
                resetMission()
                if (!accepted) {
                    Log.w(TAG, "Mission upload refused: " + payload.type().entry())
                    return
                }
                _state.update { it.copy(missionAccepted = it.missionAccepted + 1) }
                // The placeholder is not one of the pilot's own points.
                val flown = (points - 1).coerceAtLeast(0)
                if (!restart) {
                    // No set-current and no mode change: the aircraft carries
                    // on with the leg it is already flying.
                    Log.i(TAG, "Mission updated (" + flown + " waypoints)")
                    return
                }
                Log.i(TAG, "Mission uploaded (" + flown + " waypoints), starting AUTO")
                tx.execute {
                    runCatching {
                        connection.send2(
                            GCS_SYSTEM_ID,
                            GCS_COMPONENT_ID,
                            MissionSetCurrent.builder()
                                .targetSystem(sys)
                                .targetComponent(comp)
                                // Item 1 is the first real waypoint; 0 is home.
                                .seq(1)
                                .build(),
                        )
                        setMode(connection, sys, _state.value, GcsCommand.AUTO)
                    }
                }
            }
        }
    }

    /** The vehicle asking for one item. Either request message may be used. */
    private fun onMissionRequest(seq: Int) {
        val connection = connectionRef.get() ?: return
        val (sys, comp) = target.get() ?: (1 to 1)
        val point = synchronized(missionLock) {
            if (missionState != MissionState.UPLOADING) {
                return
            }
            missionPending?.getOrNull(seq)
        } ?: return
        tx.execute {
            runCatching {
                connection.send2(
                    GCS_SYSTEM_ID,
                    GCS_COMPONENT_ID,
                    MissionItemInt.builder()
                        .targetSystem(sys)
                        .targetComponent(comp)
                        .seq(seq)
                        .frame(MavFrame.MAV_FRAME_GLOBAL_RELATIVE_ALT_INT)
                        .command(MavCmd.MAV_CMD_NAV_WAYPOINT)
                        .current(0)
                        .autocontinue(1)
                        .x((point.lat * 1e7).toInt())
                        .y((point.lon * 1e7).toInt())
                        .z(point.altM)
                        .missionType(MavMissionType.MAV_MISSION_TYPE_MISSION)
                        .build(),
                )
            }.onFailure { error ->
                Log.w(TAG, "Could not send mission item " + seq + ": " + error.message)
            }
        }
    }

    private data class MissionPoint(val lat: Double, val lon: Double, val altM: Float)

    private enum class MissionState { AWAITING_CLEAR_ACK, UPLOADING }

    /**
     * Ask the vehicle for the message rates this app actually wants.
     *
     * Sent as SET_MESSAGE_INTERVAL per message, best effort: a vehicle that
     * ignores these behaves exactly as it did before, so firmware without the
     * command loses nothing. Message ids come from the library's own
     * annotations rather than being written out here, so they cannot drift
     * from the dialect being spoken.
     */
    fun applyStreamRates(rates: StreamRates) {
        streamRates = rates
        val connection = connectionRef.get() ?: return
        val (sys, comp) = target.get() ?: return
        tx.execute {
            runCatching {
                val intervals = mutableMapOf<Class<*>, Int>()
                if (rates.full) {
                    // Everything at the vehicle's own rate. Zero means "your
                    // default", which is a different thing from -1 for off.
                    (SUPPORTING_RATES.keys + DISABLED_MESSAGES + RATED_MESSAGES)
                        .forEach { intervals[it] = 0 }
                } else {
                    SUPPORTING_RATES.forEach { (type, hz) -> intervals[type] = intervalFor(hz) }
                    intervals[Attitude::class.java] = intervalFor(rates.attitudeHz)
                    intervals[GlobalPositionInt::class.java] = intervalFor(rates.positionHz)
                    DISABLED_MESSAGES.forEach { intervals[it] = -1 }
                }
                intervals.forEach { (type, interval) ->
                    messageId(type)?.let { id ->
                        sendCommand(
                            connection,
                            sys,
                            comp,
                            MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL,
                            param1 = id.toFloat(),
                            param2 = interval.toFloat(),
                        )
                    }
                }
                Log.i(
                    TAG,
                    "Stream rates applied: attitude " + rates.attitudeHz +
                        "Hz, position " + rates.positionHz + "Hz, full=" + rates.full,
                )
            }.onFailure { error ->
                Log.w(TAG, "Could not set stream rates: " + error.message)
            }
        }
    }

    private fun intervalFor(hz: Float): Int =
        if (hz > 0f) (1_000_000f / hz).toInt() else -1

    /** The id the dialect gives this message, straight off its own annotation. */
    private fun messageId(type: Class<*>): Int? =
        runCatching { type.getAnnotation(MavlinkMessageInfo::class.java)?.id }.getOrNull()

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
        // DO_SET_MODE alone. The deprecated SET_MODE used to go out beside it,
        // and ArduPilot answered both, so every rejected mode change was
        // reported twice in the vehicle's own messages.
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

    /**
     * The vehicle's own STATUSTEXT, and nothing else. ArduPilot splits a long
     * message across chunks sharing an id with an increasing sequence, so a
     * continuation is joined onto the previous line rather than logged as a
     * fragment of its own.
     */
    private fun appendVehicleMessage(payload: Statustext) {
        val text = payload.text().trimEnd('\u0000', ' ')
        if (text.isEmpty()) {
            return
        }
        val id = payload.id()
        val sequence = payload.chunkSeq()
        val continues = id != 0 && id == chunkId && sequence == chunkSequence + 1
        chunkId = id
        chunkSequence = sequence
        _state.update { current ->
            val lines = if (continues && current.statusLog.isNotEmpty()) {
                current.statusLog.dropLast(1) + (current.statusLog.last() + text)
            } else {
                current.statusLog + text
            }
            current.copy(statusLog = lines.takeLast(MAX_VEHICLE_MESSAGES))
        }
    }

    companion object {
        const val GCS_SYSTEM_ID = 255
        const val GCS_COMPONENT_ID = 190
        private const val TAG = "MavlinkClient"
        /**
         * What this app reads, and how often it needs it. Anything not named
         * here or in DISABLED_MESSAGES keeps whatever rate the vehicle chose.
         */
        private val SUPPORTING_RATES: Map<Class<*>, Float> = mapOf(
            VfrHud::class.java to 2f, // airspeed, altitude, climb
            SysStatus::class.java to 1f, // battery voltage, sensor health
            GpsRawInt::class.java to 1f, // satellite count, HDOP
            NavControllerOutput::class.java to 2f, // waypoint distance
            Wind::class.java to 1f, // the compass wind arrow
            TerrainReport::class.java to 1f, // terrain altitude
            BatteryStatus::class.java to 0.5f,
            EkfStatusReport::class.java to 0.5f,
            Vibration::class.java to 0.5f,
            ScaledPressure::class.java to 0.5f, // QNH
            MissionCurrent::class.java to 1f, // which waypoint is being flown
            Rangefinder::class.java to 1f,
            DistanceSensor::class.java to 0.5f,
        )

        /** The two whose rate the pilot chooses. */
        private val RATED_MESSAGES: List<Class<*>> = listOf(
            Attitude::class.java,
            GlobalPositionInt::class.java,
        )

        /**
         * Streamed by ArduPilot but never read here. On a link with bandwidth
         * to spare they are harmless; on a slow radio they crowd out the
         * messages that matter, and the radio drops whatever overflows without
         * caring which.
         */
        private val DISABLED_MESSAGES: List<Class<*>> = listOf(
            Simstate::class.java,
            AoaSsa::class.java,
            Ahrs::class.java,
            Ahrs2::class.java,
            RawImu::class.java,
            ScaledImu2::class.java,
            ScaledImu3::class.java,
            ScaledPressure2::class.java,
            ServoOutputRaw::class.java,
            RcChannels::class.java,
            Meminfo::class.java,
            PowerStatus::class.java,
            LocalPositionNed::class.java,
            PositionTargetGlobalInt::class.java,
            SystemTime::class.java,
        )

        private const val MAX_VEHICLE_MESSAGES = 200
        private const val MAV_MODE_FLAG_CUSTOM_MODE_ENABLED = 1
        private const val MAV_FRAME_GLOBAL_RELATIVE_ALT = 3
        private const val SPEED_TYPE_AIRSPEED = 0
        private const val LOITER_RADIUS_PARAM = "WP_LOITER_RAD"
        /**
         * The value MAV_CMD_COMPONENT_ARM_DISARM expects in param2 to skip the
         * pre-arm checks. Anything else there leaves them in force.
         */
        private const val FORCE_ARM_MAGIC = 21196f
        private const val REPOSITION_CHANGE_MODE = 1
        private const val HOME_POSITION_MESSAGE_ID = 242
        /**
         * How long the mode change is given to take effect onboard before
         * the clear follows it. ArduPilot ignores a clear that arrives
         * while it is still in AUTO.
         */
        private const val MISSION_CLEAR_SETTLE_MS = 500L
        private const val HEARTBEAT_INTERVAL_MS = 1000L
        private const val MIN_COURSE_SPEED_MS = 1f
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
