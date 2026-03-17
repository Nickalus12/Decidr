package com.brewtech.decidr.agent

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebSocket client for the Gemini Live (BidiGenerateContent) streaming API.
 *
 * Connects over WSS, sends a setup message to configure the model,
 * then streams raw PCM audio bidirectionally.
 *
 * - Outbound: 16 kHz, 16-bit, mono PCM encoded as base64
 * - Inbound:  24 kHz, 16-bit, mono PCM decoded from base64
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val systemPrompt: String
) {

    companion object {
        private const val TAG = "GeminiLive"
        private const val MAX_RETRIES = 3
        private const val BASE_ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val MODEL = "models/gemini-2.0-flash-live-001"
        private const val VOICE = "Kore"
    }

    // ── Callbacks ────────────────────────────────────────────────────────
    private var onConnected: (() -> Unit)? = null
    private var onAudioReceived: ((ByteArray) -> Unit)? = null
    private var onTextReceived: ((String) -> Unit)? = null
    private var onTurnComplete: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var onDisconnected: (() -> Unit)? = null

    // ── State ────────────────────────────────────────────────────────────
    private val connected = AtomicBoolean(false)
    private val setupSent = AtomicBoolean(false)
    private val retryCount = AtomicInteger(0)
    private val intentionalClose = AtomicBoolean(false)

    private var webSocket: WebSocket? = null
    private val audioBuffer = ConcurrentLinkedQueue<ByteArray>()

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)   // no read timeout for streaming
        .pingInterval(20, TimeUnit.SECONDS)       // keep-alive
        .build()

    // ── Public API ───────────────────────────────────────────────────────

    fun connect(
        onConnected: () -> Unit,
        onAudioReceived: (ByteArray) -> Unit,
        onTextReceived: (String) -> Unit,
        onTurnComplete: () -> Unit,
        onError: (String) -> Unit,
        onDisconnected: () -> Unit
    ) {
        this.onConnected = onConnected
        this.onAudioReceived = onAudioReceived
        this.onTextReceived = onTextReceived
        this.onTurnComplete = onTurnComplete
        this.onError = onError
        this.onDisconnected = onDisconnected

        intentionalClose.set(false)
        retryCount.set(0)
        openWebSocket()
    }

    /** Send raw 16 kHz PCM audio to Gemini. Thread-safe. */
    fun sendAudio(pcmData: ByteArray) {
        if (!connected.get() || !setupSent.get()) {
            // Buffer until the session is ready
            audioBuffer.add(pcmData)
            return
        }
        sendAudioMessage(pcmData)
    }

    /** Send a text message (alternative to voice). */
    fun sendText(text: String) {
        if (!connected.get() || !setupSent.get()) {
            Log.w(TAG, "sendText called before session ready — dropping")
            return
        }
        val msg = JSONObject().apply {
            put("clientContent", JSONObject().apply {
                put("turns", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", text)
                    }))
                }))
                put("turnComplete", true)
            })
        }
        webSocket?.send(msg.toString())
    }

    fun disconnect() {
        intentionalClose.set(true)
        webSocket?.close(1000, "Client disconnect")
        cleanup()
    }

    fun isConnected(): Boolean = connected.get()

    // ── WebSocket Lifecycle ──────────────────────────────────────────────

    private fun openWebSocket() {
        val url = "$BASE_ENDPOINT?key=$apiKey"
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, Listener())
        Log.d(TAG, "Opening WebSocket connection…")
    }

    private inner class Listener : WebSocketListener() {

        override fun onOpen(ws: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket opened")
            connected.set(true)
            retryCount.set(0)
            sendSetup(ws)
        }

        override fun onMessage(ws: WebSocket, text: String) {
            try {
                handleServerMessage(text)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing server message", e)
            }
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $code $reason")
            ws.close(1000, null)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code $reason")
            cleanup()
            onDisconnected?.invoke()
            maybeReconnect()
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message}", t)
            cleanup()
            onError?.invoke(t.message ?: "WebSocket connection failed")
            maybeReconnect()
        }
    }

    // ── Setup ────────────────────────────────────────────────────────────

    private fun sendSetup(ws: WebSocket) {
        val setup = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", MODEL)
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", VOICE)
                            })
                        })
                    })
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", systemPrompt)
                    }))
                })
            })
        }

        val sent = ws.send(setup.toString())
        if (sent) {
            Log.d(TAG, "Setup message sent")
            setupSent.set(true)
            onConnected?.invoke()
            flushAudioBuffer()
        } else {
            Log.e(TAG, "Failed to send setup message")
            onError?.invoke("Failed to send setup message")
        }
    }

    // ── Inbound Message Handling ─────────────────────────────────────────

    private fun handleServerMessage(raw: String) {
        val json = JSONObject(raw)

        // ── setupComplete ────────────────────────────────────────────────
        if (json.has("setupComplete")) {
            Log.d(TAG, "Session setup confirmed by server")
            return
        }

        // ── serverContent ────────────────────────────────────────────────
        val serverContent = json.optJSONObject("serverContent") ?: return

        // Check for turn completion
        if (serverContent.optBoolean("turnComplete", false)) {
            onTurnComplete?.invoke()
        }

        // Extract audio / text from modelTurn.parts[]
        val modelTurn = serverContent.optJSONObject("modelTurn") ?: return
        val parts = modelTurn.optJSONArray("parts") ?: return

        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)

            // Inline audio data
            val inlineData = part.optJSONObject("inlineData")
            if (inlineData != null) {
                val b64 = inlineData.optString("data", "")
                if (b64.isNotEmpty()) {
                    val pcm = Base64.decode(b64, Base64.NO_WRAP)
                    onAudioReceived?.invoke(pcm)
                }
            }

            // Text part (transcription or text response)
            val text = part.optString("text", "")
            if (text.isNotEmpty()) {
                onTextReceived?.invoke(text)
            }
        }
    }

    // ── Outbound Audio ───────────────────────────────────────────────────

    private fun sendAudioMessage(pcmData: ByteArray) {
        val b64 = Base64.encodeToString(pcmData, Base64.NO_WRAP)
        val msg = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("mediaChunks", JSONArray().put(JSONObject().apply {
                    put("mimeType", "audio/pcm;rate=16000")
                    put("data", b64)
                }))
            })
        }
        webSocket?.send(msg.toString())
    }

    private fun flushAudioBuffer() {
        while (audioBuffer.isNotEmpty()) {
            val chunk = audioBuffer.poll() ?: break
            sendAudioMessage(chunk)
        }
    }

    // ── Reconnection ─────────────────────────────────────────────────────

    private fun maybeReconnect() {
        if (intentionalClose.get()) return
        val attempt = retryCount.incrementAndGet()
        if (attempt > MAX_RETRIES) {
            Log.w(TAG, "Max reconnect attempts reached ($MAX_RETRIES)")
            onError?.invoke("Connection lost after $MAX_RETRIES retries")
            return
        }
        Log.d(TAG, "Reconnecting (attempt $attempt/$MAX_RETRIES)…")
        val delayMs = (attempt * 1000).toLong()   // linear back-off: 1s, 2s, 3s
        Thread {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) { return@Thread }
            if (!intentionalClose.get()) {
                openWebSocket()
            }
        }.start()
    }

    // ── Cleanup ──────────────────────────────────────────────────────────

    private fun cleanup() {
        connected.set(false)
        setupSent.set(false)
    }
}
