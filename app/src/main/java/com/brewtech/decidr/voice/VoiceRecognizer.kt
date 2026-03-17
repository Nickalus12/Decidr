package com.brewtech.decidr.voice

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale

/**
 * Wrapper around Android's RecognizerIntent for speech-to-text on Wear OS.
 * Uses ACTION_RECOGNIZE_SPEECH which is the most reliable approach on Galaxy Watch.
 */
class VoiceRecognizer(activity: ComponentActivity) {

    private var onResult: ((String) -> Unit)? = null
    private var onError: (() -> Unit)? = null

    private val launcher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val spokenText = result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()

                if (!spokenText.isNullOrBlank()) {
                    onResult?.invoke(spokenText)
                } else {
                    onError?.invoke()
                }
            } else {
                onError?.invoke()
            }
            // Clear callbacks after use
            onResult = null
            onError = null
        }

    /**
     * Launches the speech recognition activity.
     *
     * @param onResult called with the recognized text on success
     * @param onError called when no speech is recognized or the user cancels
     */
    fun startListening(onResult: (String) -> Unit, onError: () -> Unit) {
        this.onResult = onResult
        this.onError = onError

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask your question...")
        }

        try {
            launcher.launch(intent)
        } catch (e: Exception) {
            onError()
            this.onResult = null
            this.onError = null
        }
    }
}
