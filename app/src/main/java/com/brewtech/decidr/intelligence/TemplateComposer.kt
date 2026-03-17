package com.brewtech.decidr.intelligence

import com.brewtech.decidr.voice.QuestionCategory
import java.util.Calendar
import kotlin.random.Random

/**
 * Mood derived from sensor state — drives fragment selection bias.
 */
enum class SensorMood {
    CALM,
    TENSE,
    ENERGETIC,
    TIRED,
    LATE_NIGHT
}

/**
 * Tracery-style template composition engine that generates 2.5M+ unique responses
 * by combining sentence fragments across four slots:
 *
 *   [opening] + [observation] + [advice] + [closing]
 *
 * Not every response uses all four slots. Some standalone responses bypass
 * composition entirely for variety. Fragment selection is weighted by
 * question category, sensor mood, and sentiment polarity.
 *
 * Now includes fragment pools for voice state, user profile observations,
 * and shake emotion reads.
 *
 * Runs in <1ms with ~50KB memory footprint.
 */
class TemplateComposer {

    // ── Voice / Profile / Shake State Enums (for external callers) ────────

    enum class VoiceState {
        STRESSED_VOICE,
        CONFIDENT_VOICE,
        WHISPERING,
        RUSHING
    }

    enum class ProfileState {
        REPEAT_QUESTION,
        ABOVE_NORMAL_HR,
        LATE_FOR_USER,
        UNUSUALLY_ACTIVE
    }

    enum class ShakeEmotionState {
        CONTEMPLATIVE_SHAKE,
        DESPERATE_SHAKE,
        PLAYFUL_SHAKE,
        IMPATIENT_SHAKE
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Compose a natural-sounding response from fragment pools.
     *
     * @param category The classified question type
     * @param sensorMood Current physical/temporal mood from sensors
     * @param sentiment Question sentiment from -1.0 (negative) to 1.0 (positive)
     * @return A composed human-sounding response string
     */
    fun compose(
        category: QuestionCategory,
        sensorMood: SensorMood,
        sentiment: Float
    ): String {
        val rng = Random(System.nanoTime())

        // 15% chance to use a standalone response that bypasses composition
        if (rng.nextFloat() < 0.15f) {
            val standalone = pickStandalone(category, sensorMood, sentiment, rng)
            if (standalone != null) return standalone
        }

        // 20% chance for a punchy short-form: Opening + Advice only (no observation, no closing)
        if (rng.nextFloat() < 0.20f) {
            val opening = pickWeighted(getOpenings(sensorMood, sentiment), rng)
            val advice = pickWeighted(getAdvice(category, sentiment), rng)
            return "$opening $advice"
        }

        // Full composition: select fragments with weighted randomness
        val opening = pickWeighted(getOpenings(sensorMood, sentiment), rng)
        val observation = pickObservation(sensorMood, category, rng)
        val advice = pickWeighted(getAdvice(category, sentiment), rng)
        val closing = pickClosing(rng)

        return assemble(opening, observation, advice, closing)
    }

    /**
     * Pick a random fragment from the voice state observation pool.
     */
    fun pickFromVoiceState(state: VoiceState): String {
        val pool = voiceStateFragments[state] ?: return ""
        return pool[Random(System.nanoTime()).nextInt(pool.size)]
    }

    /**
     * Pick a random fragment from the profile state observation pool.
     */
    fun pickFromProfileState(state: ProfileState): String {
        val pool = profileStateFragments[state] ?: return ""
        return pool[Random(System.nanoTime()).nextInt(pool.size)]
    }

    /**
     * Pick a random fragment from the shake emotion observation pool.
     */
    fun pickFromShakeEmotion(state: ShakeEmotionState): String {
        val pool = shakeEmotionFragments[state] ?: return ""
        return pool[Random(System.nanoTime()).nextInt(pool.size)]
    }

    // ── Assembly ──────────────────────────────────────────────────────────

    private fun assemble(
        opening: String,
        observation: String?,
        advice: String?,
        closing: String?
    ): String {
        val parts = mutableListOf(opening)
        if (observation != null) parts.add(observation)
        if (advice != null) parts.add(advice)
        if (!closing.isNullOrEmpty()) parts.add(closing)
        return parts.joinToString(" ")
    }

    // ── Fragment Selection Logic ──────────────────────────────────────────

