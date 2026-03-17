package com.brewtech.decidr.oracle

import com.brewtech.decidr.sensor.SensorHub
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.sqrt

// ── Public Types (preserved for UI compatibility) ────────────────────────────

enum class ResponseMood { OMINOUS, POSITIVE, CAUTIONARY, MYSTICAL, DARK }

data class OracleResponse(
    val text: String,
    val mood: ResponseMood,
    val sensorInsight: String
)

// ── Internal Classification ──────────────────────────────────────────────────

private enum class QuestionCategory {
    YES_NO, RELATIONSHIP, CAREER, HEALTH, TIMING, FEAR, GENERAL
}

private enum class SensorVibe {
    CALM, ELEVATED, LATE_NIGHT, HIGH_ACTIVITY, LOW_ACTIVITY,
    PRESSURE_DROPPING, PRESSURE_RISING, NEUTRAL
}

private enum class ShakeQuality { GENTLE, MODERATE, AGGRESSIVE }

// ── Engine ───────────────────────────────────────────────────────────────────

class OracleEngine(private val sensorHub: SensorHub) {

    // ── Public API ───────────────────────────────────────────────────────

    fun generateResponse(shakeIntensity: Float, question: String? = null): OracleResponse {
        val category = classifyQuestion(question)
        val vibe = readSensorVibe()
        val shake = classifyShake(shakeIntensity)
        val pool = buildPool(category, vibe, shake)

        // Pick using magnetic entropy for hardware-seeded randomness
        val seed = sensorHub.magneticEntropySeed()
        val index = abs((seed xor System.nanoTime()).toInt()) % pool.size
        val picked = pool[index]

        return OracleResponse(
            text = picked.first,
            mood = picked.second,
            sensorInsight = buildInsightHint(vibe, shake)
        )
    }

    // ── Question Classification ──────────────────────────────────────────

    private fun classifyQuestion(question: String?): QuestionCategory {
        if (question.isNullOrBlank()) return QuestionCategory.GENERAL
        val q = question.lowercase().trim()

        return when {
            // Fear / worry — check before yes/no since "should I be worried" is fear
            q.containsAny("scared", "afraid", "worried", "anxious", "nervous",
                "panic", "stress", "fear", "terrified", "overwhelm") ->
                QuestionCategory.FEAR

            // Relationship
            q.containsAny("love", "relationship", "dating", "partner", "ex ",
                "crush", "boyfriend", "girlfriend", "marriage", "marry",
                "breakup", "break up", "tinder", "bumble", "date",
                "soulmate", "soul mate", "miss them", "miss her", "miss him",
                "text them", "text her", "text him", "ghost") ->
                QuestionCategory.RELATIONSHIP

            // Career / money
            q.containsAny("job", "work", "career", "boss", "quit", "money",
                "salary", "promotion", "interview", "hired", "fired",
                "raise", "resign", "business", "invest", "startup",
                "freelance", "side hustle", "coworker", "resume") ->
                QuestionCategory.CAREER

            // Health
            q.containsAny("health", "sick", "exercise", "diet", "sleep",
                "gym", "weight", "doctor", "therapy", "mental health",
                "burnout", "tired", "exhausted", "meditat") ->
                QuestionCategory.HEALTH

            // Timing
            q.containsAny("when", "how long", "how soon", "what time",
                "how many days", "how many weeks", "how many months",
                "deadline", "too late", "right time") ->
                QuestionCategory.TIMING

            // Yes/No — broad catch for decision questions
            q.containsAny("should i", "will i", "is it", "can i", "do i",
                "am i", "would it", "could i", "does it", "does he",
                "does she", "will it", "is he", "is she", "are they",
                "will they", "should we", "is this", "was it", "would i") ->
                QuestionCategory.YES_NO

            else -> QuestionCategory.GENERAL
        }
    }

    // ── Sensor Reading ───────────────────────────────────────────────────

    private fun readSensorVibe(): SensorVibe {
        val hr = sensorHub.heartRate.value
        val pressureTrend = sensorHub.pressureTrend.value
        val steps = sensorHub.steps.value
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        // Priority ordering: most distinctive state wins
        return when {
            hour in 0..4 || hour == 23 -> SensorVibe.LATE_NIGHT
            hr > 95f -> SensorVibe.ELEVATED
            hr in 1f..62f -> SensorVibe.CALM
            pressureTrend == -1 -> SensorVibe.PRESSURE_DROPPING
            pressureTrend == 1 -> SensorVibe.PRESSURE_RISING
            steps > 5000f -> SensorVibe.HIGH_ACTIVITY
            steps in 1f..500f -> SensorVibe.LOW_ACTIVITY
            else -> SensorVibe.NEUTRAL
        }
    }

