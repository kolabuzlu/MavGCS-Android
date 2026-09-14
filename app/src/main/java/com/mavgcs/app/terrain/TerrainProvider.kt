package com.mavgcs.app.terrain

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.floor
import java.util.Locale

/**
 * Terrain elevation from the Copernicus GLO-30 DEM, the same free source the
 * desktop uses. No key and no sign-up: the tiles sit in a public S3 bucket, one
 * GeoTIFF per one-degree cell.
 *
 * The desktop downloads a whole tile (36MB) and decodes it to a 3600x3600 float
 * grid, some 52MB resident. That is not something to do on a tablet, and it is
 * not necessary: the files are cloud-optimised, so their pixels are stored as
 * 1024x1024 blocks that can be fetched one at a time with a range request. A
 * radar fan reaches 3.6km at most -- about 117 pixels -- so a single block, or
 * four where the fan crosses a boundary, covers everything drawn.
 *
 * Every call here blocks on the network. Only call it from a background
 * dispatcher.
 */
object TerrainProvider {

    private const val TAG = "TerrainProvider"
    private const val BASE_URL = "https://copernicus-dem-30m.s3.amazonaws.com"
    private const val TIMEOUT_MS = 20_000

    /** Enough to cover the first IFD and the tag arrays hanging off it. */
    private const val HEADER_BYTES = 65_536L

    /**
     * Decoded blocks held in memory, 4MB each. A fan spans at most 2x2 blocks,
     * so this holds one fan's worth without thrashing.
     */
    private const val MAX_CACHED_BLOCKS = 4

    /**
     * Tiles whose header has been read. A null value marks a cell the bucket
     * does not have -- ocean, mostly -- so it is asked for once and not again.
     * A transient failure is not recorded, and so will be retried.
     */
    private val headers = HashMap<String, CogHeader?>()