    private fun pickWeighted(pool: List<WeightedFragment>, rng: Random): String {
        val totalWeight = pool.sumOf { it.weight.toDouble() }.toFloat()
        var roll = rng.nextFloat() * totalWeight
        for (fragment in pool) {
            roll -= fragment.weight
            if (roll <= 0f) return fragment.text
        }
        return pool.last().text
    }

    private fun pickObservation(
        mood: SensorMood,
        category: QuestionCategory,
        rng: Random
    ): String? {
        // 70% chance to include an observation
        if (rng.nextFloat() > 0.70f) return null
        val pool = getObservations(mood, category)
        return pickWeighted(pool, rng)
    }

    private fun pickClosing(rng: Random): String? {
        // 45% chance to include a closing
        if (rng.nextFloat() > 0.45f) return null
        val pool = closings
        return pickWeighted(pool, rng)
    }

    private fun pickStandalone(
        category: QuestionCategory,
        mood: SensorMood,
        sentiment: Float,
        rng: Random
    ): String? {
        val pool = getStandalones(category, mood, sentiment)
        if (pool.isEmpty()) return null
        return pool[rng.nextInt(pool.size)]
    }

    // ── Data Class ────────────────────────────────────────────────────────

    private data class WeightedFragment(val text: String, val weight: Float = 1.0f)

    private fun w(text: String, weight: Float = 1.0f) = WeightedFragment(text, weight)

    // ═══════════════════════════════════════════════════════════════════════
    //  VOICE STATE FRAGMENTS
    // ═══════════════════════════════════════════════════════════════════════

    private val voiceStateFragments = mapOf(
        VoiceState.STRESSED_VOICE to listOf(
            "I can hear the tension in your voice.",
            "Take a breath — I hear the worry.",
            "Your voice is tight. Whatever this is, it's weighing on you.",
            "The stress in your voice says more than the words.",
            "I hear it. You're carrying something heavy right now."
        ),
        VoiceState.CONFIDENT_VOICE to listOf(
            "You sound sure of yourself already.",
            "That confidence in your voice? Trust it.",
            "You didn't ask that like someone who needs advice.",
            "Your voice says you already know. So why are you asking?",
            "That's the voice of someone who's already decided."
        ),
        VoiceState.WHISPERING to listOf(
            "You're whispering this question. That tells me everything.",
            "The fact that you whispered it means you already know the stakes.",
            "A whispered question carries more weight than a shouted one.",
            "You're being quiet about this. That means it matters.",
            "Whispers are for secrets and prayers. Which one is this?"
        ),
        VoiceState.RUSHING to listOf(
            "Slow down. You're talking so fast you can't even hear yourself think.",
            "Breathe. The words are tumbling out faster than your thoughts.",
            "You're rushing through this like the answer has an expiration date.",
            "Slow it down. Speed doesn't equal urgency.",
            "Take a beat. Your mouth is outrunning your brain right now."
        )
    )

    // ═══════════════════════════════════════════════════════════════════════
    //  PROFILE STATE FRAGMENTS
    // ═══════════════════════════════════════════════════════════════════════

    private val profileStateFragments = mapOf(
        ProfileState.REPEAT_QUESTION to listOf(
            "You asked me this before. The answer hasn't changed.",
            "Again? You already know.",
            "We've been here before. You know what I said last time.",
            "This question keeps coming back. Maybe that's your answer.",
            "You keep circling this question. Time to act on it."
        ),
        ProfileState.ABOVE_NORMAL_HR to listOf(
            "Your heart is racing faster than your usual. This one matters to you.",
            "Your pulse is higher than normal right now. This isn't casual.",
            "I can tell this one has you wired — your heart rate is above your baseline.",
            "Your body is reacting to this question more than usual.",
            "That elevated heart rate? Your body already answered."
        ),
        ProfileState.LATE_FOR_USER to listOf(
            "You're up way past your normal bedtime asking me this.",
            "This is late for you. Whatever kept you up must be important.",
            "You're usually asleep by now. This question couldn't wait?",
            "Past your bedtime and still asking. That's dedication — or anxiety.",
            "The fact that you're still awake tells me this matters."
        ),
        ProfileState.UNUSUALLY_ACTIVE to listOf(
            "You've been on your feet more than usual today. Restless about something?",
            "More active than your normal day. Something's driving you.",
            "You've been moving more than usual. Can't sit still with this one?",
            "All this extra activity today — your body is processing something.",
            "Unusually active for you. That energy needs somewhere to go."
        )
    )

