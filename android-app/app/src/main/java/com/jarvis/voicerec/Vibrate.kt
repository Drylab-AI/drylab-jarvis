package com.jarvis.voicerec

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Haptic feedback: one buzz on start, two short buzzes on stop. */
object Vibrate {
    private fun vibrator(ctx: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    fun once(ctx: Context) {
        vibrator(ctx).vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun twice(ctx: Context) {
        // wait, buzz, gap, buzz
        val pattern = longArrayOf(0, 100, 120, 100)
        vibrator(ctx).vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
}
