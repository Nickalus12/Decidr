package com.brewtech.decidr.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.math.abs
import kotlin.math.sqrt

// ── Data Models ──────────────────────────────────────────────────────────────

data class ShakeProfile(
    val intensity: Float,
    val duration: Long,
    val pattern: ShakePattern,
    val jerkSmoothness: Float,
    val preShakeStillness: Long,
    val postShakeStillness: Long,
    val dominantAxis: ShakeAxis,
    val wristRotation: Float,
    val emotionalRead: ShakeEmotion
)

enum class ShakePattern {
    SINGLE_JOLT,
    STEADY_SHAKE,
    ERRATIC,
    GENTLE_ROLL,
    AGGRESSIVE,
    PLAYFUL
}

enum class ShakeAxis { X, Y, Z, MULTI }

enum class ShakeEmotion {
    DELIBERATE,
    ANXIOUS,
    IMPATIENT,
    PLAYFUL,
    DESPERATE,
    CONTEMPLATIVE
}

// ── Ring Buffer ──────────────────────────────────────────────────────────────

private class RingBuffer<T>(private val capacity: Int, private val factory: () -> T) {
    private val data = Array<Any?>(capacity) { factory() }
    private var head = 0
    private var count = 0

    val size: Int get() = count

    fun push(item: T) {
        data[head] = item
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    @Suppress("UNCHECKED_CAST")
    fun get(index: Int): T {
        if (index < 0 || index >= count) throw IndexOutOfBoundsException()
        val realIndex = (head - count + index + capacity) % capacity
        return data[realIndex] as T
    }

    fun clear() {
        head = 0
        count = 0
    }
}

// ── Timestamped Samples ──────────────────────────────────────────────────────

private data class AccelSample(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val magnitude: Float = 0f,
    val netForce: Float = 0f,
    val timestampMs: Long = 0L
)

private data class GyroSample(
    val z: Float = 0f,
    val timestampMs: Long = 0L
)

// ── ShakeAnalyzer ────────────────────────────────────────────────────────────

class ShakeAnalyzer(context: Context) : SensorEventListener {

    private companion object {
        const val BUFFER_SIZE = 150           // ~3 sec at 50 Hz
        const val SHAKE_THRESHOLD = 12.0f     // m/s^2 net force to start a shake
        const val SHAKE_END_QUIET_MS = 300L   // below threshold this long = shake over
        const val STILLNESS_THRESHOLD = 1.5f  // net force considered "still"
        const val MAX_INTENSITY_CAP = 40.0f   // net force for intensity = 1.0
        const val GENTLE_INTENSITY = 6.0f     // below this + slow = gentle
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // Pre-allocated ring buffers
    private val accelBuffer = RingBuffer(BUFFER_SIZE) { AccelSample() }
    private val gyroBuffer = RingBuffer(BUFFER_SIZE) { GyroSample() }

    // Shake capture state (accessed only from sensor thread)
    private var shakeActive = false
    private var shakeStartMs = 0L
    private var lastAboveThresholdMs = 0L
    private val shakeCapture = mutableListOf<AccelSample>()
    private val shakeGyroCapture = mutableListOf<GyroSample>()

    // Post-shake monitoring
    private var awaitingPostStillness = false
    private var shakeEndMs = 0L
    private var postStillnessStartMs = 0L
    private var pendingProfile: ShakeProfile? = null

    // Latest completed profile
    @Volatile
    private var latestProfile: ShakeProfile? = null

    // Flow emission
    private val shakeChannel = Channel<ShakeProfile>(Channel.BUFFERED)

    fun shakeEvents(): Flow<ShakeProfile> = shakeChannel.receiveAsFlow()

    fun getShakeProfile(): ShakeProfile? = latestProfile

    // ── Lifecycle ────────────────────────────────────────────────────────

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        shakeActive = false
        awaitingPostStillness = false
        shakeCapture.clear()
        shakeGyroCapture.clear()
    }

    // ── SensorEventListener ──────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val now = System.currentTimeMillis()

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> handleAccel(event, now)
            Sensor.TYPE_GYROSCOPE -> handleGyro(event, now)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ── Accelerometer Handler ────────────────────────────────────────────

    private fun handleAccel(event: SensorEvent, now: Long) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val mag = sqrt(x * x + y * y + z * z)
        val net = mag - SensorManager.GRAVITY_EARTH

        val sample = AccelSample(x, y, z, mag, net, now)
        accelBuffer.push(sample)

