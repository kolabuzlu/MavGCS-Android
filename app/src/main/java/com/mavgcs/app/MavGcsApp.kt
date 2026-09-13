package com.mavgcs.app

import android.app.Application
import org.osmdroid.config.Configuration

class MavGcsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().load(
            this,
            getSharedPreferences("osmdroid", MODE_PRIVATE),
        )
    }
}