    // ═══════════════════════════════════════════════════════════════════════
    //  SHAKE EMOTION FRAGMENTS
    // ═══════════════════════════════════════════════════════════════════════

    private val shakeEmotionFragments = mapOf(
        ShakeEmotionState.CONTEMPLATIVE_SHAKE to listOf(
            "I appreciate the gentle ask. Here's a thoughtful answer.",
            "That careful shake deserves a careful answer.",
            "You asked that gently. Let me give you something worth sitting with.",
            "The way you asked tells me you're ready to really listen.",
            "A measured shake for a measured question. I respect that."
        ),
        ShakeEmotionState.DESPERATE_SHAKE to listOf(
            "Hey. Whatever this is — you're going to be okay.",
            "I feel the urgency. But hear me: it's going to work out.",
            "That shake was almost a prayer. Here's your answer.",
            "Easy. Take a breath. The world isn't ending, even if it feels like it.",
            "I've got you. Whatever this is, you'll get through it."
        ),
        ShakeEmotionState.PLAYFUL_SHAKE to listOf(
            "Ha — I like your energy.",
            "That was a fun one. Here's something equally fun.",
            "Nice shake. You're in a good mood and it shows.",
            "Playful question, playful answer.",
            "I like the vibe. Let's keep it going."
        ),
        ShakeEmotionState.IMPATIENT_SHAKE to listOf(
            "Fine. Here. Quick answer for a quick shake.",
            "In a hurry? Here's the short version.",
            "You shook me like you're in a rush. Here.",
            "Impatient. I get it. No preamble.",
            "That aggressive shake earned a blunt answer."
        )
    )

    // ═══════════════════════════════════════════════════════════════════════
    //  ORIGINAL FRAGMENT POOLS
    // ═══════════════════════════════════════════════════════════════════════

    // ── OPENINGS (~55 fragments) ──────────────────────────────────────────

    private fun getOpenings(mood: SensorMood, sentiment: Float): List<WeightedFragment> {
        val base = when (mood) {
            SensorMood.CALM -> calmOpenings
            SensorMood.TENSE -> tenseOpenings
            SensorMood.ENERGETIC -> energeticOpenings
            SensorMood.TIRED -> tiredOpenings
            SensorMood.LATE_NIGHT -> lateNightOpenings
        }
        // Blend in sentiment-driven openings
        val sentimentPool = if (sentiment < -0.3f) negativeOpenings
            else if (sentiment > 0.3f) positiveOpenings
            else neutralOpenings

        return base + sentimentPool
    }

    private val calmOpenings = listOf(
        w("Yes.", 1.5f),
        w("Absolutely.", 1.3f),
        w("Without question.", 1.2f),
        w("Trust yourself on this one.", 1.4f),
        w("Here's what I see.", 1.0f),
        w("You already know.", 1.2f),
        w("Listen closely.", 1.0f),
        w("The answer is clear.", 1.1f),
        w("Take this to heart.", 1.0f),
        w("Settle in.", 0.9f),
        w("You're in a good place for this.", 1.1f)
    )

    private val tenseOpenings = listOf(
        w("Take a breath first.", 1.5f),
        w("I hear you.", 1.3f),
        w("Here's the truth.", 1.4f),
        w("Okay, real talk.", 1.2f),
        w("I'm not going to sugarcoat this.", 1.1f),
        w("Brace yourself.", 1.0f),
        w("You need to hear this.", 1.3f),
        w("Let's cut through the noise.", 1.1f),
        w("Deep breath.", 1.2f),
        w("Hold on.", 1.0f),
        w("Before you spiral.", 1.1f)
    )

    private val energeticOpenings = listOf(
        w("Go.", 1.5f),
        w("Now.", 1.4f),
        w("Don't wait.", 1.3f),
        w("Full send.", 1.2f),
        w("Light's green.", 1.1f),
        w("Let's go.", 1.3f),
        w("Move.", 1.2f),
        w("This is it.", 1.1f),
        w("You're ready.", 1.2f),
        w("No hesitation.", 1.1f),
        w("Right now.", 1.0f)
    )

    private val tiredOpenings = listOf(
        w("Rest first, then decide.", 1.5f),
        w("You're running on fumes.", 1.3f),
        w("I know you're tired.", 1.2f),
        w("Easy does it.", 1.1f),
        w("Slow down.", 1.3f),
        w("Give yourself a break.", 1.2f),
        w("Not everything needs to happen today.", 1.1f),
        w("Low battery, big question.", 1.0f),
        w("You've been pushing hard.", 1.1f),
        w("Take a beat.", 1.0f),
        w("When you're rested, you'll see it clearly.", 1.2f)
    )

