package com.brewtech.decidr

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.brewtech.decidr.haptic.HapticEngine
import com.brewtech.decidr.profile.UserProfile
import com.brewtech.decidr.sensor.SensorHub
import com.brewtech.decidr.sensor.ShakeAnalyzer
import com.brewtech.decidr.sensor.ShakeDetector
import com.brewtech.decidr.sensor.ShakeProfile
import com.brewtech.decidr.ui.CoinFlipScreen
import com.brewtech.decidr.ui.DiceRollScreen
import com.brewtech.decidr.ui.HomeScreen
import com.brewtech.decidr.ui.MagicBallScreen
import com.brewtech.decidr.ui.WheelSpinScreen
import com.brewtech.decidr.agent.AgentController
import com.brewtech.decidr.agent.AgentScreen
import com.brewtech.decidr.ui.theme.DecidrTheme
import com.brewtech.decidr.voice.VoiceAnalyzer
import com.brewtech.decidr.voice.VoiceProfile
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var shakeDetector: ShakeDetector
    private lateinit var sensorHub: SensorHub
    private lateinit var hapticEngine: HapticEngine
    private lateinit var userProfile: UserProfile
    private lateinit var shakeAnalyzer: ShakeAnalyzer
    private lateinit var voiceAnalyzer: VoiceAnalyzer
    private lateinit var agentController: AgentController

    // Native speech recognizer (no UI overlay)
    private var speechRecognizer: SpeechRecognizer? = null

    // State passed to composables
    private var spokenQuestion by mutableStateOf<String?>(null)
    private var currentVoiceProfile by mutableStateOf<VoiceProfile?>(null)
    private var currentShakeProfile by mutableStateOf<ShakeProfile?>(null)
    private var isListening by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        shakeDetector = ShakeDetector(this)
        sensorHub = SensorHub(this)
        hapticEngine = HapticEngine(this)
        userProfile = UserProfile(this)
        shakeAnalyzer = ShakeAnalyzer(this)
        voiceAnalyzer = VoiceAnalyzer(this)
        agentController = AgentController(this, sensorHub)

        // Create native speech recognizer (no UI)
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(createRecognitionListener())
        }

        // Collect shake events
        lifecycleScope.launch {
            shakeAnalyzer.shakeEvents().collect { profile ->
                currentShakeProfile = profile
            }
        }

        setContent {
            DecidrTheme {
                DecidrNavHost(
                    shakeDetector = shakeDetector,
                    sensorHub = sensorHub,
                    hapticEngine = hapticEngine,
                    userProfile = userProfile,
                    agentController = agentController,
                    spokenQuestion = spokenQuestion,
                    voiceProfile = currentVoiceProfile,
                    shakeProfile = currentShakeProfile,
                    isListening = isListening,
                    onStartVoiceInput = { startNativeListening() },
                    onClearQuestion = { spokenQuestion = null },
                    onResponseGenerated = { question, response ->
                        userProfile.updateHeartRate(sensorHub.heartRate.value)
                        userProfile.updateSteps(sensorHub.steps.value.toInt())
                        currentVoiceProfile?.let { vp ->
                            userProfile.updateVoicePitch(vp.pitchHz)
                        }
                        userProfile.updateShakeIntensity(sensorHub.shakeIntensity.value)
                        if (question != null) {
                            userProfile.addQuestion(question, response)
                        }
                        userProfile.save()
                    }
                )
            }
        }
    }

    private fun startNativeListening() {
        if (isListening) return

        // Start voice frequency analysis regardless
        voiceAnalyzer.startCapture { profile ->
            currentVoiceProfile = profile
        }

        isListening = true
        spokenQuestion = null
        hapticEngine.lightTap()

        val recognizer = speechRecognizer
        if (recognizer == null) {
            // No speech recognizer available — still capture audio for voice analysis
            // Auto-stop after 3 seconds
            lifecycleScope.launch {
                kotlinx.coroutines.delay(3000)
                isListening = false
                val finalProfile = voiceAnalyzer.stopCapture()
                if (finalProfile != null) currentVoiceProfile = finalProfile
            }
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            voiceAnalyzer.stopCapture()
        }
    }

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            isListening = false
            val finalProfile = voiceAnalyzer.stopCapture()
            if (finalProfile != null) currentVoiceProfile = finalProfile

            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val question = matches?.firstOrNull()
            if (!question.isNullOrBlank()) {
                spokenQuestion = question
            }
            hapticEngine.lightTap()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!partial.isNullOrBlank()) {
                spokenQuestion = partial
            }
        }

        override fun onError(error: Int) {
            isListening = false
            voiceAnalyzer.stopCapture()
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun onResume() {
        super.onResume()
        shakeDetector.start()
        sensorHub.start()
        shakeAnalyzer.start()
    }

    override fun onPause() {
        super.onPause()
        shakeDetector.stop()
        sensorHub.stop()
        shakeAnalyzer.stop()
        agentController.stop()
        if (isListening) {
            speechRecognizer?.stopListening()
            voiceAnalyzer.stopCapture()
            isListening = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        agentController.release()
    }
}

@Composable
fun DecidrNavHost(
    shakeDetector: ShakeDetector,
    sensorHub: SensorHub,
    hapticEngine: HapticEngine,
    userProfile: UserProfile,
    agentController: AgentController,
    spokenQuestion: String?,
    voiceProfile: VoiceProfile?,
    shakeProfile: ShakeProfile?,
    isListening: Boolean,
    onStartVoiceInput: () -> Unit,
    onClearQuestion: () -> Unit,
    onResponseGenerated: (question: String?, response: String) -> Unit
) {
    val navController = rememberSwipeDismissableNavController()

    SwipeDismissableNavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen(
                onCoinFlip = { navController.navigate("coin_flip") },
                onWheelSpin = { navController.navigate("wheel_spin") },
                onDiceRoll = { navController.navigate("dice_roll") },
                onMagicBall = { navController.navigate("magic_ball") },
                onLuminaAgent = { navController.navigate("lumina") }
            )
        }
        composable("coin_flip") {
            CoinFlipScreen(shakeDetector = shakeDetector, hapticEngine = hapticEngine)
        }
        composable("wheel_spin") {
            WheelSpinScreen(hapticEngine = hapticEngine)
        }
        composable("dice_roll") {
            DiceRollScreen(shakeDetector = shakeDetector, hapticEngine = hapticEngine)
        }
        composable("magic_ball") {
            MagicBallScreen(
                hapticEngine = hapticEngine,
                sensorHub = sensorHub,
                userProfile = userProfile,
                onStartVoiceInput = onStartVoiceInput,
                spokenQuestion = spokenQuestion,
                voiceProfile = voiceProfile,
                shakeProfile = shakeProfile,
                onResponseGenerated = onResponseGenerated
            )
        }
        composable("lumina") {
            val state by agentController.agentState.collectAsState()
            val micLvl by agentController.micLevel.collectAsState()
            val speakerLvl by agentController.speakerLevel.collectAsState()
            val transcriptEntries by agentController.transcript.collectAsState()

            // Start agent when entering screen, stop when leaving
            androidx.compose.runtime.DisposableEffect(Unit) {
                agentController.start()
                onDispose {
                    agentController.stop()
                }
            }

            AgentScreen(
                state = state,
                micLevel = micLvl,
                speakerLevel = speakerLvl,
                transcript = transcriptEntries,
                onTapToTalk = { agentController.tapToTalk() },
                onStop = {
                    agentController.stop()
                    navController.popBackStack()
                }
            )
        }
    }
}
