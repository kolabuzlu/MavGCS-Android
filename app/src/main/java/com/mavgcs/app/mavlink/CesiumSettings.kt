package com.mavgcs.app.mavlink

import android.content.Context

/**
 * The Cesium Ion access token, which the 3D view cannot work without.
 *
 * Kept in preferences and typed in by the pilot rather than shipped in the
 * app: it is theirs, tied to their own free account, and a token compiled
 * into a public build would be everybody's.
 */
object CesiumSettings {

    private const val FILE = "mavgcs"
    private const val KEY_TOKEN = "cesium_ion_token"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun token(context: Context): String =
        prefs(context).getString(KEY_TOKEN, "").orEmpty().trim()

    fun setToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TOKEN, token.trim()).apply()
    }
}
