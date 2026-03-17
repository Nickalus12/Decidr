package com.brewtech.decidr.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Classified emotional tone derived from vocal characteristics.
 */
enum class VoiceTone {
    CALM,        // Low pitch variation, steady volume, normal pace
    ANXIOUS,     // High pitch, fast pace, pitch tremors
    CONFIDENT,   // Strong volume, moderate pace, low pitch variation
    EXCITED,     // High pitch variation, fast pace, high volume
    SAD,         // Low pitch, slow pace, low volume
    ANGRY,       // High volume, fast pace, low pitch
    WHISPERED,   // Very low volume
    UNCERTAIN    // Rising pitch at end, hesitations detected
}

/**
 * Snapshot of vocal characteristics extracted from raw audio.
 */
data class VoiceProfile(
    val pitchHz: Float,           // Fundamental frequency (typically 85-255 Hz for adults)
    val pitchVariation: Float,    // Std deviation of pitch (monotone vs animated)
    val stressLevel: Float,       // 0.0 (calm) to 1.0 (highly stressed) from micro-tremors
    val speakingPace: Float,      // Estimated words per second
    val volumeDb: Float,          // Average volume in dB
    val volumeVariation: Float,   // Volume consistency (std deviation of dB across frames)
    val emotionalTone: VoiceTone  // Classified tone
)

/**
 * Real-time voice frequency analyzer for Samsung Galaxy Watch Ultra.
 *
 * Captures raw PCM audio from the watch microphone and extracts vocal
 * characteristics including pitch, stress indicators, speaking pace,
 * volume, and an overall emotional tone classification.
 *
 * Designed to run alongside [VoiceRecognizer] — call [startCapture] before
 * launching speech recognition, then [stopCapture] when recognition completes
 * to obtain the aggregated [VoiceProfile].
 */
class VoiceAnalyzer(private val context: Context) {

    companion object {
        private const val TAG = "VoiceAnalyzer"

        // Audio capture parameters
        private const val SAMPLE_RATE = 16000          // 16 kHz — good balance for voice
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Analysis frame parameters
        private const val FRAME_SIZE_MS = 30           // 30 ms frames for pitch detection
        private const val FRAME_SIZE = SAMPLE_RATE * FRAME_SIZE_MS / 1000  // 480 samples

        // Pitch detection bounds (human voice range)
        private const val MIN_PITCH_HZ = 80f
        private const val MAX_PITCH_HZ = 400f
        private const val MIN_LAG = SAMPLE_RATE / MAX_PITCH_HZ.toInt()   // 40 samples
        private const val MAX_LAG = SAMPLE_RATE / MIN_PITCH_HZ.toInt()   // 200 samples

        // Stress analysis window
        private const val STRESS_WINDOW_MS = 200
        private const val FRAMES_PER_STRESS_WINDOW = STRESS_WINDOW_MS / FRAME_SIZE_MS  // ~6-7 frames

        // Capture duration
        private const val CAPTURE_DURATION_MS = 2500L  // 2.5 seconds of audio

        // Volume reference for dB calculation (near silence for 16-bit PCM)
        private const val DB_REFERENCE = 1.0f

        // Syllable detection — minimum energy ratio to consider a peak
        private const val SYLLABLE_ENERGY_RATIO = 1.4f

        // Average syllables per English word
        private const val SYLLABLES_PER_WORD = 1.5f
    }

    // Pre-allocated buffers (no allocations in the processing loop)
    private val frameBuffer = FloatArray(FRAME_SIZE)
    private val windowedFrame = FloatArray(FRAME_SIZE)
    private val autocorrelation = FloatArray(MAX_LAG + 1)
    private val hammingWindow = FloatArray(FRAME_SIZE)

    // Collected per-frame metrics
    private val pitchValues = ArrayList<Float>(128)
    private val rmsValues = ArrayList<Float>(128)
    private val energyValues = ArrayList<Float>(128)