    private val lateNightOpenings = listOf(
        w("At this hour?", 1.5f),
        w("Sleep first.", 1.4f),
        w("The night makes everything feel bigger.", 1.3f),
        w("Late night honesty incoming.", 1.2f),
        w("After midnight, the rules change.", 1.1f),
        w("The dark is tricking you.", 1.0f),
        w("Night thoughts are louder.", 1.1f),
        w("Tomorrow's version of you would disagree.", 1.2f),
        w("Careful. Decisions at this hour rarely age well.", 1.3f),
        w("Okay, since you're still up.", 1.0f),
        w("This is a pillow question, not a phone question.", 1.1f)
    )

    private val positiveOpenings = listOf(
        w("Yes.", 1.6f),
        w("Absolutely.", 1.5f),
        w("One hundred percent.", 1.3f),
        w("Without a doubt.", 1.2f),
        w("Count on it.", 1.1f),
        w("The answer you want is the right one.", 1.0f)
    )

    private val negativeOpenings = listOf(
        w("No.", 1.6f),
        w("Not this time.", 1.4f),
        w("Walk away.", 1.3f),
        w("Hard pass.", 1.2f),
        w("That's a no.", 1.1f),
        w("Not even close.", 1.0f),
        w("Nope.", 1.1f),
        w("Don't do it.", 1.2f)
    )

    private val neutralOpenings = listOf(
        w("Interesting.", 1.0f),
        w("Let me think about that.", 1.1f),
        w("It depends.", 1.0f),
        w("Hmm.", 0.9f),
        w("Fair question.", 1.0f),
        w("Consider this.", 1.1f)
    )

    // ── OBSERVATIONS (~55 fragments) ──────────────────────────────────────

    private fun getObservations(
        mood: SensorMood,
        category: QuestionCategory
    ): List<WeightedFragment> {
        val moodPool = when (mood) {
            SensorMood.CALM -> calmObservations
            SensorMood.TENSE -> tenseObservations
            SensorMood.ENERGETIC -> energeticObservations
            SensorMood.TIRED -> tiredObservations
            SensorMood.LATE_NIGHT -> lateNightObservations
        }
        // Add category-flavored observations if available
        val categoryPool = categoryObservations[category] ?: emptyList()
        return moodPool + categoryPool
    }

    private val calmObservations = listOf(
        w("You already feel settled about this.", 1.3f),
        w("Your gut is quiet — that's peace.", 1.2f),
        w("Everything in you feels settled right now.", 1.1f),
        w("There's no tension in this question.", 1.0f),
        w("You're unusually calm right now — that's a good sign.", 1.1f),
        w("That stillness is your answer forming.", 1.0f),
        w("That quiet confidence? It's earned.", 1.1f),
        w("Something about this feels resolved already.", 1.2f),
        w("The noise has settled and you can think straight.", 1.0f),
        w("Peace like this doesn't lie.", 1.1f)
    )

    private val tenseObservations = listOf(
        w("Something about this one has you wired.", 1.3f),
        w("Your body is reacting before your brain catches up.", 1.2f),
        w("That racing feeling? It's not fear — it's readiness.", 1.1f),
        w("I can feel the tension in this question.", 1.0f),
        w("You're on edge, and that's data.", 1.1f),
        w("This one hit a nerve.", 1.2f),
        w("That knot in your stomach? That's your body telling you this matters.", 1.0f),
        w("Your pulse is telling a different story than your words.", 1.1f),
        w("There's electricity in this decision.", 1.0f),
        w("You're wound tight. Use that energy.", 1.1f)
    )

    private val energeticObservations = listOf(
        w("You've been on the move all day.", 1.2f),
        w("All that energy needs a direction.", 1.1f),
        w("You're restless for a reason.", 1.0f),
        w("You've got this kind of buzzy momentum going.", 1.1f),
        w("You've got fire right now. Aim it.", 1.2f),
        w("That drive is pushing you toward the right thing.", 1.0f),
        w("You're practically vibrating right now. Channel that.", 1.1f),
        w("The energy is there. The clarity will follow.", 1.0f),
        w("You're in motion and that matters.", 1.1f),
        w("All this activity is your body voting yes.", 1.0f)
    )