    private fun classifyShake(intensity: Float): ShakeQuality = when {
        intensity > 35f -> ShakeQuality.AGGRESSIVE
        intensity > 20f -> ShakeQuality.MODERATE
        else -> ShakeQuality.GENTLE
    }

    // ── Insight Hint (subtle, no raw numbers) ────────────────────────────

    private fun buildInsightHint(vibe: SensorVibe, shake: ShakeQuality): String {
        return when (vibe) {
            SensorVibe.ELEVATED -> "Your pulse says this one matters."
            SensorVibe.CALM -> "You asked that with total composure."
            SensorVibe.LATE_NIGHT -> "Late night thoughts hit different."
            SensorVibe.HIGH_ACTIVITY -> "Asked on the move. Good energy."
            SensorVibe.LOW_ACTIVITY -> "A quiet moment for a big question."
            SensorVibe.PRESSURE_DROPPING -> "Something in the air is shifting."
            SensorVibe.PRESSURE_RISING -> "The atmosphere is on your side."
            SensorVibe.NEUTRAL -> when (shake) {
                ShakeQuality.AGGRESSIVE -> "You really wanted this answer."
                ShakeQuality.GENTLE -> "A gentle ask. Noted."
                ShakeQuality.MODERATE -> "Fair shake. Fair answer."
            }
        }
    }

    // ── Response Pool Builder ────────────────────────────────────────────

    private fun buildPool(
        category: QuestionCategory,
        vibe: SensorVibe,
        shake: ShakeQuality
    ): List<Pair<String, ResponseMood>> {
        val primary = responsesFor(category, vibe, shake)
        // Always have enough variety — pad with general if pool is small
        return if (primary.size >= 6) primary
        else primary + responsesFor(QuestionCategory.GENERAL, vibe, shake)
    }

    private fun responsesFor(
        category: QuestionCategory,
        vibe: SensorVibe,
        shake: ShakeQuality
    ): List<Pair<String, ResponseMood>> = when (category) {
        QuestionCategory.YES_NO -> yesNoResponses(vibe, shake)
        QuestionCategory.RELATIONSHIP -> relationshipResponses(vibe)
        QuestionCategory.CAREER -> careerResponses(vibe)
        QuestionCategory.HEALTH -> healthResponses(vibe)
        QuestionCategory.TIMING -> timingResponses(vibe)
        QuestionCategory.FEAR -> fearResponses(vibe)
        QuestionCategory.GENERAL -> generalResponses(vibe, shake)
    }

    // ── YES / NO ─────────────────────────────────────────────────────────

