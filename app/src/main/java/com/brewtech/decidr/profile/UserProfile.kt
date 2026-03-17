package com.brewtech.decidr.profile

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.abs

/**
 * Persistent user profile that learns personal baselines over time using
 * exponential moving averages. Baselines stabilize after ~5 data points
 * and continuously adapt with alpha=0.1 weighting.
 *
 * All baseline reads are safe from any thread. Mutations should happen
 * from a single thread (typically the main/sensor callback thread).
 * Call [save] after a batch of updates to persist to SharedPreferences.
 */
class UserProfile(context: Context) {

    companion object {
        private const val PREFS_KEY = "decidr_user_profile"

        // EMA smoothing factor: new value gets 10% weight
        private const val EMA_ALPHA = 0.1f

        // Minimum data points before baselines are considered learned
        private const val MIN_DATA_POINTS = 5

        // Deviation thresholds
        private const val HR_HIGH_THRESHOLD = 0.15f   // 15% above baseline
        private const val HR_LOW_THRESHOLD = 0.15f     // 15% below baseline
        private const val STEP_HIGH_THRESHOLD = 0.30f  // 30% above baseline
        private const val STEP_LOW_THRESHOLD = 0.30f   // 30% below baseline
        private const val VOICE_STRESS_THRESHOLD = 0.20f // 20% above baseline

        // Question tracking
        private const val MAX_RECENT_QUESTIONS = 20
        private const val JACCARD_SIMILARITY_THRESHOLD = 0.5f
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)

    // ── Baselines (learned via EMA) ─────────────────────────────────────

    @Volatile var restingHR: Float = 0f; private set
    @Volatile var typicalHRV: Float = 0f; private set
    @Volatile var avgDailySteps: Int = 0; private set
    @Volatile var voicePitchBaseline: Float = 0f; private set
    @Volatile var typicalShakeIntensity: Float = 0f; private set
    @Volatile var activeHoursStart: Int = 8; private set
    @Volatile var activeHoursEnd: Int = 22; private set

    // ── Data point counts (per metric) ──────────────────────────────────

    private var hrDataPoints: Int = 0
    private var stepsDataPoints: Int = 0
    private var voiceDataPoints: Int = 0
    private var shakeDataPoints: Int = 0
    private var activityHourDataPoints: Int = 0

    // ── History ─────────────────────────────────────────────────────────

    val recentQuestions: MutableList<String> = mutableListOf()
    val questionCategories: MutableMap<String, Int> = mutableMapOf()
    var sessionCount: Int = 0; private set

    // Tracks today's step total for daily comparison
    private var todaySteps: Int = 0
    private var todayDate: String = ""

    init {
        load()
    }

    // ── Comparison functions ────────────────────────────────────────────

    /** True if current HR is >15% above learned resting baseline. */
    fun isAboveNormalHR(currentHR: Float): Boolean {
        if (!isBaselineLearned(hrDataPoints)) return false
        return currentHR > restingHR + (restingHR * HR_HIGH_THRESHOLD)
    }

    /** True if current HR is >15% below learned resting baseline. */
    fun isBelowNormalHR(currentHR: Float): Boolean {
        if (!isBaselineLearned(hrDataPoints)) return false
        return currentHR < restingHR - (restingHR * HR_LOW_THRESHOLD)
    }

    /** True if current step count is >30% above daily average. */
    fun isMoreActiveThanUsual(currentSteps: Int): Boolean {
        if (!isBaselineLearned(stepsDataPoints) || avgDailySteps == 0) return false
        return currentSteps > avgDailySteps + (avgDailySteps * STEP_HIGH_THRESHOLD).toInt()
    }

    /** True if current step count is >30% below daily average. */
    fun isLessActiveThanUsual(currentSteps: Int): Boolean {
        if (!isBaselineLearned(stepsDataPoints) || avgDailySteps == 0) return false
        return currentSteps < avgDailySteps - (avgDailySteps * STEP_LOW_THRESHOLD).toInt()
    }

    /** True if the current hour is past the user's typical end-of-activity + 1 hour. */
    fun isLateForUser(currentHour: Int): Boolean {
        if (!isBaselineLearned(activityHourDataPoints)) return false
        return currentHour > activeHoursEnd + 1
    }

    /** True if current voice pitch is >20% above their baseline pitch. */
    fun isVoiceStressedVsBaseline(currentPitch: Float): Boolean {
        if (!isBaselineLearned(voiceDataPoints) || voicePitchBaseline == 0f) return false
        return currentPitch > voicePitchBaseline * (1f + VOICE_STRESS_THRESHOLD)
    }

