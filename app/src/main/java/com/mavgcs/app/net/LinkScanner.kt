package com.mavgcs.app.net

import android.content.Context
import android.net.ConnectivityManager
import com.mavgcs.app.mavlink.LinkType
import com.mavgcs.app.mavlink.UdpMode
import io.dronefleet.mavlink.MavlinkConnection
import io.dronefleet.mavlink.minimal.Heartbeat
import io.dronefleet.mavlink.minimal.MavAutopilot
import io.dronefleet.mavlink.minimal.MavState
import io.dronefleet.mavlink.minimal.MavType
import io.dronefleet.mavlink.util.EnumValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * Find MAVLink endpoints on the network the tablet is already on.
 *
 * The point is to remove the step where the pilot reads an IP off a laptop and
 * types it in. Everything here is ordinary client traffic on the local subnet:
 * a TCP connect, or a GCS heartbeat sent to a port, which is exactly what this
 * app does anyway once a link is up.
 *
 * TCP and UDP have to be found in opposite ways, which is why this is not one
 * loop. A TCP server can be discovered by connecting to it and seeing what it
 * says. UDP has no connection to make and no error when nobody is listening,
 * so silence proves nothing: the only way to find a vehicle is to speak first
 * and see whether anything answers.
 */
object LinkScanner {

    /** An endpoint that answered, ready to be dropped into the connection form. */
    data class Found(
        val type: LinkType,
        val host: String,
        val port: Int,
        val udpMode: UdpMode,
        /** What answered, for telling two vehicles apart in the list. */
        val detail: String,
    ) {
        val label: String get() = "$host:$port"
    }

    /**
     * Where MAVLink is conventionally served over TCP.
     *
     * 5760 is ArduPilot SITL's first port and the one mavlink-router and the
     * ESP telemetry bridges default to; 5762 and 5763 are SITL's second and
     * third, handed out when something already holds the first. 4560 is PX4's.
     */
    private val TCP_PORTS = listOf(5760, 5762, 5763, 4560)

    /**
     * Where a vehicle or simulator listens for a GCS to announce itself.
     *
     * 14550 is the standard GCS port and covers nearly everything; the rest
     * are what a second and third vehicle on one host end up with.
     */
    private val UDP_PORTS = listOf(14550, 14551, 14555)

    /** Long enough for a switch to answer, short enough to sweep a subnet. */
    private const val TCP_CONNECT_MS = 350

    /** A vehicle streams at a few hertz, so a frame is due well inside this. */
    private const val TCP_READ_MS = 900

    /** How long to keep reading a confirmed stream, hunting for a heartbeat. */
    private const val TCP_IDENTIFY_MS = 1200L

    /** And a ceiling on frames, for a link fast enough to flood that window. */
    private const val TCP_IDENTIFY_FRAMES = 60

    /** How long to keep listening after the last probe goes out. */
    private const val UDP_LISTEN_MS = 1500L

    /** Enough sockets to sweep a /24 quickly, few enough not to drown the NIC. */
    private const val PARALLEL = 128

    /**
     * The port the liveness pass knocks on.
     *
     * It does not matter whether anything is listening — a refusal proves the
     * machine is there just as well as an answer does. 5760 is used because a
     * host that does have MAVLink on it most likely has this one open, so the
     * knock doubles as a hit.
     */
    private const val LIVENESS_PORT = 5760

    /** A machine on the same subnet answers in single-digit milliseconds. */
    private const val LIVENESS_MS = 300

