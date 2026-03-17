package com.brewtech.decidr

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.brewtech.decidr.ui.theme.DecidrTheme
import com.brewtech.decidr.voice.VoiceAnalyzer
import com.brewtech.decidr.voice.VoiceProfile
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var shakeDetector: ShakeDetector
    private lateinit var sensorHub: SensorHub
    private lateinit var hapticEngine: HapticEngine
    private lateinit var userProfile: UserProfile
    private lateinit var shakeAnalyzer: ShakeAnalyzer
    private lateinit var voiceAnalyzer: VoiceAnalyzer

    // Voice recognition
    private lateinit var voiceLauncher: ActivityResultLauncher<Intent>
    private var spokenQuestion by mutableStateOf<String?>(null)
    private var voiceResultCallback: ((String) -> Unit)? = null

    // Intelligence state passed to composables
    private var currentVoiceProfile by mutableStateOf<VoiceProfile?>(null)
    private var currentShakeProfile by mutableStateOf<ShakeProfile?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        shakeDetector = ShakeDetector(this)
        sensorHub = SensorHub(this)
        hapticEngine = HapticEngine(this)
        userProfile = UserProfile(this)
        shakeAnalyzer = ShakeAnalyzer(this)
        voiceAnalyzer = VoiceAnalyzer(this)

        // Register voice recognition launcher (must be done before STARTED)
        voiceLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            // Stop voice frequency analysis and capture final profile
            val finalVoiceProfile = voiceAnalyzer.stopCapture()
            if (finalVoiceProfile != null) {
                currentVoiceProfile = finalVoiceProfile
            }

            if (result.resultCode == Activity.RESULT_OK) {
                val matches = result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val question = matches?.firstOrNull()
                if (!question.isNullOrBlank()) {
                    spokenQuestion = question
                    voiceResultCallback?.invoke(question)
                }
            }
        }

        // Collect shake events from ShakeAnalyzer into compose state
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
                    spokenQuestion = spokenQuestion,
                    voiceProfile = currentVoiceProfile,
                    shakeProfile = currentShakeProfile,
                    onStartVoiceInput = { startVoiceRecognition() },
                    onClearQuestion = { spokenQuestion = null },
                    onResponseGenerated = { question, response ->
                        // Update user profile after each 8-ball response
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
                    },
                    onVoiceProfileUpdated = { profile -> currentVoiceProfile = profile },
                    onShakeProfileUpdated = { profile -> currentShakeProfile = profile }
                )
            }
        }
    }

    private fun startVoiceRecognition() {
        // Start voice frequency analysis in parallel with speech recognition
        voiceAnalyzer.startCapture { profile ->
            currentVoiceProfile = profile
        }

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask your question...")
            }
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            // Voice recognition not available — stop capture and silently fail
            voiceAnalyzer.stopCapture()
        }
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
    }
}

@Composable
fun DecidrNavHost(
    shakeDetector: ShakeDetector,
    sensorHub: SensorHub,
    hapticEngine: HapticEngine,
    userProfile: UserProfile,
    spokenQuestion: String?,
    voiceProfile: VoiceProfile?,
    shakeProfile: ShakeProfile?,
    onStartVoiceInput: () -> Unit,
    onClearQuestion: () -> Unit,
    onResponseGenerated: (question: String?, response: String) -> Unit,
    onVoiceProfileUpdated: (VoiceProfile?) -> Unit,
    onShakeProfileUpdated: (ShakeProfile?) -> Unit
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
                onMagicBall = { navController.navigate("magic_ball") }
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
    }
}
