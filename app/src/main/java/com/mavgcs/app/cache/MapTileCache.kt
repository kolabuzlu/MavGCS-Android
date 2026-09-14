package com.mavgcs.app.cache

import android.content.Context
import android.util.Log
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.ITileSource
import java.io.InputStream

/**
 * Offline map support, built on the tile database osmdroid already keeps.
 *
 * The desktop runs a local HTTP proxy in front of the tile providers because a
 * browser's own cache obeys their expiry headers and evicts what it likes.
 * osmdroid has no such problem -- it owns its cache outright -- so the same
 * behaviour is had by configuring it rather than by proxying:
 *
 *  - reads always happen, so an area flown once keeps working with no signal;
 *  - the limit only governs whether newly fetched tiles are *written*, and how
 *    much is kept. "No Cache" serves what is saved and saves nothing new, so
 *    choosing it never loses a collected area -- only Clear does that;
 *  - a saved tile stays saved. Providers hand out expiry dates a few days out,
 *    and an expired tile is re-fetched, which is exactly what cannot happen at
 *    a field with no coverage.
 */
object MapTileCache {

    private const val TAG = "MapTileCache"

    /**
     * How long a downloaded tile is treated as current. Long enough to mean
     * "until deleted" without the overflow that a truly enormous value would
     * cause once added to the clock.
     */
    private const val KEEP_MS = 10L * 365L * 24L * 60L * 60L * 1000L

    private var writer: GatedTileWriter? = null

    @Volatile
    private var limitMb = CachePrefs.MAP_DEFAULT_MB

    /**
     * The cache every tile provider in the app should write through. Sharing
     * one keeps the imagery and the reference overlays under a single limit,
     * and a single database.
     */
    val fileCache: IFilesystemCache?
        get() = writer

    /** Call once from the Application, after osmdroid's own config is loaded. */
    fun attach(context: Context) {
        limitMb = CachePrefs.mapLimitMb(context)
        Configuration.getInstance().expirationOverrideDuration = KEEP_MS
        applyLimit()
        writer = runCatching { GatedTileWriter() }
            .onFailure { Log.w(TAG, "No tile cache: ${it.message}") }
            .getOrNull()
            ?.apply { writesEnabled = limitMb > 0 }
    }

    fun limitMb(): Int = limitMb

    fun setLimitMb(context: Context, megabytes: Int) {
        limitMb = megabytes.coerceAtLeast(0)
        CachePrefs.setMapLimitMb(context, limitMb)
        applyLimit()
        writer?.writesEnabled = limitMb > 0
        if (limitMb > 0) {
            // Lowering the limit should take effect now, not once enough new
            // tiles have arrived to notice.
            runCatching { writer?.runCleanupOperation() }
        }
    }

    /** Tiles held and bytes on disk. Touches the database, so call it off the
     *  main thread. */
    fun stats(): CacheStats {
        val cache = writer ?: return CacheStats(limitBytes = limitBytes())
        val tiles = runCatching { cache.getRowCount(null) }.getOrDefault(0L)
        val used = runCatching { cache.size }.getOrDefault(0L)
        return CacheStats(items = tiles, usedBytes = used, limitBytes = limitBytes())
    }

    /** Delete every saved tile. */
    fun clear() {
        runCatching { writer?.purgeCache() }
            .onFailure { Log.w(TAG, "Could not clear the map cache: ${it.message}") }
    }

    private fun limitBytes(): Long = limitMb * 1024L * 1024L

    private fun applyLimit() {
        val config = Configuration.getInstance()
        if (limitMb > 0) {
            config.tileFileSystemCacheMaxBytes = limitBytes()
            config.tileFileSystemCacheTrimBytes =
                (limitBytes() * CACHE_TRIM_FRACTION).toLong()
        } else {
            // Not zero: osmdroid reads these as "trim down to here", so zero
            // would turn "stop saving" into "delete the offline map".
            config.tileFileSystemCacheMaxBytes = Long.MAX_VALUE
            config.tileFileSystemCacheTrimBytes = Long.MAX_VALUE
        }
    }

    /**
     * osmdroid's tile database, with writing switchable. The downloader hands
     * the tile to the map from the stream it fetched rather than by reading it
     * back, so refusing to save one still displays it -- it just is not there
     * next time.
     */
    private class GatedTileWriter : SqlTileWriter() {

        @Volatile
        var writesEnabled: Boolean = true

        override fun saveFile(
            source: ITileSource?,
            index: Long,
            stream: InputStream?,
            expires: Long?,
        ): Boolean {
            if (!writesEnabled) {
                return false
            }
            return super.saveFile(source, index, stream, expires)
        }

        /**
         * The map and every reference overlay share this one writer, so a
         * provider being torn down must not close the database out from under
         * the others. It lives as long as the process.
         */
        override fun onDetach() = Unit
    }
}
