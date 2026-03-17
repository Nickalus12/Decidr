package com.brewtech.decidr.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Comprehensive sensor manager for Samsung Galaxy Watch Ultra.
 *
 * Registers listeners for every available sensor and exposes real-time
 * values as [StateFlow]s. Computed properties like compass bearing,
 * altitude estimation, shake detection, wrist-twist detection, and tilt
 * angles are derived automatically.
 *
 * Usage:
 *   val hub = SensorHub(context)
 *   hub.start()          // onResume / composable enters composition
 *   hub.stop()           // onPause / composable leaves composition
 */
class SensorHub(context: Context) : SensorEventListener {

    // ── Sensor Manager ──────────────────────────────────────────────────
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // ── Individual Sensors (nullable — may not exist on device) ─────────
    private val accelerometer       = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope           = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magnetometer        = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val barometer           = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val lightSensor         = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val heartRateSensor     = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
    private val stepCounter         = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val gravitySensor       = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val rotationVector      = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // ── Raw Value Flows ─────────────────────────────────────────────────

    /** Accelerometer XYZ in m/s^2 */
    private val _accelerometer = MutableStateFlow(floatArrayOf(0f, 0f, 0f))
    val accelerometerValues: StateFlow<FloatArray> = _accelerometer.asStateFlow()

    /** Gyroscope XYZ in rad/s */
    private val _gyroscope = MutableStateFlow(floatArrayOf(0f, 0f, 0f))
    val gyroscopeValues: StateFlow<FloatArray> = _gyroscope.asStateFlow()

    /** Magnetometer XYZ in micro-Tesla */
    private val _magnetometer = MutableStateFlow(floatArrayOf(0f, 0f, 0f))
    val magnetometerValues: StateFlow<FloatArray> = _magnetometer.asStateFlow()

    /** Atmospheric pressure in hPa (mbar) */
    private val _pressure = MutableStateFlow(0f)
    val pressure: StateFlow<Float> = _pressure.asStateFlow()

    /** Ambient light in lux */
    private val _light = MutableStateFlow(0f)
    val light: StateFlow<Float> = _light.asStateFlow()

    /** Heart rate in BPM (requires BODY_SENSORS permission) */
    private val _heartRate = MutableStateFlow(0f)
    val heartRate: StateFlow<Float> = _heartRate.asStateFlow()

    /** Cumulative step count since last device reboot */
    private val _steps = MutableStateFlow(0f)
    val steps: StateFlow<Float> = _steps.asStateFlow()

    /** Gravity vector XYZ in m/s^2 */
    private val _gravity = MutableStateFlow(floatArrayOf(0f, 0f, SensorManager.GRAVITY_EARTH))
    val gravityValues: StateFlow<FloatArray> = _gravity.asStateFlow()

    /** Rotation vector (quaternion components x, y, z, [w]) */
    private val _rotationVector = MutableStateFlow(floatArrayOf(0f, 0f, 0f, 0f))
    val rotationVectorValues: StateFlow<FloatArray> = _rotationVector.asStateFlow()

    // ── Computed / Derived Flows ────────────────────────────────────────

    /** Compass bearing in degrees (0-360, 0 = north) */
    private val _compassBearing = MutableStateFlow(0f)
    val compassBearing: StateFlow<Float> = _compassBearing.asStateFlow()

    /** Estimated altitude in meters derived from barometer (ISA sea-level ref) */
    private val _altitude = MutableStateFlow(0f)
    val altitude: StateFlow<Float> = _altitude.asStateFlow()

    /** Estimated altitude in feet */
    private val _altitudeFeet = MutableStateFlow(0f)
    val altitudeFeet: StateFlow<Float> = _altitudeFeet.asStateFlow()

    /** True for one read when a shake gesture is detected */
    private val _shakeDetected = MutableStateFlow(false)
    val shakeDetected: StateFlow<Boolean> = _shakeDetected.asStateFlow()

    /** Magnitude of the most recent shake (net force above gravity) */
    private val _shakeIntensity = MutableStateFlow(0f)
    val shakeIntensity: StateFlow<Float> = _shakeIntensity.asStateFlow()

    /** True for one read when a wrist-twist gesture is detected */
    private val _wristTwist = MutableStateFlow(false)
    val wristTwist: StateFlow<Boolean> = _wristTwist.asStateFlow()

    /** Tilt angle around X axis in degrees (-180..180) */
    private val _tiltAngleX = MutableStateFlow(0f)
    val tiltAngleX: StateFlow<Float> = _tiltAngleX.asStateFlow()

    /** Tilt angle around Y axis in degrees (-180..180) */
    private val _tiltAngleY = MutableStateFlow(0f)
    val tiltAngleY: StateFlow<Float> = _tiltAngleY.asStateFlow()

    /** Gyroscope rotation speed around Z axis in rad/s (useful for spin detection) */
    private val _gyroZ = MutableStateFlow(0f)
    val gyroZ: StateFlow<Float> = _gyroZ.asStateFlow()

