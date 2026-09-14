package com.mavgcs.app

import android.app.Application
import com.mavgcs.app.cache.MapTileCache
import com.mavgcs.app.terrain.TerrainDiskCache
import org.osmdroid.config.Configuration

class MavGcsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().load(
            this,
            getSharedPreferences("osmdroid", MODE_PRIVATE),
        )
        // After osmdroid's own config is loaded, so the saved cache size is
        // what ends up in force rather than osmdroid's default.
        MapTileCache.attach(this)
        TerrainDiskCache.attach(this)
    }
}