    /**
     * Sweep the local subnet and return whatever answered.
     *
     * [onStage] names the pass now running. Deliberately not a percentage: the
     * three passes take wildly different times for the same number of steps,
     * so a bar counting addresses would reach the end in the first second and
     * then sit still for the rest, which reads as a hang rather than progress.
     */
    suspend fun scan(
        context: Context,
        onStage: (String) -> Unit = {},
    ): List<Found> = withContext(Dispatchers.IO) {
        val hosts = subnetHosts(context)
        val found = ConcurrentHashMap.newKeySet<Found>()
        val gate = Semaphore(PARALLEL)

        // Two passes, because an empty address is the expensive case and there
        // are usually 250 of them. Probing every port on every address means
        // every dead one costs the full timeout four times over, which is the
        // difference between a button that answers and one that hangs.
        //
        // A machine that is switched on says so immediately, whether the port
        // is open or not: a listening port returns SYN-ACK, a closed one an
        // RST, and both arrive in milliseconds. Only an address with nothing
        // at it stays silent for the whole timeout. So the first pass asks one
        // port per address purely to sort the living from the empty, and only
        // the living are asked the rest.
        val alive = ConcurrentHashMap.newKeySet<String>()
        onStage("Checking ${hosts.size} addresses")

        coroutineScope {
            hosts.map { host ->
                async {
                    gate.withPermit {
                        ensureActive()
                        if (responds(host, LIVENESS_PORT)) alive.add(host)
                    }
                }
            }.awaitAll()
        }

        // The tablet itself counts: a simulator can be running on it, and
        // loopback is not part of any subnet sweep.
        val tcpTargets = (alive + "127.0.0.1").flatMap { h -> TCP_PORTS.map { h to it } }
        onStage(
            if (alive.size == 1) "Asking 1 device" else "Asking ${alive.size} devices"
        )
        coroutineScope {
            tcpTargets.map { (host, port) ->
                async {
                    gate.withPermit {
                        ensureActive()
                        probeTcp(host, port)?.let { found.add(it) }
                    }
                }
            }.awaitAll()
        }

        // UDP last: it is one broadside of datagrams and then a wait, so it
        // has a floor on how fast it can be and does not parallelise away.
        onStage("Listening for UDP")
        found.addAll(probeUdp(alive.toList() + "127.0.0.1"))

        found.sortedWith(compareBy({ it.type.ordinal }, { it.host }, { it.port }))
    }

