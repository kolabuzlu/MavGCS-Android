package com.mavgcs.app.mavlink

import android.util.Log
import io.dronefleet.mavlink.MavlinkConnection
import io.dronefleet.mavlink.MavlinkMessage
import io.dronefleet.mavlink.ardupilotmega.Rangefinder
import io.dronefleet.mavlink.ardupilotmega.Wind
import io.dronefleet.mavlink.common.Attitude
import io.dronefleet.mavlink.common.BatteryStatus
import io.dronefleet.mavlink.common.CommandAck
import io.dronefleet.mavlink.common.CommandInt
import io.dronefleet.mavlink.common.CommandLong
import io.dronefleet.mavlink.common.DistanceSensor
import io.dronefleet.mavlink.common.GlobalPositionInt
import io.dronefleet.mavlink.common.MavResult
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
import io.dronefleet.mavlink.common.ParamValue
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs
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
    /**
     * Who to address, learned from the vehicle's own heartbeat, or null before
     * one has arrived.
     *
     * Nothing is sent while this is null. It used to fall back to system 1,
     * component 1 -- harmless only for as long as the panel refused to send
     * anything before first contact, which stopped being true when the
     * controls were held open through a blackout. A guess is the wrong thing
     * to arm: on a routed network with more than one airframe, system 1 is
     * somebody, and not necessarily the aircraft in front of the pilot.
     */
    private val target = AtomicReference<Pair<Int, Int>?>(null)
    private var worker: Thread? = null
    private var heartbeat: Thread? = null

    /**
     * The thread decoding the inbound stream.
     *
     * Kept so that disconnect() can reach it. It was left unreferenced, which
     * meant the only thing that ever stopped it was noticing [running] had gone
     * false -- and connect() sets that back to true a few statements after
     * disconnect() returns, so a parse thread that woke inside that window
     * carried on reading a connection nobody else still had, and writing into
     * link statistics that had just been reset for the new session.
     */
    private var parser: Thread? = null
    private var datagramSocket: DatagramSocket? = null
    // The outstanding mode request. Guarded because it is written from the UI
    // thread and read from the heartbeat thread that resends it.
    /** Throughput and loss on the live link. See [LinkStats]. */
    private val linkStats = LinkStats()

    private val modeLock = Any()
    private var modeWanted: String? = null
    private var modeWantedCustom: Long = 0L
    private var modeRetryNextMs: Long = 0L
    private var modeRetryUntilMs: Long = 0L
    private var tcpSocket: Socket? = null
    /** Guards the upload state machine, which the parse and tx threads share. */
    private val missionLock = Any()
    private var missionPending: List<MissionPoint>? = null
    private var clearWanted: String? = null
    private var clearUntilMs = 0L
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
        linkStats.reset()
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
                // Said out loud, not just logged. A refused port and a quiet
                // vehicle look identical on the panel -- more so now that
                // nothing greys out when the link goes quiet -- so the one
                // case the app can be certain about is worth stating.
                //
                // Only when the link was not being closed on purpose. Closing
                // the socket is how disconnect() gets the reader out of a
                // blocking receive, and that arrives here as an ordinary
                // SocketException -- so without this test, pressing Disconnect
                // on a UDP link reported a failure to reach a vehicle that had
                // been answering perfectly a moment earlier. disconnect()
                // clears running before it closes anything, which is what
                // tells the two cases apart.
                if (running.get()) {
                    note("Could not reach ${config.host}:${config.port} — ${reasonFor(error)}")
                }
            } finally {
                running.set(false)
                clearModeRequest()
                _state.update { it.copy(linkUp = false, modePending = null) }
            }
        }
    }

    fun disconnect() {
        running.set(false)
        clearModeRequest()
        // Forget who the vehicle was. The stream rates are applied once, on
        // first contact, and first contact is decided by this being null --
        // so leaving it set meant every reconnect after the first silently
        // kept whatever rates the vehicle happened to have. On a link that
        // drops and is redialled, which is the normal life of an LTE modem,
        // that is the reconnect quietly going back to flooding.
        target.set(null)
        worker?.interrupt()
        heartbeat?.interrupt()
        parser?.interrupt()
        runCatching { datagramSocket?.close() }
        runCatching { tcpSocket?.close() }
        connectionRef.set(null)
        worker = null
        heartbeat = null
        parser = null
        datagramSocket = null
        tcpSocket = null
        // The link meter describes a link. With none open there is nothing for
        // it to describe, and leaving the last live figures there had the
        // Settings panel reporting a healthy 400 B/s for a connection that
        // ended minutes ago -- the readout's own liveness test is a session
        // total, so it never lapsed on its own.
        linkStats.reset()
        _state.update {
            it.copy(
                linkUp = false,
                modePending = null,
                link = LinkQuality(),
                rssiPercent = null,
            )
        }
    }

    fun send(command: GcsCommand) {
        val connection = connectionRef.get() ?: return
        val (sys, comp) = target.get() ?: return
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
        val (sys, comp) = target.get() ?: return
        tx.execute {
            try {
                sendModeChange(connection, sys, comp, customMode)
                Log.i(TAG, "Sent mode $label")
            } catch (error: Exception) {
                Log.w(TAG, "Mode change failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
        // Held open until a heartbeat says the aircraft is in it. A second
        // press replaces this one rather than queueing behind it, so there is
        // only ever one mode outstanding.
        val now = System.currentTimeMillis()
        synchronized(modeLock) {
            modeWanted = label
            modeWantedCustom = customMode
            modeRetryNextMs = now + MODE_RETRY_EVERY_MS
            modeRetryUntilMs = now + MODE_RETRY_FOR_MS
        }
        _state.update { it.copy(modePending = label) }
    }

    /**
     * Resend the outstanding mode, or give up on it.
     *
     * Driven from the heartbeat thread, which ticks regardless of which
     * transport is in use and whether anything is arriving on it.
     */
    private fun driveModeRequest() {
        val now = System.currentTimeMillis()
        var resend: Pair<String, Long>? = null
        var abandoned: String? = null
        synchronized(modeLock) {
            val wanted = modeWanted
            if (wanted != null) {
                if (now >= modeRetryUntilMs) {
                    abandoned = wanted
                    modeWanted = null
                    modeRetryNextMs = 0L
                    modeRetryUntilMs = 0L
                } else if (now >= modeRetryNextMs) {
                    modeRetryNextMs = now + MODE_RETRY_EVERY_MS
                    resend = wanted to modeWantedCustom
                }
            }
        }
        abandoned?.let { label ->
            _state.update { it.copy(modePending = null) }
            // Two very different failures used to be reported as one. A command
            // can go unanswered because the link is dropping frames, and then
            // pressing again is the right advice. It can also go unanswered
            // because the radio carries telemetry down but nothing up -- and on
            // one of those the old message read "too much of the link is being
            // lost" while the link meter beside it said 1.5%, which sent the
            // search to the wrong end of the problem. Twenty-one presses later
            // the aircraft still had not acknowledged one of them.
            note(
                if (linkSoundsOneWay()) {
                    "Mode change to $label was not acknowledged, though " +
                        "telemetry is arriving normally. The aircraft is being " +
                        "heard but is not hearing this app - check that the " +
                        "radio link carries both directions."
                } else {
                    "Mode change to $label was not acknowledged - too much of " +
                        "the link is being lost. Press it again."
                },
            )
        }
        resend?.let { (_, custom) ->
            val connection = connectionRef.get() ?: return
            val (sys, comp) = target.get() ?: return
            tx.execute { runCatching { sendModeChange(connection, sys, comp, custom) } }
        }
    }

    /**
     * The aircraft's verdict on a command, said out loud.
     *
     * Only when it is not a plain acceptance: an accepted command proves
     * itself when the heartbeat comes back in the new mode, and narrating
     * every success would bury the vehicle's own messages.
     *
     * A refused mode change also stops being pending immediately. Holding the
     * button amber and resending for ten more seconds is pointless once the
     * aircraft has said no, and it delays telling the pilot why.
     */
    private fun onCommandAck(ack: CommandAck) {
        val command = runCatching { ack.command().entry() }.getOrNull()
        val result = runCatching { ack.result().entry() }.getOrNull()
        command?.let { awaited.remove(it) }
        // Every answer to the log, accepted ones included. A command that is
        // accepted and then does not happen is a different fault from one that
        // never arrived, and only the log can tell them apart.
        Log.i(TAG, "ACK ${command?.name ?: "?"} -> ${result?.name ?: "?"}")
        if (command == MavCmd.MAV_CMD_DO_SET_HOME && result == MavResult.MAV_RESULT_ACCEPTED) {
            // ArduPilot does not volunteer HOME_POSITION when home changes, and
            // the app only asks for it at first contact. Without this the
            // marker sits at the old place until the next reconnection, which
            // looks exactly like the move having failed.
            val connection = connectionRef.get()
            val addressed = target.get()
            if (connection != null && addressed != null) {
                tx.execute {
                    runCatching {
                        requestHomePosition(connection, addressed.first, addressed.second)
                    }
                }
            }
        }
        if (result == MavResult.MAV_RESULT_ACCEPTED || result == MavResult.MAV_RESULT_IN_PROGRESS) {
            return
        }
        // The pilot asked for none of these -- the app sends them itself on
        // every connection to set up the stream. A refused one is worth a log
        // line and nothing more: narrating it would put a row of identical
        // complaints where the aircraft's own PreArm messages should be.
        if (command in HOUSEKEEPING) return
        val what = spoken(command)
        val why = when (result) {
            MavResult.MAV_RESULT_TEMPORARILY_REJECTED ->
                "not right now - the aircraft is busy or not in a state to do it"
            MavResult.MAV_RESULT_DENIED -> "refused"
            MavResult.MAV_RESULT_UNSUPPORTED -> "not supported by this firmware"
            MavResult.MAV_RESULT_FAILED -> "tried and failed"
            MavResult.MAV_RESULT_CANCELLED -> "cancelled"
            else -> "answered ${result?.name ?: "with something unrecognised"}"
        }
        note("The aircraft $why: $what.")
        if (command == MavCmd.MAV_CMD_DO_SET_MODE) {
            clearModeRequest()
            _state.update { it.copy(modePending = null) }
        }
    }

    /** What the aircraft says it is holding for a parameter that was set. */
    private fun onParamValue(payload: ParamValue) {
        val id = runCatching { payload.paramId() }.getOrNull()
            ?.trim { it <= ' ' } ?: return
        val wanted = synchronized(paramLock) {
            val pending = paramAwaited
            if (pending == null || pending.id != id) return
            paramAwaited = null
            pending
        }
        val got = payload.paramValue()
        note(
            if (abs(got - wanted.asked) < PARAM_SAME_ENOUGH) {
                "${wanted.what} is now ${round(got)}${wanted.unit}."
            } else {
                "${wanted.what} is now ${round(got)}${wanted.unit} - the " +
                    "aircraft would not take ${round(wanted.asked)}${wanted.unit}."
            },
        )
    }

    /** Say so if a parameter write is never answered. */
    private fun sweepParam() {
        val late = synchronized(paramLock) {
            val pending = paramAwaited ?: return
            if (System.currentTimeMillis() < pending.dueMs) return
            paramAwaited = null
            pending
        }
        note(
            if (linkSoundsOneWay()) {
                "The aircraft did not answer the ${late.what.lowercase()}, " +
                    "though telemetry is arriving normally. It is being heard " +
                    "but is not hearing this app - check that the radio link " +
                    "carries both directions."
            } else {
                "The aircraft did not answer the ${late.what.lowercase()} - " +
                    "too much of the link is being lost. Try it again."
            },
        )
    }

    /** Metres, without a trailing zero nobody needs. */
    private fun round(value: Float): String =
        if (abs(value - value.toInt()) < 0.05f) value.toInt().toString()
        else "%.1f".format(Locale.ROOT, value)

    /** Nothing outstanding any more, whatever the reason. */
    private fun clearModeRequest() {
        synchronized(modeLock) {
            modeWanted = null
            modeRetryNextMs = 0L
            modeRetryUntilMs = 0L
        }
    }

    /**
     * The short version of why a link would not open.
     *
     * Android's own text runs to a full line of local port numbers and
     * millisecond counts around the one word that matters, and this goes in a
     * panel four lines tall shared with the vehicle's own messages.
     */
    private fun reasonFor(error: Throwable): String {
        val text = error.message.orEmpty()
        return when {
            text.contains("ECONNREFUSED", true) || error is java.net.ConnectException ->
                "nothing is listening there"
            text.contains("EHOSTUNREACH", true) || text.contains("ENETUNREACH", true) ->
                "no route to that address"
            error is java.net.SocketTimeoutException || text.contains("timeout", true) ->
                "timed out"
            text.contains("EACCES", true) -> "permission denied"
            else -> error.javaClass.simpleName
        }
    }

    /**
     * Telemetry arriving cleanly while nothing answers what is being sent.
     *
     * The two ways a request goes unanswered want opposite advice, and the
     * link meter is what separates them: a radio dropping a third of its
     * frames will swallow a command now and then, and pressing again is the
     * cure. A radio carrying telemetry down and nothing up will swallow every
     * one of them, and pressing again is a waste of the pilot's attention.
     */
    private fun linkSoundsOneWay(): Boolean {
        val quality = _state.value.link
        val loss = quality.lossPercent
        return quality.rxPerSec > 1f && loss != null && loss < ONE_WAY_LOSS_PERCENT
    }

    /** A command's name as it should be read out. */
    private fun spoken(command: MavCmd?): String =
        command?.name?.removePrefix("MAV_CMD_")?.replace('_', ' ')?.lowercase()
            ?: "the command"

    private data class Awaited(val what: String, val dueMs: Long)

    private data class ParamAwaited(
        val id: String,
        val what: String,
        val asked: Float,
        val unit: String,
        val dueMs: Long,
    )

    /**
     * The parameter write waiting to be confirmed.
     *
     * A parameter is not a command and gets no COMMAND_ACK; the autopilot
     * answers with the value it ended up holding. That answer was being
     * thrown away, which left the loiter radius the one control in the app
     * that could not be checked by any means at all -- the map shows no
     * radius, and a write that was dropped, refused or clamped looked exactly
     * like one that took.
     *
     * Worth reading rather than merely counting, because the value that comes
     * back is often not the one asked for: ArduPilot holds WP_LOITER_RAD to
     * its own limits, and a plane quietly orbiting at a radius the pilot did
     * not choose is the thing to say out loud.
     */
    private val paramLock = Any()
    private var paramAwaited: ParamAwaited? = null

    /**
     * Commands sent and not yet answered.
     *
     * Only the mode change used to be watched, so every other button was
     * fire and forget: arm, disarm, the guided controls, the reposition. On a
     * link that silently dropped all of them -- which is what an ExpressLRS
     * radio was doing until the frames were trimmed -- the app said a cheerful
     * nothing while the aircraft did a cheerful nothing.
     *
     * Reported, never resent. An autopilot answers a command it has already
     * obeyed, so a missing answer can mean the command arrived and the answer
     * did not, and quietly running preflight calibration a second time, or
     * re-arming behind the pilot's back, is worse than saying so once. Keyed
     * by command, so pressing a button twice replaces its own entry rather
     * than queueing a second complaint.
     */
    private val awaited = ConcurrentHashMap<MavCmd, Awaited>()

    /** Start expecting an answer to [command]. */
    private fun expect(command: MavCmd) {
        // The mode has its own watcher, which also resends; two of them would
        // talk over each other. The stream setup the app does for itself is
        // not the pilot's business either way.
        if (command == MavCmd.MAV_CMD_DO_SET_MODE || command in HOUSEKEEPING) return
        awaited[command] = Awaited(
            spoken(command),
            System.currentTimeMillis() + COMMAND_ANSWER_MS,
        )
    }

    /** Say so about anything that has run out of time to answer. */
    private fun sweepAwaited() {
        val now = System.currentTimeMillis()
        awaited.entries.filter { now >= it.value.dueMs }.forEach { entry ->
            awaited.remove(entry.key)
            note(
                if (linkSoundsOneWay()) {
                    "The aircraft did not answer ${entry.value.what}, though " +
                        "telemetry is arriving normally. It is being heard but " +
                        "is not hearing this app - check that the radio link " +
                        "carries both directions."
                } else {
                    "The aircraft did not answer ${entry.value.what} - too " +
                        "much of the link is being lost. Try it again."
                },
            )
        }
    }

    /** A line for the Messages panel that did not come from the vehicle. */
    private fun note(text: String) {
        _state.update { current ->
            current.copy(
                statusLog = (current.statusLog + text).takeLast(MAX_VEHICLE_MESSAGES),
            )
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
        synchronized(paramLock) {
            paramAwaited = ParamAwaited(
                id = LOITER_RADIUS_PARAM,
                what = "Loiter radius",
                asked = meters,
                unit = " m",
                dueMs = System.currentTimeMillis() + COMMAND_ANSWER_MS,
            )
        }
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
            expect(MavCmd.MAV_CMD_DO_REPOSITION)
        }

    /**
     * Move the point RTL flies back to and relative altitudes are measured from.
     *
     * COMMAND_INT for the reason the reposition uses it: a float32 cannot hold
     * a 1e7 scaled latitude without losing metres of it.
     *
     * The altitude sent is the one home already has, not a new one. The pilot
     * has dragged a marker across a flat map and said nothing about height,
     * and home's altitude is the datum every relative altitude on the aircraft
     * is measured against -- inventing one here would move the RTL height and
     * the altitude readout along with it.
     */
    fun setHome(lat: Double, lon: Double) {
        val altitude = _state.value.homeAltM
        if (altitude == null) {
            note("The aircraft has not said where home is yet, so it cannot be moved.")
            return
        }
        guided("home to %.6f, %.6f".format(Locale.ROOT, lat, lon)) { connection, sys, comp ->
            val payload = CommandInt.builder()
                .targetSystem(sys)
                .targetComponent(comp)
                .frame(MavFrame.MAV_FRAME_GLOBAL)
                .command(MavCmd.MAV_CMD_DO_SET_HOME)
                .current(0)
                .autocontinue(0)
                // Zero means the place named here, rather than wherever the
                // aircraft happens to be standing.
                .param1(0f)
                .param2(0f)
                .param3(0f)
                .param4(Float.NaN)
                .x((lat * 1e7).roundToInt())
                .y((lon * 1e7).roundToInt())
                .z(altitude.toFloat())
                .build()
            connection.send2(GCS_SYSTEM_ID, GCS_COMPONENT_ID, payload)
            expect(MavCmd.MAV_CMD_DO_SET_HOME)
        }
    }

    private fun guided(description: String, block: (MavlinkConnection, Int, Int) -> Unit) {
        val connection = connectionRef.get() ?: return
        val (sys, comp) = target.get() ?: return
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
                // Counted here, on the far side of the decision, rather than in
                // a wrapper above it. In LISTEN mode there is no peer until
                // something speaks first, and this returns quietly until then.
                // A counter outside could see only the call and not the
                // silence, which had the link meter reporting an uplink of
                // 21 B/s on the same screen that said no vehicle had ever been
                // heard -- the two new readings contradicting each other, and
                // the wrong one being the reassuring one.
                val dest = remote.get() ?: return
                socket.send(DatagramPacket(b, off, len, dest.address, dest.port))
                linkStats.onTx(len, frame = true)
            }
        }
        startConnection(input, output)
        val buffer = ByteArray(2048)
        // Closed however this ends, including the way it usually ends: the
        // socket being shut under a blocked receive, which throws straight past
        // here. Without the finally, the queue was never closed and the parse
        // thread reading it stayed blocked on a poll that would never return
        // again -- one stranded thread for every UDP session of the run.
        try {
            while (running.get() && !Thread.currentThread().isInterrupted) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    if (config.udpMode == UdpMode.LISTEN) {
                        // Whoever just spoke is who to answer. When dialling out
                        // the peer is the one that was asked for, whatever port
                        // it happens to reply from.
                        val from = InetSocketAddress(packet.address, packet.port)
                        if (remote.getAndSet(from) != from) {
                            Log.i(TAG, "peer ${from.address.hostAddress}:${from.port}")
                        }
                    }
                    input.offer(buffer.copyOf(packet.length))
                } catch (_: java.net.SocketTimeoutException) {
                    checkStale()
                }
            }
        } finally {
            input.close()
        }
    }

    private fun runTcp(config: LinkConfig) {
        val socket = Socket()
        socket.connect(InetSocketAddress(config.host, config.port), 5000)
        socket.tcpNoDelay = true
        tcpSocket = socket
        startConnection(socket.getInputStream(), counting(socket.getOutputStream()))
        while (running.get() && !socket.isClosed) {
            Thread.sleep(500)
            checkStale()
        }
    }

    /**
     * The truncated form of one MAVLink 2 frame, or null to send it unchanged.
     */
    private fun trimPayload(b: ByteArray, off: Int, len: Int): ByteArray? {
        if (len < V2_HEADER + 2) return null
        if (b[off].toInt() and 0xFF != V2_MAGIC) return null
        // Signed frames carry 13 more bytes and a signature over the contents.
        if (b[off + 1 + 1].toInt() and 0x01 != 0) return null
        val payload = b[off + 1].toInt() and 0xFF
        // Exactly one whole frame, or leave it alone.
        if (len != V2_HEADER + payload + 2) return null
        var kept = payload
        while (kept > 1 && b[off + V2_HEADER + kept - 1].toInt() == 0) kept--
        if (kept == payload) return null

        // The seed the sender mixed in after the frame, read back off the
        // checksum it produced.
        var full = CRC_INIT
        for (i in 1 until V2_HEADER + payload) full = crcAccumulate(b[off + i].toInt(), full)
        val was = (b[off + V2_HEADER + payload].toInt() and 0xFF) or
            ((b[off + V2_HEADER + payload + 1].toInt() and 0xFF) shl 8)
        val seed = (0..255).firstOrNull { crcAccumulate(it, full) == was } ?: return null

        val out = ByteArray(V2_HEADER + kept + 2)
        System.arraycopy(b, off, out, 0, V2_HEADER + kept)
        out[1] = kept.toByte()
        var crc = CRC_INIT
        for (i in 1 until V2_HEADER + kept) crc = crcAccumulate(out[i].toInt(), crc)
        crc = crcAccumulate(seed, crc)
        out[V2_HEADER + kept] = (crc and 0xFF).toByte()
        out[V2_HEADER + kept + 1] = ((crc shr 8) and 0xFF).toByte()
        return out
    }

    /** One byte of CRC-16/MCRF4XX, the checksum MAVLink frames carry. */
    private fun crcAccumulate(byte: Int, crc: Int): Int {
        var tmp = (byte and 0xFF) xor (crc and 0xFF)
        tmp = (tmp xor (tmp shl 4)) and 0xFF
        return ((crc shr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp shr 4)) and 0xFFFF
    }

    /**
     * Drops the trailing zero bytes MAVLink 2 allows a sender to leave out.
     *
     * The format calls this optional and every autopilot accepts either form,
     * so the library not doing it is not a bug. The radio is another matter.
     * Over an ExpressLRS link the untruncated frame is simply gone: the same
     * DO_SET_MODE, same target, same sysid, sent seconds apart down the same
     * socket, was answered MAV_RESULT_ACCEPTED at 44 bytes and answered with
     * nothing at 45. One trailing zero on the confirmation field was the whole
     * difference, and it cost every command the app has ever sent over that
     * radio -- mode changes, stream rates, the lot -- while telemetry poured
     * back the other way and made the link look healthy.
     *
     * The frame carries no table of the CRC seeds each message type needs, so
     * rather than ship one that has to track the dialect, recover the seed
     * from the checksum already on the frame. The per-byte step is a bijection
     * for a fixed running value, so exactly one of the 256 candidates can have
     * produced it.
     *
     * Anything not a plain unsigned MAVLink 2 frame is passed through
     * untouched: version 1 has no truncation, a signed frame must not be
     * rewritten, and a buffer that is not exactly one whole frame is not ours
     * to interpret.
     */
    private fun truncating(out: OutputStream): OutputStream = object : OutputStream() {
        override fun write(b: Int) = out.write(b)

        override fun flush() = out.flush()

        override fun close() = out.close()

        override fun write(b: ByteArray, off: Int, len: Int) {
            val trimmed = runCatching { trimPayload(b, off, len) }.getOrNull()
            if (trimmed == null) out.write(b, off, len) else out.write(trimmed, 0, trimmed.size)
        }
    }

    private fun startConnection(input: InputStream, output: OutputStream) {
        // [output] is expected to count its own transmitted bytes. TCP is
        // wrapped by the caller; UDP counts inside its own writer, because only
        // that writer knows whether a datagram had anywhere to go. The inbound
        // side is wrapped here instead, because both transports need the same
        // treatment and neither can do it further up: what arrives has to be
        // counted before the parser is free to throw any of it away.
        val connection = MavlinkConnection.create(counting(input), truncating(output))
        connectionRef.set(connection)
        val opened = System.currentTimeMillis()
        var silenceReported = false
        heartbeat = thread(name = "mavlink-hb", isDaemon = true) {
            // disconnect() interrupts this thread, which makes the sleep throw.
            // An uncaught exception on any thread takes the whole process down
            // with it, so the normal way out has to be caught here.
            try {
                while (running.get() && !Thread.currentThread().isInterrupted) {
                    runCatching { tx.execute { runCatching { sendHeartbeat(connection) } } }
                    runCatching { driveModeRequest() }
                    runCatching { driveMissionClear() }
                    runCatching { sweepAwaited() }
                    runCatching { sweepParam() }
                    runCatching {
                        val sample = linkStats.sample(System.currentTimeMillis())
                        _state.update { it.copy(link = sample) }
                    }
                    // A socket that opened onto nothing. Said once: the
                    // address was probably wrong, and repeating it every
                    // second would bury the log it is written into.
                    if (!silenceReported &&
                        target.get() == null &&
                        System.currentTimeMillis() - opened > SILENT_LINK_MS
                    ) {
                        silenceReported = true
                        note(
                            "Link is open but no vehicle has been heard in " +
                                "${SILENT_LINK_MS / 1000} seconds. Check the address, " +
                                "the port, and that the vehicle is powered.",
                        )
                    }
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        parser = thread(name = "mavlink-parse", isDaemon = true) {
            try {
                while (running.get() && !Thread.currentThread().isInterrupted) {
                    // Out of the loop when the stream ends, not round it again.
                    // The library drops frames it cannot parse by itself and
                    // throws only when the stream is finished or broken, so
                    // there is nothing here to recover from by retrying. This
                    // was a continue, and a TCP peer that had closed made
                    // next() throw instantly and forever: a daemon thread at
                    // 100% of a core for the rest of the flight, because a
                    // socket reports itself open long after the other end has
                    // gone, and nothing on the panel said otherwise.
                    val message = runCatching { connection.next() }.getOrNull() ?: break
                    // Nothing is counted here any more. It used to be, and it
                    // could only ever count what survived parsing -- which is
                    // the wrong population to measure a radio by, and meant
                    // asking the library for the raw frame, which hands back a
                    // fresh copy of every message purely so its length can be
                    // read and the copy dropped. Both went away together when
                    // the counting moved down to the stream itself.
                    //
                    // A malformed message must not be fatal either.
                    runCatching { onMessage(connection, message) }
                }
                // Reached by the break above, so only when the stream ended
                // under us rather than because the pilot asked. Worth saying:
                // a vehicle that stops talking and a server that hung up look
                // identical on a panel that deliberately holds its last
                // reading, and this is the half the app can be sure about.
                if (running.get()) {
                    running.set(false)
                    note("The link closed at the other end.")
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
            // The receiver's signal strength, 0..254, with 255 meaning it has
            // nothing to report -- which is not the same as no signal, and is
            // what a MAVLink-injected RC link leaves there.
            is RcChannels -> _state.update {
                val raw = payload.rssi()
                it.copy(rssiPercent = if (raw >= 255) null else raw * 100f / 254f)
            }
            // What the aircraft made of a command it was sent.
            //
            // Ignored entirely until now, which left the app guessing. A mode
            // that did not take was reported as the link losing too much,
            // because that was the only explanation the app had -- it waited
            // ten seconds for a heartbeat in the new mode and gave up. But an
            // autopilot answers every command, and a refusal is not silence:
            // it says so, and says which of several things it means. Blaming
            // the radio for a refusal sends the pilot to look in the wrong
            // place entirely.
            is CommandAck -> onCommandAck(payload)
            is ParamValue -> onParamValue(payload)
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
                // One message carries both which way the controller is
                // steering and how far it has left to go, which is the whole
                // of the line to the target.
                it.copy(
                    distToWpM = payload.wpDist().toFloat(),
                    navBearingDeg = normaliseBearing(payload.targetBearing().toFloat()),
                )
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
                    homeAltM = payload.altitude() / 1000.0,
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
        val speaker = message.originSystemId to message.originComponentId
        val was = target.getAndSet(speaker)
        val firstContact = was == null
        // Commands are addressed to whoever sent the last heartbeat, so a
        // second component appearing on the link silently takes them over. It
        // is the explanation that fits a vehicle that is heard perfectly and
        // obeys nothing, so the day it happens the log should say so rather
        // than leave it to be deduced.
        if (was != null && was != speaker) {
            Log.i(
                TAG,
                "target moved ${was.first}/${was.second} -> " +
                    "${speaker.first}/${speaker.second} $typeName($typeValue)",
            )
        }
        // Read off this heartbeat, above the block that needs it. It used to be
        // worked out below, after the stream rates had already been sent, which
        // left those rates deciding what to ask a PX4 for while still believing
        // the firmware was unknown.
        val firmware = when {
            autopilotName.contains("ARDUPILOT") -> Firmware.ARDUPILOT
            autopilotName.contains("PX4") -> Firmware.PX4
            else -> Firmware.UNKNOWN
        }
        if (firstContact) {
            // Order matters, and getting it wrong silently undid everything
            // below. REQUEST_DATA_STREAM is the old blunt instrument: one rate
            // for a whole group of messages, and ALL means every group. It is
            // kept because firmware too old for per-message intervals still
            // understands it, but ArduPilot rebuilds its message schedule from
            // the stream rates when it arrives -- so sent afterwards it wipes
            // the intervals set here, every one of them, and puts the disabled
            // messages back on air.
            //
            // It went second until now, which is why the rates in Settings
            // appeared to do nothing on a fresh connection: the app asked for
            // exactly what it wanted and then immediately asked for everything
            // at 4Hz instead.
            requestStreams(connection, message.originSystemId, message.originComponentId)
            // Only once the vehicle has said who it is: every request has to be
            // addressed to it.
            applyStreamRates(streamRates, firmware)
        }
        val vehicleType = typeName.removePrefix("MAV_TYPE_").ifBlank { "TYPE $typeValue" }
        val mode = when (firmware) {
            Firmware.ARDUPILOT -> FlightModes.ardupilotMode(vehicleType, custom)
            Firmware.PX4 -> FlightModes.px4ModeName(custom)
            Firmware.UNKNOWN -> "MODE $custom"
        }
        // The aircraft saying which mode it is in is the only confirmation
        // worth having. A COMMAND_ACK says the request was received, not that
        // the mode took -- and a mode the pilot selected on their own switch
        // clears the request just the same, because there is nothing left to
        // chase either way.
        val confirmed = synchronized(modeLock) {
            if (modeWanted != null && modeWanted == mode) {
                modeWanted = null
                modeRetryNextMs = 0L
                modeRetryUntilMs = 0L
                true
            } else {
                false
            }
        }
        _state.update {
            it.copy(
                linkUp = true,
                modePending = if (confirmed) null else it.modePending,
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
        val (sys, comp) = target.get() ?: return
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
     * goes first and the clear only follows once a heartbeat says the aircraft
     * is really in it. Any upload in flight is abandoned too, or its stale
     * acknowledgement would arrive later and be read as this clear's result.
     *
     * It used to send the mode once, sleep half a second and send the clear
     * regardless. One lost frame and the aircraft stayed in AUTO, refused the
     * clear without a word, and carried on flying a mission the map had
     * already rubbed out. Going through the Flight Mode panel's own path
     * instead means the mode is resent until it takes and the pilot is told
     * when it does not, and waiting for the heartbeat means the clear is only
     * sent when it can actually work.
     */
    fun clearMission() {
        val connection = connectionRef.get() ?: return
        val (sys, comp) = target.get() ?: return
        resetMission()
        val snapshot = _state.value
        val custom = when (snapshot.firmware) {
            Firmware.PX4 -> FlightModes.px4CustomMode(GcsCommand.LOITER)
            else -> FlightModes.ardupilotCustomMode(snapshot.vehicleType, GcsCommand.LOITER)
        }
        if (custom == null) {
            // Firmware with no loiter to ask for. Nothing to wait on, so send
            // the clear and let its acknowledgement speak for it.
            beginClear(connection, sys, comp)
            return
        }
        // Derived exactly as the heartbeat derives the mode it will be
        // compared against. Naming it any other way makes a string that can
        // never match, and a clear that waits out its whole window and then
        // reports a mode change that had in fact already happened.
        val label = when (snapshot.firmware) {
            Firmware.ARDUPILOT -> FlightModes.ardupilotMode(snapshot.vehicleType, custom)
            Firmware.PX4 -> FlightModes.px4ModeName(custom)
            Firmware.UNKNOWN -> "MODE $custom"
        }
        setFlightMode(label, custom)
        synchronized(missionLock) {
            clearWanted = label
            clearUntilMs = System.currentTimeMillis() + MISSION_CLEAR_WAIT_MS
        }
    }

    /** Send the clear, and watch for the acknowledgement that settles it. */
    private fun beginClear(connection: MavlinkConnection, sys: Int, comp: Int) {
        synchronized(missionLock) { missionState = MissionState.CLEARING }
        tx.execute {
            runCatching {
                sendMissionClear(connection, sys, comp)
                Log.i(TAG, "Sent mission clear")
            }.onFailure { error ->
                Log.w(TAG, "Could not clear the mission: " + error.message)
            }
        }
    }

    /**
     * Send the waiting clear once the aircraft is in the mode that allows it.
     *
     * Driven from the heartbeat thread rather than waited for inline: the
     * sending thread is a single one, and blocking it here would stop the very
     * mode retries this is waiting on.
     */
    private fun driveMissionClear() {
        val (wanted, deadline) = synchronized(missionLock) {
            (clearWanted ?: return) to clearUntilMs
        }
        if (_state.value.mode.equals(wanted, ignoreCase = true)) {
            synchronized(missionLock) { clearWanted = null }
            val connection = connectionRef.get() ?: return
            val (sys, comp) = target.get() ?: return
            beginClear(connection, sys, comp)
            return
        }
        if (System.currentTimeMillis() >= deadline) {
            synchronized(missionLock) { clearWanted = null }
            note(
                "The mission was not cleared - the aircraft never went into " +
                    "$wanted, and it will not clear a mission it is flying. " +
                    "It is still flying it.",
            )
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
        val (sys, comp) = target.get() ?: return
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

            // A clear asked for on its own, rather than to make room for an
            // upload. Only this settles what the aircraft is holding, which is
            // why the map keeps drawing the old mission until it arrives.
            MissionState.CLEARING -> {
                resetMission()
                val accepted = payload.type().entry() == MavMissionResult.MAV_MISSION_ACCEPTED
                if (accepted) {
                    _state.update { it.copy(missionCleared = it.missionCleared + 1) }
                    Log.i(TAG, "Mission cleared")
                } else {
                    Log.w(TAG, "Mission clear refused: " + payload.type().entry())
                    note("The aircraft would not clear the mission: " + payload.type().entry())
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
        val (sys, comp) = target.get() ?: return
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

    private enum class MissionState { AWAITING_CLEAR_ACK, UPLOADING, CLEARING }

    /**
     * Ask the vehicle for the message rates this app actually wants.
     *
     * Sent as SET_MESSAGE_INTERVAL per message, best effort: a vehicle that
     * ignores these behaves exactly as it did before, so firmware without the
     * command loses nothing. Message ids come from the library's own
     * annotations rather than being written out here, so they cannot drift
     * from the dialect being spoken.
     */
    fun applyStreamRates(rates: StreamRates) =
        applyStreamRates(rates, _state.value.firmware)

    /**
     * [firmware] is passed rather than read, because the one caller that
     * matters knows it before the state does.
     *
     * On first contact this is called from inside the heartbeat that identifies
     * the vehicle, several statements before that heartbeat publishes what it
     * learned. Reading the published value here meant reading UNKNOWN every
     * time -- so the firmware test below was dead on exactly the connection it
     * was written for, and only ever came true later, if the pilot happened to
     * open Settings and change a rate.
     */
    private fun applyStreamRates(rates: StreamRates, firmware: Firmware) {
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
                // The wind estimate arrives as WIND on ArduPilot and as
                // WIND_COV on PX4, and neither firmware implements the
                // other's. Asking for the one it does not have earns a
                // complaint back on every connect, so it is left out rather
                // than disabled -- there is nothing there to turn off.
                //
                // Outside the branch, because "full rates" asks for every key
                // in the table and so asked PX4 for WIND too.
                if (firmware == Firmware.PX4) {
                    intervals.remove(Wind::class.java)
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
                // By number, because these postdate the MAVLink library this
                // app is built against and so have no class to name. Silencing
                // one does not require being able to parse it.
                val unwantedInterval = if (rates.full) 0f else -1f
                DISABLED_MESSAGE_IDS.forEach { id ->
                    sendCommand(
                        connection,
                        sys,
                        comp,
                        MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL,
                        param1 = id.toFloat(),
                        param2 = unwantedInterval,
                    )
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
    /**
     * The same stream, with what passes through it counted.
     *
     * Wrapped here rather than counted at each of the thirteen places that
     * send something, so nothing can be added later that quietly escapes the
     * tally. The library serialises a frame and writes it in one call, which
     * is what lets an array write stand for a message.
     */
    /**
     * The same stream, with the frames crossing it counted.
     *
     * Safe to count here, at the very bottom, even though the reader above
     * rewinds itself constantly: it does that by pushing bytes back into a
     * buffer of its own, so each byte is drawn from this stream exactly once
     * however many times the parser reconsiders it.
     */
    private fun counting(input: InputStream): InputStream = object : InputStream() {
        private val frames = FrameCounter(linkStats)

        override fun read(): Int {
            val value = input.read()
            if (value >= 0) frames.byte(value)
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = input.read(b, off, len)
            for (i in 0 until read) frames.byte(b[off + i].toInt())
            return read
        }

        override fun available(): Int = input.available()
        override fun close() = input.close()
    }

    private fun counting(output: OutputStream): OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            output.write(b)
            linkStats.onTx(1, frame = false)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            output.write(b, off, len)
            linkStats.onTx(len, frame = true)
        }

        override fun flush() = output.flush()
        override fun close() = output.close()
    }

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
        expect(command)
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
            // The only thing either firmware will tell us about the radio.
            // Neither ArduPilot nor PX4 puts link quality or signal-to-noise
            // in any standard MAVLink field, so the receiver's RSSI is the
            // whole of what can be known, and this is the message carrying it.
            RcChannels::class.java to 1f,
            // Home is announced once, at arming. Once is no guarantee at all
            // on a radio that drops packets, and missing it means no home
            // marker and no distance-to-home for the rest of the flight. The
            // explicit request on connect still goes out; this is the slow
            // repeat that catches the case where the answer to it was lost.
            HomePosition::class.java to 0.2f,
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
            Meminfo::class.java,
            PowerStatus::class.java,
            LocalPositionNed::class.java,
            PositionTargetGlobalInt::class.java,
            SystemTime::class.java,
        )

        /**
         * Streamed by ArduPilot, never read here, and silenced by number
         * rather than by class.
         *
         * Measured on a plane at the rates this app asks for, the first two
         * below were 316 B/s of an 891 B/s stream -- a third of everything the
         * vehicle sent, for data nothing displays. On a link with room to
         * spare that is merely wasteful; on ELRS at 435 B/s it is most of the
         * budget, and the radio drops whatever overflows without caring which.
         *
         * 11030 ESC_TELEMETRY_1_TO_4, 4Hz and 220 B/s of it.
         * 295   AIRSPEED, whose figure VFR_HUD already carries.
         * 143   SCALED_PRESSURE3, a third barometer nothing reads.
         *
         * Every id here must be one the firmware can actually schedule.
         * ArduPilot maps a MAVLink id to an internal slot before it will
         * change a rate, and for an id with no slot it answers "No ap_message
         * for mavlink id (n)" -- which lands in the pilot's message panel on
         * every connect, for a message that was never being sent in the first
         * place. So an obsolete id costs a complaint and saves nothing.
         *
         * 165 HWSTATUS was in this list and is the reason that is written
         * down. It was deprecated in 2022 in favour of POWER_STATUS and its
         * slot is commented out in ArduPilot's own table, so asking to turn it
         * off produced exactly that line on every connection. 182 AHRS3 is
         * obsolete the same way and must not be added either.
         */
        private val DISABLED_MESSAGE_IDS: List<Int> = listOf(
            11030, // ESC_TELEMETRY_1_TO_4
            295, // AIRSPEED
            143, // SCALED_PRESSURE3
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
        /**
         * How long a command has to be answered before the app says it was not.
         *
         * An autopilot answers the moment it has read one, so this is almost
         * all link: long enough for a slow radio's downlink to find room for
         * the acknowledgement, short enough that the pilot is not left
         * believing a button worked.
         */
        private const val COMMAND_ANSWER_MS = 5_000L

        /** Close enough that the aircraft took the value that was asked for. */
        private const val PARAM_SAME_ENOUGH = 0.05f

        /**
         * How long to wait for the mode the clear needs before giving up.
         *
         * A little beyond the mode's own retry window, so the answer is
         * whether the mode ever took rather than whether it took in time.
         */
        private const val MISSION_CLEAR_WAIT_MS = 12_000L
        private const val HEARTBEAT_INTERVAL_MS = 1000L

        /** How often an unconfirmed mode request goes back on the wire. */
        /**
         * How long a link may be open and silent before saying so.
         *
         * Long enough that a vehicle still booting is not accused of being
         * absent, short enough to catch a mistyped port before the pilot has
         * given up on it.
         */
        private const val SILENT_LINK_MS = 10_000L

        /** The requests the app makes for itself, not for the pilot. */
        private val HOUSEKEEPING = setOf(
            MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL,
            MavCmd.MAV_CMD_REQUEST_MESSAGE,
        )

        /** Magic, and the bytes before the payload, of a MAVLink 2 frame. */
        private const val V2_MAGIC = 0xFD
        private const val V2_HEADER = 10
        private const val CRC_INIT = 0xFFFF

        private const val MODE_RETRY_EVERY_MS = 1000L

        /**
         * Below this much loss a silent command is not the link dropping it.
         *
         * Every retry inside the window has to vanish for a command to be
         * abandoned at all, which chance alone will not do at a few percent.
         * Set well above the couple of percent a healthy radio shows and well
         * below the third or more that actually swallows a whole burst.
         */
        private const val ONE_WAY_LOSS_PERCENT = 15f

        /**
         * How long to keep trying before admitting it is not getting through.
         *
         * Long enough to ride out the burst losses and handover stalls these
         * links produce, short enough that a button does not sit lit when the
         * link has genuinely gone.
         */
        private const val MODE_RETRY_FOR_MS = 10_000L
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

/**
 * Finds frame boundaries in a raw MAVLink byte stream.
 *
 * This exists because the measurement has to happen before the library gets a
 * look at the stream. The library discards any frame it cannot put a name to,
 * and the sequence number of a discarded frame is spent all the same -- so
 * counting parsed messages instead made a hole in the numbering that the loss
 * meter then reported as a lost packet. On a link dropping nothing at all,
 * with ArduPilot streaming the one message this dialect has no class for, that
 * read 11.2%. The bytes of those frames went uncounted for the same reason,
 * which pushed the throughput figure the other way.
 *
 * Only the header is read. A frame announces its own length, so there is no
 * need to understand what it carries -- which is the entire point, since the
 * frames that matter here are the ones nothing can understand.
 */
private class FrameCounter(private val stats: LinkStats) {
    private companion object {
        const val MAGIC_V1 = 0xFE
        const val MAGIC_V2 = 0xFD

        /** Header, checksum and, on v2, the optional signature. */
        const val OVERHEAD_V1 = 8
        const val OVERHEAD_V2 = 12
        const val SIGNATURE_BYTES = 13
    }

    /** Bytes taken from the current frame, magic included. Zero means hunting. */
    private var index = 0
    private var v2 = false
    private var total = 0
    private var seq = 0
    private var sys = 0
    private var comp = 0

    /**
     * A frame is complete but not yet counted.
     *
     * Held back one byte on purpose. Frames run back to back, so the byte after
     * one must begin the next; if it does not, this was never a frame and the
     * reader had latched onto a payload byte that happened to look like a
     * marker. That happens whenever a connection opens partway through a frame,
     * which over TCP to a running simulator is the normal case, and without
     * this check the mistake would go on inventing losses rather than
     * correcting itself.
     */
    private var pending = false
    private var pendingTotal = 0
    private var pendingSeq = 0
    private var pendingSys = 0
    private var pendingComp = 0

    fun reset() {
        index = 0
        pending = false
    }

    fun byte(value: Int) {
        val b = value and 0xFF
        if (pending) {
            pending = false
            if (b == MAGIC_V1 || b == MAGIC_V2) {
                stats.onRx(pendingTotal, pendingSys, pendingComp, pendingSeq)
            } else {
                // Out of step. Drop the supposed frame and hunt for a marker.
                index = 0
                return
            }
        }
        if (index == 0) {
            when (b) {
                MAGIC_V2 -> { v2 = true; index = 1 }
                MAGIC_V1 -> { v2 = false; index = 1 }
            }
            return
        }
        when {
            index == 1 -> total = b + if (v2) OVERHEAD_V2 else OVERHEAD_V1
            // The low bit of the incompatibility flags is the one that adds a
            // signature to the end, and so changes how long the frame is.
            v2 && index == 2 -> if (b and 0x01 != 0) total += SIGNATURE_BYTES
            v2 && index == 4 -> seq = b
            v2 && index == 5 -> sys = b
            v2 && index == 6 -> comp = b
            !v2 && index == 2 -> seq = b
            !v2 && index == 3 -> sys = b
            !v2 && index == 4 -> comp = b
        }
        index += 1
        if (index >= total) {
            pendingTotal = total
            pendingSeq = seq
            pendingSys = sys
            pendingComp = comp
            pending = true
            index = 0
        }
    }
}