    /**
     * Checks if the user has asked a similar question before using
     * Jaccard similarity on word sets (threshold: 0.5).
     */
    fun hasAskedAboutThisBefore(question: String): Boolean {
        val queryWords = tokenize(question)
        if (queryWords.isEmpty()) return false
        return recentQuestions.any { previous ->
            jaccardSimilarity(queryWords, tokenize(previous)) >= JACCARD_SIMILARITY_THRESHOLD
        }
    }

    /** Returns the category the user asks about most, or null. */
    fun getMostAskedCategory(): String? {
        return questionCategories.maxByOrNull { it.value }?.key
    }

    /**
     * Returns a contextual personal insight based on current state, or null
     * if nothing notable is detected.
     */
    fun getPersonalInsight(): String? {
        val now = Calendar.getInstance()
        val currentHour = now.get(Calendar.HOUR_OF_DAY)

        // Check late night
        if (isLateForUser(currentHour)) {
            val hoursLate = currentHour - activeHoursEnd
            return "You're up $hoursLate hour${if (hoursLate != 1) "s" else ""} past your normal bedtime."
        }

        // Check category frequency
        val topCategory = getMostAskedCategory()
        if (topCategory != null) {
            val count = questionCategories[topCategory] ?: 0
            if (count >= 3) {
                return "You've asked about $topCategory $count times."
            }
        }

        // Check step deviation
        if (isMoreActiveThanUsual(todaySteps)) {
            return "You've been way more active than usual today."
        }
        if (isLessActiveThanUsual(todaySteps)) {
            return "You've been less active than usual today."
        }

        // Check repeat question (most recent)
        if (recentQuestions.size >= 2) {
            val last = recentQuestions.last()
            val previous = recentQuestions.dropLast(1)
            if (previous.any { jaccardSimilarity(tokenize(last), tokenize(it)) >= JACCARD_SIMILARITY_THRESHOLD }) {
                return "You asked this same question before."
            }
        }

        return null
    }

    /**
     * Returns a personal insight that incorporates a live heart rate reading,
     * in addition to the standard insight checks.
     */
    fun getPersonalInsight(currentHR: Float): String? {
        if (isAboveNormalHR(currentHR) && restingHR > 0f) {
            val pctAbove = ((currentHR - restingHR) / restingHR * 100).toInt()
            return "Your heart rate is running ${pctAbove}% above your usual."
        }
        return getPersonalInsight()
    }

    // ── Update functions ────────────────────────────────────────────────

    /** Feed a new heart rate reading into the EMA baseline. */
    fun updateHeartRate(hr: Float) {
        if (hr <= 0f) return
        restingHR = ema(restingHR, hr, hrDataPoints)
        hrDataPoints++
    }

    /** Feed today's cumulative step count. Resets on new day. */
    fun updateSteps(steps: Int) {
        if (steps < 0) return
        val today = todayString()
        if (today != todayDate) {
            // New day: commit yesterday's total into daily average baseline
            if (todayDate.isNotEmpty() && todaySteps > 0) {
                avgDailySteps = ema(avgDailySteps.toFloat(), todaySteps.toFloat(), stepsDataPoints).toInt()
                stepsDataPoints++
            }
            todayDate = today
            todaySteps = steps
        } else {
            todaySteps = steps
        }
    }

    /** Feed a voice pitch reading (Hz) into the baseline. */
    fun updateVoicePitch(pitchHz: Float) {
        if (pitchHz <= 0f) return
        voicePitchBaseline = ema(voicePitchBaseline, pitchHz, voiceDataPoints)
        voiceDataPoints++
    }

    /** Feed a shake intensity reading into the baseline. */
    fun updateShakeIntensity(intensity: Float) {
        if (intensity <= 0f) return
        typicalShakeIntensity = ema(typicalShakeIntensity, intensity, shakeDataPoints)
        shakeDataPoints++
    }

    /** Record the current hour as an active hour, updating active window. */
    fun updateActiveHour(hour: Int) {
        if (hour < 0 || hour > 23) return
        if (activityHourDataPoints == 0) {
            activeHoursStart = hour
            activeHoursEnd = hour
        } else {
            if (hour < activeHoursStart) {
                activeHoursStart = ema(activeHoursStart.toFloat(), hour.toFloat(), activityHourDataPoints).toInt()
            }
            if (hour > activeHoursEnd) {
                activeHoursEnd = ema(activeHoursEnd.toFloat(), hour.toFloat(), activityHourDataPoints).toInt()
            }
        }
        activityHourDataPoints++
    }

    /** Log a question and its category. Trims to last [MAX_RECENT_QUESTIONS]. */
    fun addQuestion(question: String, category: String) {
        recentQuestions.add(question)
        while (recentQuestions.size > MAX_RECENT_QUESTIONS) {
            recentQuestions.removeFirst()
        }
        questionCategories[category] = (questionCategories[category] ?: 0) + 1
    }