    private val blocks = object : LinkedHashMap<String, FloatArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>) =
            size > MAX_CACHED_BLOCKS
    }

    /** Terrain elevation in metres at [lat]/[lon], or null where unavailable. */
    fun elevation(lat: Double, lon: Double): Float? {
        val key = demKey(lat, lon)
        val header = header(key) ?: return null

        val column = (lon - header.originLon) / header.pixelLon
        val row = (header.originLat - lat) / header.pixelLat
        // The extent is the cell the tile covers, not its last pixel centre.
        // A 3600-pixel grid at one arcsecond spans the full degree, so its
        // furthest centre sits a pixel short of the far edge: rejecting past
        // that would leave a 30m strip of every one-degree boundary reading as
        // no data, which shows up as blank cells wherever a fan crosses one.
        if (column < 0 || row < 0 || column > header.width || row > header.height) {
            return null
        }

        val c0 = column.toInt().coerceIn(0, header.width - 1)
        val r0 = row.toInt().coerceIn(0, header.height - 1)
        val c1 = (c0 + 1).coerceAtMost(header.width - 1)
        val r1 = (r0 + 1).coerceAtMost(header.height - 1)

        // Interpolating across a block boundary would need two blocks for one
        // reading, so the neighbour is clamped into this one instead. It costs
        // at most half a pixel, 15m on the ground, and only on the seam itself.
        val blockX = c0 / header.blockWidth
        val blockY = r0 / header.blockHeight
        val block = block(key, header, blockX, blockY) ?: return null
        val fc = (column - c0).toFloat().coerceIn(0f, 1f)
        val fr = (row - r0).toFloat().coerceIn(0f, 1f)
        val top = block.at(header, blockX, blockY, c0, r0) * (1 - fc) +
            block.at(header, blockX, blockY, c1, r0) * fc
        val bottom = block.at(header, blockX, blockY, c0, r1) * (1 - fc) +
            block.at(header, blockX, blockY, c1, r1) * fc
        return top * (1 - fr) + bottom * fr
    }

    /**
     * Reads a pixel out of the block held in [this], clamped to that block's
     * own extent.
     *
     * The block has to be named rather than worked out from the pixel: a
     * neighbour one past the block's last column belongs to the next block, so
     * deriving the origin from it would subtract the wrong one and wrap the
     * read back to column zero -- a pixel some 30km away. That put a single
     * wrong reading into one line of pixels every block, which the radar drew
     * as a stray hot cell among its neighbours.
     */
    private fun FloatArray.at(
        header: CogHeader,
        blockX: Int,
        blockY: Int,
        column: Int,
        row: Int,
    ): Float {
        val x = (column - blockX * header.blockWidth).coerceIn(0, header.blockWidth - 1)
        val y = (row - blockY * header.blockHeight).coerceIn(0, header.blockHeight - 1)
        return this[y * header.blockWidth + x]
    }

    /** Copernicus tile name for the one-degree cell holding [lat]/[lon]. */
    fun demKey(lat: Double, lon: Double): String {
        val latIndex = floor(lat).toInt()
        val lonIndex = floor(lon).toInt()
        val ns = if (latIndex >= 0) "N" else "S"
        val ew = if (lonIndex >= 0) "E" else "W"
        return "Copernicus_DSM_COG_10_%s%02d_00_%s%03d_00_DEM"
            .format(Locale.ROOT, ns, abs(latIndex), ew, abs(lonIndex))
    }

    private fun urlFor(key: String) = "$BASE_URL/$key/$key.tif"

    @Synchronized
    private fun header(key: String): CogHeader? {
        if (headers.containsKey(key)) {
            return headers[key]
        }
        val name = "$key$HEADER_SUFFIX"
        // A saved header that will not parse is worse than no header at all:
        // it would pin the tile as unreadable for as long as the file sat
        // there. Fall through to the network and let a fresh copy replace it.
        TerrainDiskCache.read(name)
            ?.let { runCatching { parseHeader(it) }.getOrNull() }
            ?.let {
                headers[key] = it
                return it
            }

        val response = fetch(urlFor(key), 0, HEADER_BYTES - 1)
        if (response.missing) {
            headers[key] = null
            return null
        }
        val bytes = response.body ?: return null
        val parsed = runCatching { parseHeader(bytes) }.getOrNull()
        if (parsed == null) {
            Log.w(TAG, "Could not read the DEM header for $key")
            return null
        }
        TerrainDiskCache.write(name, bytes)
        headers[key] = parsed
        return parsed
    }

    /**
     * Drop everything held in memory. Called when the saved copies are cleared,
     * so the radar cannot keep drawing from data the pilot asked to remove.
     */
    @Synchronized
    fun forgetCached() {
        headers.clear()
        blocks.clear()
    }

    @Synchronized
    private fun block(key: String, header: CogHeader, blockX: Int, blockY: Int): FloatArray? {
        val across = (header.width + header.blockWidth - 1) / header.blockWidth
        val index = blockY * across + blockX
        if (index < 0 || index >= header.blockOffsets.size) {
            return null
        }
        val cacheKey = "$key/$index"
        blocks[cacheKey]?.let { return it }

        val name = "$key.$index$BLOCK_SUFFIX"
        val saved = TerrainDiskCache.read(name)
        val compressed = saved ?: run {
            val offset = header.blockOffsets[index]
            val length = header.blockLengths[index]
            if (length <= 0L) {
                return null
            }
            fetch(urlFor(key), offset, offset + length - 1).body ?: return null
        }

        val pixels = header.blockWidth * header.blockHeight
        val raw = inflate(compressed, pixels * 4)
        if (raw == null) {
            // Freshly fetched bytes that will not inflate are a lost cause for
            // now, but a saved block that will not is one to be rid of, so the
            // next look fetches rather than failing on the same bytes again.
            if (saved != null) {
                TerrainDiskCache.remove(name)
            }
            return null
        }
        if (saved == null) {
            TerrainDiskCache.write(name, compressed)
        }
        undoFloatPredictor(raw, header.blockWidth, header.blockHeight)

        val values = FloatArray(pixels)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values)
        blocks[cacheKey] = values
        return values
    }

    // --- TIFF ---------------------------------------------------------------

    private class CogHeader(
        val width: Int,
        val height: Int,
        val blockWidth: Int,
        val blockHeight: Int,
        val blockOffsets: LongArray,
        val blockLengths: LongArray,
        val originLat: Double,
        val originLon: Double,
        val pixelLat: Double,
        val pixelLon: Double,
    )

    private fun parseHeader(bytes: ByteArray): CogHeader? {
        val littleEndian = bytes[0] == LITTLE_ENDIAN_MARK && bytes[1] == LITTLE_ENDIAN_MARK
        val bigEndian = bytes[0] == BIG_ENDIAN_MARK && bytes[1] == BIG_ENDIAN_MARK
        if (!littleEndian && !bigEndian) {
            return null
        }
        val buffer = ByteBuffer.wrap(bytes)
            .order(if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        if (buffer.getShort(2).toInt() != 42) {
            // BigTIFF (43) lays its entries out differently; Copernicus is classic.
            return null
        }

        val ifd = buffer.getInt(4)
        val entries = buffer.getShort(ifd).toInt() and 0xFFFF
        val values = HashMap<Int, LongArray>()
        val doubles = HashMap<Int, DoubleArray>()

        for (i in 0 until entries) {
            val entry = ifd + 2 + i * 12
            if (entry + 12 > bytes.size) {
                return null
            }
            val tag = buffer.getShort(entry).toInt() and 0xFFFF
            val type = buffer.getShort(entry + 2).toInt() and 0xFFFF
            val count = buffer.getInt(entry + 4)
            val unit = when (type) {
                1, 2, 6, 7 -> 1
                3, 8 -> 2
                4, 9, 11 -> 4
                5, 10, 12 -> 8
                else -> continue
            }
            val size = unit.toLong() * count
            val at = if (size <= 4) entry + 8 else buffer.getInt(entry + 8)
            // A tag pointing past what was fetched means the header runs longer
            // than assumed, not that the file is broken; skip just that tag.
            if (count <= 0 || at < 0 || at + size > bytes.size) {
                continue
            }

            when (type) {
                12 -> doubles[tag] = DoubleArray(count) { buffer.getDouble(at + it * 8) }
                3 -> values[tag] = LongArray(count) {
                    (buffer.getShort(at + it * 2).toInt() and 0xFFFF).toLong()
                }
                4 -> values[tag] = LongArray(count) {
                    buffer.getInt(at + it * 4).toLong() and 0xFFFFFFFFL
                }
            }
        }

        val width = values[TAG_IMAGE_WIDTH]?.firstOrNull()?.toInt() ?: return null
        val height = values[TAG_IMAGE_LENGTH]?.firstOrNull()?.toInt() ?: return null
        val blockWidth = values[TAG_TILE_WIDTH]?.firstOrNull()?.toInt() ?: return null
        val blockHeight = values[TAG_TILE_LENGTH]?.firstOrNull()?.toInt() ?: return null
        val offsets = values[TAG_TILE_OFFSETS] ?: return null
        val lengths = values[TAG_TILE_BYTE_COUNTS] ?: return null
        val scale = doubles[TAG_PIXEL_SCALE] ?: return null
        val tie = doubles[TAG_TIE_POINT] ?: return null
        if (offsets.size != lengths.size || scale.size < 2 || tie.size < 5) {
            return null
        }

        // Anything but deflated 32-bit float with the floating-point predictor
        // would need a different decoder, so refuse it rather than draw noise.
        val compression = values[TAG_COMPRESSION]?.firstOrNull()?.toInt() ?: 1
        val bits = values[TAG_BITS_PER_SAMPLE]?.firstOrNull()?.toInt() ?: 0
        val format = values[TAG_SAMPLE_FORMAT]?.firstOrNull()?.toInt() ?: 1
        val predictor = values[TAG_PREDICTOR]?.firstOrNull()?.toInt() ?: 1
        if (compression != COMPRESSION_DEFLATE && compression != COMPRESSION_ADOBE_DEFLATE) {
            return null
        }
        if (bits != 32 || format != SAMPLE_FORMAT_FLOAT || predictor != PREDICTOR_FLOAT) {
            return null
        }

        return CogHeader(
            width = width,
            height = height,
            blockWidth = blockWidth,
            blockHeight = blockHeight,
            blockOffsets = offsets,
            blockLengths = lengths,
            pixelLon = abs(scale[0]),
            pixelLat = abs(scale[1]),
            originLon = tie[3] - tie[0] * abs(scale[0]),
            originLat = tie[4] + tie[1] * abs(scale[1]),
        )
    }

    /**
     * Undoes TIFF predictor 3, which the Copernicus tiles use. Each row holds
     * byte-wise differences, and is then split into planes: all the first bytes
     * of the row's floats, then all the second bytes, and so on. Deflate
     * compresses that far better than raw floats, because neighbouring ground
     * heights share their high bytes and so difference to zero.
     */
    private fun undoFloatPredictor(bytes: ByteArray, width: Int, height: Int) {
        val rowBytes = width * 4
        val scratch = ByteArray(rowBytes)
        for (row in 0 until height) {
            val base = row * rowBytes
            var running = bytes[base]
            for (i in 1 until rowBytes) {
                running = (bytes[base + i] + running).toByte()
                bytes[base + i] = running
            }
            System.arraycopy(bytes, base, scratch, 0, rowBytes)
            for (column in 0 until width) {
                val out = base + column * 4
                bytes[out + 3] = scratch[column]
                bytes[out + 2] = scratch[width + column]
                bytes[out + 1] = scratch[2 * width + column]
                bytes[out] = scratch[3 * width + column]
            }
        }
    }

    private fun inflate(compressed: ByteArray, expected: Int): ByteArray? {
        val out = ByteArray(expected)
        val inflater = Inflater()
        return try {
            inflater.setInput(compressed)
            var done = 0
            while (done < expected && !inflater.finished()) {
                val read = inflater.inflate(out, done, expected - done)
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break
                }
                done += read
            }
            if (done == expected) out else null
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    // --- HTTP ---------------------------------------------------------------

    private class Response(val body: ByteArray?, val missing: Boolean)

    private fun fetch(url: String, from: Long, to: Long): Response {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Range", "bytes=$from-$to")
            }
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL ->
                    Response(connection.inputStream.use { it.readBytes() }, missing = false)

                HttpURLConnection.HTTP_NOT_FOUND, HttpURLConnection.HTTP_FORBIDDEN ->
                    Response(null, missing = true)

                else -> {
                    // A plain 200 means the range was ignored and the body is
                    // the whole 36MB file. Drop it rather than read it.
                    Log.w(TAG, "Unexpected $code for a range request on $url")
                    Response(null, missing = false)
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Terrain fetch failed: ${error.message ?: error.javaClass.simpleName}")
            Response(null, missing = false)
        } finally {
            connection?.disconnect()
        }
    }

    /** Saved as they arrive: the tile's header, and each block of pixels. */
    private const val HEADER_SUFFIX = ".hdr"
    private const val BLOCK_SUFFIX = ".blk"

    private const val LITTLE_ENDIAN_MARK: Byte = 0x49 // 'I'
    private const val BIG_ENDIAN_MARK: Byte = 0x4D // 'M'

    private const val TAG_IMAGE_WIDTH = 256
    private const val TAG_IMAGE_LENGTH = 257
    private const val TAG_BITS_PER_SAMPLE = 258
    private const val TAG_COMPRESSION = 259
    private const val TAG_PREDICTOR = 317
    private const val TAG_TILE_WIDTH = 322
    private const val TAG_TILE_LENGTH = 323
    private const val TAG_TILE_OFFSETS = 324
    private const val TAG_TILE_BYTE_COUNTS = 325
    private const val TAG_SAMPLE_FORMAT = 339
    private const val TAG_PIXEL_SCALE = 33550
    private const val TAG_TIE_POINT = 33922

    private const val COMPRESSION_DEFLATE = 8
    private const val COMPRESSION_ADOBE_DEFLATE = 32946
    private const val SAMPLE_FORMAT_FLOAT = 3
    private const val PREDICTOR_FLOAT = 3
}