    /** Magnetic field total magnitude in micro-Tesla (entropy source) */
    private val _magneticMagnitude = MutableStateFlow(0f)
    val magneticMagnitude: StateFlow<Float> = _magneticMagnitude.asStateFlow()

    /** Pressure trend: +1 rising, 0 stable, -1 falling */
    private val _pressureTrend = MutableStateFlow(0)
    val pressureTrend: StateFlow<Int> = _pressureTrend.asStateFlow()

    // ── Detection Thresholds ────────────────────────────────────────────

    private companion object {
        const val SHAKE_THRESHOLD      = 14.0f   // m/s^2 above gravity
        const val SHAKE_COOLDOWN_MS    = 500L
        const val TWIST_THRESHOLD      = 4.0f    // rad/s on Z-axis
        const val TWIST_COOLDOWN_MS    = 600L
        const val PRESSURE_WINDOW_SIZE = 20      // samples for trend calculation
        const val SEA_LEVEL_PRESSURE   = SensorManager.PRESSURE_STANDARD_ATMOSPHERE // 1013.25 hPa
    }

    // ── Internal State ──────────────────────────────────────────────────

    private var lastShakeTime = 0L
    private var lastTwistTime = 0L
    private var isRunning     = false

    // Rotation matrix buffers (reused to avoid allocation in hot path)
    private val rotationMatrix    = FloatArray(9)
    private val inclinationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // Pressure trend ring buffer
    private val pressureHistory = FloatArray(PRESSURE_WINDOW_SIZE)
    private var pressureIndex   = 0
    private var pressureFilled  = false

    // ── Lifecycle ───────────────────────────────────────────────────────

    /**
     * Begin reading from all available sensors.
     * Call from onResume or when the composable enters composition.
     */
    fun start() {
        if (isRunning) return
        isRunning = true

        val uiRate  = SensorManager.SENSOR_DELAY_UI
        val gameRate = SensorManager.SENSOR_DELAY_GAME

        // Motion sensors at game rate for responsiveness
        registerSafe(accelerometer, gameRate)
        registerSafe(gyroscope, gameRate)
        registerSafe(gravitySensor, uiRate)
        registerSafe(rotationVector, uiRate)

        // Environment sensors at UI rate (they change slowly)
        registerSafe(magnetometer, uiRate)
        registerSafe(barometer, uiRate)
        registerSafe(lightSensor, uiRate)

        // Body / activity sensors at UI rate
        registerSafe(heartRateSensor, uiRate)
        registerSafe(stepCounter, uiRate)
    }