    private fun yesNoResponses(vibe: SensorVibe, shake: ShakeQuality): List<Pair<String, ResponseMood>> {
        val base = listOf(
            "Yes. And deep down, you already knew that." to ResponseMood.POSITIVE,
            "No. Not this time." to ResponseMood.CAUTIONARY,
            "Yes — but only if you actually commit to it." to ResponseMood.POSITIVE,
            "No. And that's okay." to ResponseMood.CAUTIONARY,
            "Absolutely. Stop second-guessing yourself." to ResponseMood.POSITIVE,
            "Not yet. The timing is off." to ResponseMood.CAUTIONARY,
            "Yes. The hard part is already behind you." to ResponseMood.POSITIVE,
            "No. You deserve better than that option." to ResponseMood.DARK,
            "Yes, but not the way you're imagining it." to ResponseMood.MYSTICAL,
            "Hard no. Move on." to ResponseMood.DARK,
            "Yes — and it's going to be better than you think." to ResponseMood.POSITIVE,
            "Not a chance. Next question." to ResponseMood.DARK,
            "Yes. Trust your gut on this one." to ResponseMood.POSITIVE,
            "No, and honestly? You knew that already." to ResponseMood.CAUTIONARY,
            "One hundred percent yes." to ResponseMood.POSITIVE,
            "That's a no from me. Try a different approach." to ResponseMood.CAUTIONARY
        )

        val vibeSpecific = when (vibe) {
            SensorVibe.ELEVATED -> listOf(
                "Yes — go now before you overthink it." to ResponseMood.POSITIVE,
                "No. Your racing heart is telling you something." to ResponseMood.CAUTIONARY,
                "Yes. You're nervous because it matters." to ResponseMood.POSITIVE,
                "No — and breathe. You'll find a better path." to ResponseMood.CAUTIONARY,
                "Yes. Channel that energy into action." to ResponseMood.POSITIVE,
                "No. Step back and let this one pass." to ResponseMood.DARK
            )
            SensorVibe.CALM -> listOf(
                "Yes. You're calm enough to handle whatever comes." to ResponseMood.POSITIVE,
                "No — but you're taking it well, which means you already knew." to ResponseMood.CAUTIONARY,
                "Yes. That quiet confidence? It's earned." to ResponseMood.POSITIVE,
                "No. And your steady pulse says you can handle hearing that." to ResponseMood.CAUTIONARY,
                "Yes. You thought this through and you're right." to ResponseMood.POSITIVE,
                "No, but you're in the right headspace to find plan B." to ResponseMood.CAUTIONARY
            )
            SensorVibe.LATE_NIGHT -> listOf(
                "Yes, but sleep on it first. Ask me again tomorrow." to ResponseMood.MYSTICAL,
                "No. And 2 AM isn't the time to act on this anyway." to ResponseMood.CAUTIONARY,
                "Yes — but the real question is why you're still up thinking about it." to ResponseMood.MYSTICAL,
                "No. Morning will bring clarity." to ResponseMood.CAUTIONARY,
                "Yes. Some truths only surface at night." to ResponseMood.MYSTICAL,
                "No. Your pillow has better advice right now." to ResponseMood.DARK
            )
            SensorVibe.HIGH_ACTIVITY -> listOf(
                "Yes — you've got the momentum. Keep going." to ResponseMood.POSITIVE,
                "No. Save that energy for something that deserves it." to ResponseMood.CAUTIONARY,
                "Yes. You're already moving — this is just the next step." to ResponseMood.POSITIVE,
                "No. Redirect that drive somewhere better." to ResponseMood.CAUTIONARY
            )
            SensorVibe.LOW_ACTIVITY -> listOf(
                "Yes. But you'll need to get moving to make it happen." to ResponseMood.POSITIVE,
                "No. Sit with that for a bit — it'll make sense soon." to ResponseMood.CAUTIONARY,
                "Yes. Sometimes the best decisions come from stillness." to ResponseMood.MYSTICAL,
                "No. Rest and revisit this with fresh eyes." to ResponseMood.CAUTIONARY
            )
            SensorVibe.PRESSURE_DROPPING -> listOf(
                "Yes, but proceed with caution." to ResponseMood.CAUTIONARY,
                "No. The conditions aren't in your favor right now." to ResponseMood.OMINOUS,
                "Yes — but have a backup plan ready." to ResponseMood.CAUTIONARY,
                "No. Wait for things to settle first." to ResponseMood.OMINOUS
            )
            SensorVibe.PRESSURE_RISING -> listOf(
                "Yes. Everything is aligning for you." to ResponseMood.POSITIVE,
                "No, but something better is coming your way." to ResponseMood.POSITIVE,
                "Yes — the momentum is building in your favor." to ResponseMood.POSITIVE,
                "No for now, but the tide is turning. Be patient." to ResponseMood.MYSTICAL
            )
            SensorVibe.NEUTRAL -> when (shake) {
                ShakeQuality.AGGRESSIVE -> listOf(
                    "Shaking harder won't change the answer. It's yes." to ResponseMood.POSITIVE,
                    "That aggressive shake tells me you want a yes. Fine — yes." to ResponseMood.POSITIVE,
                    "Shaking like that? No. Calm down and try again." to ResponseMood.DARK,
                    "The answer is no, no matter how hard you shake." to ResponseMood.DARK
                )
                ShakeQuality.GENTLE -> listOf(
                    "That gentle shake deserves a gentle yes." to ResponseMood.POSITIVE,
                    "You barely asked. The answer barely matters to you. No." to ResponseMood.CAUTIONARY,
                    "Yes — asked softly, answered honestly." to ResponseMood.POSITIVE,
                    "No. But you didn't seem too invested anyway." to ResponseMood.CAUTIONARY
                )
                ShakeQuality.MODERATE -> listOf(
                    "Yes. Solid question, solid answer." to ResponseMood.POSITIVE,
                    "No. But it was worth asking." to ResponseMood.CAUTIONARY,
                    "Yes — straightforward ask, straightforward answer." to ResponseMood.POSITIVE,
                    "No. Sometimes the simple answer is the right one." to ResponseMood.CAUTIONARY
                )
            }
        }

        return base + vibeSpecific
    }