    // Capture state
    @Volatile
    private var isCapturing = false
    private var captureThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    init {
        // Pre-compute Hamming window coefficients
        for (i in 0 until FRAME_SIZE) {
            hammingWindow[i] = (0.54 - 0.46 * StrictMath.cos(2.0 * Math.PI * i / (FRAME_SIZE - 1))).toFloat()
        }
    }

    /**
     * Analyzes a buffer of raw PCM 16-bit audio data and returns a [VoiceProfile].
     *
     * @param audioData raw 16-bit PCM samples
     * @param sampleRate sample rate of the audio (should be 16000)
     * @return analyzed voice profile
     */
    fun analyzeAudio(audioData: ShortArray, sampleRate: Int): VoiceProfile {
        pitchValues.clear()
        rmsValues.clear()
        energyValues.clear()

        val frameSamples = sampleRate * FRAME_SIZE_MS / 1000
        val totalFrames = audioData.size / frameSamples

        for (frameIdx in 0 until totalFrames) {
            val offset = frameIdx * frameSamples

            // Convert short samples to float and apply Hamming window
            for (i in 0 until min(frameSamples, FRAME_SIZE)) {
                frameBuffer[i] = audioData[offset + i].toFloat()
                windowedFrame[i] = frameBuffer[i] * hammingWindow[i]
            }

            // Compute RMS for this frame
            val rms = computeRms(frameBuffer, min(frameSamples, FRAME_SIZE))
            rmsValues.add(rms)

            // Compute energy for syllable detection
            val energy = computeEnergy(frameBuffer, min(frameSamples, FRAME_SIZE))
            energyValues.add(energy)

            // Pitch detection via autocorrelation (skip very quiet frames)
            if (rms > 50f) {
                val pitch = detectPitch(windowedFrame, min(frameSamples, FRAME_SIZE), sampleRate)
                if (pitch > 0f) {
                    pitchValues.add(pitch)
                }
            }
        }

        return buildProfile(audioData.size.toFloat() / sampleRate)
    }

