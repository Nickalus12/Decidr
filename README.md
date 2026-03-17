# Decidr

**The world's most intelligent decision-making app for Samsung Galaxy Watch Ultra.**

Decidr doesn't just flip coins — it reads your body, listens to your voice, and understands your question to deliver responses that feel genuinely psychic. Built with 9 sensor integrations, voice frequency analysis, real-time sentiment detection, and a hybrid neural response engine that generates 13.5 million unique answers.

---

## Features

### Four Decision Modes

| Mode | Description |
|------|-------------|
| **Coin Flip** | 3D metallic gold coin with specular highlights, embossed text, sparkle effects, idle wobble, flip trail, and spring-bounce landing. Shake or tap to flip. |
| **Wheel Spin** | Game-show quality donut wheel with gradient segments, gold rim tick marks, pointer bounce physics, particle burst on win, and color-matched edge aura. |
| **Dice Roll** | Isometric 3D dice with front/right/top faces, proper pip rendering with highlights and shadows, spring bounce animation. 1-2 dice with total. |
| **Magic 8-Ball** | Liquid physics sphere with 40 depth-particles, accelerometer-reactive sloshing, specular glass reflection, voice input, and the Neural Response Engine. |

### The Magic 8-Ball — What Makes It Special

The 8-Ball is the centerpiece. It doesn't give random answers — it **reads you**.

**Voice Intelligence:**
- Captures raw audio via the watch microphone at 16kHz
- FFT-based pitch detection identifies your fundamental frequency
- Voice stress analysis detects micro-tremors (8-12 Hz fluctuations)
- Speaking pace estimation (syllable counting via zero-crossings)
- Emotional tone classification: Calm, Anxious, Confident, Excited, Sad, Angry, Whispered, Uncertain

**Sensor Awareness (9 sensors):**
- Heart rate (PPG) — elevated HR biases toward calming, direct responses
- Barometric pressure — dropping pressure triggers cautionary tone
- Magnetometer — erratic fields add uncertainty to responses; stable fields provide entropy for randomness
- Accelerometer — shake quality analysis reveals emotional state
- Gyroscope — wrist rotation detection, movement patterns
- Light sensor — dark environments get more atmospheric delivery
- Step counter — high activity gets action-oriented responses
- Gravity sensor — tilt-reactive liquid particle physics
- Rotation vector — 3D orientation tracking

**Advanced Shake Analysis:**
- Jerk analysis (acceleration derivative) classifies shake smoothness
- Pre-shake stillness measurement — long pause before shaking = contemplative
- Post-shake patience — holding still after = patient, moving = impatient
- Pattern detection: Single Jolt, Steady Shake, Erratic, Gentle Roll, Aggressive, Playful
- Emotional classification: Deliberate, Anxious, Impatient, Playful, Desperate, Contemplative

**User Profiling:**
- Learns YOUR personal baselines over 7+ days via exponential moving average
- Knows your typical resting heart rate, voice pitch, daily step count, active hours
- Detects deviations: "Your heart rate is 18% above YOUR normal"
- Tracks question history — catches repeat questions
- Identifies your most-asked category (relationships, career, health, etc.)
- Persists across app restarts via SharedPreferences

**Neural Response Engine (128KB, 12ms):**
- Template Composition: 327 fragments across 4 slots = 13.5M+ unique combinations
- Sentiment Analyzer: 789-word AFINN lexicon with negation, intensifiers, mood detection
- Markov Generator: Trigram chain trained on 260 responses for novel variations
- Quality Pipeline: 5 retries, duplicate detection via Jaccard overlap, auto-polish
- Question Understanding: Keyword-based intent classification (yes/no, relationship, career, health, timing, fear)

### Visual Design

