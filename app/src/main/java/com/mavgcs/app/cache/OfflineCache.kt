package com.mavgcs.app.cache

import android.content.Context
import kotlin.math.roundToInt
import java.util.Locale

/**
 * What one cache currently holds, as the status row reports it. A
 * [limitBytes] of zero means saving is switched off: whatever is already on
 * disk is still served, nothing new joins it.
 */
data class CacheStats(
    val items: Long = 0L,
    val usedBytes: Long = 0L,
    val limitBytes: Long = 0L,
)

/** One entry in a cache-size dropdown. */
data class CacheLimit(val label: String, val megabytes: Int)

/**
 * Map sizes, matching the desktop's. A map tile is 10-30 KB, so half a
 * gigabyte is a few hundred square kilometres of flying country.
 */
val MAP_CACHE_LIMITS = listOf(
    CacheLimit("No Cache", 0),
    CacheLimit("100 MB", 100),
    CacheLimit("200 MB", 200),
    CacheLimit("500 MB", 500),
    CacheLimit("1 GB", 1024),
    CacheLimit("2 GB", 2048),
    CacheLimit("5 GB", 5120),
)

/**
 * Terrain sizes. Smaller steps than the desktop's on purpose: it stores whole
 * 40MB elevation tiles, while this app keeps only the 1024x1024 blocks it
 * actually reads, at roughly 2.4MB each. One block covers about 30km square,
 * so 500MB here is a far larger area than 500MB would buy on the desktop.
 */
val TERRAIN_CACHE_LIMITS = listOf(
    CacheLimit("No Cache", 0),
    CacheLimit("100 MB", 100),
    CacheLimit("250 MB", 250),
    CacheLimit("500 MB", 500),
    CacheLimit("1 GB", 1024),
    CacheLimit("2 GB", 2048),
)

/** Trim back to this fraction of the limit, so a cache sitting on the
 *  boundary is not re-scanned after every single item written. */
const val CACHE_TRIM_FRACTION = 0.9

/** Sizes written as the desktop writes them. */
fun formatCacheSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(Locale.ROOT, bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "${(bytes / 1_048_576.0).roundToInt()} MB"
    bytes >= 1_024L -> "${(bytes / 1_024.0).roundToInt()} KB"
    else -> "$bytes B"
}

/**
 * The chosen cache sizes, remembered across runs. Named as the desktop names
 * them so the two stay recognisably the same setting.
 */
object CachePrefs {
    private const val FILE = "mavgcs"
    private const val KEY_MAP_MB = "map_cache_mb"
    private const val KEY_TERRAIN_MB = "terrain_cache_mb"

    const val MAP_DEFAULT_MB = 500
    const val TERRAIN_DEFAULT_MB = 500

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun mapLimitMb(context: Context): Int =
        prefs(context).getInt(KEY_MAP_MB, MAP_DEFAULT_MB).coerceAtLeast(0)

    fun terrainLimitMb(context: Context): Int =
        prefs(context).getInt(KEY_TERRAIN_MB, TERRAIN_DEFAULT_MB).coerceAtLeast(0)

    fun setMapLimitMb(context: Context, megabytes: Int) {
        prefs(context).edit().putInt(KEY_MAP_MB, megabytes.coerceAtLeast(0)).apply()
    }

    fun setTerrainLimitMb(context: Context, megabytes: Int) {
        prefs(context).edit().putInt(KEY_TERRAIN_MB, megabytes.coerceAtLeast(0)).apply()
    }
}
