package com.jarvis.voicerec

import android.content.Context

/** Thin wrapper over SharedPreferences for the handful of settings we keep. */
object Prefs {
    private const val FILE = "jarvis_prefs"
    private const val KEY_SERVER_URL = "server_url"

    /** Default points at this PC's LAN IP from the plan. */
    const val DEFAULT_SERVER_URL = "http://192.168.1.28:8765"

    fun serverUrl(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL

    fun setServerUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, url.trim().trimEnd('/'))
            .apply()
    }
}