    /** Increment session count. Call once per app launch / activation. */
    fun recordSession() {
        sessionCount++
    }

    // ── Persistence ─────────────────────────────────────────────────────

    /** Persist current profile state to SharedPreferences. */
    fun save() {
        val json = JSONObject().apply {
            // Baselines
            put("restingHR", restingHR.toDouble())
            put("typicalHRV", typicalHRV.toDouble())
            put("avgDailySteps", avgDailySteps)
            put("voicePitchBaseline", voicePitchBaseline.toDouble())
            put("typicalShakeIntensity", typicalShakeIntensity.toDouble())
            put("activeHoursStart", activeHoursStart)
            put("activeHoursEnd", activeHoursEnd)

            // Data point counts
            put("hrDataPoints", hrDataPoints)
            put("stepsDataPoints", stepsDataPoints)
            put("voiceDataPoints", voiceDataPoints)
            put("shakeDataPoints", shakeDataPoints)
            put("activityHourDataPoints", activityHourDataPoints)

            // Session
            put("sessionCount", sessionCount)
            put("todaySteps", todaySteps)
            put("todayDate", todayDate)

            // Recent questions
            put("recentQuestions", JSONArray(recentQuestions))

            // Category counts
            val catJson = JSONObject()
            questionCategories.forEach { (k, v) -> catJson.put(k, v) }
            put("questionCategories", catJson)
        }

        prefs.edit().putString("profile_data", json.toString()).apply()
    }

    /** Load profile state from SharedPreferences. */
    private fun load() {
        val raw = prefs.getString("profile_data", null) ?: return
        try {
            val json = JSONObject(raw)

            restingHR = json.optDouble("restingHR", 0.0).toFloat()
            typicalHRV = json.optDouble("typicalHRV", 0.0).toFloat()
            avgDailySteps = json.optInt("avgDailySteps", 0)
            voicePitchBaseline = json.optDouble("voicePitchBaseline", 0.0).toFloat()
            typicalShakeIntensity = json.optDouble("typicalShakeIntensity", 0.0).toFloat()
            activeHoursStart = json.optInt("activeHoursStart", 8)
            activeHoursEnd = json.optInt("activeHoursEnd", 22)

            hrDataPoints = json.optInt("hrDataPoints", 0)
            stepsDataPoints = json.optInt("stepsDataPoints", 0)
            voiceDataPoints = json.optInt("voiceDataPoints", 0)
            shakeDataPoints = json.optInt("shakeDataPoints", 0)
            activityHourDataPoints = json.optInt("activityHourDataPoints", 0)

            sessionCount = json.optInt("sessionCount", 0)
            todaySteps = json.optInt("todaySteps", 0)
            todayDate = json.optString("todayDate", "")

            // Recent questions
            val questionsArray = json.optJSONArray("recentQuestions")
            if (questionsArray != null) {
                recentQuestions.clear()
                for (i in 0 until questionsArray.length()) {
                    recentQuestions.add(questionsArray.getString(i))
                }
            }

            // Category counts
            val catJson = json.optJSONObject("questionCategories")
            if (catJson != null) {
                questionCategories.clear()
                catJson.keys().forEach { key ->
                    questionCategories[key] = catJson.getInt(key)
                }
            }
        } catch (_: Exception) {
            // Corrupted data — start fresh, baselines will re-learn
        }
    }

    // ── Internal helpers ────────────────────────────────────────────────

    /**
     * Exponential moving average. On the first data point (count == 0),
     * the new value becomes the baseline directly.
     */
    private fun ema(current: Float, newValue: Float, count: Int): Float {
        return if (count == 0) newValue
        else current * (1f - EMA_ALPHA) + newValue * EMA_ALPHA
    }

    /** Whether enough data points exist for a baseline to be meaningful. */
    private fun isBaselineLearned(count: Int): Boolean = count >= MIN_DATA_POINTS

    /** Tokenize a question into lowercase word set for similarity comparison. */
    private fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.length > 2 } // drop trivial words like "a", "is"
            .toSet()
    }

    /** Jaccard similarity between two word sets: |intersection| / |union|. */
    private fun jaccardSimilarity(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() && b.isEmpty()) return 0f
        val intersection = a.intersect(b).size
        val union = a.union(b).size
        return if (union == 0) 0f else intersection.toFloat() / union.toFloat()
    }

    /** Today's date as a compact string for day-change detection. */
    private fun todayString(): String {
        val cal = Calendar.getInstance()
        return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
    }
}
