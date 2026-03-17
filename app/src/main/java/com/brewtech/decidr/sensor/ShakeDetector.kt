package com.brewtech.decidr.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.math.sqrt

class ShakeDetector(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val shakeChannel = Channel<Unit>(Channel.CONFLATED)
    val shakeFlow: Flow<Unit> = shakeChannel.receiveAsFlow()

    // Shake detection parameters
    private val shakeThreshold = 15.0f    // m/s^2 above gravity
    private val shakeCooldownMs = 600L    // minimum time between shakes

    private var lastShakeTimestamp = 0L

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_UI
            )
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Calculate magnitude minus gravity
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val netForce = magnitude - SensorManager.GRAVITY_EARTH

        if (netForce > shakeThreshold) {
            val now = System.currentTimeMillis()
            if (now - lastShakeTimestamp > shakeCooldownMs) {
                lastShakeTimestamp = now
                shakeChannel.trySend(Unit)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }
}