    /**
     * Every address on the tablet's own /24.
     *
     * Deliberately only a /24 even when the interface says the subnet is
     * wider. A /16 is 65,534 addresses: at any timeout worth having that is
     * minutes of scanning, and a pilot pressing Find will have given up long
     * before. The /24 is where a laptop running SITL on the same Wi-Fi will
     * be in practice.
     */
    private fun subnetHosts(context: Context): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val network = cm.activeNetwork ?: return emptyList()
        val props = cm.getLinkProperties(network) ?: return emptyList()
        val local = props.linkAddresses
            .mapNotNull { it.address as? Inet4Address }
            .firstOrNull { !it.isLoopbackAddress } ?: return emptyList()
        val octets = local.address
        return (1..254).map { last ->
            "${octets[0].toInt() and 0xFF}.${octets[1].toInt() and 0xFF}." +
                "${octets[2].toInt() and 0xFF}.$last"
        }
    }

    /**
     * Is there a machine at this address at all?
     *
     * True for a port that answers and for one that refuses; false only for
     * silence. The one case this misses is a host whose firewall drops rather
     * than refuses, which looks exactly like an empty address from here — the
     * price of not spending a full timeout on every one of the 250 addresses
     * that really are empty.
     */
    private fun responds(host: String, port: Int): Boolean {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), LIVENESS_MS)
            true
        } catch (_: SocketTimeoutException) {
            false
        } catch (_: Exception) {
            // Refused, unreachable, reset: something is there to say so.
            true
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Connect, and let the other end prove what it is.
     *
     * An open port is not enough: 5760 could be anything. The test is whether
     * a whole MAVLink frame parses, CRC included, which no unrelated service
     * will produce by accident.
     */
    private fun probeTcp(host: String, port: Int): Found? {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), TCP_CONNECT_MS)
            socket.soTimeout = TCP_READ_MS
            val connection = MavlinkConnection.create(socket.getInputStream(), socket.getOutputStream())
            // Read on past the first frame for a heartbeat. Whatever arrives
            // first is simply whatever the vehicle was due to send, and only
            // the heartbeat carries the autopilot and airframe — the two
            // things that tell one entry in this list from another. Bounded
            // both ways, because a stream that never sends one must not hold
            // up the sweep.
            var best: io.dronefleet.mavlink.MavlinkMessage<*>? = null
            val deadline = System.currentTimeMillis() + TCP_IDENTIFY_MS
            var frames = 0
            while (frames < TCP_IDENTIFY_FRAMES && System.currentTimeMillis() < deadline) {
                val message = connection.next() ?: break
                frames++
                // Another ground station's own traffic, forwarded down this
                // same stream by whatever is serving the port. Passed over so
                // the hunt continues, exactly as both UDP paths do with it.
                // Testing it after the loop instead threw the endpoint away on
                // the strength of one frame: a router carrying a vehicle and a
                // second GCS would vanish from the list whenever the GCS
                // happened to be heard first, with the vehicle's own heartbeat
                // arriving unread a few milliseconds later.
                if (isGroundStation(message)) continue
                if (best == null) best = message
                if (message.payload is Heartbeat) {
                    best = message
                    break
                }
            }
            val identified = best ?: return null
            Found(
                type = LinkType.TCP,
                host = host,
                port = port,
                udpMode = UdpMode.CONNECT,
                detail = describe(identified.payload, identified.originSystemId),
            )
        } catch (_: Exception) {
            // Refused, unreachable, silent, or not MAVLink. All the same
            // answer here: there is nothing to offer the pilot.
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Say hello on every candidate port and collect the replies.
     *
     * A vehicle configured to wait for a GCS says nothing until it is spoken
     * to, so this sends the same heartbeat the app sends once connected. One
     * socket sends them all and then listens, because the reply comes back to
     * whichever port the probe left from.
     */
    private suspend fun probeUdp(hosts: List<String>): List<Found> = coroutineScope {
        // Two ways round, because UDP links come in two shapes and only one of
        // them can be asked a question.
        //
        // A vehicle that waits to be spoken to is found by speaking: send the
        // heartbeat this app sends anyway and see what answers. A vehicle -- or
        // a bridge, or a radio -- that simply streams at a port is found only
        // by listening at that port, because it is not waiting for anything and
        // will never reply to a probe. The second is the commoner arrangement
        // of the two and used to be invisible here.
        val listeners = UDP_PORTS.map { port -> async { listenUdp(port) } }
        val answers = async { askUdp(hosts) }
        // No deduplication needed between the two halves: the ports are
        // distinct and each listener answers for one of them, while every
        // address the active probe reports is a real one rather than the
        // wildcard, so the two cannot collide.
        listeners.awaitAll().filterNotNull() + answers.await()
    }

    /**
     * Bind a port and see whether anything is already streaming at it.
     *
     * What this finds is not an address to dial but a port to sit on, so it is
     * reported as the listening end: bind everything, this port, and the
     * traffic will arrive. The sender is named in the detail rather than in
     * the address, because on the next flight it may well have a different one.
     */
    private suspend fun listenUdp(port: Int): Found? {
        val socket = try {
            DatagramSocket(null).apply {
                soTimeout = 250
                bind(InetSocketAddress(port))
            }
        } catch (_: Exception) {
            // Already in use, most likely by this app's own live link. Nothing
            // to discover there that the pilot does not already have.
            //
            // This is the whole of the protection, which is why the socket no
            // longer asks to reuse the address. Setting that made the bind
            // succeed against a port the live link already held, and the two
            // sockets then divided the vehicle's telemetry between them -- so
            // pressing Find while flying could take the instruments away for as
            // long as the scan listened. A port that is busy should look busy.
            return null
        }
        // One entry per port, not per sender. What this finds is a port to sit
        // on, and binding it hears everything that arrives there whoever sent
        // it, so several senders are one answer with several names in it. They
        // were separate entries until now, each carrying the same wildcard
        // address and the same port -- which the caller then deduplicated by
        // exactly those two fields, quietly discarding all but one.
        val senders = LinkedHashMap<String, String>()
        val identified = HashSet<String>()
        socket.use {
            val deadline = System.currentTimeMillis() + UDP_LISTEN_MS
            val buffer = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                // So that dismissing the dialog actually ends the scan. A
                // blocking receive cannot be interrupted by cancellation, so
                // the check has to be made between them, as both TCP passes do.
                currentCoroutineContext().ensureActive()
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }
                val message = parse(packet.data, packet.length) ?: continue
                if (isGroundStation(message)) continue
                val from = packet.address.hostAddress ?: continue
                // A heartbeat outranks whatever was recorded first: it is the
                // frame that says what the thing at the other end actually is.
                val heartbeat = message.payload is Heartbeat
                if (from !in senders || (heartbeat && from !in identified)) {
                    senders[from] = describe(message.payload, message.originSystemId)
                    if (heartbeat) identified.add(from)
                }
            }
        }
        if (senders.isEmpty()) return null
        val detail = if (senders.size == 1) {
            senders.entries.first().let { "from ${it.key} · ${it.value}" }
        } else {
            "from ${senders.size} senders · " +
                senders.entries.joinToString("; ") { "${it.key} ${it.value}" }
        }
        return Found(
            type = LinkType.UDP,
            udpMode = UdpMode.LISTEN,
            host = "0.0.0.0",
            port = port,
            detail = detail,
        )
    }

    /**
     * Say hello on every candidate port and collect the replies.
     *
     * Only to addresses the sweep already found something at. Firing at all
     * 254 of them was a burst of eight hundred datagrams, most of them at
     * nothing, and the queue behind an unanswered address swallowed enough of
     * the rest that the one host which would have replied never heard it.
     */
    private suspend fun askUdp(hosts: List<String>): List<Found> {
        val hello = heartbeatFrame()
        val found = mutableMapOf<String, Found>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 200
            for (host in hosts) {
                val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: continue
                for (port in UDP_PORTS) {
                    runCatching {
                        socket.send(DatagramPacket(hello, hello.size, address, port))
                    }
                }
            }
            val deadline = System.currentTimeMillis() + UDP_LISTEN_MS
            val buffer = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                // Between receives, for the same reason as the passive side:
                // this is the only place cancellation can be noticed.
                currentCoroutineContext().ensureActive()
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }
                val message = parse(packet.data, packet.length) ?: continue
                if (isGroundStation(message)) continue
                val host = packet.address.hostAddress ?: continue
                // Keyed by endpoint: a vehicle streaming at 4Hz will answer
                // several times inside the listening window.
                val key = "$host:${packet.port}"
                val entry = Found(
                    type = LinkType.UDP,
                    udpMode = UdpMode.CONNECT,
                    host = host,
                    port = packet.port,
                    detail = describe(message.payload, message.originSystemId),
                )
                // A heartbeat outranks whatever was recorded first, for the same
                // reason as over TCP: it is the frame that says what the thing
                // at the other end actually is.
                if (key !in found || message.payload is Heartbeat) found[key] = entry
            }
        }
        return found.values.toList()
    }

    /** The same announcement the app makes on a live link. */
    private fun heartbeatFrame(): ByteArray {
        val sink = ByteArrayOutputStream()
        val connection = MavlinkConnection.create(ByteArrayInputStream(ByteArray(0)), sink)
        val heartbeat = Heartbeat.builder()
            .type(MavType.MAV_TYPE_GCS)
            .autopilot(MavAutopilot.MAV_AUTOPILOT_INVALID)
            .baseMode(EnumValue.create(0))
            .customMode(0)
            .systemStatus(MavState.MAV_STATE_ACTIVE)
            .mavlinkVersion(3)
            .build()
        connection.send2(GCS_SYSTEM_ID, GCS_COMPONENT_ID, heartbeat)
        return sink.toByteArray()
    }

    /**
     * Is this our own voice, or another ground station's?
     *
     * The active probe sends a heartbeat to every live address, and this
     * tablet is one of them, so the ports the passive side just bound hear the
     * probe come straight back -- the scan finding itself, three times over.
     * Another ground station on the network is filtered for the same reason
     * as a matter of course: neither is something to fly.
     */
    private fun isGroundStation(message: io.dronefleet.mavlink.MavlinkMessage<*>): Boolean {
        if (message.originSystemId == GCS_SYSTEM_ID &&
            message.originComponentId == GCS_COMPONENT_ID
        ) {
            return true
        }
        val payload = message.payload
        return payload is Heartbeat && payload.type().entry() == MavType.MAV_TYPE_GCS
    }

    /** One datagram, parsed with the CRC checked, or null if it is not MAVLink. */
    private fun parse(data: ByteArray, length: Int): io.dronefleet.mavlink.MavlinkMessage<*>? =
        runCatching {
            MavlinkConnection
                .create(ByteArrayInputStream(data, 0, length), NullOutput)
                .next()
        }.getOrNull()

    /**
     * What to show beside the address.
     *
     * A heartbeat names the autopilot and the airframe, which is what tells
     * two entries apart when a laptop is running more than one simulator. A
     * system that answered with something else still counts as found.
     */
    private fun describe(payload: Any?, systemId: Int): String {
        if (payload !is Heartbeat) return "MAVLink · system $systemId"
        val vehicle = payload.type().entry()?.name
            ?.removePrefix("MAV_TYPE_")?.lowercase()?.replace('_', ' ')
        val autopilot = payload.autopilot().entry()?.name
            ?.removePrefix("MAV_AUTOPILOT_")?.lowercase()?.replace('_', ' ')
        return listOfNotNull(
            autopilot?.takeUnless { it == "invalid" },
            vehicle,
            "system $systemId",
        ).joinToString(" · ")
    }

    private object NullOutput : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private const val GCS_SYSTEM_ID = 255
    private const val GCS_COMPONENT_ID = 190
}
