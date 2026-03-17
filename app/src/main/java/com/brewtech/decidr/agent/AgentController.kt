package com.brewtech.decidr.agent

import android.content.Context
import android.util.Log
import com.brewtech.decidr.sensor.SensorHub
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

class AgentController(
    private val context: Context,
    private val sensorHub: SensorHub,
    private val apiKey: String = "AIzaSyDrl7YDUghZiF99_1SKpQQ-NszF654OsHg"
) {
    companion object {
        private const val TAG = "AgentController"
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val CONTEXT_UPDATE_INTERVAL_MS = 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _agentState = MutableStateFlow(AgentState.IDLE)
    val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val _speakerLevel = MutableStateFlow(0f)
    val speakerLevel: StateFlow<Float> = _speakerLevel.asStateFlow()

    private val _transcript = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val transcript: StateFlow<List<TranscriptEntry>> = _transcript.asStateFlow()

    private var geminiClient: GeminiLiveClient? = null
    private var audioPipeline: AudioPipeline? = null
    private var sensorContext: SensorContext? = null
    private var contextUpdateJob: Job? = null
    private var reconnectAttempts = 0
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        reconnectAttempts = 0
        _agentState.value = AgentState.CONNECTING

        sensorContext = SensorContext(sensorHub, context)
        audioPipeline = AudioPipeline(context)

        connect()
    }

    private fun connect() {
        val sc = sensorContext ?: return
        val systemPrompt = sc.generateSystemPrompt()

        geminiClient = GeminiLiveClient(apiKey, systemPrompt)
        geminiClient?.connect(
            onConnected = {
                scope.launch {
                    Log.d(TAG, "Connected to Gemini")
                    reconnectAttempts = 0
                    _agentState.value = AgentState.LISTENING
                    startAudioCapture()
                    startContextUpdates()
                }
            },
            onAudioReceived = { audioData ->
                scope.launch {
                    _agentState.value = AgentState.SPEAKING
                    _speakerLevel.value = computeRms(audioData)
                    audioPipeline?.playAudio(audioData)
                }
            },
            onTextReceived = { text ->
                scope.launch {
                    addTranscriptEntry(text, isUser = false)
                }
            },
            onTurnComplete = {
                scope.launch {
                    _speakerLevel.value = 0f
                    if (isRunning) {
                        _agentState.value = AgentState.LISTENING
                    }
                }
            },
            onError = { error ->
                scope.launch {
                    Log.e(TAG, "Gemini error: $error")
                    handleDisconnect()
                }
            },
            onDisconnected = {
                scope.launch {
                    Log.d(TAG, "Disconnected from Gemini")
                    handleDisconnect()
                }
            }
        )
    }

    private fun startAudioCapture() {
        try {
            audioPipeline?.startCapture { chunk ->
                _micLevel.value = computeRms(chunk)
                geminiClient?.sendAudio(chunk)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mic capture", e)
            _agentState.value = AgentState.ERROR
        }
    }

    private fun startContextUpdates() {
        contextUpdateJob?.cancel()
        contextUpdateJob = scope.launch {
            while (isActive && isRunning) {
                delay(CONTEXT_UPDATE_INTERVAL_MS)
                val update = sensorContext?.generateContextUpdate() ?: continue
                geminiClient?.sendText(update)
            }
        }
    }

    private fun handleDisconnect() {
        if (!isRunning) return

        if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            Log.d(TAG, "Reconnecting attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS")
            _agentState.value = AgentState.CONNECTING
            stopAudioAndContext()
            scope.launch {
                delay(1000L * reconnectAttempts) // backoff
                if (isRunning) connect()
            }
        } else {
            Log.e(TAG, "Max reconnect attempts reached")
            _agentState.value = AgentState.ERROR
            stopInternal()
        }
    }

    fun tapToTalk() {
        when (_agentState.value) {
            AgentState.LISTENING -> {
                // Stop capturing — let Gemini process what it has
                audioPipeline?.stopCapture()
                _micLevel.value = 0f
                _agentState.value = AgentState.THINKING
            }
            AgentState.SPEAKING -> {
                // Interrupt playback, go back to listening
                audioPipeline?.stopPlayback()
                _speakerLevel.value = 0f
                _agentState.value = AgentState.LISTENING
                startAudioCapture()
            }
            AgentState.THINKING, AgentState.IDLE -> {
                // Resume listening
                _agentState.value = AgentState.LISTENING
                startAudioCapture()
            }
            else -> { /* CONNECTING or ERROR — ignore tap */ }
        }
    }

    fun stop() {
        isRunning = false
        stopInternal()
        _agentState.value = AgentState.IDLE
    }

    private fun stopInternal() {
        stopAudioAndContext()
        geminiClient?.disconnect()
        geminiClient = null
    }

    private fun stopAudioAndContext() {
        contextUpdateJob?.cancel()
        contextUpdateJob = null
        audioPipeline?.stopCapture()
        audioPipeline?.stopPlayback()
        _micLevel.value = 0f
        _speakerLevel.value = 0f
    }

    fun release() {
        isRunning = false
        stopInternal()
        audioPipeline?.release()
        audioPipeline = null
        sensorContext = null
        scope.cancel()
    }

    private fun addTranscriptEntry(text: String, isUser: Boolean) {
        val current = _transcript.value.toMutableList()
        current.add(TranscriptEntry(text = text, isUser = isUser, timestamp = System.currentTimeMillis()))
        _transcript.value = current
    }

    private fun computeRms(audioData: ByteArray): Float {
        if (audioData.size < 2) return 0f
        var sumSquares = 0.0
        val sampleCount = audioData.size / 2
        for (i in 0 until sampleCount) {
            val low = audioData[i * 2].toInt() and 0xFF
            val high = audioData[i * 2 + 1].toInt()
            val sample = (high shl 8) or low // 16-bit PCM little-endian
            sumSquares += (sample * sample).toDouble()
        }
        val rms = sqrt(sumSquares / sampleCount)
        // Normalize to 0..1 range (16-bit max is 32768)
        return (rms / 32768.0).toFloat().coerceIn(0f, 1f)
    }
}