    /**
     * Starts background audio capture. Call this before launching SpeechRecognizer.
     * The capture runs for ~2.5 seconds on a background thread.
     *
     * @param onProfile callback invoked with the resulting VoiceProfile when capture completes
     */
    fun startCapture(onProfile: (VoiceProfile) -> Unit) {
        if (isCapturing) {
            Log.w(TAG, "Capture already in progress")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        isCapturing = true
        pitchValues.clear()
        rmsValues.clear()
        energyValues.clear()

        captureThread = Thread({
            var recorder: AudioRecord? = null
            try {
                val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "Invalid min buffer size: $minBufferSize")
                    isCapturing = false
                    return@Thread
                }

                // Buffer large enough for smooth reading
                val bufferSize = max(minBufferSize, FRAME_SIZE * 2 * 4) // *2 for 16-bit, *4 for headroom

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    recorder.release()
                    isCapturing = false
                    return@Thread
                }

                audioRecord = recorder
                recorder.startRecording()

                val readBuffer = ShortArray(FRAME_SIZE)
                val startTime = System.currentTimeMillis()
                var totalSamplesRead = 0

                while (isCapturing && (System.currentTimeMillis() - startTime) < CAPTURE_DURATION_MS) {
                    val samplesRead = recorder.read(readBuffer, 0, FRAME_SIZE)
                    if (samplesRead <= 0) continue

                    totalSamplesRead += samplesRead

                    // Convert to float and window
                    for (i in 0 until samplesRead) {
                        frameBuffer[i] = readBuffer[i].toFloat()
                        windowedFrame[i] = frameBuffer[i] * hammingWindow[min(i, FRAME_SIZE - 1)]
                    }

                    // RMS
                    val rms = computeRms(frameBuffer, samplesRead)
                    synchronized(rmsValues) { rmsValues.add(rms) }

                    // Energy for syllable detection
                    val energy = computeEnergy(frameBuffer, samplesRead)
                    synchronized(energyValues) { energyValues.add(energy) }

                    // Pitch (skip silence)
                    if (rms > 50f) {
                        val pitch = detectPitch(windowedFrame, samplesRead, SAMPLE_RATE)
                        if (pitch > 0f) {
                            synchronized(pitchValues) { pitchValues.add(pitch) }
                        }
                    }
                }

                val durationSec = totalSamplesRead.toFloat() / SAMPLE_RATE
                val profile = buildProfile(durationSec)
                onProfile(profile)

            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException during audio capture", e)
            } catch (e: Exception) {
                Log.e(TAG, "Error during audio capture", e)
            } finally {
                try {
                    recorder?.stop()
                } catch (_: IllegalStateException) { }
                try {
                    recorder?.release()
                } catch (_: Exception) { }
                audioRecord = null
                isCapturing = false
            }
        }, "VoiceAnalyzer-Capture")

        captureThread?.priority = Thread.MAX_PRIORITY
        captureThread?.start()
    }

    /**
     * Stops an ongoing capture and returns the aggregated [VoiceProfile],
     * or null if no capture was running or no data was collected.
     */
    fun stopCapture(): VoiceProfile? {
        if (!isCapturing) return null

        isCapturing = false

        // Wait for the capture thread to finish (with timeout)
        try {
            captureThread?.join(500)
        } catch (_: InterruptedException) { }
        captureThread = null

        val hasPitchData: Boolean
        val hasRmsData: Boolean

        synchronized(pitchValues) { hasPitchData = pitchValues.isNotEmpty() }
        synchronized(rmsValues) { hasRmsData = rmsValues.isNotEmpty() }

        if (!hasRmsData) return null

        // Estimate duration from collected frames
        val durationSec: Float
        synchronized(rmsValues) {
            durationSec = rmsValues.size * FRAME_SIZE_MS / 1000f
        }
        return buildProfile(durationSec)
    }

    // -----------------------------------------------------------------------
    // Pitch detection via autocorrelation
    // -----------------------------------------------------------------------

    /**
     * Detects fundamental frequency using autocorrelation method.
     *
     * 1. Compute autocorrelation for lags in the human voice range
     * 2. Find the first significant peak after the initial drop
     * 3. Convert lag to frequency
     */
    private fun detectPitch(frame: FloatArray, length: Int, sampleRate: Int): Float {
        val minLag = sampleRate / MAX_PITCH_HZ.toInt()  // shortest period (highest pitch)
        val maxLag = min(sampleRate / MIN_PITCH_HZ.toInt(), length - 1) // longest period (lowest pitch)

        if (maxLag <= minLag || maxLag >= length) return -1f

        // Compute autocorrelation for each lag
        var maxCorrelation = 0f
        var bestLag = -1

        for (lag in minLag..maxLag) {
            var sum = 0f
            val limit = length - lag
            for (i in 0 until limit) {
                sum += frame[i] * frame[i + lag]
            }
            autocorrelation[lag - minLag] = sum

            // Normalize by the number of summed terms
            val normalized = sum / limit

            if (normalized > maxCorrelation) {
                maxCorrelation = normalized
                bestLag = lag
            }
        }

        if (bestLag <= 0) return -1f

        // Validate: the peak must be at least 40% of the zero-lag autocorrelation (energy)
        var zeroLagSum = 0f
        for (i in 0 until length) {
            zeroLagSum += frame[i] * frame[i]
        }
        if (zeroLagSum <= 0f) return -1f

        val peakRatio = maxCorrelation / (zeroLagSum / length)
        if (peakRatio < 0.4f) return -1f

        // Parabolic interpolation around the peak for sub-sample accuracy
        val refinedLag = if (bestLag > minLag && bestLag < maxLag) {
            val idxPrev = bestLag - minLag - 1
            val idxCurr = bestLag - minLag
            val idxNext = bestLag - minLag + 1

            if (idxPrev >= 0 && idxNext < autocorrelation.size) {
                val a = autocorrelation[idxPrev]
                val b = autocorrelation[idxCurr]
                val c = autocorrelation[idxNext]
                val denominator = 2f * (2f * b - a - c)
                if (denominator != 0f) {
                    bestLag + (a - c) / denominator
                } else {
                    bestLag.toFloat()
                }
            } else {
                bestLag.toFloat()
            }
        } else {
            bestLag.toFloat()
        }

        val pitchHz = sampleRate.toFloat() / refinedLag

        // Final bounds check
        return if (pitchHz in MIN_PITCH_HZ..MAX_PITCH_HZ) pitchHz else -1f
    }

    // -----------------------------------------------------------------------
    // Signal metrics
    // -----------------------------------------------------------------------

    private fun computeRms(samples: FloatArray, length: Int): Float {
        var sumSquares = 0f
        for (i in 0 until length) {
            sumSquares += samples[i] * samples[i]
        }
        return sqrt(sumSquares / length)
    }

    private fun computeEnergy(samples: FloatArray, length: Int): Float {
        var sum = 0f
        for (i in 0 until length) {
            sum += abs(samples[i])
        }
        return sum / length
    }

    private fun rmsToDb(rms: Float): Float {
        return if (rms > 0f) {
            (20.0 * ln((rms / DB_REFERENCE).toDouble()) / ln(10.0)).toFloat()
        } else {
            -96f // effective silence for 16-bit
        }
    }

    // -----------------------------------------------------------------------
    // Stress detection
    // -----------------------------------------------------------------------

    /**
     * Analyzes pitch stability to detect vocal stress.
     *
     * Micro-tremors in the 8-12 Hz range manifest as rapid pitch fluctuations
     * within short windows. High pitch variance over 200ms windows indicates stress.
     *
     * @return stress level from 0.0 (calm) to 1.0 (highly stressed)
     */
    private fun computeStressLevel(pitches: List<Float>): Float {
        if (pitches.size < FRAMES_PER_STRESS_WINDOW) return 0f

        val windowVariances = ArrayList<Float>()

        var windowStart = 0
        while (windowStart + FRAMES_PER_STRESS_WINDOW <= pitches.size) {
            val windowEnd = windowStart + FRAMES_PER_STRESS_WINDOW

            // Mean pitch in this window
            var mean = 0f
            for (i in windowStart until windowEnd) {
                mean += pitches[i]
            }
            mean /= FRAMES_PER_STRESS_WINDOW

            // Variance
            var variance = 0f
            for (i in windowStart until windowEnd) {
                val diff = pitches[i] - mean
                variance += diff * diff
            }
            variance /= FRAMES_PER_STRESS_WINDOW

            windowVariances.add(variance)
            windowStart += FRAMES_PER_STRESS_WINDOW
        }

        if (windowVariances.isEmpty()) return 0f

        // Average variance across windows
        var avgVariance = 0f
        for (v in windowVariances) avgVariance += v
        avgVariance /= windowVariances.size

        // Map variance to 0-1 stress scale
        // Empirical: variance < 25 Hz^2 = calm, > 400 Hz^2 = very stressed
        val normalized = (avgVariance - 25f) / (400f - 25f)
        return normalized.coerceIn(0f, 1f)
    }

    // -----------------------------------------------------------------------
    // Speaking pace estimation
    // -----------------------------------------------------------------------

    /**
     * Estimates speaking pace by detecting energy peaks (syllable nuclei).
     *
     * Syllables correspond to energy peaks — voiced segments separated by
     * brief dips. We count peaks exceeding a threshold relative to the
     * mean energy and convert to words per second.
     *
     * @return estimated words per second
     */
    private fun estimateSpeakingPace(energies: List<Float>, durationSec: Float): Float {
        if (energies.size < 3 || durationSec <= 0f) return 0f

        // Compute mean energy
        var meanEnergy = 0f
        for (e in energies) meanEnergy += e
        meanEnergy /= energies.size

        val threshold = meanEnergy * SYLLABLE_ENERGY_RATIO
        var syllableCount = 0
        var wasAbove = false

        for (e in energies) {
            if (e > threshold) {
                if (!wasAbove) {
                    syllableCount++
                    wasAbove = true
                }
            } else {
                wasAbove = false
            }
        }

        val words = syllableCount / SYLLABLES_PER_WORD
        return words / durationSec
    }

    // -----------------------------------------------------------------------
    // Emotional tone classification
    // -----------------------------------------------------------------------

    /**
     * Rule-based classifier combining pitch, pace, volume, and their variations.
     */
    private fun classifyTone(
        avgPitch: Float,
        pitchVariation: Float,
        stressLevel: Float,
        pace: Float,
        volumeDb: Float,
        volumeVariation: Float,
        pitches: List<Float>
    ): VoiceTone {
        // Whispered — very low volume overrides everything
        if (volumeDb < 35f) return VoiceTone.WHISPERED

        // Check for rising pitch at end (uncertainty marker)
        if (pitches.size >= 6) {
            val lastThird = pitches.subList(pitches.size * 2 / 3, pitches.size)
            val firstThird = pitches.subList(0, pitches.size / 3)

            val lastMean = lastThird.sum() / lastThird.size
            val firstMean = firstThird.sum() / firstThird.size

            // Pitch rises more than 15% toward the end
            if (lastMean > firstMean * 1.15f) return VoiceTone.UNCERTAIN
        }

        // High stress with fast pace → Anxious
        if (stressLevel > 0.6f && pace > 3.0f) return VoiceTone.ANXIOUS

        // High pitch variation + fast pace + high volume → Excited
        if (pitchVariation > 30f && pace > 2.8f && volumeDb > 65f) return VoiceTone.EXCITED

        // High volume + fast pace + low pitch → Angry
        if (volumeDb > 70f && pace > 2.5f && avgPitch < 160f) return VoiceTone.ANGRY

        // Low pitch + slow pace + low volume → Sad
        if (avgPitch < 140f && pace < 1.8f && volumeDb < 55f) return VoiceTone.SAD

        // Strong volume + moderate pace + low pitch variation → Confident
        if (volumeDb > 55f && pace in 1.5f..3.2f && pitchVariation < 25f) return VoiceTone.CONFIDENT

        // Default: Calm
        return VoiceTone.CALM
    }

    // -----------------------------------------------------------------------
    // Profile assembly
    // -----------------------------------------------------------------------

    /**
     * Aggregates per-frame metrics into a final [VoiceProfile].
     */
    private fun buildProfile(durationSec: Float): VoiceProfile {
        val pitches: List<Float>
        val rmsList: List<Float>
        val energies: List<Float>

        synchronized(pitchValues) { pitches = ArrayList(pitchValues) }
        synchronized(rmsValues) { rmsList = ArrayList(rmsValues) }
        synchronized(energyValues) { energies = ArrayList(energyValues) }

        // --- Pitch ---
        val avgPitch = if (pitches.isNotEmpty()) pitches.sum() / pitches.size else 0f

        val pitchVar = if (pitches.size > 1) {
            var sumSqDiff = 0f
            for (p in pitches) {
                val d = p - avgPitch
                sumSqDiff += d * d
            }
            sqrt(sumSqDiff / pitches.size)
        } else 0f

        // --- Stress ---
        val stress = computeStressLevel(pitches)

        // --- Speaking pace ---
        val pace = estimateSpeakingPace(energies, durationSec)

        // --- Volume ---
        val dbValues = FloatArray(rmsList.size) { rmsToDb(rmsList[it]) }
        val avgDb = if (dbValues.isNotEmpty()) {
            var s = 0f; for (d in dbValues) s += d; s / dbValues.size
        } else 0f

        val volVar = if (dbValues.size > 1) {
            var sumSq = 0f
            for (d in dbValues) {
                val diff = d - avgDb
                sumSq += diff * diff
            }
            sqrt(sumSq / dbValues.size)
        } else 0f

        // --- Tone ---
        val tone = classifyTone(avgPitch, pitchVar, stress, pace, avgDb, volVar, pitches)

        return VoiceProfile(
            pitchHz = avgPitch,
            pitchVariation = pitchVar,
            stressLevel = stress,
            speakingPace = pace,
            volumeDb = avgDb,
            volumeVariation = volVar,
            emotionalTone = tone
        )
    }
}
