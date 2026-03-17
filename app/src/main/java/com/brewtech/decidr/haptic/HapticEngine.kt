package com.brewtech.decidr.haptic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class HapticEngine(context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    /**
     * A light, subtle tap — for UI interactions like button presses.
     */
    fun lightTap() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(30, 80)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(30)
        }
    }

    /**
     * A strong, punchy thud — for results landing (coin flip result, dice result).
     */
    fun heavyThud() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(100, 255)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(100)
        }
    }

    /**
     * Rapid tick-tick — for wheel segments passing by.
     */
    fun tickTick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(15, 120)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(15)
        }
    }

    /**
     * A celebratory buzz pattern — for final results and reveals.
     * Pattern: pause, buzz, pause, buzz, pause, long buzz
     */
    fun celebrationBuzz() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = longArrayOf(0, 50, 60, 50, 60, 150)
            val amplitudes = intArrayOf(0, 180, 0, 200, 0, 255)
            vibrator.vibrate(
                VibrationEffect.createWaveform(timings, amplitudes, -1)
            )
        } else {
            @Suppress("DEPRECATION")
            val pattern = longArrayOf(0, 50, 60, 50, 60, 150)
            vibrator.vibrate(pattern, -1)
        }
    }

    /**
     * A soft double-tap — for Magic 8-Ball answer reveal.
     */
    fun revealPulse() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = longArrayOf(0, 40, 80, 60)
            val amplitudes = intArrayOf(0, 100, 0, 200)
            vibrator.vibrate(
                VibrationEffect.createWaveform(timings, amplitudes, -1)
            )
        } else {
            @Suppress("DEPRECATION")
            val pattern = longArrayOf(0, 40, 80, 60)
            vibrator.vibrate(pattern, -1)
        }
    }
}