    // ── RELATIONSHIP ─────────────────────────────────────────────────────

    private fun relationshipResponses(vibe: SensorVibe): List<Pair<String, ResponseMood>> {
        val base = listOf(
            "They're thinking about you too. Reach out." to ResponseMood.POSITIVE,
            "Let them come to you this time." to ResponseMood.CAUTIONARY,
            "You already know this isn't working. Be honest." to ResponseMood.DARK,
            "Give it one more real conversation before deciding." to ResponseMood.CAUTIONARY,
            "They're not your person. You'll know when you find them." to ResponseMood.DARK,
            "Stop replaying old texts. Write new ones." to ResponseMood.POSITIVE,
            "You're ready for something real. Stay open." to ResponseMood.POSITIVE,
            "The right person won't make you feel this uncertain." to ResponseMood.CAUTIONARY,
            "Send the text. The worst they can say is nothing." to ResponseMood.POSITIVE,
            "This one has potential. Give it time, not pressure." to ResponseMood.POSITIVE,
            "You're not missing out. You're leveling up." to ResponseMood.POSITIVE,
            "That situationship? It's not going anywhere. You know it." to ResponseMood.DARK,
            "Love isn't supposed to feel like a puzzle you can't solve." to ResponseMood.CAUTIONARY,
            "They miss you. But missing isn't the same as choosing you." to ResponseMood.OMINOUS,
            "Stop looking for signs and start having conversations." to ResponseMood.CAUTIONARY,
            "You're overthinking their last message. It meant what it said." to ResponseMood.POSITIVE
        )

        val vibeSpecific = when (vibe) {
            SensorVibe.ELEVATED -> listOf(
                "Your heart is racing just asking. That's your answer." to ResponseMood.POSITIVE,
                "If they make your pulse spike like this, tell them." to ResponseMood.POSITIVE,
                "That heartbeat says you care more than you're admitting." to ResponseMood.MYSTICAL,
                "Breathe. Then decide if it's excitement or anxiety." to ResponseMood.CAUTIONARY
            )
            SensorVibe.CALM -> listOf(
                "You're calm about this. That either means peace or indifference." to ResponseMood.MYSTICAL,
                "The fact you can ask this calmly means you're ready for the truth." to ResponseMood.POSITIVE,
                "Steady heart, clear mind. You already know what to do." to ResponseMood.POSITIVE,
                "Your calm says you've already moved on. Trust that." to ResponseMood.CAUTIONARY
            )
            SensorVibe.LATE_NIGHT -> listOf(
                "Don't text them at this hour. You'll thank me tomorrow." to ResponseMood.CAUTIONARY,
                "Late night feelings aren't always real feelings. Sleep first." to ResponseMood.CAUTIONARY,
                "If you're still thinking about them at this hour, it matters." to ResponseMood.MYSTICAL,
                "The 2 AM version of you is not your best decision-maker." to ResponseMood.DARK
            )
            else -> listOf(
                "Actions tell you everything words won't." to ResponseMood.CAUTIONARY,
                "If it's right, it won't feel this complicated." to ResponseMood.MYSTICAL,
                "You deserve someone who doesn't make you wonder." to ResponseMood.POSITIVE,
                "Stop asking the universe and start asking them." to ResponseMood.POSITIVE
            )
        }

        return base + vibeSpecific
    }

    // ── CAREER ───────────────────────────────────────────────────────────

