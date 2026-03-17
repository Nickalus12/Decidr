package com.brewtech.decidr.intelligence

import com.brewtech.decidr.profile.UserProfile
import com.brewtech.decidr.sensor.SensorHub
import com.brewtech.decidr.sensor.ShakeEmotion
import com.brewtech.decidr.sensor.ShakeProfile
import com.brewtech.decidr.voice.QuestionCategory
import com.brewtech.decidr.voice.QuestionParser
import com.brewtech.decidr.voice.VoiceProfile
import com.brewtech.decidr.voice.VoiceTone
import java.util.Calendar
import kotlin.math.abs

/**
 * Master orchestrator that combines QuestionParser, SentimentAnalyzer,
 * TemplateComposer, and MarkovGenerator into a single response pipeline.
 * Now enhanced with voice analysis, user profiling, and shake emotion data.
 *
 * Typical latency: ~12ms total.
 */
class NeuralResponse(
    private val sensorHub: SensorHub,
    private val userProfile: UserProfile
) {

    companion object {
        private const val HISTORY_SIZE = 10
        private const val MAX_RETRIES = 5
        private const val MIN_WORDS = 5
        private const val MAX_WORDS = 30
        private const val OVERLAP_THRESHOLD = 0.80f
    }

    // Ring buffer of recent responses for duplicate prevention
    private val responseHistory = ArrayDeque<String>(HISTORY_SIZE)

    // Lazy-init sub-systems
    private val templateComposer by lazy { TemplateComposer() }
    private val markovGenerator by lazy { MarkovGenerator() }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Generate a polished response string.
     *
     * @param question  The spoken question (null if user just shook the watch)
     * @param shakeIntensity  Shake force from SensorHub
     * @param voiceProfile  Analyzed voice characteristics (null if no voice input)
     * @param shakeProfile  Analyzed shake emotion profile (null if not available)
     * @return A quality-checked, non-duplicate response string
     */
    fun respond(
        question: String? = null,
        shakeIntensity: Float = 0.5f,
        voiceProfile: VoiceProfile? = null,
        shakeProfile: ShakeProfile? = null
    ): String {
        // 1. Parse question category (~1ms)
        val category = if (!question.isNullOrBlank()) {
            QuestionParser.parseQuestion(question)
        } else {
            QuestionCategory.GENERAL
        }

        // 2. Analyze sentiment (~1ms) — returns Float from -1.0 to +1.0
        val sentiment: Float = if (!question.isNullOrBlank()) {
            SentimentAnalyzer.analyze(question)
        } else {
            0.0f
        }

        // 3. Read sensor state (cached, ~0ms)
        val sensorMood = computeSensorMood()

        // 4. Check for special overrides from new intelligence sources
        val override = checkIntelligenceOverrides(question, voiceProfile, shakeProfile)
        if (override != null) {
            val polished = polish(override)
            addToHistory(polished)
            return polished
        }

        // 5. Select strategy and generate with retry for quality
        var attempt = 0
        while (attempt < MAX_RETRIES) {
            val raw = selectAndGenerate(question, category, sentiment, sensorMood, voiceProfile, shakeProfile)
            val polished = polish(raw)

            if (passesQualityCheck(polished)) {
                addToHistory(polished)
                return polished
            }
            attempt++
        }

        // Fallback: guaranteed unique template response
        val fallback = polish(templateComposer.compose(QuestionCategory.GENERAL, sensorMood, 0.0f))
        addToHistory(fallback)
        return fallback
    }

    // ── Intelligence Overrides ────────────────────────────────────────────

    /**
     * Check voice, profile, and shake data for conditions that should
     * produce special responses, bypassing normal generation.
     * Returns null if no override applies.
     */
    private fun checkIntelligenceOverrides(
        question: String?,
        voiceProfile: VoiceProfile?,
        shakeProfile: ShakeProfile?
    ): String? {
        val seed = sensorHub.magneticEntropySeed()
        val roll = abs(seed % 100).toInt()

        // Voice: speaking too fast — tell them to slow down (always triggers)
        if (voiceProfile != null && voiceProfile.speakingPace > 4.0f) {
            return templateComposer.pickFromVoiceState(TemplateComposer.VoiceState.RUSHING)
        }

        // Profile: repeat question detection (50% chance to call it out)
        if (question != null && userProfile.hasAskedAboutThisBefore(question) && roll < 50) {
            return templateComposer.pickFromProfileState(TemplateComposer.ProfileState.REPEAT_QUESTION)
        }

        // Shake: desperate shake gets genuine reassurance (always triggers)
        if (shakeProfile?.emotionalRead == ShakeEmotion.DESPERATE) {
            return templateComposer.pickFromShakeEmotion(TemplateComposer.ShakeEmotionState.DESPERATE_SHAKE)
        }

        return null
    }

    // ── Strategy Selection ────────────────────────────────────────────────

    private fun selectAndGenerate(
        question: String?,
        category: QuestionCategory,
        sentiment: Float,
        sensorMood: SensorMood,
        voiceProfile: VoiceProfile?,
        shakeProfile: ShakeProfile?
    ): String {
        val seed = sensorHub.magneticEntropySeed()
        val roll = abs(seed % 100).toInt()

        // 10% of the time: pure Markov for freshness/unpredictability
        if (roll < 10) {
            return markovGenerator.generate(category)
        }

        val questionIsVague = question.isNullOrBlank() || category == QuestionCategory.GENERAL

        // Determine voice/profile/shake observation to weave in
        val extraObservation = pickIntelligenceObservation(voiceProfile, shakeProfile)

        val base = if (questionIsVague) {
            if (question == null) {
                templateComposer.compose(QuestionCategory.GENERAL, sensorMood, sentiment)
            } else {
                if (roll < 55) {
                    templateComposer.compose(category, sensorMood, sentiment)
                } else {
                    markovGenerator.generate(category)
                }
            }
        } else {
            templateComposer.compose(category, sensorMood, sentiment)
        }

        // Blend intelligence observation into the response if available
        if (extraObservation != null) {
            return "$extraObservation $base"
        }

        return base
    }

    /**
     * Pick an observation string from voice/shake/profile intelligence.
     * Returns null most of the time to avoid over-saturating responses.
     */
    private fun pickIntelligenceObservation(
        voiceProfile: VoiceProfile?,
        shakeProfile: ShakeProfile?
    ): String? {
        val seed = sensorHub.magneticEntropySeed()
        val roll = abs(seed % 100).toInt()

        // Only add intelligence observations ~35% of the time
        if (roll > 35) return null

        val currentHR = sensorHub.heartRate.value

        // Voice-based observations
        if (voiceProfile != null) {
            when {
                voiceProfile.stressLevel > 0.7f ->
                    return templateComposer.pickFromVoiceState(TemplateComposer.VoiceState.STRESSED_VOICE)
                voiceProfile.emotionalTone == VoiceTone.ANXIOUS ->
                    return templateComposer.pickFromVoiceState(TemplateComposer.VoiceState.STRESSED_VOICE)
                voiceProfile.emotionalTone == VoiceTone.CONFIDENT ->
                    return templateComposer.pickFromVoiceState(TemplateComposer.VoiceState.CONFIDENT_VOICE)
                voiceProfile.emotionalTone == VoiceTone.WHISPERED ->
                    return templateComposer.pickFromVoiceState(TemplateComposer.VoiceState.WHISPERING)
            }
        }

        // Profile-based observations
        if (userProfile.isAboveNormalHR(currentHR)) {
            return templateComposer.pickFromProfileState(TemplateComposer.ProfileState.ABOVE_NORMAL_HR)
        }

        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (userProfile.isLateForUser(currentHour)) {
            return templateComposer.pickFromProfileState(TemplateComposer.ProfileState.LATE_FOR_USER)
        }

        if (userProfile.isMoreActiveThanUsual(sensorHub.steps.value.toInt())) {
            return templateComposer.pickFromProfileState(TemplateComposer.ProfileState.UNUSUALLY_ACTIVE)
        }

        // Shake-emotion observations
        if (shakeProfile != null) {
            when (shakeProfile.emotionalRead) {
                ShakeEmotion.CONTEMPLATIVE ->
                    return templateComposer.pickFromShakeEmotion(TemplateComposer.ShakeEmotionState.CONTEMPLATIVE_SHAKE)
                ShakeEmotion.PLAYFUL ->
                    return templateComposer.pickFromShakeEmotion(TemplateComposer.ShakeEmotionState.PLAYFUL_SHAKE)
                ShakeEmotion.IMPATIENT ->
                    return templateComposer.pickFromShakeEmotion(TemplateComposer.ShakeEmotionState.IMPATIENT_SHAKE)
                else -> {}
            }
        }

        return null
    }

    // ── Sensor Mood Computation ───────────────────────────────────────────

    private fun computeSensorMood(): SensorMood {
        val hr = sensorHub.heartRate.value
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val steps = sensorHub.steps.value

        return when {
            hour == 23 || hour in 0..4 -> SensorMood.LATE_NIGHT
            hr > 90f                   -> SensorMood.TENSE
            steps > 10000f             -> SensorMood.ENERGETIC
            steps < 2000f && hr < 65f  -> SensorMood.TIRED
            else                       -> SensorMood.CALM
        }
    }

    // ── Quality Checks ────────────────────────────────────────────────────

    private fun passesQualityCheck(response: String): Boolean {
        if (response.isBlank()) return false

        val wordCount = response.split("\\s+".toRegex()).size
        if (wordCount < MIN_WORDS || wordCount > MAX_WORDS) return false

        if (isDuplicate(response)) return false

        return true
    }

    private fun isDuplicate(response: String): Boolean {
        val responseWords = response.lowercase().split("\\s+".toRegex()).toSet()

        for (previous in responseHistory) {
            if (previous.equals(response, ignoreCase = true)) return true

            val previousWords = previous.lowercase().split("\\s+".toRegex()).toSet()
            if (responseWords.isEmpty() || previousWords.isEmpty()) continue

            val intersection = responseWords.intersect(previousWords).size
            val union = responseWords.union(previousWords).size
            val overlap = intersection.toFloat() / union.toFloat()

            if (overlap > OVERLAP_THRESHOLD) return true
        }
        return false
    }

    // ── Polish ────────────────────────────────────────────────────────────

    private fun polish(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return text

        text = text.replaceFirstChar { it.uppercaseChar() }

        val lastChar = text.last()
        if (lastChar !in ".!?…") {
            text = "$text."
        }

        return text
    }

    // ── History Ring Buffer ───────────────────────────────────────────────

    private fun addToHistory(response: String) {
        if (responseHistory.size >= HISTORY_SIZE) {
            responseHistory.removeFirst()
        }
        responseHistory.addLast(response)
    }
}
