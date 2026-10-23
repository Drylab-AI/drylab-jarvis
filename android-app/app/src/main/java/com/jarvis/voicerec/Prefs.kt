package com.jarvis.voicerec

import android.content.Context

/** Thin wrapper over SharedPreferences for the handful of settings we keep. */
object Prefs {
    private const val FILE = "jarvis_prefs"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_WAKE_ENABLED = "wake_enabled"

    /**
     * Default points at this PC's LAN IP. Port 8787 is used because ports
     * 8765/8766 on the target machine are already held by another local app;
     * change this (or the URL on the settings screen) for a different host.
     */
    const val DEFAULT_SERVER_URL = "http://192.168.1.28:8787"

    fun serverUrl(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL

    fun setServerUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, url.trim().trimEnd('/'))
            .apply()
    }

    /** Whether the on-device "Jarvis" wake word (WakeService) should run. */
    fun wakeEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_WAKE_ENABLED, false)

    fun setWakeEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_WAKE_ENABLED, on).apply()
    }

    private const val KEY_AUTO_ASK = "auto_ask_after_recording"

    /** After a Volume-Up recording, open the assistant to ask what to do. */
    fun autoAskAfterRecording(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_ASK, false)

    fun setAutoAskAfterRecording(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_ASK, on).apply()
    }
}