    private fun careerResponses(vibe: SensorVibe): List<Pair<String, ResponseMood>> {
        val base = listOf(
            "Update your resume this week. Trust me." to ResponseMood.POSITIVE,
            "You're underpaid. Start looking." to ResponseMood.DARK,
            "Stay put for now. Your moment is coming." to ResponseMood.CAUTIONARY,
            "That side project? Make it your main thing." to ResponseMood.POSITIVE,
            "Your boss doesn't see your value. Someone else will." to ResponseMood.CAUTIONARY,
            "Take the meeting. Even if nothing comes of it, you'll learn." to ResponseMood.POSITIVE,
            "Don't quit on a bad day. Don't stay for a good one." to ResponseMood.CAUTIONARY,
            "The raise won't come from waiting. Ask." to ResponseMood.POSITIVE,
            "You're in the right field, wrong company." to ResponseMood.CAUTIONARY,
            "Network more. Your next opportunity is one conversation away." to ResponseMood.POSITIVE,
            "Stop overdelivering for people who undervalue you." to ResponseMood.DARK,
            "Learn that new skill you've been eyeing. It'll pay off." to ResponseMood.POSITIVE,
            "This job is a stepping stone, not a destination. Act like it." to ResponseMood.CAUTIONARY,
            "You've been coasting. Time to push." to ResponseMood.DARK,
            "The market is better than you think. Test the waters." to ResponseMood.POSITIVE,
            "Document everything. Future you will be grateful." to ResponseMood.CAUTIONARY
        )

        val vibeSpecific = when (vibe) {
            SensorVibe.CALM -> listOf(
                "You're in the right place. Give it six more months." to ResponseMood.POSITIVE,
                "That calm energy? Bring it to your next negotiation." to ResponseMood.POSITIVE,
                "Stability isn't settling. You're building something." to ResponseMood.POSITIVE,
                "Your patience will outlast their chaos. Stay steady." to ResponseMood.MYSTICAL
            )
            SensorVibe.ELEVATED -> listOf(
                "Your stress level says it all. Something needs to change." to ResponseMood.CAUTIONARY,
                "Don't make career moves when your heart is racing." to ResponseMood.CAUTIONARY,
                "That anxiety is data. Listen to it." to ResponseMood.DARK,
                "Feeling the pressure? Good. That means you care." to ResponseMood.POSITIVE
            )
            SensorVibe.HIGH_ACTIVITY -> listOf(
                "You're hustling. Make sure it's for the right people." to ResponseMood.CAUTIONARY,
                "That energy is bankable. Point it at something you own." to ResponseMood.POSITIVE,
                "Working hard is good. Working smart is better." to ResponseMood.POSITIVE,
                "You're on a roll. Ride it." to ResponseMood.POSITIVE
            )
            SensorVibe.LATE_NIGHT -> listOf(
                "Working late again? That's not dedication, that's a red flag." to ResponseMood.DARK,
                "The best career decisions are made rested. Close the laptop." to ResponseMood.CAUTIONARY,
                "If you're grinding at this hour, you'd better own equity." to ResponseMood.DARK,
                "Sleep on the resignation letter. Send it if you still feel it at noon." to ResponseMood.CAUTIONARY
            )
            else -> listOf(
                "Bet on yourself. You've been betting on everyone else." to ResponseMood.POSITIVE,
                "The comfortable path and the right path split here. Choose." to ResponseMood.MYSTICAL,
                "You're closer to a breakthrough than you think." to ResponseMood.POSITIVE,
                "Sometimes lateral moves are the smartest moves." to ResponseMood.CAUTIONARY
            )
        }

        return base + vibeSpecific
    }

    // ── HEALTH ───────────────────────────────────────────────────────────

    private fun healthResponses(vibe: SensorVibe): List<Pair<String, ResponseMood>> {
        val base = listOf(
            "Drink water. Right now. I'll wait." to ResponseMood.POSITIVE,
            "Your body is talking. Start listening." to ResponseMood.CAUTIONARY,
            "One good habit this week. That's all. Pick one." to ResponseMood.POSITIVE,
            "You don't need a complete overhaul. Just start walking." to ResponseMood.POSITIVE,
            "Sleep is the cheat code everyone ignores. Try eight hours." to ResponseMood.CAUTIONARY,
            "Book the appointment you've been putting off." to ResponseMood.CAUTIONARY,
            "Progress isn't linear. A bad week doesn't erase a good month." to ResponseMood.POSITIVE,
            "Stop comparing your chapter one to their chapter twenty." to ResponseMood.MYSTICAL,
            "Rest is not laziness. It's maintenance." to ResponseMood.POSITIVE,
            "Your future self is begging you to start today." to ResponseMood.CAUTIONARY,
            "Consistency beats intensity. Show up, even half-effort." to ResponseMood.POSITIVE,
            "That thing you keep avoiding? It's the thing you most need to do." to ResponseMood.DARK,
            "You're stronger than the excuse you were about to make." to ResponseMood.POSITIVE,
            "Mental health counts as health. Take care of your head too." to ResponseMood.MYSTICAL,
            "Small changes compound. Give it ninety days." to ResponseMood.POSITIVE
        )

        val vibeSpecific = when (vibe) {
            SensorVibe.ELEVATED -> listOf(
                "Your heart rate is up. Take five slow breaths before anything else." to ResponseMood.CAUTIONARY,
                "High pulse? Step away from the screen for ten minutes." to ResponseMood.CAUTIONARY,
                "Your body is sending signals. Don't ignore this one." to ResponseMood.DARK,
                "Stress shows up in the body first. You're seeing it now." to ResponseMood.CAUTIONARY
            )
            SensorVibe.CALM -> listOf(
                "You're in a good zone right now. Build on it." to ResponseMood.POSITIVE,
                "That resting calm? That's the goal. Protect it." to ResponseMood.POSITIVE,
                "Your body is at peace. Let your mind catch up." to ResponseMood.MYSTICAL
            )
            SensorVibe.LATE_NIGHT -> listOf(
                "Put the phone down and sleep. Everything else can wait." to ResponseMood.DARK,
                "Late nights cost more than you think. Rest is productive." to ResponseMood.CAUTIONARY,
                "Your body needs recovery time. Give it some." to ResponseMood.CAUTIONARY
            )
            else -> listOf(
                "Start where you are. Use what you have." to ResponseMood.POSITIVE,
                "You don't have to earn rest. Take it anyway." to ResponseMood.POSITIVE,
                "The best workout is the one you'll actually do." to ResponseMood.POSITIVE
            )
        }

        return base + vibeSpecific
    }

