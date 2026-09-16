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

        /** Per bulk read. Short, so a stalled stream is noticed rather than hung on. */
        private const val READ_TIMEOUT_MS = 300

        /**
         * How long one payload is, header included.
         *
         * Read off a raw capture rather than assumed: headers landed at 0,
         * 1024, 2048 and so on. A device that chose another size would need
         * this learned from the gap between the first two headers instead.
         */
        private const val PAYLOAD_BYTES = 1024

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
     * Read one whole video frame, or -1 if none arrived in time.
     *
     * The stream is a continuous run of small payloads, each a 12-byte header
     * followed by its data, packed end to end with nothing between them. On
     * this card a payload is 1024 bytes, so a 32KB read carries thirty-two of
     * them. That is worth stating because two plausible readings of the
     * specification are both wrong here and both produce a picture that almost
     * works: a header on every read gives evenly spaced stripes, and a header
     * once per frame gives stripes that lean. The bytes settled it -- headers
     * sit at 0, 1024, 2048 and so on, whatever the read boundaries are doing.
     *
     * The frame boundary is the frame-ID bit flipping in the header, not the
     * end-of-frame flag, because this card never sets that flag: a raw capture
     * showed 193 reads carrying six megabytes with it set exactly zero times.
     *
     * Payload boundaries survive a lost byte by scanning forward for the next
     * header rather than assuming the stride held, since a frame ends on a
     * short payload that knocks the rhythm out of step.
     */
    fun readFrame(into: ByteArray, timeoutMs: Int): Int {
        val packet = ByteArray(endpoint.maxPacketSize * PACKETS_PER_READ)
        val deadline = System.currentTimeMillis() + timeoutMs
        var filled = 0
        var started = false
        var frameId = -1
        var remaining = 0          // data left in the payload being read
        while (System.currentTimeMillis() < deadline) {
            val read = connection.bulkTransfer(endpoint, packet, packet.size, READ_TIMEOUT_MS)
            if (read <= 0) continue
            var at = 0
            while (at < read) {
                if (remaining > 0) {
                    val take = minOf(remaining, read - at)
                    if (started && filled + take <= into.size) {
                        System.arraycopy(packet, at, into, filled, take)
                        filled += take
                    }
                    at += take
                    remaining -= take
                    continue
                }
                // A payload header, or hunt for one.
                if (at + 2 > read) break
                val headerLength = packet[at].toInt() and 0xFF
                val info = packet[at + 1].toInt() and 0xFF
                if (headerLength !in 2..64 || (info and 0x80) == 0) {
                    at += 1
                    continue
                }
                val id = info and 0x01
                if (!started) {
                    // Begin at a boundary, so the frame handed back is whole
                    // rather than starting halfway down the picture.
                    frameId = id
                    started = true
                    filled = 0
                } else if (id != frameId) {
                    return filled
                }
                if ((info and 0x40) != 0) filled = 0       // the card flagged an error
                at += headerLength
                remaining = (PAYLOAD_BYTES - headerLength).coerceAtLeast(0)
            }
        }
        return -1
    }

    /**
     * One raw frame turned into something that can be drawn.
     *
     * Through the platform's own YUV encoder rather than a hand-written colour
     * conversion: it is correct, it is fast, and two million pixels a frame is
     * not somewhere to be inventing arithmetic. NV12 and NV21 differ only in
     * which of the two chroma bytes comes first, so the swap is the whole of
     * the work; the planar and packed layouts are gathered into that shape.
     */
    fun toBitmap(data: ByteArray, length: Int, format: Format): android.graphics.Bitmap? {
        val width = format.width
        val height = format.height
        val luma = width * height
        if (length < luma) return null
        val nv21 = ByteArray(luma + luma / 2)
        // Neutral chroma, so a frame that arrives short of its colour renders
        // as grey rather than as the violent green that zeroes would give.
        java.util.Arrays.fill(nv21, luma, nv21.size, 128.toByte())
        when (format.fourcc.uppercase()) {
            "NV12" -> {
                System.arraycopy(data, 0, nv21, 0, luma)
                var i = 0
                while (luma + i + 1 < nv21.size && luma + i + 1 < length) {
                    nv21[luma + i] = data[luma + i + 1]
                    nv21[luma + i + 1] = data[luma + i]
                    i += 2
                }
            }
            "I420", "YU12" -> {
                System.arraycopy(data, 0, nv21, 0, luma)
                val quarter = luma / 4
                for (i in 0 until quarter) {
                    val u = luma + i
                    val v = luma + quarter + i
                    if (v < length) {
                        nv21[luma + i * 2] = data[v]
                        nv21[luma + i * 2 + 1] = data[u]
                    }
                }
            }
            "YUY2", "YUYV" -> {
                for (y in 0 until height) {
                    for (x in 0 until width step 2) {
                        val at = (y * width + x) * 2
                        if (at + 3 >= length) break
                        nv21[y * width + x] = data[at]
                        nv21[y * width + x + 1] = data[at + 2]
                        if (y % 2 == 0) {
                            val c = luma + (y / 2) * width + x
                            if (c + 1 < nv21.size) {
                                nv21[c] = data[at + 3]
                                nv21[c + 1] = data[at + 1]
                            }
                        }
                    }
                }
            }
            else -> return null
        }
        return runCatching {
            val out = java.io.ByteArrayOutputStream(luma / 4)
            android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
                .compressToJpeg(android.graphics.Rect(0, 0, width, height), 80, out)
            val bytes = out.toByteArray()
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    /** What the stream actually looks like, for when it will not assemble. */
    data class Shape(
        val reads: Int,
        val bytes: Long,
        val shortReads: Int,
        val validHeaders: Int,
        val endOfFrames: Int,
        val sizes: List<Int>,
    )

    /**
     * Watch the raw stream for a moment and describe its shape.
     *
     * When frames will not assemble, the useful question is not "why" but
     * "what is actually arriving" -- how big the reads are, whether any of
     * them begin with something that looks like a header, and whether the
     * end-of-frame flag is ever set. Three numbers settle it where guessing
     * does not.
     */
    fun describeStream(millis: Int): Shape {
        val packet = ByteArray(endpoint.maxPacketSize * PACKETS_PER_READ)
        val deadline = System.currentTimeMillis() + millis
        var reads = 0
        var bytes = 0L
        var shortReads = 0
        var headers = 0
        var eofs = 0
        val sizes = mutableListOf<Int>()
        var expectHeader = true
        while (System.currentTimeMillis() < deadline) {
            val read = connection.bulkTransfer(endpoint, packet, packet.size, READ_TIMEOUT_MS)
            if (read < 0) { expectHeader = true; continue }
            reads++
            bytes += read
            if (sizes.size < 12) sizes += read
            if (expectHeader && read >= 2) {
                val headerLength = packet[0].toInt() and 0xFF
                val info = packet[1].toInt() and 0xFF
                // A real header is a plausible length with the end-of-header
                // bit set, which is the only cheap way to tell one from pixels.
                if (headerLength in 2..64 && (info and 0x80) != 0) {
                    headers++
                    if ((info and 0x02) != 0) eofs++
                }
            }
            if (read < packet.size) {
                shortReads++
                expectHeader = true
            } else {
                expectHeader = false
            }
        }
        return Shape(reads, bytes, shortReads, headers, eofs, sizes)
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