    private val tiredObservations = listOf(
        w("You've been sitting with this too long.", 1.2f),
        w("Stillness has turned to stagnation.", 1.1f),
        w("Your energy is low, but your instincts still work.", 1.0f),
        w("You're running on fumes and it shows.", 1.1f),
        w("You need fuel before decisions.", 1.0f),
        w("The exhaustion is speaking louder than the question.", 1.1f),
        w("Even your question sounds tired.", 1.0f),
        w("Rest would sharpen this answer.", 1.1f),
        w("You're dragging, and that's affecting your judgment.", 1.0f),
        w("Low energy doesn't mean wrong instincts.", 1.1f)
    )

    private val lateNightObservations = listOf(
        w("The darkness amplifies everything.", 1.2f),
        w("Late hours bend the truth.", 1.1f),
        w("Your defenses are down at this hour.", 1.0f),
        w("Night makes small things feel enormous.", 1.1f),
        w("The quiet is making you overthink.", 1.2f),
        w("Everything feels more urgent when the world is asleep.", 1.0f),
        w("This question is louder because everything else is silent.", 1.1f),
        w("Your filter is off. That can be honest or reckless.", 1.0f),
        w("The night strips away pretense.", 1.1f),
        w("Moonlight clarity is real, but so is sleep deprivation.", 1.0f)
    )

    private val categoryObservations = mapOf(
        QuestionCategory.RELATIONSHIP to listOf(
            w("Love doesn't wait for the perfect moment.", 1.0f),
            w("Hearts are messy. That's not a bug, it's a feature.", 1.0f),
            w("The person you're thinking about? They've crossed your mind three times today.", 1.1f),
            w("Connection isn't built on certainty.", 1.0f),
            w("You're asking about them, but the real question is about you.", 1.1f)
        ),
        QuestionCategory.CAREER to listOf(
            w("Your skills are worth more than your current paycheck.", 1.0f),
            w("The corporate ladder isn't the only way up.", 1.0f),
            w("Success and comfort rarely share the same address.", 1.1f),
            w("The market rewards the bold right now.", 1.0f),
            w("Your resume doesn't capture half of what you can do.", 1.1f)
        ),
        QuestionCategory.HEALTH to listOf(
            w("Your body keeps score.", 1.0f),
            w("Small consistent effort beats dramatic overhaul.", 1.1f),
            w("You already know what you need to change.", 1.0f),
            w("Health is wealth — literally.", 1.0f),
            w("The best time to start was yesterday. The second best is now.", 1.1f)
        ),
        QuestionCategory.FEAR to listOf(
            w("Fear is just excitement without permission.", 1.0f),
            w("The thing you're avoiding is exactly where growth lives.", 1.1f),
            w("Your worry is rehearsing a future that probably won't happen.", 1.0f),
            w("Anxiety lies. Check the receipts.", 1.0f),
            w("Courage is fear that said its prayers.", 1.1f)
        ),
        QuestionCategory.TIMING to listOf(
            w("Time is less linear than you think.", 1.0f),
            w("The window is open, but it won't stay that way.", 1.1f),
            w("Urgency and impatience are not the same thing.", 1.0f),
            w("The clock is ticking, but it's ticking in your favor.", 1.0f),
            w("Seasons change. So do opportunities.", 1.1f)
        )
    )

    // ── ADVICE (~60 fragments) ────────────────────────────────────────────

    private fun getAdvice(
        category: QuestionCategory,
        sentiment: Float
    ): List<WeightedFragment> {
        // Start with sentiment-biased general advice
        val sentimentPool = when {
            sentiment < -0.3f -> cautiousAdvice
            sentiment > 0.3f -> positiveAdvice
            else -> directAdvice
        }

        // Add category-specific advice
        val categoryPool = when (category) {
            QuestionCategory.RELATIONSHIP -> relationshipAdvice
            QuestionCategory.CAREER -> careerAdvice
            QuestionCategory.HEALTH -> healthAdvice
            QuestionCategory.FEAR -> fearAdvice
            QuestionCategory.TIMING -> timingAdvice
            QuestionCategory.YES_NO -> positiveAdvice + cautiousAdvice
            QuestionCategory.GENERAL -> directAdvice
        }

        return sentimentPool + categoryPool
    }