        // Post-shake stillness monitoring
        if (awaitingPostStillness) {
            if (net > STILLNESS_THRESHOLD) {
                // Movement detected — record how long they were still
                val postStillness = now - shakeEndMs
                finalizeProfile(postStillness)
                return
            }
            // If 3 seconds of stillness, finalize anyway
            if (now - shakeEndMs > 3000L) {
                finalizeProfile(now - shakeEndMs)
                return
            }
        }

        if (!shakeActive) {
            // Waiting for shake to start
            if (net > SHAKE_THRESHOLD) {
                shakeActive = true
                shakeStartMs = now
                lastAboveThresholdMs = now
                shakeCapture.clear()
                shakeGyroCapture.clear()
                shakeCapture.add(sample)
            }
        } else {
            // Currently in a shake
            shakeCapture.add(sample)

            if (net > SHAKE_THRESHOLD) {
                lastAboveThresholdMs = now
            }

            // Check if shake has ended (quiet for SHAKE_END_QUIET_MS)
            if (now - lastAboveThresholdMs > SHAKE_END_QUIET_MS) {
                shakeActive = false
                shakeEndMs = now

                // Snapshot gyro samples that overlap the shake window
                synchronized(gyroBuffer) {
                    shakeGyroCapture.clear()
                    for (i in 0 until gyroBuffer.size) {
                        val gs = gyroBuffer.get(i)
                        if (gs.timestampMs in shakeStartMs..now) {
                            shakeGyroCapture.add(gs)
                        }
                    }
                }

                // Build profile (post-stillness TBD)
                awaitingPostStillness = true
                postStillnessStartMs = now
            }
        }
    }

    private fun handleGyro(event: SensorEvent, now: Long) {
        val sample = GyroSample(event.values[2], now)
        synchronized(gyroBuffer) {
            gyroBuffer.push(sample)
        }
    }

    // ── Profile Construction ─────────────────────────────────────────────

    private fun finalizeProfile(postStillness: Long) {
        awaitingPostStillness = false
        if (shakeCapture.isEmpty()) return

        val profile = buildProfile(postStillness)
        latestProfile = profile
        shakeChannel.trySend(profile)
        shakeCapture.clear()
        shakeGyroCapture.clear()
    }

    private fun buildProfile(postStillness: Long): ShakeProfile {
        val samples = shakeCapture.toList()

        // Duration
        val duration = if (samples.size >= 2)
            samples.last().timestampMs - samples.first().timestampMs
        else 0L

        // Intensity — peak net force normalized to 0..1
        val peakNet = samples.maxOf { it.netForce }
        val intensity = ((peakNet - SHAKE_THRESHOLD) /
                (MAX_INTENSITY_CAP - SHAKE_THRESHOLD)).coerceIn(0f, 1f)

        // Jerk analysis — derivative of net force between consecutive samples
        val jerkValues = mutableListOf<Float>()
        for (i in 1 until samples.size) {
            val dt = (samples[i].timestampMs - samples[i - 1].timestampMs).toFloat()
            if (dt > 0f) {
                jerkValues.add(abs(samples[i].netForce - samples[i - 1].netForce) / dt)
            }
        }
        val jerkVariance = if (jerkValues.size >= 2) {
            val mean = jerkValues.average().toFloat()
            jerkValues.map { (it - mean) * (it - mean) }.average().toFloat()
        } else 0f

        // Normalize jerk smoothness: low variance = smooth (1.0), high = jerky (0.0)
        // Empirical: variance > 2.0 is very jerky
        val jerkSmoothness = (1f - (jerkVariance / 2f).coerceIn(0f, 1f))

        // Pre-shake stillness — look back in the accel buffer before shake start
        val preShakeStillness = computePreStillness()

        // Dominant axis
        val dominantAxis = computeDominantAxis(samples)

        // Wrist rotation — integrate gyro Z during shake
        val wristRotation = computeWristRotation()

        // Zero crossings for pattern classification
        val zeroCrossings = countZeroCrossings(samples)

        // Rhythm regularity
        val rhythmRegularity = computeRhythmRegularity(samples)

        // Axis spread (are multiple axes active?)
        val multiAxis = isMultiAxis(samples)

        // Pattern classification
        val pattern = classifyPattern(
            samples, zeroCrossings, multiAxis, rhythmRegularity,
            intensity, duration, peakNet
        )

        // Emotional read
        val emotion = classifyEmotion(
            intensity, jerkSmoothness, preShakeStillness, postStillness,
            duration, pattern, multiAxis
        )

        return ShakeProfile(
            intensity = intensity,
            duration = duration,
            pattern = pattern,
            jerkSmoothness = jerkSmoothness,
            preShakeStillness = preShakeStillness,
            postShakeStillness = postStillness,
            dominantAxis = dominantAxis,
            wristRotation = wristRotation,
            emotionalRead = emotion
        )
    }