    // ── TIMING ───────────────────────────────────────────────────────────

    private fun timingResponses(vibe: SensorVibe): List<Pair<String, ResponseMood>> {
        val base = listOf(
            "Sooner than you think. This month." to ResponseMood.POSITIVE,
            "Not yet. But you'll know when it's time." to ResponseMood.CAUTIONARY,
            "The timing is now. Stop waiting for perfect." to ResponseMood.POSITIVE,
            "Give it three more weeks. Then reassess." to ResponseMood.CAUTIONARY,
            "You missed the ideal window, but the next one is close." to ResponseMood.CAUTIONARY,
            "It's already happening. You just haven't noticed yet." to ResponseMood.MYSTICAL,
            "Stop watching the clock and start making moves." to ResponseMood.POSITIVE,
            "The right time was yesterday. The second-best time is now." to ResponseMood.POSITIVE,
            "Patience. This one needs another season." to ResponseMood.CAUTIONARY,
            "Within the year. Sooner if you push." to ResponseMood.POSITIVE,
            "You're early, not wrong. That's a good place to be." to ResponseMood.MYSTICAL,
            "Timing is less important than you think. Readiness matters more." to ResponseMood.POSITIVE,
            "By summer, if you start this week." to ResponseMood.POSITIVE,
            "It'll take longer than you want but less than you fear." to ResponseMood.CAUTIONARY,
            "The wait is almost over. Stay focused." to ResponseMood.POSITIVE
        )

        val vibeSpecific = when (vibe) {
            SensorVibe.ELEVATED -> listOf(
                "Soon — but don't rush it just because you're anxious." to ResponseMood.CAUTIONARY,
                "Your urgency is valid. Act on it this week." to ResponseMood.POSITIVE,
                "Not while your heart is pounding. Calm first, then move." to ResponseMood.CAUTIONARY
            )
            SensorVibe.LATE_NIGHT -> listOf(
                "Tomorrow morning. Seriously, go to sleep." to ResponseMood.DARK,
                "The answer will be clearer in daylight." to ResponseMood.MYSTICAL,
                "Not at this hour. Some things need sunlight." to ResponseMood.CAUTIONARY
            )
            SensorVibe.PRESSURE_RISING -> listOf(
                "The conditions are improving. Move soon." to ResponseMood.POSITIVE,
                "Things are trending your way. Strike while it lasts." to ResponseMood.POSITIVE
            )
            SensorVibe.PRESSURE_DROPPING -> listOf(
                "Hold off. The conditions are shifting against you." to ResponseMood.OMINOUS,
                "Wait for the dust to settle. Then act decisively." to ResponseMood.CAUTIONARY
            )
            else -> listOf(
                "When you stop asking when and start doing." to ResponseMood.POSITIVE,
                "The timeline is in your hands more than you realize." to ResponseMood.MYSTICAL,
                "Soon enough. Keep building." to ResponseMood.POSITIVE
            )
        }

        return base + vibeSpecific
    }

    // ── FEAR / WORRY ─────────────────────────────────────────────────────

