package com.brewtech.decidr.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-duplex audio pipeline for the Decidr watch agent.
 *
 * - **Capture**: Records from the microphone at 16 kHz, mono, 16-bit PCM.
 *   Delivers chunks of 1600 samples (100 ms) as [ByteArray] via callback.
 * - **Playback**: Queues and plays 24 kHz, mono, 16-bit PCM audio received
 *   from the Gemini Live API.
 *
 * Both capture and playback run on their own high-priority background threads
 * and can operate simultaneously for natural duplex conversation.
 */
class AudioPipeline(private val context: Context) {

    companion object {
        private const val TAG = "AudioPipeline"

        // ── Capture constants ────────────────────────────────────────────
        private const val CAPTURE_SAMPLE_RATE = 16_000
        private const val CAPTURE_CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val CAPTURE_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CAPTURE_CHUNK_SAMPLES = 1_600   // 100 ms at 16 kHz
        private const val CAPTURE_CHUNK_BYTES = CAPTURE_CHUNK_SAMPLES * 2  // 16-bit = 2 bytes/sample

        // ── Playback constants ───────────────────────────────────────────
        private const val PLAYBACK_SAMPLE_RATE = 24_000
        private const val PLAYBACK_CHANNEL = AudioFormat.CHANNEL_OUT_MONO
        private const val PLAYBACK_ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    // ── State flags ──────────────────────────────────────────────────────
    private val capturing = AtomicBoolean(false)
    private val playing = AtomicBoolean(false)

    // ── Audio objects ────────────────────────────────────────────────────
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    // ── Threads ──────────────────────────────────────────────────────────
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null

    // ── Playback queue ───────────────────────────────────────────────────
    private val playbackQueue = ConcurrentLinkedQueue<ByteArray>()

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Start capturing audio from the microphone.
     * Each chunk (100 ms / 3200 bytes) is delivered via [onAudioChunk].
     *
     * Requires [Manifest.permission.RECORD_AUDIO].
     */
    fun startCapture(onAudioChunk: (ByteArray) -> Unit) {
        if (capturing.get()) {
            Log.w(TAG, "Capture already running")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            CAPTURE_SAMPLE_RATE, CAPTURE_CHANNEL, CAPTURE_ENCODING
        )
        val bufferSize = maxOf(minBuf, CAPTURE_CHUNK_BYTES * 2)

        @Suppress("MissingPermission")   // checked above
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            CAPTURE_SAMPLE_RATE,
            CAPTURE_CHANNEL,
            CAPTURE_ENCODING,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialise")
            record.release()
            return
        }

        audioRecord = record
        capturing.set(true)

        captureThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            record.startRecording()
            Log.d(TAG, "Mic capture started")

            val shortBuf = ShortArray(CAPTURE_CHUNK_SAMPLES)

            while (capturing.get()) {
                val read = record.read(shortBuf, 0, CAPTURE_CHUNK_SAMPLES)
                if (read > 0) {
                    val byteArray = shortsToBytes(shortBuf, read)
                    onAudioChunk(byteArray)
                } else if (read < 0) {
                    Log.e(TAG, "AudioRecord.read error: $read")
                    break
                }
            }

            record.stop()
            Log.d(TAG, "Mic capture stopped")
        }, "AudioCapture").apply {
            isDaemon = true
            start()
        }
    }

    /** Stop microphone capture. Safe to call if not capturing. */
    fun stopCapture() {
        if (!capturing.getAndSet(false)) return
        captureThread?.join(500)
        captureThread = null
        audioRecord?.release()
        audioRecord = null
    }

    /**
     * Queue raw 24 kHz PCM data for playback.
     * Starts the playback thread automatically on first call.
     */
    fun playAudio(pcmData: ByteArray) {
        playbackQueue.add(pcmData)
        ensurePlaybackRunning()
    }

    /** Stop playback and clear the queue. */
    fun stopPlayback() {
        playing.set(false)
        playbackQueue.clear()
        playbackThread?.join(500)
        playbackThread = null
        audioTrack?.apply {
            stop()
            release()
        }
        audioTrack = null
    }

    fun isCapturing(): Boolean = capturing.get()

    fun isPlaying(): Boolean = playing.get()

    /** Release all resources. Call when the pipeline is no longer needed. */
    fun release() {
        stopCapture()
        stopPlayback()
    }

    // ── Playback internals ───────────────────────────────────────────────

    private fun ensurePlaybackRunning() {
        if (playing.get()) return

        val minBuf = AudioTrack.getMinBufferSize(
            PLAYBACK_SAMPLE_RATE, PLAYBACK_CHANNEL, PLAYBACK_ENCODING
        )
        val bufferSize = maxOf(minBuf, 4800 * 2)   // at least 100 ms at 24 kHz

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(PLAYBACK_SAMPLE_RATE)
                    .setChannelMask(PLAYBACK_CHANNEL)
                    .setEncoding(PLAYBACK_ENCODING)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack failed to initialise")
            track.release()
            return
        }

        audioTrack = track
        playing.set(true)

        playbackThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            track.play()
            Log.d(TAG, "Playback started")

            while (playing.get()) {
                val chunk = playbackQueue.poll()
                if (chunk != null) {
                    track.write(chunk, 0, chunk.size)
                } else {
                    // No data — sleep briefly to avoid busy-waiting
                    try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                }
            }

            track.stop()
            Log.d(TAG, "Playback stopped")
        }, "AudioPlayback").apply {
            isDaemon = true
            start()
        }
    }

    // ── Utilities ────────────────────────────────────────────────────────

    /**
     * Convert a [ShortArray] of PCM samples to a [ByteArray] in little-endian order.
     */
    private fun shortsToBytes(shorts: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = shorts[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = (s shr 8 and 0xFF).toByte()
        }
        return bytes
    }
}
