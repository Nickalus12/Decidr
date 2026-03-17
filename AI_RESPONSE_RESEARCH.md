# AI Response System Research: Lightweight Alternatives to Full LLMs

**Target Device:** Samsung Galaxy Watch Ultra (2GB RAM, Exynos W1000, Wear OS)
**Goal:** Intelligent response generation that FEELS like an LLM but runs instantly without full model overhead
**Context:** Magic 8-Ball oracle app with 300+ template responses; SmolLM-135M produced incoherent output

---

## Table of Contents
1. [Markov Chain Text Generation](#1-markov-chain-text-generation)
2. [N-gram Language Models](#2-n-gram-language-models)
3. [Template Composition / Procedural Generation](#3-template-composition--procedural-generation)
4. [Retrieval-Augmented Response Selection](#4-retrieval-augmented-response-selection)
5. [Sentence Transformers / Semantic Search](#5-sentence-transformers--semantic-search)
6. [Rule-Based NLU](#6-rule-based-nlu)
7. [TFLite Text Classification](#7-tflite-text-classification)
8. [Hybrid Architecture (Recommended)](#8-hybrid-architecture-recommended)
9. [Lightweight NLP Libraries for Android](#9-lightweight-nlp-libraries-for-android)
10. [On-Device Conversational AI without LLMs](#10-on-device-conversational-ai-without-llms)
11. [Final Recommendation](#11-final-recommendation)

---

## 1. Markov Chain Text Generation

### How It Works
A Markov chain generates text by predicting the next word based solely on the previous N words (the "order" of the chain). It has no long-term memory -- each word choice depends only on the immediately preceding words. For a 2nd-order chain, given "the stars", it picks the next word from all words that followed "the stars" in training data, weighted by frequency.

### Training on 300+ Oracle Responses
- Train on the existing 300+ oracle responses as corpus
- The chain learns word transition probabilities from these responses
- Can generate novel sentence variations that sound stylistically similar
- **Quality depends heavily on corpus size** -- 300 responses is a small corpus for Markov chains
- Higher-order chains (3-4) produce more coherent text but less novelty
- Lower-order chains (1-2) produce more variety but can be nonsensical

### Markovify (Python) -- Train & Export as JSON
- **Library:** [github.com/jsvine/markovify](https://github.com/jsvine/markovify)
- Train in Python, export model as JSON: `model.to_json()` / `markovify.Text.from_json(json_string)`
- The JSON contains the transition probability table (state -> next_word -> count)
- **Export format:** Nested dictionary: `{ "state": { "next_word": count, ... }, ... }`
- Can load this JSON in Kotlin and implement the sampling logic natively

### Pure Kotlin Implementation
No established Kotlin Markov chain library exists, but implementation is straightforward:

```kotlin
// Core data structure: Map<List<String>, MutableMap<String, Int>>
// Key = N previous words, Value = map of next_word to frequency count
class MarkovChain(private val order: Int = 2) {
    private val chain: MutableMap<List<String>, MutableMap<String, Int>> = mutableMapOf()

    fun train(text: String) { /* tokenize, slide window, build counts */ }
    fun generate(maxWords: Int = 20): String { /* random walk through chain */ }
    fun toJson(): String { /* serialize with kotlinx.serialization */ }
    fun fromJson(json: String) { /* deserialize */ }
}
```

Use `kotlinx.serialization` for JSON serialization -- it's already standard in Kotlin/Android projects.

### Quality Assessment
- **Pros:** Can generate novel variations; easy to implement; deterministic behavior
- **Cons:** With only 300 responses (~3,000-5,000 words), output can be repetitive or incoherent
- **Mitigation:** Use order 2-3, add post-generation filtering (min length, no repeated words, etc.)
- **Best use:** Generating variations of EXISTING response patterns, not wholly new content

### Memory & Speed
| Metric | Value |
|--------|-------|
| Model size (300 responses) | ~50-200 KB JSON |
| RAM at runtime | ~500 KB |
| Generation speed | <1ms per sentence |
| Implementation complexity | Low |

---

## 2. N-gram Language Models

### Overview
N-gram models predict the next word based on the previous (N-1) words. Similar to Markov chains but formalized as a language model with probability distributions and smoothing techniques.

### Tiny N-gram Models for On-Device
- **Trigram (3-gram):** Each prediction uses 2 previous words
- **4-gram:** Uses 3 previous words, better coherence
- Key optimization: **Stupid Backoff** -- if no 4-gram match, fall back to trigram, then bigram

### Op-Ngram: Optimized for Mobile
Research on "Real-Time Optimized N-Gram for Mobile Devices" (Op-Ngram) shows:
- **37% reduction** in model ROM size
- **76% reduction** in RAM usage
- **88% reduction** in loading time
- **89% reduction** in suggestion time
- Uses pruning strategies to keep only high-frequency n-grams

### Building & Deploying on Wear OS
1. **Train in Python:** Build n-gram frequency tables from corpus
2. **Prune:** Remove n-grams with count < threshold (keep top 80%)
3. **Export:** Save as compact binary or JSON lookup table
4. **Deploy:** Load in Kotlin, implement backoff sampling

### Memory & Speed
| Metric | Value |
|--------|-------|
| Model size (300 responses, pruned) | ~100-500 KB |
| RAM at runtime | ~200 KB - 1 MB |
| Prediction speed | <1ms |
| Quality vs Markov chains | Similar, slightly better with smoothing |

### Verdict for Our Use Case
N-grams and Markov chains are essentially the same approach at different formalization levels. For 300 oracle responses, there isn't enough corpus data to train a meaningful n-gram model. **Better suited as a component** (word prediction) rather than standalone generation.

---

## 3. Template Composition / Procedural Generation

### The Core Idea
Instead of 300 fixed templates, decompose responses into composable sentence fragments:

```
[opening] + [observation] + [advice] + [closing]
```

Example:
> "Your heart tells me" + "you already know the answer" + ". Go for it" + " -- today."

### Combinatorial Power

| Component | Count | Examples |
|-----------|-------|---------|
| Openings | 50 | "The stars reveal...", "Your heart tells me...", "I sense that..." |
| Observations | 50 | "change is coming", "you already know the answer", "the timing is right" |
| Advice | 50 | "Trust your instincts", "Wait for clarity", "Take the leap" |
| Closings | 20 | "...today.", "...when the moment feels right.", "...and don't look back." |

**Total unique combinations: 50 x 50 x 50 x 20 = 2,500,000 responses**

Even with compatibility filtering (not all fragments work together), you'd have hundreds of thousands of grammatically correct, meaningful responses.

### Tracery: Grammar-Based Generation
- **Library:** [github.com/galaxykate/tracery](https://github.com/galaxykate/tracery)
- JSON grammar format for procedural text generation
- Originally JavaScript, ports exist for Python, Rust, and other languages
- Supports modifiers: `.capitalize`, `.s` (plurals), `.ed` (past tense), `.a` (a/an)

**Tracery grammar example for oracle:**
```json
{
    "origin": ["#opening# #observation#. #advice##closing#"],
    "opening": ["The stars reveal", "Your heart tells me", "I sense that", "The universe whispers"],
    "observation": ["change is upon you", "you already know the answer", "a new path awaits"],
    "advice": [". Trust your instincts", ". Wait for the right moment", ". Take the leap"],
    "closing": [" -- today.", " when you're ready.", " and don't look back."]
}
```

### Advanced Techniques
- **Weighted selection:** Assign probability weights to fragments based on question sentiment
- **Compatibility tags:** Mark which fragments work together grammatically
- **Contextual selection:** Use question keywords to select relevant fragment pools
- **Tone matching:** Tag fragments as positive/negative/neutral, match to question tone

### Memory & Speed
| Metric | Value |
|--------|-------|
| Grammar file size | ~20-50 KB JSON |
| RAM at runtime | ~100 KB |
| Generation speed | <1ms |
| Unique combinations | 2,500,000+ |
| Implementation complexity | Low-Medium |

### Verdict
**This is the highest-value, lowest-risk approach.** Guaranteed grammatical output, massive variety, tiny footprint, instant speed. The main challenge is crafting fragments that combine well -- this is a creative/writing task, not a technical one.

---

## 4. Retrieval-Augmented Response Selection

### Concept
Instead of generating text, MATCH the user's question to the most relevant pre-written response using semantic similarity:

1. Pre-compute embeddings for all 300 responses
2. At runtime, embed the user's question
3. Find the closest response(s) using cosine similarity
4. Return the best match

### Tiny Embedding Models for TFLite

| Model | Size | Parameters | Embedding Dim | Speed | Viable? |
|-------|------|------------|---------------|-------|---------|
| **model2vec potion-base-2M** | ~3 MB | 1.8M | 256 | ~25K sent/sec | YES |
| **model2vec potion-base-4M** | ~5 MB | 3.7M | 256 | ~25K sent/sec | YES |
| **model2vec potion-base-8M** | ~8 MB | 7.5M | 256 | ~25K sent/sec | YES |
| NanoBERT (LoRA) | ~3 MB | - | - | Fast | MAYBE |
| MobileBERT-tiny | ~15 MB | 15M | 512 | ~30ms | MAYBE |
| MobileBERT | ~25 MB | 25M | 512 | ~60ms | STRETCH |
| all-MiniLM-L6-v2 | ~91 MB | 22M | 384 | ~100ms | TOO BIG |
| all-MiniLM-L6-v2 (INT8) | ~23 MB | 22M | 384 | ~50ms | MAYBE |

### Model2Vec: Best Option for Smartwatch
- **GitHub:** [github.com/MinishLab/model2vec](https://github.com/MinishLab/model2vec)
- **Android lib:** [github.com/shubham0204/Sentence-Embeddings-Android](https://github.com/shubham0204/Sentence-Embeddings-Android)
- Static embeddings via fast lookup table -- no transformer inference needed
- potion-base-2M is ~3 MB, achieves ~80-90% accuracy of full transformer models
- **500x faster** than sentence-transformers on CPU
- Available as Maven dependency: `io.gitlab.shubham0204:model2vec:v6`

### Implementation Flow
```
[User question]
    -> tokenize
    -> lookup embeddings in model2vec table
    -> average token embeddings
    -> cosine similarity with 300 pre-computed response embeddings
    -> return top match
```

### Pre-computed Response Embeddings
- 300 responses x 256 dimensions x 4 bytes = ~307 KB
- Store as a flat binary file, load into memory at startup
- Cosine similarity over 300 vectors: <1ms

### Memory & Speed
| Metric | Value |
|--------|-------|
| Model size (potion-base-2M) | ~3 MB |
| Pre-computed embeddings | ~307 KB |
| RAM at runtime | ~5-10 MB |
| Inference speed | ~5-10ms |
| Quality | Good semantic matching |

### Verdict
**Excellent approach** for making responses feel contextually relevant. The user asks about love, they get a love-related response. model2vec makes this feasible on a smartwatch. Combine with template composition for best results.

---

## 5. Sentence Transformers / Semantic Search

### Model Size Comparison

| Model | FP32 Size | ONNX INT8 | Viable on Watch? |
|-------|-----------|-----------|------------------|
| all-MiniLM-L6-v2 | 91 MB | ~23 MB | Borderline |
| paraphrase-MiniLM-L3-v2 | 69 MB | ~17 MB | Borderline |
| bge-small-en-v1.5 | 130 MB | ~33 MB | Too big |
| snowflake-arctic-embed-s | ~130 MB | ~33 MB | Too big |
| **model2vec potion-base-8M** | **8 MB** | **N/A (static)** | **YES** |
| **model2vec potion-base-2M** | **~3 MB** | **N/A (static)** | **YES** |

### ONNX Runtime on Android
- Sentence-Embeddings-Android uses ONNX Runtime compiled as native libraries
- Supports FP32 and FP16 precision
- Optional XNNPACK acceleration
- NDK r28b for 16KB page-size support (modern Android)
- Maven: `io.gitlab.shubham0204:sentence-embeddings:v6.1`

### Key Insight
Traditional sentence transformers (MiniLM, BERT variants) are **too large** for a smartwatch at 70-130 MB. Even quantized INT8 versions at 17-33 MB push the limits.

**model2vec is the clear winner** here -- it achieves 80-90% of the accuracy at 3-8 MB with 500x faster inference. It uses static lookup tables instead of transformer inference, so there's no heavy computation.

---

## 6. Rule-Based NLU (Natural Language Understanding)

### Intent Classification Without ML
Classify user questions into categories using pattern matching:

```kotlin
enum class QuestionIntent {
    LOVE,           // "love", "relationship", "partner", "heart", "crush"
    CAREER,         // "job", "work", "career", "boss", "promotion"
    MONEY,          // "money", "rich", "invest", "financial", "afford"
    HEALTH,         // "health", "sick", "exercise", "diet", "weight"
    DECISION,       // "should I", "choose", "decide", "pick", "option"
    TIMING,         // "when", "how long", "soon", "time", "ready"
    YES_NO,         // "will", "is it", "am I", "can I", "does"
    GENERAL         // fallback
}
```

### Regex + Keyword Patterns
```kotlin
val intentPatterns = mapOf(
    QuestionIntent.LOVE to listOf(
        Regex("\\b(love|relationship|partner|boyfriend|girlfriend|crush|marriage|dating|heart)\\b", IGNORE_CASE)
    ),
    QuestionIntent.CAREER to listOf(
        Regex("\\b(job|work|career|boss|promotion|hire|fired|interview|salary)\\b", IGNORE_CASE)
    ),
    // ...
)

fun classifyIntent(question: String): QuestionIntent {
    return intentPatterns.maxByOrNull { (_, patterns) ->
        patterns.count { it.containsMatchIn(question) }
    }?.key ?: QuestionIntent.GENERAL
}
```

### Sentiment Analysis with AFINN Lexicon
- **AFINN:** Word list with 3,000+ words scored -5 to +5
- **Kotlin library exists:** [github.com/kotlin-tools/afinn](https://github.com/kotlin-tools/afinn) -- Maven dependency available
- Score the user's question to detect emotional tone
- Use sentiment to bias response selection (positive question -> encouraging response)

**VADER-like rules** (can implement in pure Kotlin):
- Negation handling: "not happy" flips sentiment
- Intensifiers: "very", "extremely" amplify scores
- Punctuation: "!!!" increases intensity
- Capitalization: "GREAT" vs "great"

### Entity Extraction with Regex
```kotlin
// Extract names
val namePattern = Regex("(?:about|with|for)\\s+([A-Z][a-z]+)")

// Extract time references
val timePattern = Regex("\\b(today|tomorrow|this week|this month|this year|soon|ever)\\b", IGNORE_CASE)

// Extract topic nouns
val topicPattern = Regex("\\b(move|travel|invest|marry|quit|start|buy|sell)\\b", IGNORE_CASE)
```

### Memory & Speed
| Metric | Value |
|--------|-------|
| AFINN lexicon size | ~30 KB |
| Regex patterns | ~5 KB |
| RAM at runtime | ~50 KB |
| Classification speed | <1ms |
| Accuracy | 70-80% for well-defined intents |

### Verdict
**Essential component** for understanding questions. Not a response generator itself, but enables smart response selection. Extremely lightweight and instant. The AFINN Kotlin library makes sentiment analysis trivial.

---

## 7. TFLite Text Classification

### TFLite Model Maker: Average Word Vector Classifier
Google's TFLite Model Maker provides two architectures for text classification:

| Architecture | Model Size | Training Time | Accuracy | Speed |
|-------------|-----------|---------------|----------|-------|
| **Average Word Vector** | **<1 MB** | ~1 minute | Good | <5ms |
| MobileBERT | ~25 MB | ~30 minutes | Better | ~60ms |

### Average Word Vector Architecture
- Parameters: `num_words=10000, seq_len=256, wordvec_dim=16`
- Extremely compact: under 1 MB after post-training quantization
- Runs in milliseconds on any device
- **Perfect for intent classification** with 10-20 categories

### Training for Oracle Intent Detection
1. Prepare dataset: 200-500 labeled questions per intent category
2. Train with TFLite Model Maker (Python, 1 minute)
3. Export `.tflite` file (<1 MB)
4. Deploy in Wear OS app assets folder
5. Use `NLClassifier` API from TFLite Task Library

### Android Integration
```kotlin
// Add dependencies
// implementation 'org.tensorflow:tensorflow-lite-task-text:0.4.4'

val classifier = NLClassifier.createFromFile(context, "intent_model.tflite")
val results = classifier.classify(userQuestion)
val topIntent = results.maxByOrNull { it.score }
```

### Training Data Requirements
- Need ~100-500 labeled examples per intent category
- Can synthetically generate training data from question templates
- Example categories: love, career, money, health, timing, yes/no, general

### Memory & Speed
| Metric | Value |
|--------|-------|
| Model size | 500 KB - 1 MB |
| RAM at runtime | ~2 MB |
| Inference speed | <5ms |
| Training time | ~1 minute |
| Accuracy (10 intents) | 85-95% |

### Verdict
**Great for intent classification.** Under 1 MB, runs in milliseconds, easy to train. Better accuracy than regex for intent detection. Can be combined with rule-based NLU as a fallback.

---

## 8. Hybrid Architecture (Recommended)

### The Optimal Stack

Combine multiple lightweight approaches for a system that feels intelligent:

```
[User Question]
       |
       v
[Layer 1: Question Understanding] -------- 2-5ms, ~1 MB
  |-- Intent Classification (TFLite Average Word Vector, <1 MB)
  |-- Sentiment Analysis (AFINN lexicon, ~30 KB)
  |-- Keyword Extraction (regex, ~5 KB)
  |-- Question Type Detection (regex: yes/no, when, how, why)
       |
       v
[Layer 2: Response Selection] ------------ 5-10ms, ~3-8 MB
  |-- Semantic Matching (model2vec potion-base-2M, ~3 MB)
  |-- Intent-filtered response pool (from Layer 1)
  |-- Sentiment-matched response tone
       |
       v
[Layer 3: Response Generation] ----------- <1ms, ~50 KB
  |-- Template Composition (grammar fragments, ~50 KB)
  |-- Markov chain variation (trained on responses, ~200 KB)
  |-- Quality filter (length, grammar, no repeats)
       |
       v
[Final Response]
```

### How It Works Together

1. **User asks:** "Will I find love this year?"

2. **Layer 1 - Understanding:**
   - Intent: `LOVE` (TFLite classifier)
   - Sentiment: Slightly positive/hopeful (AFINN score: +2)
   - Keywords: "love", "year"
   - Type: Yes/No question
   - Time reference: "this year"

3. **Layer 2 - Selection:**
   - Filter to love-themed response pool (~40 responses)
   - Semantic match: find responses about "finding love" and "timing"
   - Sentiment match: select encouraging/positive responses
   - Top 5 candidate responses scored

4. **Layer 3 - Generation:**
   - Option A: Return best semantic match directly (highest confidence)
   - Option B: Use template composition with love+timing fragments
   - Option C: Markov variation on the top matched response
   - Quality check: ensure grammatical, adequate length, not recently used

5. **Output:** "The stars align for matters of the heart. Love finds those who remain open to it -- and your time draws near."

### Total Resource Budget

| Component | Size | RAM | Speed |
|-----------|------|-----|-------|
| TFLite intent classifier | ~800 KB | ~2 MB | <5ms |
| AFINN sentiment lexicon | ~30 KB | ~50 KB | <1ms |
| Regex patterns | ~5 KB | ~10 KB | <1ms |
| model2vec potion-base-2M | ~3 MB | ~5 MB | ~5ms |
| Pre-computed embeddings | ~307 KB | ~307 KB | <1ms |
| Template grammar | ~50 KB | ~100 KB | <1ms |
| Markov chain model | ~200 KB | ~500 KB | <1ms |
| **TOTAL** | **~4.4 MB** | **~8 MB** | **~12ms** |

This fits comfortably in the Galaxy Watch Ultra's 2GB RAM and runs in ~12ms total -- effectively instant.

### Degradation Strategy
If resources are tight, drop layers in this order:
1. Drop model2vec (save 3 MB) -- fall back to keyword matching
2. Drop TFLite classifier (save 800 KB) -- fall back to regex intent detection
3. Drop Markov chain (save 200 KB) -- use only template composition
4. **Minimum viable:** Template composition + AFINN + regex = ~85 KB total

---

## 9. Lightweight NLP Libraries for Android

### Recommended Libraries

| Library | Language | Size | Features | Viable on Wear OS? |
|---------|----------|------|----------|---------------------|
| **kotlin-tools/afinn** | Kotlin | ~50 KB | Sentiment analysis, multi-language, emoticons | YES |
| **model2vec (Android)** | Kotlin | ~3-8 MB | Static sentence embeddings | YES |
| **TFLite Task Text** | Java/Kotlin | ~2 MB | Text classification, NLClassifier | YES |
| Apache OpenNLP | Java | ~5 MB+ | Tokenization, POS tagging, NER, chunking | MAYBE |
| KotlinNLP/SimpleDNN | Kotlin | Variable | Lightweight DNN for NLP | MAYBE |
| Londogard NLP Toolkit | Kotlin | Variable | JVM NLP toolkit | MAYBE |
| Stanford CoreNLP | Java | ~500 MB+ | Full NLP pipeline | NO (too heavy) |
| Google ML Kit Smart Reply | Android | ~5.7 MB | Pre-built smart replies | YES (but English only) |

### Apache OpenNLP on Android
- Works on Android via Gradle: `implementation 'org.apache.opennlp:opennlp-tools:1.9.3'`
- Version 3.x has modular structure -- import only needed components
- Supports: tokenization, sentence detection, POS tagging, NER
- Models are separate downloads (1-15 MB each depending on task)
- **May be overkill** for our use case -- regex covers most needs

### Google ML Kit Smart Reply
- **Pre-built on-device smart reply system**
- Bundled model: 5.7 MB increase to APK
- Unbundled (via Google Play Services): only 200 KB increase
- Generates up to 3 reply suggestions for English conversations
- **Limitation:** Designed for conversational replies, not oracle-style responses
- **API:** `SmartReplyGenerator.suggestReplies(conversation)`
- Requires Android API 23+
- [Documentation](https://developers.google.com/ml-kit/language/smart-reply/android)

### AFINN Kotlin (Best for Sentiment)
- **GitHub:** [github.com/kotlin-tools/afinn](https://github.com/kotlin-tools/afinn)
- Pure Kotlin, no native dependencies
- 3,000+ words with sentiment scores (-5 to +5)
- Supports emoticons
- Trivial integration via Maven

---

## 10. On-Device Conversational AI without LLMs

### Google Smart Reply Architecture

Google's on-device Smart Reply system (deployed on Wear OS since 2017) uses:

1. **ProjectionNet Architecture:**
   - Transforms input into compact bit vector representations
   - Uses "projection" operations instead of heavy transformer layers
   - Teacher-student training: large LSTM (teacher) trains compact ProjectionNet (student)
   - Compiled to TFLite operations optimized for mobile

2. **Semi-Supervised Graph Learning:**
   - Maps similar messages to nearby vector representations
   - Uses graph learning to discover semantic clusters
   - Emotion labeling via semi-supervised propagation over word graphs
   - Seeds graphs with known emotion labels, propagates to discover categories

3. **Model Compression:**
   - Quantized training with 8-bit operations
   - Example: On ATIS dataset, quantized ProjectionNet = **285 KB** with 91% accuracy
   - "Several orders of magnitude reduction" compared to full models

4. **Latest Evolution (2025-2026):**
   - Smart Reply for Pixel Watch 3/4 now uses a derivative of **Gemma**
   - Suggestion speed doubled, memory efficiency tripled
   - Still runs fully on-device

### Key Takeaway from Google's Approach
Google's Smart Reply proves that **you don't need a full LLM** for intelligent responses. Their progression:
- 2017: ProjectionNet (~285 KB quantized)
- 2019: ML Kit Smart Reply (5.7 MB)
- 2025: Gemma-derivative (size unknown, but optimized for watch)

The core insight: **project questions into compact vector space, match against pre-computed response embeddings.** This is exactly what model2vec enables for us.

### Open Source Alternatives
- **TFLite Smart Reply demo:** Available in TensorFlow examples repository
- **ProjectionNet paper:** [Efficient On-Device Models using Neural Projections](http://proceedings.mlr.press/v97/ravi19a/ravi19a.pdf)
- No complete open-source Smart Reply clone exists, but the components are individually available

---

## 11. Final Recommendation

### Recommended Architecture: "Oracle Intelligence Engine"

**Tier 1: Minimum Viable (85 KB, <2ms)**
Use this if we want absolute minimum footprint:
- Template composition with Tracery-style grammar (~50 KB)
- AFINN sentiment analysis to match question tone (~30 KB)
- Regex intent/keyword detection (~5 KB)
- **Generates 2.5M+ unique responses, matches tone to question**

**Tier 2: Smart Selection (4.4 MB, ~12ms) -- RECOMMENDED**
Best balance of intelligence and resource usage:
- Everything in Tier 1, PLUS:
- model2vec potion-base-2M for semantic question-response matching (~3 MB)
- TFLite Average Word Vector classifier for intent detection (~800 KB)
- Markov chain for response variations (~200 KB)
- **Feels like an LLM because it understands the question and gives contextually relevant answers**

**Tier 3: Maximum Intelligence (10-15 MB, ~30ms)**
If we have headroom to spare:
- Everything in Tier 2, PLUS:
- model2vec potion-base-8M for better semantic matching (~8 MB)
- Larger template grammar with 100+ fragments per category
- Response history tracking to avoid repeats
- Entity extraction for personalized responses

### Why Tier 2 is the Sweet Spot
- **4.4 MB total** fits easily on Galaxy Watch Ultra (2GB RAM)
- **12ms response time** feels instant to the user
- **Semantic matching** makes it FEEL like AI understands the question
- **Template composition** ensures grammatically perfect output every time
- **Sentiment matching** makes responses emotionally appropriate
- **No incoherent output** (unlike SmolLM-135M) because we never "generate" -- we select and compose
- **Offline-first** -- everything runs locally with zero latency

### Implementation Priority
1. Build template composition system with 50+ fragments per slot (Week 1)
2. Implement AFINN sentiment + regex intent classification (Week 1)
3. Integrate model2vec for semantic matching (Week 2)
4. Train TFLite intent classifier with synthetic question data (Week 2)
5. Add Markov chain for response variation (Week 3)
6. Polish: response history, anti-repeat, quality filters (Week 3)

---

## Key GitHub Repositories

| Resource | Link |
|----------|------|
| Markovify (Python, JSON export) | [github.com/jsvine/markovify](https://github.com/jsvine/markovify) |
| Tracery (procedural text) | [github.com/galaxykate/tracery](https://github.com/galaxykate/tracery) |
| model2vec (static embeddings) | [github.com/MinishLab/model2vec](https://github.com/MinishLab/model2vec) |
| Sentence-Embeddings-Android | [github.com/shubham0204/Sentence-Embeddings-Android](https://github.com/shubham0204/Sentence-Embeddings-Android) |
| AFINN Kotlin | [github.com/kotlin-tools/afinn](https://github.com/kotlin-tools/afinn) |
| TFLite Text Classification | [ai.google.dev/edge/litert/libraries/modify/text_classification](https://ai.google.dev/edge/litert/libraries/modify/text_classification) |
| OpenNLP Android Example | [github.com/duckyngo/OpenNLP-Android-Example](https://github.com/duckyngo/OpenNLP-Android-Example) |
| ML Kit Smart Reply | [developers.google.com/ml-kit/language/smart-reply](https://developers.google.com/ml-kit/language/smart-reply) |
| ProjectionNet Paper | [proceedings.mlr.press/v97/ravi19a](http://proceedings.mlr.press/v97/ravi19a/ravi19a.pdf) |
| NanoBERT Paper | [dl.acm.org/doi/fullHtml/10.1145/3632410.3632451](https://dl.acm.org/doi/fullHtml/10.1145/3632410.3632451) |
| Op-Ngram Mobile Paper | [researchgate.net/publication/331748543](https://www.researchgate.net/publication/331748543_Real-Time_Optimized_N-Gram_for_Mobile_Devices) |
| Google Smart Reply Blog | [research.google/blog/on-device-conversational-modeling-with-tensorflow-lite](https://research.google/blog/on-device-conversational-modeling-with-tensorflow-lite/) |