    private val positiveAdvice = listOf(
        w("Go for it.", 1.4f),
        w("Say yes.", 1.3f),
        w("Take the leap.", 1.2f),
        w("This is your moment.", 1.1f),
        w("Trust the feeling.", 1.2f),
        w("Lean in.", 1.0f),
        w("You've got this.", 1.1f),
        w("The upside is bigger than you think.", 1.0f),
        w("Commit fully.", 1.1f),
        w("Bet on yourself.", 1.2f),
        w("The risk is worth it.", 1.0f),
        w("You'll surprise yourself.", 1.1f)
    )

    private val cautiousAdvice = listOf(
        w("But check in with yourself tomorrow.", 1.3f),
        w("Give it 48 hours.", 1.2f),
        w("Move carefully.", 1.1f),
        w("Protect your energy.", 1.2f),
        w("Don't rush this one.", 1.0f),
        w("Sleep on it first.", 1.1f),
        w("Guard your peace.", 1.0f),
        w("Tread lightly.", 1.0f),
        w("Proceed, but keep your eyes open.", 1.1f),
        w("Hold your cards close.", 1.0f),
        w("Think twice before you act.", 1.1f),
        w("Measure twice, cut once.", 1.0f)
    )

    private val directAdvice = listOf(
        w("Stop overthinking.", 1.4f),
        w("You already know.", 1.3f),
        w("Do the thing.", 1.2f),
        w("Make the call.", 1.1f),
        w("Decide and move.", 1.2f),
        w("Pick one and commit.", 1.0f),
        w("Action beats analysis.", 1.1f),
        w("Trust your gut.", 1.2f),
        w("The answer is in front of you.", 1.0f),
        w("Less thinking, more doing.", 1.1f),
        w("Stop waiting for a sign. This is the sign.", 1.0f),
        w("Flip the switch.", 1.0f)
    )

    private val relationshipAdvice = listOf(
        w("Tell them how you feel.", 1.3f),
        w("Let it go.", 1.2f),
        w("They're worth it.", 1.1f),
        w("You deserve better.", 1.2f),
        w("Stop reading into their texts.", 1.0f),
        w("Have the hard conversation.", 1.1f),
        w("Show up for them.", 1.0f),
        w("Set a boundary.", 1.1f),
        w("Open your heart, but protect it too.", 1.0f),
        w("Love is a verb. Act on it.", 1.1f),
        w("Don't settle for convenience.", 1.0f),
        w("Be honest, even when it's uncomfortable.", 1.1f)
    )

    private val careerAdvice = listOf(
        w("Update your resume.", 1.3f),
        w("Ask for the raise.", 1.2f),
        w("Start the side project.", 1.1f),
        w("This job isn't forever.", 1.2f),
        w("Network before you need to.", 1.0f),
        w("Document your wins.", 1.1f),
        w("Learn the new skill.", 1.0f),
        w("Take the meeting.", 1.1f),
        w("Negotiate harder.", 1.0f),
        w("Build something of your own.", 1.1f),
        w("Invest in yourself first.", 1.0f),
        w("Play the long game.", 1.1f)
    )

    private val healthAdvice = listOf(
        w("Drink water right now.", 1.3f),
        w("Move your body today.", 1.2f),
        w("Book the appointment.", 1.1f),
        w("Start small. One habit this week.", 1.2f),
        w("Get outside for ten minutes.", 1.0f),
        w("Put the phone down.", 1.1f),
        w("Prioritize sleep tonight.", 1.0f),
        w("Stretch. Seriously.", 1.1f),
        w("Your body is asking for attention. Listen.", 1.0f),
        w("Eat something green.", 1.0f),
        w("Take the walk.", 1.1f),
        w("Rest is productive.", 1.0f)
    )

    private val fearAdvice = listOf(
        w("Name the fear out loud.", 1.3f),
        w("Do it scared.", 1.2f),
        w("Feel it, then move through it.", 1.1f),
        w("The fear is lying to you.", 1.2f),
        w("Breathe through it.", 1.0f),
        w("One small step forward.", 1.1f),
        w("You've survived worse.", 1.0f),
        w("Call a friend. Say it out loud.", 1.1f),
        w("Write down the worst case. Then the most likely case.", 1.0f),
        w("Shrink the problem to its actual size.", 1.1f),
        w("What would the brave version of you do?", 1.0f),
        w("Fear fades. Regret doesn't.", 1.1f)
    )