- Deep black AMOLED backgrounds (#0A0A14) — zero wasted pixels
- Cyan accent (#00E5FF) with color-matched edge auras
- Custom Canvas-drawn icons — no emoji, no images
- Spring physics animations throughout
- Rich haptic feedback: light taps, heavy thuds, celebration buzzes, crescendo reveals

---

## Architecture

```
com.brewtech.decidr/
├── ui/                          # Compose UI screens
│   ├── HomeScreen.kt            # Diamond layout hub with orb buttons
│   ├── CoinFlipScreen.kt        # 3D metallic coin with animations
│   ├── WheelSpinScreen.kt       # Game-show wheel with particle effects
│   ├── DiceRollScreen.kt        # Isometric 3D dice
│   ├── MagicBallScreen.kt       # Liquid physics sphere (825 lines)
│   └── theme/DecidrTheme.kt     # Color palette & reusable composables
│
├── intelligence/                 # Neural Response Engine
│   ├── NeuralResponse.kt        # Master orchestrator (pipeline + quality)
│   ├── TemplateComposer.kt      # Tracery-style fragment composition
│   ├── SentimentAnalyzer.kt     # 789-word AFINN lexicon
│   └── MarkovGenerator.kt       # Trigram chain text generator
│
├── voice/                        # Voice Intelligence
│   ├── VoiceAnalyzer.kt         # FFT pitch detection, stress analysis
│   ├── VoiceRecognizer.kt       # Speech-to-text wrapper
│   └── QuestionParser.kt        # Intent classification
│
├── sensor/                       # Sensor Intelligence
│   ├── SensorHub.kt             # 9-sensor aggregator with computed values
│   ├── ShakeDetector.kt         # Basic shake detection
│   └── ShakeAnalyzer.kt         # Advanced shake quality & emotion analysis
│
├── profile/                      # User Profiling
│   └── UserProfile.kt           # Persistent baselines, deviation detection
│
├── haptic/                       # Haptic Feedback
│   └── HapticEngine.kt          # 5 distinct vibration patterns
│
├── oracle/                       # Legacy response engine
│   └── OracleEngine.kt          # Original template system (superseded)
│
└── MainActivity.kt              # App entry point, sensor lifecycle
```

---

## Tech Stack

| Component | Technology |
|-----------|-----------|
| **UI** | Jetpack Compose for Wear OS |
| **Navigation** | SwipeDismissableNavHost |
| **Graphics** | Compose Canvas API (all custom drawing) |
| **Audio** | Android AudioRecord (16kHz PCM) |
| **DSP** | Autocorrelation pitch detection, FFT |
| **NLP** | AFINN sentiment, keyword intent classification |
| **Text Gen** | Tracery-style composition + Markov chains |
| **Sensors** | Android SensorManager (9 sensor types) |
| **Speech** | Android RecognizerIntent (on-device STT) |
| **Storage** | SharedPreferences + JSON serialization |
| **Haptics** | Android VibrationEffect API |

---

## Building

### Requirements
- Android Studio (Arctic Fox or later)
- JDK 17+
- Android SDK 34
- A Wear OS device or emulator (optimized for Galaxy Watch Ultra 480x480)

### Build & Install
```bash
# Clone
git clone https://github.com/Nickalus12/Decidr.git
cd Decidr

# Build
./gradlew assembleDebug

# Install to connected watch
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch
adb shell am start -n com.brewtech.decidr/.MainActivity
```

---

## Target Device

Optimized for **Samsung Galaxy Watch Ultra** (SM-L705U):
- 480x480 circular AMOLED display
- Exynos W1000 (Cortex-A78 + 4x A55)
- 2GB RAM, 32GB storage
- Wear OS 6 / One UI 8 Watch
- 9 sensors: accelerometer, gyroscope, magnetometer, barometer, light, heart rate, step counter, gravity, rotation vector

Works on any Wear OS 3+ device, but visual design is tuned for the Watch Ultra's round 480px display.

---

## How the 8-Ball "Reads" You

```
You speak: "Should I quit my job?"
    │
    ├─ VoiceAnalyzer ──── pitch: 185Hz (elevated), stress: 0.6, pace: fast
    ├─ QuestionParser ──── category: CAREER (keyword: "quit", "job")
    ├─ SentimentAnalyzer ── score: -0.3 (negative/worried)
    ├─ SensorHub ────────── HR: 92 (TENSE), steps: 2100 (LOW), 11PM (LATE_NIGHT)
    ├─ ShakeAnalyzer ────── pattern: ERRATIC, emotion: ANXIOUS
    ├─ UserProfile ──────── HR is 22% above YOUR baseline, asked about career 3x this week
    │
    ▼
NeuralResponse selects: TemplateComposer (clear career question detected)
    │
    ├─ Opening: "Take a breath first." (TENSE mood)
    ├─ Observation: "You've asked about this three times now." (UserProfile)
    ├─ Advice: "Update your resume this week. Trust me." (CAREER category)
    │
    ▼
"Take a breath first. You've asked about this three times now. Update your resume this week. Trust me."
```

---

## License

MIT License. See [LICENSE](LICENSE) for details.

---

## Author

**Nickalus Brewer** — [@Nickalus12](https://github.com/Nickalus12)

Built with love for the Samsung Galaxy Watch Ultra.