    // ── Analysis Helpers ─────────────────────────────────────────────────

    private fun computePreStillness(): Long {
        // Walk backward through the accel buffer to find samples before shake start
        var stillnessMs = 0L
        if (accelBuffer.size < 2) return 0L

        // Find samples before shake start
        var i = accelBuffer.size - 1
        // Skip samples during the shake
        while (i >= 0 && accelBuffer.get(i).timestampMs >= shakeStartMs) {
            i--
        }
        if (i < 0) return 0L

        val latestPreShakeMs = accelBuffer.get(i).timestampMs
        // Walk backward while still
        while (i >= 0) {
            val s = accelBuffer.get(i)
            if (s.netForce > STILLNESS_THRESHOLD) break
            i--
        }
        val stillStartMs = if (i >= 0) accelBuffer.get(i).timestampMs else {
            // Entire buffer was still
            accelBuffer.get(0).timestampMs
        }
        stillnessMs = latestPreShakeMs - stillStartMs
        return stillnessMs.coerceAtLeast(0L)
    }

    private fun computeDominantAxis(samples: List<AccelSample>): ShakeAxis {
        // Compute variance on each axis (subtract mean)
        val meanX = samples.map { it.x }.average().toFloat()
        val meanY = samples.map { it.y }.average().toFloat()
        val meanZ = samples.map { it.z }.average().toFloat()

        val varX = samples.map { (it.x - meanX) * (it.x - meanX) }.average().toFloat()
        val varY = samples.map { (it.y - meanY) * (it.y - meanY) }.average().toFloat()
        val varZ = samples.map { (it.z - meanZ) * (it.z - meanZ) }.average().toFloat()

        val maxVar = maxOf(varX, varY, varZ)
        val total = varX + varY + varZ
        if (total == 0f) return ShakeAxis.X

        // If no single axis dominates (>50% of total variance), it's multi-axis
        val dominance = maxVar / total
        if (dominance < 0.5f) return ShakeAxis.MULTI

        return when (maxVar) {
            varX -> ShakeAxis.X
            varY -> ShakeAxis.Y
            else -> ShakeAxis.Z
        }
    }

    private fun computeWristRotation(): Float {
        if (shakeGyroCapture.size < 2) return 0f
        var totalRotation = 0f
        for (i in 1 until shakeGyroCapture.size) {
            val dt = (shakeGyroCapture[i].timestampMs - shakeGyroCapture[i - 1].timestampMs) / 1000f
            if (dt > 0f && dt < 0.1f) { // sanity: skip gaps > 100ms
                totalRotation += shakeGyroCapture[i].z * dt
            }
        }
        return totalRotation // radians
    }

    private fun countZeroCrossings(samples: List<AccelSample>): Int {
        if (samples.size < 2) return 0
        // Use net force relative to mean for zero-crossing detection
        val mean = samples.map { it.netForce }.average().toFloat()
        var crossings = 0
        var prevSign = samples[0].netForce - mean >= 0
        for (i in 1 until samples.size) {
            val curSign = samples[i].netForce - mean >= 0
            if (curSign != prevSign) {
                crossings++
                prevSign = curSign
            }
        }
        return crossings
    }

    private fun computeRhythmRegularity(samples: List<AccelSample>): Float {
        // Find peak timestamps, compute inter-peak intervals, measure regularity
        val peaks = mutableListOf<Long>()
        for (i in 1 until samples.size - 1) {
            if (samples[i].netForce > samples[i - 1].netForce &&
                samples[i].netForce > samples[i + 1].netForce &&
                samples[i].netForce > SHAKE_THRESHOLD * 0.5f
            ) {
                peaks.add(samples[i].timestampMs)
            }
        }
        if (peaks.size < 3) return 0f

        val intervals = mutableListOf<Long>()
        for (i in 1 until peaks.size) {
            intervals.add(peaks[i] - peaks[i - 1])
        }
        if (intervals.isEmpty()) return 0f

        val meanInterval = intervals.average()
        if (meanInterval == 0.0) return 0f
        val cv = sqrt(intervals.map { (it - meanInterval) * (it - meanInterval) }.average()) / meanInterval
        // CV close to 0 = very regular; CV > 1 = very irregular
        return (1f - cv.toFloat().coerceIn(0f, 1f))
    }