    private val timingAdvice = listOf(
        w("The window is now.", 1.3f),
        w("Give it one more week.", 1.2f),
        w("Stop waiting for perfect.", 1.1f),
        w("Soon, but not yet.", 1.2f),
        w("Start today, even imperfectly.", 1.0f),
        w("The timing will never feel right. Go anyway.", 1.1f),
        w("By the end of this month.", 1.0f),
        w("Patience. It's almost here.", 1.1f),
        w("You're early, not wrong.", 1.0f),
        w("Three more weeks, then reassess.", 1.0f),
        w("Move now. Adjust later.", 1.1f),
        w("The calendar is on your side.", 1.0f)
    )

    // ── CLOSINGS (~25 fragments) ──────────────────────────────────────────

    private val closings = listOf(
        w("Today.", 1.2f),
        w("Right now.", 1.1f),
        w("Before you sleep.", 1.0f),
        w("This week.", 1.1f),
        w("When you're ready.", 1.0f),
        w("No rush.", 0.9f),
        w("— trust me.", 1.2f),
        w("", 2.0f),  // empty — no closing sometimes, higher weight
        w("", 1.5f),  // second empty to increase no-closing odds
        w("You'll thank yourself.", 1.0f),
        w("The rest will follow.", 0.9f),
        w("And don't look back.", 1.1f),
        w("You've got time.", 0.9f),
        w("One step at a time.", 1.0f),
        w("Before the moment passes.", 1.0f),
        w("Starting now.", 1.0f),
        w("On your terms.", 1.0f),
        w("— and mean it.", 1.1f),
        w("That's the move.", 1.0f),
        w("End of story.", 0.9f),
        w("Full stop.", 0.8f),
        w("While you still can.", 1.0f),
        w("No excuses.", 1.0f),
        w("— I'm serious.", 1.0f),
        w("Let that sink in.", 0.9f)
    )

    // ── STANDALONE RESPONSES (~40 complete bypass responses) ──────────────

    private fun getStandalones(
        category: QuestionCategory,
        mood: SensorMood,
        sentiment: Float
    ): List<String> {
        val pool = mutableListOf<String>()

        // Universal standalones
        pool.addAll(universalStandalones)

        // Sensor-specific zingers (direct sensor references)
        sensorZingers[mood]?.let { pool.addAll(it) }

        // Mood-specific
        when (mood) {
            SensorMood.CALM -> pool.addAll(listOf(
                "You asked that with total composure. You already know the answer.",
                "Your calm energy answers this better than I can.",
                "Clear mind, clear path. Walk it.",
                "The stillness in you is the answer."
            ))
            SensorMood.TENSE -> pool.addAll(listOf(
                "Take three breaths. Now read this: you're going to be fine.",
                "You're wound up, and that's okay. The answer is still yes.",
                "I can feel the stakes in this question. Trust your training.",
                "Tension means you care. That's not weakness — it's fuel."
            ))
            SensorMood.ENERGETIC -> pool.addAll(listOf(
                "That energy? Point it at the thing you've been avoiding.",
                "You're vibrating at the right frequency for this. Go.",
                "You didn't come this far to only come this far.",
                "Momentum like this doesn't come around often. Use it."
            ))
            SensorMood.TIRED -> pool.addAll(listOf(
                "You're asking the right question at the wrong time. Sleep first.",
                "Tired decisions become tomorrow's problems. Rest up.",
                "Your heart knows, but your brain needs sleep to hear it.",
                "This deserves your full energy. Come back to it refreshed."
            ))
            SensorMood.LATE_NIGHT -> pool.addAll(listOf(
                "Put the phone down. The answer will still be there in the morning.",
                "Nothing good happens after 2 AM. Including asking a watch for advice.",
                "The night is lying to you. Go to sleep.",
                "Late night clarity is just exhaustion wearing a costume."
            ))
        }

        // Category-specific
        when (category) {
            QuestionCategory.RELATIONSHIP -> pool.addAll(listOf(
                "If they wanted to, they would. You know this.",
                "Stop checking their social media and start living your life.",
                "The right person won't make you question everything.",
                "You're not asking about love. You're asking about yourself."
            ))
            QuestionCategory.CAREER -> pool.addAll(listOf(
                "Your dream job won't come from playing it safe.",
                "Nobody on their deathbed wished they'd spent more time at a job they hate.",
                "You're not stuck. You're just scared to move. There's a difference.",
                "Bet on your skills. The market will catch up."
            ))
            QuestionCategory.HEALTH -> pool.addAll(listOf(
                "Your body is the only place you have to live. Take care of it.",
                "You don't need a plan. You need to start.",
                "Every expert was once a beginner. Lace up.",
                "The best workout is the one you actually do."
            ))
            QuestionCategory.FEAR -> pool.addAll(listOf(
                "Everything you want is on the other side of fear.",
                "You've been through worse and you're still here.",
                "Fear is a compass. It points toward growth.",
                "The scary path and the right path are usually the same."
            ))
            QuestionCategory.TIMING -> pool.addAll(listOf(
                "If not now, when? If not you, who?",
                "You've been waiting for a sign. This is it.",
                "The best time was yesterday. The second best time is right now.",
                "Stop watching the clock and start making moves."
            ))
            else -> {} // YES_NO and GENERAL use universal standalones
        }

        // Sentiment bias — filter toward positive or negative standalones
        return if (sentiment < -0.3f) {
            pool.filter { !it.startsWith("Yes") && !it.contains("go for it", ignoreCase = true) }
        } else {
            pool
        }
    }