    /**
     * Stop all sensor listeners.
     * Call from onPause or when the composable leaves composition.
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        sensorManager.unregisterListener(this)
    }

    // ── SensorEventListener ─────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        try {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER   -> handleAccelerometer(event)
                Sensor.TYPE_GYROSCOPE       -> handleGyroscope(event)
                Sensor.TYPE_MAGNETIC_FIELD   -> handleMagnetometer(event)
                Sensor.TYPE_PRESSURE         -> handlePressure(event)
                Sensor.TYPE_LIGHT            -> handleLight(event)
                Sensor.TYPE_HEART_RATE       -> handleHeartRate(event)
                Sensor.TYPE_STEP_COUNTER     -> handleStepCounter(event)
                Sensor.TYPE_GRAVITY          -> handleGravity(event)
                Sensor.TYPE_ROTATION_VECTOR  -> handleRotationVector(event)
            }
        } catch (_: Exception) {
            // Never crash on sensor data — silently ignore malformed events.
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used — accuracy changes don't affect our flows.
    }

    // ── Per-sensor Handlers ─────────────────────────────────────────────

    private fun handleAccelerometer(event: SensorEvent) {
        val values = event.values.copyOf(3)
        _accelerometer.value = values

        // --- Shake detection ---
        val magnitude = sqrt(
            values[0] * values[0] +
            values[1] * values[1] +
            values[2] * values[2]
        )
        val netForce = magnitude - SensorManager.GRAVITY_EARTH

        if (netForce > SHAKE_THRESHOLD) {
            val now = System.currentTimeMillis()
            if (now - lastShakeTime > SHAKE_COOLDOWN_MS) {
                lastShakeTime = now
                _shakeDetected.value = true
                _shakeIntensity.value = netForce
            }
        } else {
            // Reset so collectors see the edge
            _shakeDetected.value = false
        }

        // --- Tilt angles ---
        val ax = values[0]
        val ay = values[1]
        val az = values[2]
        _tiltAngleX.value = Math.toDegrees(
            atan2(ay.toDouble(), sqrt((ax * ax + az * az).toDouble()))
        ).toFloat()
        _tiltAngleY.value = Math.toDegrees(
            atan2(ax.toDouble(), sqrt((ay * ay + az * az).toDouble()))
        ).toFloat()

        // --- Compass (needs magnetometer data too) ---
        recomputeCompass()
    }

    private fun handleGyroscope(event: SensorEvent) {
        val values = event.values.copyOf(3)
        _gyroscope.value = values
        _gyroZ.value = values[2]

        // --- Wrist twist detection (Z-axis spike) ---
        if (abs(values[2]) > TWIST_THRESHOLD) {
            val now = System.currentTimeMillis()
            if (now - lastTwistTime > TWIST_COOLDOWN_MS) {
                lastTwistTime = now
                _wristTwist.value = true
            }
        } else {
            _wristTwist.value = false
        }
    }

    private fun handleMagnetometer(event: SensorEvent) {
        val values = event.values.copyOf(3)
        _magnetometer.value = values

        _magneticMagnitude.value = sqrt(
            values[0] * values[0] +
            values[1] * values[1] +
            values[2] * values[2]
        )

        recomputeCompass()
    }

    private fun handlePressure(event: SensorEvent) {
        val hpa = event.values[0]
        _pressure.value = hpa

        // Altitude from barometric formula
        val altMeters = SensorManager.getAltitude(SEA_LEVEL_PRESSURE, hpa)
        _altitude.value = altMeters
        _altitudeFeet.value = altMeters * 3.28084f

        // Pressure trend
        pressureHistory[pressureIndex] = hpa
        pressureIndex = (pressureIndex + 1) % PRESSURE_WINDOW_SIZE
        if (pressureIndex == 0) pressureFilled = true

        if (pressureFilled) {
            val oldest = pressureHistory[pressureIndex] // next slot is oldest in ring
            val diff = hpa - oldest
            _pressureTrend.value = when {
                diff > 0.3f  ->  1   // rising
                diff < -0.3f -> -1   // falling
                else         ->  0   // stable
            }
        }
    }

    private fun handleLight(event: SensorEvent) {
        _light.value = event.values[0]
    }

    private fun handleHeartRate(event: SensorEvent) {
        val bpm = event.values[0]
        if (bpm > 0f) {
            _heartRate.value = bpm
        }
    }

    private fun handleStepCounter(event: SensorEvent) {
        _steps.value = event.values[0]
    }

    private fun handleGravity(event: SensorEvent) {
        _gravity.value = event.values.copyOf(3)
    }

    private fun handleRotationVector(event: SensorEvent) {
        val size = minOf(event.values.size, 4)
        _rotationVector.value = event.values.copyOf(size)
    }

    // ── Compass Computation ─────────────────────────────────────────────

    /**
     * Recompute compass bearing from latest accelerometer + magnetometer.
     * Both must have received at least one event.
     */
    private fun recomputeCompass() {
        val accel = _accelerometer.value
        val mag   = _magnetometer.value

        // Need non-zero data from both
        if (accel.all { it == 0f } || mag.all { it == 0f }) return

        val success = SensorManager.getRotationMatrix(
            rotationMatrix, inclinationMatrix, accel, mag
        )
        if (!success) return

        SensorManager.getOrientation(rotationMatrix, orientationAngles)

        // orientationAngles[0] is azimuth in radians (-π..π)
        var degrees = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
        if (degrees < 0f) degrees += 360f
        _compassBearing.value = degrees
    }

    // ── Utility ─────────────────────────────────────────────────────────

    /**
     * Convenience: produce a random seed seeded with magnetic field noise.
     * Use this for "hardware-ish" randomness in coin flips, etc.
     */
    fun magneticEntropySeed(): Long {
        val m = _magnetometer.value
        val bits = (m[0].toBits().toLong() xor
                    m[1].toBits().toLong().shl(21) xor
                    m[2].toBits().toLong().shl(42) xor
                    System.nanoTime())
        return bits
    }

    /**
     * Lucky number derived from step count: (steps % 6) + 1
     */
    fun luckyNumber(): Int {
        val s = _steps.value.toInt()
        return if (s > 0) (s % 6) + 1 else (1..6).random()
    }

    /**
     * Returns true if the ambient light level suggests a dark environment (< 20 lux).
     */
    fun isDarkEnvironment(): Boolean = _light.value < 20f

    /**
     * Returns a brightness multiplier (0.6..1.4) inversely proportional to light.
     * Dark rooms get brighter UI, bright rooms get subtler UI.
     */
    fun lightAdaptiveBrightness(): Float {
        val lux = _light.value.coerceIn(1f, 500f)
        // Map [1, 500] -> [1.4, 0.6]
        return 1.4f - (lux - 1f) / 499f * 0.8f
    }

    /**
     * Mood based on heart rate for Magic 8-Ball answer biasing.
     * Returns "excited" (HR > 90), "calm" (HR < 70), or "neutral".
     */
    fun heartRateMood(): String {
        val hr = _heartRate.value
        return when {
            hr > 90f -> "excited"
            hr < 70f -> "calm"
            else     -> "neutral"
        }
    }

    /**
     * Weather mood from pressure trend for 8-Ball answer biasing.
     * Returns "ominous" (falling), "positive" (rising), or "neutral".
     */
    fun pressureMood(): String {
        return when (_pressureTrend.value) {
            -1   -> "ominous"
             1   -> "positive"
            else -> "neutral"
        }
    }

    // ── Private Helpers ─────────────────────────────────────────────────

    private fun registerSafe(sensor: Sensor?, delay: Int) {
        sensor?.let {
            sensorManager.registerListener(this, it, delay)
        }
    }
}
