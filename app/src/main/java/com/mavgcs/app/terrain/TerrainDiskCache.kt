package com.mavgcs.app.terrain

import android.content.Context
import android.util.Log
import com.mavgcs.app.cache.CACHE_TRIM_FRACTION
import com.mavgcs.app.cache.CachePrefs
import com.mavgcs.app.cache.CacheStats
import java.io.File

/**
 * Elevation data kept on disk so the terrain radar works with no signal, and
 * so a restart does not re-download what was already read.
 *
 * The desktop caches whole 40MB DEM tiles. This keeps the two pieces the COG
 * reader actually fetches instead -- a tile's header, and each 1024x1024 block
 * of pixels -- which is the same coverage for a small fraction of the bytes.
 * Both are stored exactly as they arrived, so a cached block still has to be
 * inflated on the way out; that is cheap next to fetching it again.
 *
 * As on the desktop, a limit of zero means "use what is already saved, save
 * nothing new", and trimming drops the oldest files first.
 */
object TerrainDiskCache {

    private const val TAG = "TerrainDiskCache"
    private const val DIR_NAME = "terrain_cache"
    private const val PART_SUFFIX = ".part"

    @Volatile
    private var directory: File? = null

    @Volatile
    private var limitBytes = 0L

    /** Call once from the Application. */
    fun attach(context: Context) {
        // Internal storage, not the cache directory: this is an offline map the
        // pilot chose to collect, and Android may empty a cache dir whenever it
        // wants the space back.
        directory = File(context.filesDir, DIR_NAME)
        limitBytes = CachePrefs.terrainLimitMb(context) * 1024L * 1024L
    }

    fun limitMb(): Int = (limitBytes / (1024L * 1024L)).toInt()

    fun setLimitMb(context: Context, megabytes: Int) {
        limitBytes = megabytes.coerceAtLeast(0) * 1024L * 1024L
        CachePrefs.setTerrainLimitMb(context, megabytes.coerceAtLeast(0))
        enforceLimit()
    }

    private val savingEnabled: Boolean get() = limitBytes > 0

    /** Saved bytes for [name], or null if it is not held. */
    fun read(name: String): ByteArray? {
        val file = File(directory ?: return null, name)
        return runCatching { if (file.isFile) file.readBytes() else null }.getOrNull()
    }

    /** Save [body] under [name], unless saving is switched off. */
    fun write(name: String, body: ByteArray) {
        if (!savingEnabled) {
            return
        }
        val dir = directory ?: return
        runCatching {
            dir.mkdirs()
            // Write then rename: a block is megabytes, and a file truncated by
            // a kill part-way through would fail to inflate from then on.
            val part = File(dir, name + PART_SUFFIX)
            part.writeBytes(body)
            if (!part.renameTo(File(dir, name))) {
                part.delete()
            }
        }.onFailure { Log.w(TAG, "Could not save $name: ${it.message}") }
        enforceLimit()
    }

    /** Files held and bytes on disk. Touches the filesystem, so call it off
     *  the main thread. */
    fun stats(): CacheStats {
        val files = files()
        return CacheStats(
            items = files.size.toLong(),
            usedBytes = files.sumOf { it.length() },
            limitBytes = limitBytes,
        )
    }

    /** Forget one saved file, when its contents turn out to be unusable. */
    fun remove(name: String) {
        val dir = directory ?: return
        runCatching { File(dir, name).delete() }
    }

    /** Delete every saved header and block. */
    fun clear() {
        files().forEach { runCatching { it.delete() } }
        TerrainProvider.forgetCached()
    }

    private fun files(): List<File> =
        directory?.listFiles()?.filter { it.isFile && !it.name.endsWith(PART_SUFFIX) }
            ?: emptyList()

    /**
     * Trim back under the limit, oldest first. Only runs when actually over,
     * and then goes to 90% so a cache sitting on the boundary is not re-scanned
     * after every single block.
     */
    private fun enforceLimit() {
        val limit = limitBytes
        if (limit <= 0L) {
            return
        }
        val files = files()
        var total = files.sumOf { it.length() }
        if (total <= limit) {
            return
        }
        val target = (limit * CACHE_TRIM_FRACTION).toLong()
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= target) {
                return@forEach
            }
            val size = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                total -= size
            }
        }
    }
}
