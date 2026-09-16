package com.mavgcs.app.video

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A USB video class capture device, driven directly over the USB host API.
 *
 * Android will not offer a capture card to an app as a camera unless the tablet
 * declares support for external cameras, and this one does not: the kernel
 * binds the card and publishes video nodes that belong to the camera service
 * and that nothing else may open. So the app talks to the device itself.
 *
 * That is only practical because of one detail in this particular card's
 * descriptors: its video endpoint is BULK. The Java USB API can read bulk and
 * cannot do isochronous at all, which is why every other Android project that
 * reaches a webcam ships a native library. A bulk device needs none of it.
 */
class UvcDevice(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val streaming: UsbInterface,
    private val endpoint: UsbEndpoint,
) {
    /** What the card is offering, read from its own descriptors. */
    data class Format(
        val formatIndex: Int,
        val frameIndex: Int,
        val width: Int,
        val height: Int,
        val fourcc: String,
        val frameIntervalNs: Long,
        val maxFrameBytes: Int,
    ) {
        val fps: Double get() = if (frameIntervalNs > 0) 10_000_000.0 / frameIntervalNs else 0.0

        /** Bytes a second this format needs on the wire, once negotiated. */
        val bytesPerSecond: Long get() = (maxFrameBytes.toLong() * fps).toLong()
    }

    companion object {
        private const val TAG = "UvcDevice"

        /** USB video class, and its two interface flavours. */
        const val CLASS_VIDEO = 0x0E
        private const val SUBCLASS_CONTROL = 1
        private const val SUBCLASS_STREAMING = 2

        // Class-specific requests, from the UVC specification.
        private const val SET_CUR = 0x01
        private const val GET_CUR = 0x81
        private const val GET_MIN = 0x82
        private const val GET_MAX = 0x83
        private const val GET_LEN = 0x85
        private const val GET_DEF = 0x87

        private const val VS_PROBE_CONTROL = 0x01
        private const val VS_COMMIT_CONTROL = 0x02

        private const val TO_DEVICE = 0x21
        private const val TO_HOST = 0xA1

        private const val CONTROL_TIMEOUT_MS = 2_000

        /** True when this device has a video-class streaming interface. */
        fun isCapture(device: UsbDevice): Boolean =
            (0 until device.interfaceCount).any { index ->
                val iface = device.getInterface(index)
                iface.interfaceClass == CLASS_VIDEO &&
                    iface.interfaceSubclass == SUBCLASS_STREAMING
            }

        /** Every attached capture device, in the order the system lists them. */
        fun attached(manager: UsbManager): List<UsbDevice> =
            manager.deviceList.values.filter { isCapture(it) }

        /**
         * Claim the device and take its streaming interface.
         *
         * The kernel's own driver already owns it -- that is what created the
         * video nodes -- so the claim has to be forced, which detaches it.
         */
        fun open(manager: UsbManager, device: UsbDevice): UvcDevice? {
            val streaming = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull {
                    it.interfaceClass == CLASS_VIDEO &&
                        it.interfaceSubclass == SUBCLASS_STREAMING
                } ?: run {
                Log.w(TAG, "No video streaming interface on ${device.deviceName}")
                return null
            }
            val endpoint = (0 until streaming.endpointCount)
                .map { streaming.getEndpoint(it) }
                .firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        it.direction == UsbConstants.USB_DIR_IN
                } ?: run {
                // Isochronous only. Nothing in the Java API can read that, and
                // saying so is more use than a generic failure.
                Log.w(TAG, "Streaming interface has no bulk IN endpoint")
                return null
            }
            val connection = manager.openDevice(device) ?: run {
                Log.w(TAG, "Could not open ${device.deviceName}")
                return null
            }
            // Both interfaces: the control one carries the unit the probe is
            // addressed through, and leaving it with the kernel invites the two
            // of us to disagree about the device's state.
            (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .filter { it.interfaceClass == CLASS_VIDEO }
                .forEach { connection.claimInterface(it, true) }
            return UvcDevice(device, connection, streaming, endpoint)
        }
    }

    val name: String get() = device.productName ?: device.deviceName

    /** The video formats the card advertises, parsed from its raw descriptors. */
    fun formats(): List<Format> = parseFormats(connection.rawDescriptors ?: ByteArray(0))

    /**
     * Agree a format with the card.
     *
     * Two rounds, as the specification requires: propose, let the device answer
     * with what it will actually do, then commit to that answer rather than to
     * the proposal. Skipping the read back is the classic way to end up with a
     * stream whose size is not the size that was asked for.
     */
    fun negotiate(format: Format): Format? {
        val length = probeLength()
        if (length <= 0) {
            Log.w(TAG, "Device would not say how long its probe block is")
            return null
        }
        val proposal = ByteArray(length)
        // Start from the device's own defaults so every field this code does
        // not understand keeps whatever the card wants in it.
        if (!getControl(GET_DEF, VS_PROBE_CONTROL, proposal)) return null
        val buffer = ByteBuffer.wrap(proposal).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(0, 0)                                   // bmHint: no preference
        buffer.put(2, format.formatIndex.toByte())
        buffer.put(3, format.frameIndex.toByte())
        buffer.putInt(4, format.frameIntervalNs.toInt())
        if (!setControl(VS_PROBE_CONTROL, proposal)) return null

        val agreed = ByteArray(length)
        if (!getControl(GET_CUR, VS_PROBE_CONTROL, agreed)) return null
        if (!setControl(VS_COMMIT_CONTROL, agreed)) return null

        val read = ByteBuffer.wrap(agreed).order(ByteOrder.LITTLE_ENDIAN)
        val interval = read.getInt(4).toLong()
        val maxFrame = if (length >= 22) read.getInt(18) else format.maxFrameBytes
        return format.copy(
            formatIndex = read.get(2).toInt(),
            frameIndex = read.get(3).toInt(),
            frameIntervalNs = interval,
            maxFrameBytes = maxFrame,
        )
    }

    /** How many bytes the card's probe block is. UVC 1.1 says 26; ask anyway. */
    private fun probeLength(): Int {
        val answer = ByteArray(2)
        if (!getControl(GET_LEN, VS_PROBE_CONTROL, answer)) return 0
        return ByteBuffer.wrap(answer).order(ByteOrder.LITTLE_ENDIAN).getShort(0).toInt()
    }

    private fun getControl(request: Int, selector: Int, into: ByteArray): Boolean {
        val read = connection.controlTransfer(
            TO_HOST, request, selector shl 8, streaming.id, into, into.size, CONTROL_TIMEOUT_MS,
        )
        if (read < 0) Log.w(TAG, "control get 0x%02X failed".format(request))
        return read >= 0
    }

    private fun setControl(selector: Int, from: ByteArray): Boolean {
        val wrote = connection.controlTransfer(
            TO_DEVICE, SET_CUR, selector shl 8, streaming.id, from, from.size, CONTROL_TIMEOUT_MS,
        )
        if (wrote < 0) Log.w(TAG, "control set failed")
        return wrote >= 0
    }

    /**
     * Read one whole video frame, or null if none arrived in time.
     *
     * Bulk payloads each begin with a short header saying whether this is the
     * end of a frame, and carrying a bit that flips between consecutive frames.
     * Either can mark the boundary; both are watched, because cards disagree
     * about which they bother to set.
     */
    fun readFrame(into: ByteArray, timeoutMs: Int): Int {
        var filled = 0
        var started = false
        var frameId = -1
        val packet = ByteArray(endpoint.maxPacketSize * PACKETS_PER_READ)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val read = connection.bulkTransfer(endpoint, packet, packet.size, timeoutMs)
            if (read <= 0) continue
            val headerLength = packet[0].toInt() and 0xFF
            if (headerLength < 2 || headerLength > read) continue
            val info = packet[1].toInt() and 0xFF
            val id = info and 0x01
            val endOfFrame = (info and 0x02) != 0
            val error = (info and 0x40) != 0
            if (error) {
                filled = 0
                started = false
                continue
            }
            if (!started) {
                frameId = id
                started = true
            } else if (id != frameId) {
                // The card moved on without flagging the end. What is held is a
                // complete frame; this payload belongs to the next one.
                return filled
            }
            val payload = read - headerLength
            if (payload > 0 && filled + payload <= into.size) {
                System.arraycopy(packet, headerLength, into, filled, payload)
                filled += payload
            }
            if (endOfFrame) return filled
        }
        return if (filled > 0) filled else -1
    }

    fun close() {
        runCatching {
            (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .filter { it.interfaceClass == CLASS_VIDEO }
                .forEach { connection.releaseInterface(it) }
        }
        runCatching { connection.close() }
    }

    /**
     * Pull the advertised formats out of the device's own descriptor blob.
     *
     * Read from the device rather than assumed, because a capture card's list
     * is not fixed: it reports what the thing plugged into its input is
     * actually sending, so it changes with the camera on the other end.
     */
    private fun parseFormats(raw: ByteArray): List<Format> {
        val found = mutableListOf<Format>()
        val all = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        var inStreaming = false
        var formatIndex = 0
        var fourcc = ""
        while (i + 1 < raw.size) {
            val length = raw[i].toInt() and 0xFF
            val type = raw[i + 1].toInt() and 0xFF
            if (length < 2) break
            if (type == 0x04 && i + 8 < raw.size) {
                inStreaming = (raw[i + 5].toInt() and 0xFF) == CLASS_VIDEO &&
                    (raw[i + 6].toInt() and 0xFF) == SUBCLASS_STREAMING
            } else if (type == 0x24 && inStreaming && i + 2 < raw.size) {
                when (raw[i + 2].toInt() and 0xFF) {
                    FORMAT_UNCOMPRESSED, FORMAT_MJPEG -> {
                        formatIndex = raw[i + 3].toInt() and 0xFF
                        fourcc = if ((raw[i + 2].toInt() and 0xFF) == FORMAT_MJPEG) {
                            "MJPG"
                        } else {
                            String(raw, i + 5, 4, Charsets.US_ASCII).trim()
                        }
                    }
                    FRAME_UNCOMPRESSED, FRAME_MJPEG -> if (i + 25 < raw.size) {
                        found += Format(
                            formatIndex = formatIndex,
                            frameIndex = raw[i + 3].toInt() and 0xFF,
                            width = all.getShort(i + 5).toInt() and 0xFFFF,
                            height = all.getShort(i + 7).toInt() and 0xFFFF,
                            fourcc = fourcc,
                            frameIntervalNs = all.getInt(i + 21).toLong() and 0xFFFFFFFFL,
                            maxFrameBytes = all.getInt(i + 17),
                        )
                    }
                }
            }
            i += length
        }
        return found
    }
}

private const val FORMAT_UNCOMPRESSED = 0x04
private const val FRAME_UNCOMPRESSED = 0x05
private const val FORMAT_MJPEG = 0x06
private const val FRAME_MJPEG = 0x07

/**
 * How many maximum-size packets to ask for in one bulk read.
 *
 * 64, which is 32KB, because measuring said so. The obvious reasoning -- that
 * each call into the USB stack costs the same whatever it carries, so ask for
 * more -- is wrong here: raising it to a megabyte per read cut the measured
 * rate from 7.9 MB/s to 5.3. Android's bulk transfer does not reward large
 * buffers, it punishes them.
 */
private const val PACKETS_PER_READ = 64