    private fun fearResponses(vibe: SensorVibe): List<Pair<String, ResponseMood>> {
        val base = listOf(
            "The thing you're afraid of? It's smaller than it feels." to ResponseMood.POSITIVE,
            "Fear means you care. That's not weakness." to ResponseMood.POSITIVE,
            "You've survived every worst day so far. This one's no different." to ResponseMood.POSITIVE,
            "Name the fear out loud. It loses power when you do." to ResponseMood.MYSTICAL,
            "Courage isn't no fear. It's fear plus action." to ResponseMood.POSITIVE,
            "Worst case scenario? You'll handle it. You always do." to ResponseMood.POSITIVE,
            "This isn't as permanent as it feels right now." to ResponseMood.CAUTIONARY,
            "You're catastrophizing. Come back to what's actually happening." to ResponseMood.CAUTIONARY,
            "The anxiety is lying to you. Check the facts." to ResponseMood.DARK,
            "Most of what you're worried about will never happen." to ResponseMood.POSITIVE,
            "You're braver than you're giving yourself credit for." to ResponseMood.POSITIVE,
            "Feel the fear. Then do it anyway." to ResponseMood.POSITIVE,
            "This feeling is temporary. Your strength isn't." to ResponseMood.MYSTICAL,
            "One step at a time. You don't have to solve it all tonight." to ResponseMood.CAUTIONARY,
            "The other side of this fear is everything you want." to ResponseMood.POSITIVE
        )

        val vibeSpecific = when (vibe) {
            SensorVibe.ELEVATED -> listOf(
                "Take three deep breaths. Now — it's smaller than it feels." to ResponseMood.CAUTIONARY,
                "I can tell you're wound up. Ground yourself first." to ResponseMood.CAUTIONARY,
                "Your body is in fight-or-flight. Slow down. You're safe." to ResponseMood.CAUTIONARY,
                "Breathe in for four, hold for four, out for four. Then read this again." to ResponseMood.POSITIVE
            )
            SensorVibe.CALM -> listOf(
                "You're calmer than you think. That's your real state." to ResponseMood.POSITIVE,
                "See? You can think about this without spiraling. You're growing." to ResponseMood.POSITIVE,
                "The fact you can sit with this calmly means you're ready." to ResponseMood.MYSTICAL
            )
            SensorVibe.LATE_NIGHT -> listOf(
                "Fears grow in the dark. They shrink in the morning." to ResponseMood.MYSTICAL,
                "Nothing good comes from worrying at this hour. Rest." to ResponseMood.CAUTIONARY,
                "The night amplifies everything. Tomorrow this will feel smaller." to ResponseMood.CAUTIONARY,
                "Your brain is tired. It's making monsters out of shadows." to ResponseMood.DARK
            )
            else -> listOf(
                "You've handled harder things than this." to ResponseMood.POSITIVE,
                "Fear is just excitement without breathing. Try breathing." to ResponseMood.POSITIVE,
                "What would you tell your best friend right now? Do that." to ResponseMood.MYSTICAL
            )
        }

        return base + vibeSpecific
    }

    // ── GENERAL ──────────────────────────────────────────────────────────

