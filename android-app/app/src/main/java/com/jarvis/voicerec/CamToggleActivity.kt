package com.jarvis.voicerec

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Invisible trampoline for the back-camera stream. The root daemon runs:
 *   am start -n com.jarvis.voicerec/.CamToggleActivity -a com.jarvis.voicerec.CAM_TOGGLE --user 0
 *
 * Unlike the audio ToggleActivity, this one must be able to appear OVER the
 * lock screen: showWhenLocked + turnScreenOn give us a visible foreground
 * activity even when the phone is locked, which is what legally lets a
 * background-started foreground service use the camera + mic on Android 14+.
 *
 * It forwards the action to CameraService, then finishes. To stop (second
 * press) we close straight away; to start we wait a brief moment so the
 * activity is actually visible before the FGS starts, which satisfies the
 * "while-in-use" requirement.
 */
class CamToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over the keyguard and wake the screen so the camera use is
        // allowed while locked.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }

        val action = when (intent?.action) {
            CameraService.ACTION_START -> CameraService.ACTION_START
            CameraService.ACTION_STOP -> CameraService.ACTION_STOP
            else -> CameraService.ACTION_TOGGLE
        }

        val svc = Intent(this, CameraService::class.java).setAction(action)

        if (action == CameraService.ACTION_STOP ||
            (action == CameraService.ACTION_TOGGLE && CameraService.isStreaming)
        ) {
            // Stopping: no need to stay visible.
            startForegroundService(svc)
            finish()
        } else {
            // Starting: give the window a moment to become visible (needed on
            // the lock screen) before launching the camera FGS.
            Handler(Looper.getMainLooper()).postDelayed({
                startForegroundService(svc)
                finish()
            }, 350)
        }
    }
}