    private val universalStandalones = listOf(
        "You already decided. You just wanted someone to agree with you.",
        "Trust yourself more than you trust a watch app.",
        "Go with your first instinct. It's usually right.",
        "The best decision is the one you actually make.",
        "You didn't need me for this one. But yes.",
        "Flip a coin. Your reaction to the result is the real answer.",
        "If it won't matter in five years, don't spend five minutes worrying about it.",
        "You're overthinking this. Just pick one and commit.",
        "Life's too short for maybe. Make it a yes or a no.",
        "The universe doesn't owe you a perfect moment. Create your own.",
        "You know what to do. You just don't want to do it.",
        "Bold move. I respect it. Do it.",
        "What's the worst that could happen? Now halve that. See?",
        "Less planning, more doing. That's always the answer.",
        "Stop asking the universe and start asking yourself.",
        "The answer is yes. It's always been yes. Now act on it.",
        // ── Blunt / witty standalones ──
        "You didn't need me for this one.",
        "That's a no, and you knew it before you asked.",
        "Shaking harder won't change the answer.",
        "You're procrastinating by asking me instead of doing the thing.",
        "The answer is the same as last time.",
        "I'm a watch, not a therapist. But yes.",
        "You just wanted permission. Fine. Permission granted.",
        "Ask me again and the answer gets worse.",
        "You've asked three different people the same question hoping for a different answer.",
        "The answer isn't going to change just because you don't like it.",
        "That question answered itself.",
        "I don't have a magic 8-ball. Oh wait, I literally do. Yes.",
        "Stop shaking me and start shaking things up.",
        "You already know. You're just being a coward about it.",
        "Imagine explaining this decision to yourself in five years. There's your answer.",
        "This is the most obvious yes I've ever seen.",
        "You're stalling. Cut it out."
    )

    // ── Sensor-specific zingers (bypass composition, used for direct sensor refs) ──

    private val sensorZingers = mapOf(
        SensorMood.TENSE to listOf(
            "Your heart rate just spiked asking that. There's your answer.",
            "I can literally feel your pulse racing through the sensors. Breathe.",
            "Your wrist is tense, your heart is loud, and your question is obvious.",
            "That spike in your pulse? That's not anxiety. That's you caring.",
            "Your heart answered before your mouth finished asking."
        ),
        SensorMood.LATE_NIGHT to listOf(
            "It's 3 AM. Nothing good comes from 3 AM decisions.",
            "The sun isn't up and neither should your decision-making be.",
            "You're doom-scrolling your own life at midnight. Go to bed.",
            "Late night you is not the CEO of your life. Morning you is.",
            "Everything feels like a crisis after midnight. It's not. Sleep."
        ),
        SensorMood.ENERGETIC to listOf(
            "You've been on your feet all day and you're still going. That's your answer.",
            "All that restless energy? You already know where it wants to go.",
            "You've been moving nonstop. Your body already made this decision.",
            "That can't-sit-still feeling? It's trying to tell you something."
        ),
        SensorMood.TIRED to listOf(
            "Your body is screaming for rest and you're asking me for life advice.",
            "You're one yawn away from a bad decision. Sleep first.",
            "Low battery mode is for phones, not for life choices. Recharge.",
            "Even your question sounded exhausted. Take a nap, then ask again."
        ),
        SensorMood.CALM to listOf(
            "You're suspiciously calm about this. Either you've made peace or you've given up.",
            "Heart rate is steady, breathing is even. You already decided, didn't you?",
            "That zen thing you've got going? Ride it. The answer is clear from here.",
            "You asked that without a hint of panic. That's rare. Trust it."
        )
    )
}