    private fun isMultiAxis(samples: List<AccelSample>): Boolean {
        val meanX = samples.map { it.x }.average().toFloat()
        val meanY = samples.map { it.y }.average().toFloat()
        val meanZ = samples.map { it.z }.average().toFloat()

        val varX = samples.map { (it.x - meanX) * (it.x - meanX) }.average().toFloat()
        val varY = samples.map { (it.y - meanY) * (it.y - meanY) }.average().toFloat()
        val varZ = samples.map { (it.z - meanZ) * (it.z - meanZ) }.average().toFloat()

        val total = varX + varY + varZ
        if (total == 0f) return false
        val maxVar = maxOf(varX, varY, varZ)
        return maxVar / total < 0.5f
    }

    // ── Pattern Classification ───────────────────────────────────────────

    private fun classifyPattern(
        samples: List<AccelSample>,
        zeroCrossings: Int,
        multiAxis: Boolean,
        rhythmRegularity: Float,
        intensity: Float,
        duration: Long,
        peakNet: Float
    ): ShakePattern {
        // Single jolt: very short duration, few crossings
        if (duration < 200L && zeroCrossings <= 2) {
            return ShakePattern.SINGLE_JOLT
        }

        // Erratic: multi-axis, irregular rhythm
        if (multiAxis && rhythmRegularity < 0.3f && zeroCrossings > 4) {
            return ShakePattern.ERRATIC
        }

        // Aggressive: high intensity, sustained
        if (intensity > 0.7f && duration > 500L) {
            return ShakePattern.AGGRESSIVE
        }

        // Gentle roll: low intensity, slow
        if (peakNet < GENTLE_INTENSITY + SensorManager.GRAVITY_EARTH &&
            duration > 300L && zeroCrossings <= 4
        ) {
            return ShakePattern.GENTLE_ROLL
        }

        // Playful: moderate intensity, rhythmic
        if (rhythmRegularity > 0.6f && intensity in 0.2f..0.6f) {
            return ShakePattern.PLAYFUL
        }

        // Steady shake: regular rhythm, many crossings
        if (zeroCrossings >= 4 && rhythmRegularity > 0.4f) {
            return ShakePattern.STEADY_SHAKE
        }

        // Default based on intensity
        return if (intensity > 0.5f) ShakePattern.AGGRESSIVE else ShakePattern.STEADY_SHAKE
    }

    // ── Emotional Classification ─────────────────────────────────────────

    private fun classifyEmotion(
        intensity: Float,
        jerkSmoothness: Float,
        preStillness: Long,
        postStillness: Long,
        duration: Long,
        pattern: ShakePattern,
        multiAxis: Boolean
    ): ShakeEmotion {
        // Contemplative: long pre-stillness + gentle + smooth
        if (preStillness > 1500L && intensity < 0.4f && jerkSmoothness > 0.6f) {
            return ShakeEmotion.CONTEMPLATIVE
        }

        // Impatient: no pre-stillness + aggressive + high jerk
        if (preStillness < 200L && intensity > 0.6f && jerkSmoothness < 0.4f) {
            return ShakeEmotion.IMPATIENT
        }

        // Anxious: erratic + multi-axis + jerky
        if (pattern == ShakePattern.ERRATIC && multiAxis && jerkSmoothness < 0.4f) {
            return ShakeEmotion.ANXIOUS
        }

        // Desperate: long, sustained, high intensity
        if (duration > 1500L && intensity > 0.6f) {
            return ShakeEmotion.DESPERATE
        }

        // Playful: rhythmic, moderate
        if (pattern == ShakePattern.PLAYFUL || pattern == ShakePattern.GENTLE_ROLL) {
            return ShakeEmotion.PLAYFUL
        }

        // Deliberate: smooth, measured, moderate
        if (jerkSmoothness > 0.5f && intensity in 0.2f..0.7f) {
            return ShakeEmotion.DELIBERATE
        }

        // Fallback
        return when {
            intensity > 0.6f -> ShakeEmotion.IMPATIENT
            jerkSmoothness < 0.3f -> ShakeEmotion.ANXIOUS
            else -> ShakeEmotion.DELIBERATE
        }
    }
}
