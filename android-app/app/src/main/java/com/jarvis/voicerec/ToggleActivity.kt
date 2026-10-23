package com.jarvis.voicerec

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Invisible trampoline. The root daemon runs:
 *   am start -n com.jarvis.voicerec/.ToggleActivity -a com.jarvis.voicerec.TOGGLE --user 0
 *
 * Because we briefly become the foreground activity, we are allowed to start
 * the microphone foreground service (Android 14+ blocks that from the
 * background). We forward the action to RecordService and finish immediately.
 */
class ToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Map the incoming action to a service action. Default is TOGGLE.
        val action = when (intent?.action) {
            RecordService.ACTION_START -> RecordService.ACTION_START
            RecordService.ACTION_STOP -> RecordService.ACTION_STOP
            else -> RecordService.ACTION_TOGGLE
        }

        val svc = Intent(this, RecordService::class.java).setAction(action)
        // We are foreground here, so a plain startForegroundService is legal.
        startForegroundService(svc)

        finish()
    }
}