    private fun generalResponses(vibe: SensorVibe, shake: ShakeQuality): List<Pair<String, ResponseMood>> {
        val base = listOf(
            "Stop asking and start doing." to ResponseMood.POSITIVE,
            "You didn't need me for this one." to ResponseMood.DARK,
            "The answer is obvious. You just wanted confirmation." to ResponseMood.CAUTIONARY,
            "Trust yourself more than you trust a watch app." to ResponseMood.POSITIVE,
            "Go with your first instinct. It's usually right." to ResponseMood.POSITIVE,
            "Flip a coin. Your reaction to the result is the real answer." to ResponseMood.MYSTICAL,
            "You're overthinking this. Just pick one and commit." to ResponseMood.CAUTIONARY,
            "The best decision is the one you actually make." to ResponseMood.POSITIVE,
            "Do the thing that scares you slightly more." to ResponseMood.POSITIVE,
            "If it won't matter in five years, don't spend five minutes on it." to ResponseMood.CAUTIONARY,
            "Follow the energy, not the logic." to ResponseMood.MYSTICAL,
            "You already decided. You're just looking for permission." to ResponseMood.POSITIVE,
            "Interesting question. Wrong timing though." to ResponseMood.CAUTIONARY,
            "Do less, but do it better." to ResponseMood.POSITIVE,
            "That's above my pay grade. But I'd say go for it." to ResponseMood.POSITIVE,
            "Bold move. I respect it." to ResponseMood.POSITIVE,
            "What's the worst that could happen? Exactly." to ResponseMood.POSITIVE,
            "You'll regret not trying more than trying and failing." to ResponseMood.POSITIVE,
            "Life's too short for maybe. Make it a yes or a no." to ResponseMood.DARK,
            "This is one of those moments you'll look back on. Choose well." to ResponseMood.MYSTICAL,
            "Less planning, more doing." to ResponseMood.POSITIVE,
            "The universe doesn't care about your five-year plan. Adapt." to ResponseMood.DARK,
            "You know what to do. You just don't want to do it." to ResponseMood.DARK,
            "Take the shot. You miss every one you don't take." to ResponseMood.POSITIVE,
            "Think less. Act more." to ResponseMood.POSITIVE
        )

        val vibeSpecific = when (vibe) {
            SensorVibe.ELEVATED -> listOf(
                "You're worked up. Whatever you're about to do — wait an hour." to ResponseMood.CAUTIONARY,
                "Your pulse is speaking louder than your question." to ResponseMood.MYSTICAL,
                "Hot take: cool down first, then decide." to ResponseMood.CAUTIONARY,
                "The answer you need isn't the one you want right now." to ResponseMood.DARK
            )
            SensorVibe.CALM -> listOf(
                "You already know. You're just looking for permission." to ResponseMood.POSITIVE,
                "Your calm energy answers this better than I can." to ResponseMood.MYSTICAL,
                "Clear mind, clear answer: go for it." to ResponseMood.POSITIVE,
                "You're centered. Whatever you decide right now will be right." to ResponseMood.POSITIVE
            )
            SensorVibe.LATE_NIGHT -> listOf(
                "Save this question for daylight. Trust me." to ResponseMood.CAUTIONARY,
                "Late night wisdom: go to bed." to ResponseMood.DARK,
                "The best thing you can do right now is sleep on it." to ResponseMood.CAUTIONARY,
                "Night thoughts feel true but they're often just tired." to ResponseMood.MYSTICAL
            )
            SensorVibe.HIGH_ACTIVITY -> listOf(
                "You're on the go. Good. Keep that energy for what matters." to ResponseMood.POSITIVE,
                "Active body, active mind. Use both." to ResponseMood.POSITIVE,
                "You're moving — that's already half the answer." to ResponseMood.POSITIVE
            )
            SensorVibe.LOW_ACTIVITY -> listOf(
                "Still waters run deep. Take your time with this one." to ResponseMood.MYSTICAL,
                "Quiet moments are for honest answers. Here's yours: go for it." to ResponseMood.POSITIVE,
                "You're in reflection mode. Good. The answer is clearer from here." to ResponseMood.MYSTICAL
            )
            SensorVibe.PRESSURE_DROPPING -> listOf(
                "Proceed carefully. Something is shifting." to ResponseMood.OMINOUS,
                "Not the best moment for big moves. Wait it out." to ResponseMood.CAUTIONARY,
                "Hold steady. The ground is moving under you." to ResponseMood.OMINOUS
            )
            SensorVibe.PRESSURE_RISING -> listOf(
                "Things are looking up. Move with confidence." to ResponseMood.POSITIVE,
                "The wind is at your back. Now is the time." to ResponseMood.POSITIVE,
                "Good conditions ahead. Make your move." to ResponseMood.POSITIVE
            )
            SensorVibe.NEUTRAL -> when (shake) {
                ShakeQuality.AGGRESSIVE -> listOf(
                    "Shaking harder won't change the answer. It's yes." to ResponseMood.POSITIVE,
                    "Easy there. The answer is: do it, but calmly." to ResponseMood.CAUTIONARY,
                    "All that force for a simple question? The answer is yes." to ResponseMood.POSITIVE,
                    "Whoa. That shake says more than your question did." to ResponseMood.DARK
                )
                ShakeQuality.GENTLE -> listOf(
                    "A gentle ask deserves a thoughtful answer: yes, when you're ready." to ResponseMood.POSITIVE,
                    "Soft shake, soft answer: take your time." to ResponseMood.MYSTICAL,
                    "You barely asked. Maybe you don't really need an answer." to ResponseMood.CAUTIONARY,
                    "That hesitant shake? Listen to it. Wait a bit longer." to ResponseMood.CAUTIONARY
                )
                ShakeQuality.MODERATE -> listOf(
                    "Fair shake. Fair answer. Go for it." to ResponseMood.POSITIVE,
                    "Balanced energy, balanced response: it'll work out." to ResponseMood.POSITIVE,
                    "Good shake. Good question. Good answer: yes." to ResponseMood.POSITIVE,
                    "Middle of the road question gets a straight answer: do it." to ResponseMood.POSITIVE
                )
            }
        }

        return base + vibeSpecific
    }

    // ── Utility Extension ────────────────────────────────────────────────

    private fun String.containsAny(vararg keywords: String): Boolean =
        keywords.any { this.contains(it, ignoreCase = true) }
}
