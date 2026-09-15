package com.mavgcs.app.mavlink

/**
 * What the link is actually carrying, and what it is losing.
 *
 * Four numbers the pilot can act on. Throughput says whether the radio has
 * room: an ELRS downlink is 435 bytes a second and a GCS that asks for more
 * gets the difference thrown away by the radio, silently and without regard
 * for which messages mattered. Loss says whether what was asked for is
 * arriving.
 */
data class LinkQuality(
    val rxBytesPerSec: Int = 0,
    val txBytesPerSec: Int = 0,
    val rxPerSec: Float = 0f,
    val txPerSec: Float = 0f,
    /**
     * Share of the vehicle's messages that went missing over the last few
     * seconds, 0..100, or null until enough have passed to divide by.
     *
     * Recent rather than cumulative on purpose: a figure averaged over a
     * twenty minute flight says nothing about the radio now, which is the only
     * thing worth knowing while flying it.
     */
    val lossPercent: Float? = null,
    /** Total messages the vehicle sent that did not arrive, this session. */
    val lost: Long = 0L,
    /** Total messages received, this session. */
    val received: Long = 0L,
)

/**
 * Counts what crosses the link and works out what went missing.
 *
 * Loss is measured rather than guessed. Every MAVLink frame carries a
 * sequence number that steps by one for each message its sender emits, so a
 * jump of more than one is exactly the number that did not make it -- the same
 * arithmetic every other ground station uses, and the only way to know the
 * difference between a vehicle that has gone quiet and a radio that is
 * dropping what it sends.
 *
 * Kept per sender: an autopilot and a companion computer each number their own
 * messages, and comparing one's sequence against the other's would invent
 * losses that never happened.
 */
class LinkStats {
    private companion object {
        /** How many completed seconds the reported loss is measured over. */
        const val LOSS_WINDOW_SECONDS = 10
    }

    private val lock = Any()

    private var windowStart = 0L
    private var rxBytes = 0L
    private var txBytes = 0L
    private var rxCount = 0L
    private var txCount = 0L

    /** Last sequence seen from each sender, keyed by system and component. */
    private val lastSequence = HashMap<Int, Int>()
    private var lost = 0L
    private var received = 0L

    /** The last few completed seconds, so the reported loss is a recent one. */
    private val recentLost = ArrayDeque<Long>()
    private val recentTotal = ArrayDeque<Long>()
    private var windowLost = 0L
    private var windowReceived = 0L

    @Volatile
    private var snapshot = LinkQuality()

    fun reset() {
        synchronized(lock) {
            windowStart = 0L
            rxBytes = 0L; txBytes = 0L; rxCount = 0L; txCount = 0L
            lastSequence.clear()
            lost = 0L; received = 0L
            recentLost.clear(); recentTotal.clear()
            windowLost = 0L; windowReceived = 0L
            snapshot = LinkQuality()
        }
    }

    /**
     * A frame arrived.
     *
     * [sequence] is the sender's own counter, which is what makes the gap
     * meaningful; [bytes] is the frame on the wire, header and checksum
     * included, because that is what the radio had to carry.
     */
    fun onRx(bytes: Int, systemId: Int, componentId: Int, sequence: Int) {
        synchronized(lock) {
            rxBytes += bytes
            rxCount += 1
            received += 1
            windowReceived += 1
            val key = (systemId shl 8) or componentId
            val previous = lastSequence.put(key, sequence)
            if (previous != null) {
                // Wraps at 256. A gap of one is the next message, so anything
                // beyond that is the count that went missing.
                val gap = ((sequence - previous - 1) + 256) % 256
                // A gap of nearly a whole cycle is far likelier to be a sender
                // that restarted its numbering than 250 lost messages, and
                // counting it would swamp the figure for the rest of the
                // flight.
                if (gap in 1..64) {
                    lost += gap
                    windowLost += gap
                }
            }
        }
    }

    /**
     * Bytes went out. [frame] marks the write that carried a whole message,
     * so the message count follows frames while the byte count follows bytes.
     */
    fun onTx(bytes: Int, frame: Boolean) {
        synchronized(lock) {
            txBytes += bytes
            if (frame) txCount += 1
        }
    }

    /** Loss over the seconds still in the ring, or null before there are enough. */
    private fun recentLossPercent(): Float? {
        val total = recentTotal.sum()
        // A handful of messages can read 50% off a single gap, which would
        // flicker alarmingly on an otherwise sound link.
        if (total < 20) return null
        return 100f * recentLost.sum() / total
    }

    /**
     * Roll the one-second window if it has elapsed, and return the latest.
     *
     * Called from whatever thread is already ticking rather than run on one of
     * its own: there is nothing here worth a thread.
     */
    fun sample(nowMs: Long): LinkQuality {
        synchronized(lock) {
            if (windowStart == 0L) {
                windowStart = nowMs
                return snapshot
            }
            val elapsed = (nowMs - windowStart) / 1000f
            if (elapsed >= 1f) {
                snapshot = LinkQuality(
                    rxBytesPerSec = (rxBytes / elapsed).toInt(),
                    txBytesPerSec = (txBytes / elapsed).toInt(),
                    rxPerSec = rxCount / elapsed,
                    txPerSec = txCount / elapsed,
                    // Held back until there is enough to divide by, so the
                    // first second of a connection does not read 100%.
                    lossPercent = recentLossPercent(),
                    lost = lost,
                    received = received,
                )
                rxBytes = 0; txBytes = 0; rxCount = 0; txCount = 0
                recentLost.addLast(windowLost)
                recentTotal.addLast(windowLost + windowReceived)
                while (recentLost.size > LOSS_WINDOW_SECONDS) {
                    recentLost.removeFirst()
                    recentTotal.removeFirst()
                }
                windowLost = 0L; windowReceived = 0L
                windowStart = nowMs
            }
            return snapshot
        }
    }
}
