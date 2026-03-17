# ML/LLM Research for Samsung Galaxy Watch Ultra

## Device Specifications Reference

| Component | Specification |
|-----------|--------------|
| SoC | Exynos W1000 (3nm GAA) |
| CPU | 1x Cortex-A78 @ 1.6GHz + 4x Cortex-A55 @ 1.5GHz |
| GPU | Mali-G68 MP2 |
| RAM | 2GB |
| Storage | 32GB |
| Battery | 590mAh |
| OS | Wear OS 6 / One UI 8 |
| ABI | Reports armeabi-v7a, cores are arm64-v8a capable |
| NPU | None dedicated (no NNAPI NPU backend) |

---

## 1. llama.cpp on Android Wearables

### Cross-Compilation

llama.cpp officially supports Android cross-compilation via CMake and the Android NDK.

**Build command:**
```bash
mkdir build-android && cd build-android
cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 \
  -DCMAKE_BUILD_TYPE=Release
make -j$(nproc)
```

For ARM32 (armeabi-v7a), change `-DANDROID_ABI=armeabi-v7a`. Note: there have been issues with ARMv7 NEON FP16 intrinsic errors when cross-compiling with NDK r26b (GitHub issue #11636). **Recommendation: target arm64-v8a** since the Cortex-A78/A55 cores support it, even though the watch may report armeabi-v7a.

Hardware acceleration supports up to SME2 for ARM, with automatic CPU feature detection at runtime.

### JNI Bridge Options

Several projects provide JNI/JNA bridges for Android integration:

| Project | Language | Approach | URL |
|---------|----------|----------|-----|
| llama-jni | Java | JNI bindings with encapsulated common functions | [GitHub](https://github.com/shixiangcap/llama-jni) |
| llama-cpp-kt | Kotlin | JNA-powered wrapper | [GitHub](https://github.com/hurui200320/llama-cpp-kt) |
| kotlinllamacpp | Kotlin | Native Android binding, ARM-optimized | [GitHub](https://github.com/ljcamargo/kotlinllamacpp) |

**Best practice:** Build the `.so` separately, write JNI/JNA binding manually for better control. The `.so` can be bundled inside an APK and sideloaded via ADB.

### Wear OS Deployment

No Wear OS-specific llama.cpp builds exist yet. The standard Android build should work since Wear OS shares the Android NDK/runtime. Deploy via:
1. Build arm64-v8a `.so` library
2. Create Android app with JNI bridge
3. Sideload APK via ADB over Wi-Fi (enable Developer Options > ADB Debugging > Wireless Debugging)

**Sources:**
- [llama.cpp Android docs](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md)
- [ARMv7 compile issues](https://github.com/ggml-org/llama.cpp/issues/11636)
- [llama.cpp Android tutorial](https://github.com/JackZeng0208/llama.cpp-android-tutorial)

---

## 2. SmolLM-135M GGUF

### Model Variants and File Sizes

| Quantization | File Size | Memory at Runtime (est.) | Quality |
|-------------|-----------|-------------------------|---------|
| Q2_K | ~88 MB | ~120 MB | Lowest |
| Q4_K_M | ~105 MB | ~150 MB | Good balance |
| Q6_K | ~138 MB | ~180 MB | High |
| Q8_0 | ~145 MB | ~200 MB | Near-lossless |
| FP16 | ~270 MB | ~350 MB | Full precision |

### Memory Feasibility on 2GB RAM

With Wear OS consuming ~800MB-1GB of RAM, leaving ~1-1.2GB available:
- **Q4_K_M (105MB model, ~150MB runtime):** Feasible with comfortable headroom
- **Q8_0 (145MB model, ~200MB runtime):** Feasible
- **FP16 (270MB model, ~350MB runtime):** Feasible but tight

**Conclusion: SmolLM-135M at Q4_K_M is highly viable** on the Galaxy Watch Ultra's 2GB RAM.

### Inference Speed Estimates

No direct benchmarks exist for 135M models on Cortex-A78. Extrapolating from available data:
- Llama2-7B Q4 on Raspberry Pi 5 (Cortex-A76): ~0.1 tok/s
- MobileLLM-125M on iPhone: ~50 tok/s
- 135M is ~50x smaller than 7B; Cortex-A78 is faster than A76

**Estimated inference speed on Galaxy Watch Ultra: 20-60 tokens/second** for Q4_K_M SmolLM-135M. This would be responsive for short text generation.

### ARM-Optimized Quantization

llama.cpp provides Q4_0_X_X formats specifically optimized for ARM NEON, offering substantial speedups over generic quantization on ARM chips.

**Sources:**
- [QuantFactory/SmolLM2-135M-GGUF](https://huggingface.co/QuantFactory/SmolLM2-135M-GGUF)
- [bartowski/SmolLM2-135M-Instruct-GGUF](https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF)
- [SmolLM2 Quantization Guide](https://markaicode.com/smollm2-quantization-ultra-low-memory-deployment/)

---

## 3. ExecuTorch for Wearables

### Overview

ExecuTorch is Meta's on-device inference framework for PyTorch models, designed for mobile and edge devices.

| Feature | Detail |
|---------|--------|
| Base runtime footprint | **50KB** |
| Android integration | AAR via Maven Central |
| Supported ops | Core ATen Op set (most PyTorch ops decompose to this) |
| Performance | Up to 30+ tok/s for 8B models on smartphones |
| Model export | PyTorch -> ExecuTorch `.pte` format |

### Setup on Android/Wear OS

```gradle
// build.gradle
dependencies {
    implementation "org.pytorch:executorch-android:${executorch_version}"
}
```

ExecuTorch provides both generic (image/audio) and LLM-specific (LLaMA) prebuilt AAR artifacts. The 50KB base footprint makes it extremely attractive for wearables.

### Wear OS Considerations

- No Wear OS-specific guide exists yet
- The standard Android AAR should work on Wear OS since they share the same runtime
- ExecuTorch supports dynamic shapes (variable input sizes without recompilation)
- CPU backend works out of the box; GPU delegate may have limited Mali-G68 support

### Model Conversion Pipeline

```
PyTorch model -> torch.export() -> ExecuTorch exporter -> .pte file -> Deploy on device
```

**Sources:**
- [ExecuTorch GitHub](https://github.com/pytorch/executorch)
- [ExecuTorch Android docs](https://docs.pytorch.org/executorch/stable/using-executorch-android.html)
- [ExecuTorch official site](https://executorch.ai/)

---

## 4. MobileLLM by Meta

### Model Details

| Variant | Parameters | Key Finding |
|---------|-----------|-------------|
| MobileLLM-125M | 125M | 2.7% accuracy improvement over comparable models |
| MobileLLM-350M | 350M | Better accuracy, larger footprint |
| MobileLLM-600M | 600M | May be too large for watch |
| MobileLLM-1B | 1B | Too large for 2GB RAM device |

### Architecture Insights

MobileLLM's key finding: **at small scale, architecture matters more than parameter count.** Deep-thin architectures (more layers, smaller hidden dimensions) consistently outperform wide-shallow ones.

Key techniques:
- Embedding sharing (reduces parameter count)
- Grouped-query attention (reduces memory)
- Block-wise immediate weight sharing

### GGUF Availability

GGUF versions are available: [pjh64/MobileLLM-125M-GGUF](https://huggingface.co/pjh64/MobileLLM-125M-GGUF)

### Performance

- **~50 tokens/second on iPhone** (125M variant)
- Galaxy Watch Ultra estimate: **20-40 tok/s** (less powerful CPU, but same ARM ISA)

### Conversion to Other Formats

- **GGUF:** Already available on HuggingFace
- **TFLite:** Use `torch.export()` -> TFLite converter or ONNX intermediate
- **ExecuTorch:** Use PyTorch export pipeline directly

**Sources:**
- [MobileLLM GitHub](https://github.com/facebookresearch/MobileLLM)
- [MobileLLM on HuggingFace](https://huggingface.co/facebook/MobileLLM-125M)
- [MobileLLM Benchmark & Architecture Teardown](https://www.ywian.com/blog/mobilellm-benchmark-architecture-teardown)
- [On-Device LLMs: State of the Union, 2026](https://v-chandra.github.io/on-device-llms/)

---

## 5. TFLite Text Generation

### Available Pre-built Models

| Model | Format | Size | Use Case |
|-------|--------|------|----------|
| DistilGPT2 | TFLite | ~250MB | Text generation |
| GPT-2 | TFLite | ~500MB+ | Text generation (too large) |
| Smart Reply | TFLite | Small | Conversational reply suggestions |

### Key Projects

- **[tflite-android-transformers](https://github.com/huggingface/tflite-android-transformers):** HuggingFace's GPT-2/DistilBERT for on-device inference with Android demo apps
- **[TextGeneration-TFLite](https://github.com/NikhithaN-lab/TextGeneration-TFLite):** Character-level text generation model optimized for mobile/edge
- **Google Codelabs:** On-device LLMs with Keras and TFLite

### Wear OS Viability

DistilGPT2 at ~250MB may work but leaves less headroom than SmolLM-135M. TFLite's advantage is mature Android tooling and potential NNAPI acceleration (though W1000 lacks NPU for NNAPI offload).

**Note:** TFLite requires 4GB+ RAM for recommended models. For the 2GB watch, only the smallest custom-trained TFLite models would be viable.

**Sources:**
- [HuggingFace TFLite Android Transformers](https://github.com/huggingface/tflite-android-transformers)
- [Google Codelabs - On-device LLMs](https://codelabs.developers.google.com/kerasnlp-tflite)

---

## 6. Samsung Neural SDK / Exynos NN

### Current Status

**Samsung Neural SDK is no longer available to third-party developers.** Samsung has restricted access following a policy change.

### Samsung ONE (On-device Neural Engine)

Samsung maintains the open-source **ONE** project:
- **GitHub:** [Samsung/ONE](https://github.com/Samsung/ONE)
- Supports TFLite, Circle, and TVN model formats
- ONNX support via onnx-tensorflow conversion pipeline
- Runs on Linux kernel-based OS (Android, Tizen, Ubuntu)
- Supports CPU, GPU, DSP, NPU backends

### Exynos W1000 NPU Status

The Exynos W1000 **does not have a dedicated NPU**. All inference must run on CPU (Cortex-A78/A55) or GPU (Mali-G68 MP2). This is a significant limitation compared to the upcoming Snapdragon Wear Elite which includes a Hexagon NPU.

### Future Direction

Samsung has announced the next Galaxy Watch generation will switch from Exynos W1000 to **Qualcomm Snapdragon Wear Elite** specifically for better AI capabilities. The current Galaxy Watch Ultra with W1000 is CPU/GPU-only for ML inference.

**Sources:**
- [Samsung Neural SDK Overview](https://developer.samsung.com/neural/overview.html)
- [Samsung ONE GitHub](https://github.com/Samsung/ONE)
- [Exynos W1000 Specs](https://semiconductor.samsung.com/processor/wearable-processor/exynos-w1000/)

---

## 7. ONNX Runtime on Wear OS

### Build Instructions

```bash
./build.sh \
  --android \
  --android_sdk_path <sdk_path> \
  --android_ndk_path <ndk_path> \
  --android_abi arm64-v8a \
  --android_api 28
```

Supports both `arm64-v8a` (ARM64) and `armeabi-v7a` (ARM32).

### Performance Characteristics

| Feature | ARM32 (armeabi-v7a) | ARM64 (arm64-v8a) |
|---------|--------------------|--------------------|
| NEON SIMD | Yes | Yes (wider) |
| Performance | Baseline | ~20-40% faster |
| Memory addressing | 4GB limit | Full 64-bit |
| Recommended | No | Yes |

### Execution Providers

- **CPU:** Default, uses ARM NEON optimizations
- **NNAPI:** Available but W1000 has no NPU backend
- **XNNPACK:** Optimized CPU execution for mobile

### Model Compatibility

ONNX Runtime supports models exported from PyTorch, TensorFlow, and scikit-learn. SmolLM-135M can be exported to ONNX format and run via ONNX Runtime on the watch.

**Sources:**
- [ONNX Runtime Android Build](https://onnxruntime.ai/docs/build/android.html)
- [Arm Learning Path - ONNX on ARM](https://learn.arm.com/learning-paths/mobile-graphics-and-gaming/onnx/01_fundamentals/)

---

## 8. Fine-Tuning Small Models

### Fine-Tuning SmolLM-135M for Custom Corpus

#### LoRA Configuration

```python
from peft import LoraConfig

lora_config = LoraConfig(
    r=16,                      # Rank
    lora_alpha=32,             # Scaling factor
    target_modules=["q_proj", "v_proj"],
    lora_dropout=0.05,
    bias="none",
    task_type="CAUSAL_LM"
)
```

#### Dataset Format

For mystical/fortune-telling text, prepare data as:

```jsonl
{"text": "The stars align to reveal your inner truth. When the moon rises..."}
{"text": "A journey awaits beyond the veil of certainty. Trust the signs..."}
{"text": "The crystal speaks of transformation. Your path shifts when..."}
```

Or conversational format:
```jsonl
{"prompt": "What does my future hold?", "completion": "The celestial energies suggest a period of transformation..."}
```

#### Training Pipeline

```python
from transformers import AutoModelForCausalLM, AutoTokenizer, TrainingArguments
from peft import get_peft_model

model = AutoModelForCausalLM.from_pretrained("HuggingFaceTB/SmolLM-135M")
tokenizer = AutoTokenizer.from_pretrained("HuggingFaceTB/SmolLM-135M")
model = get_peft_model(model, lora_config)

training_args = TrainingArguments(
    output_dir="./smollm-mystical",
    num_train_epochs=3,
    per_device_train_batch_size=8,
    learning_rate=2e-4,
    fp16=True,
    logging_steps=10,
)
```

#### Resource Estimates

| Aspect | Estimate |
|--------|----------|
| GPU required | Single consumer GPU (RTX 3060+ or T4) |
| Training time | 30-60 minutes on 10K examples |
| LoRA adapter size | ~5-10 MB |
| Full fine-tune VRAM | ~2-4 GB |
| LoRA fine-tune VRAM | ~1-2 GB |

#### Post-Training Conversion

After fine-tuning, merge LoRA weights and convert to GGUF:
```bash
python merge_lora.py --base HuggingFaceTB/SmolLM-135M --lora ./smollm-mystical
python convert_hf_to_gguf.py ./merged_model --outtype q4_k_m
```

**Sources:**
- [Fine-Tuning SmolLM-135M for Paraphrasing](https://ashishware.com/2025/11/08/lora_training_slm/)
- [SFT Guide: SmolLM-135M](https://medium.com/@bavalpreetsinghh/supervised-fine-tuning-sft-guide-fine-tuning-smollm-135m-on-xsum-a0e2a57fab2b)
- [Fine-tune SmolLM from HuggingFace](https://mikulskibartosz.name/fine-tune-small-language-model)

---

## 9. Existing Smartwatch ML Projects

### Direct ML on Smartwatch Projects

There are **very few** projects running ML inference directly on Galaxy Watch or Wear OS:

| Project | Description | Platform |
|---------|-------------|----------|
| Open-Watch | Health parameter extraction using ML from sensor data | Custom hardware |
| android/wear-os-samples | Official Wear OS samples (no ML inference) | Wear OS |
| GalaxyOPTool | Performance optimization for Galaxy Watch (not ML) | Galaxy Watch |

### Related Mobile ML Projects

| Project | Description | Relevance |
|---------|-------------|-----------|
| llama.cpp-android | Optimized Android port of llama.cpp | Direct applicability |
| tflite-android-transformers | HuggingFace transformer models on Android | TFLite path |
| awesome-mobile-llm | Curated list of mobile LLM resources | Research reference |

**Key finding:** Running LLM inference on a smartwatch is essentially uncharted territory. No public projects demonstrate text generation on Galaxy Watch or Wear OS devices. This would be a first-of-its-kind project.

**Sources:**
- [Galaxy Watch GitHub topic](https://github.com/topics/galaxy-watch)
- [Wear OS GitHub topic](https://github.com/topics/wear-os)
- [awesome-mobile-llm](https://github.com/stevelaskaridis/awesome-mobile-llm)

---

## 10. Battery Impact

### Galaxy Watch Ultra Battery Specs

- **Battery:** 590 mAh
- **Normal use:** ~60 hours
- **Power saving:** ~100 hours
- **Exercise power saving:** ~48 hours

### Power Consumption Estimates

| State | Estimated Power Draw | Battery Life |
|-------|---------------------|--------------|
| Idle / watch face | ~10-15 mW | 60+ hours |
| Active use (no ML) | ~100-200 mW | 6-12 hours |
| CPU inference (135M Q4) | ~500 mW - 1.5 W | 1-3 hours continuous |
| Peak burst (all cores) | ~2-3 W | 20-40 minutes |

### Inference-Per-Charge Estimates

Assumptions:
- Single inference = ~50-100 tokens generated
- At 30 tok/s, each inference takes ~2-3 seconds
- Power during inference: ~1W
- Energy per inference: ~1W x 3s = 3 Joules = 0.83 mWh

**With 590 mAh battery (2.12 Wh):**
- Theoretical max: ~2,500 inferences (inference only, no OS overhead)
- **Practical estimate: 200-500 inferences per charge** alongside normal watch use
- Impact per inference: ~0.1-0.2% battery drain

### Thermal Considerations

The Exynos W1000's 3nm fabrication with FOPLP packaging provides good thermal management. However:
- Sustained inference may cause thermal throttling
- The watch's small form factor limits heat dissipation
- Recommended: limit inference bursts to <30 seconds, with cooldown periods

**Sources:**
- [Galaxy Watch Ultra review (battery)](https://www.gsmarena.com/samsung_galaxy_watch_ultra-review-2737p3.php)
- [Exynos W1000 specs](https://semiconductor.samsung.com/processor/wearable-processor/exynos-w1000/)
- [LLM Energy Consumption research](https://arxiv.org/html/2511.05597)

---

## 11. Model Optimization Techniques

### Quantization Methods

| Method | Type | Best For | Sub-135M Support |
|--------|------|----------|-----------------|
| GGUF (llama.cpp) | Post-training | CPU inference, edge | Yes |
| GPTQ | Post-training | GPU inference | Yes (tested on 125M+) |
| AWQ | Post-training | Activation-aware | Yes |
| QLoRA | Training-time | Fine-tuning | Yes |

### Recommended Pipeline for Galaxy Watch

1. **Start:** SmolLM-135M or MobileLLM-125M (FP16)
2. **Fine-tune:** LoRA on mystical/fortune corpus
3. **Merge:** LoRA weights back to base model
4. **Quantize:** GGUF Q4_K_M (best speed/quality for ARM)
5. **Optimize:** Use Q4_0_4_4 ARM-optimized format if available
6. **Result:** ~105 MB model file, ~150 MB runtime memory

### Pruning

For sub-billion models, structured pruning can remove 20-30% of parameters with minimal quality loss. However, at 135M parameters, the model is already small enough that pruning may hurt quality more than it helps. **Not recommended** for this use case.

### Distillation

Knowledge distillation from a larger model (e.g., Llama-3.2-1B or SmolLM-1.7B) into a 135M student model can improve quality while maintaining the small footprint. This is a viable path if the base 135M model's output quality is insufficient.

**Sources:**
- [GPTQ Paper](https://arxiv.org/abs/2210.17323)
- [Model Quantization Guide 2026](https://www.meta-intelligence.tech/en/insight-quantization)
- [NVIDIA GTC 2025 - Quantization, Pruning, Distillation](https://www.nvidia.com/en-us/on-demand/session/gtc25-dlit71489/)

---

## 12. Alternative Approaches

### Approach Comparison

| Approach | Memory | Speed | Quality | Complexity |
|----------|--------|-------|---------|------------|
| SmolLM-135M GGUF | ~150 MB | 20-60 tok/s | Good | Medium |
| MobileLLM-125M GGUF | ~130 MB | 20-50 tok/s | Good | Medium |
| Markov Chain (order 3-5) | ~1-10 MB | 1000+ tok/s | Low-Medium | Low |
| N-gram model (5-gram) | ~5-50 MB | 500+ tok/s | Low | Low |
| Tiny Transformer (5-10M) | ~10-20 MB | 100+ tok/s | Medium | High |
| Template + Random | ~100 KB | Instant | Variable | Very Low |

### Markov Chains

**Pros:**
- Extremely fast (no neural computation)
- Tiny memory footprint (1-10 MB for large corpus)
- Simple implementation, easy to debug
- Only stores previous state(s)

**Cons:**
- No long-range coherence
- Repetitive output patterns
- Cannot generalize beyond training data
- Quality drops sharply for longer outputs

**Best for:** Short, mystical phrases where some randomness adds to the "mystical" feel.

### N-gram Models

Higher-order n-grams (5-gram or higher) with Kneser-Ney smoothing can produce surprisingly coherent text for domain-specific content like fortune-telling. Memory footprint depends on vocabulary and corpus size.

### Tiny Transformers Trained from Scratch

Training a 5-10M parameter transformer on a curated mystical text corpus:
- **Architecture:** 4-6 layers, 256 hidden dim, 4 heads
- **Training:** ~2-4 hours on a single GPU
- **Inference:** Very fast on ARM CPU
- **Model size:** 10-20 MB quantized
- **Quality:** Can be surprisingly good for narrow domains

### Hybrid Approach (Recommended for MVP)

1. **Template system** for structured fortune outputs
2. **Markov chain** for mystical filler phrases
3. **SmolLM-135M Q4_K_M** for actual AI-generated content when battery allows
4. **Fallback gracefully** between approaches based on battery level

---

## Recommendations Summary

### Primary Path: SmolLM-135M via llama.cpp

| Step | Action | Tool |
|------|--------|------|
| 1 | Fine-tune SmolLM-135M on mystical corpus | HuggingFace + LoRA |
| 2 | Convert to GGUF Q4_K_M | llama.cpp convert script |
| 3 | Cross-compile llama.cpp for arm64-v8a | Android NDK + CMake |
| 4 | Build Android app with JNI bridge | Kotlin + llama-jni |
| 5 | Package and sideload to Galaxy Watch | ADB over Wi-Fi |

### Alternative Path: MobileLLM-125M via ExecuTorch

| Step | Action | Tool |
|------|--------|------|
| 1 | Fine-tune MobileLLM-125M | PyTorch + LoRA |
| 2 | Export to ExecuTorch .pte | torch.export() |
| 3 | Build Android app with ExecuTorch AAR | Gradle + Maven |
| 4 | Deploy to Galaxy Watch | ADB |

### Fallback Path: Markov Chain + Templates

If ML inference proves too slow or battery-hungry:
- Pre-generate a library of mystical texts
- Use Markov chains for variation
- Template system for structured responses
- Zero ML overhead, instant responses

### Key Metrics to Target

| Metric | Target | Stretch Goal |
|--------|--------|-------------|
| Model size on disk | < 150 MB | < 100 MB |
| Runtime memory | < 300 MB | < 200 MB |
| Inference speed | > 10 tok/s | > 30 tok/s |
| Battery per inference | < 0.5% | < 0.2% |
| Time to first token | < 2 seconds | < 500 ms |

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| OOM on 2GB device | Low | High | Use Q4_K_M, limit context size |
| Thermal throttling | Medium | Medium | Limit inference duration, cooldown |
| Battery drain too fast | Medium | High | Hybrid approach, user-triggered only |
| ARM32-only ABI | Medium | Medium | Test arm64 builds, may need root |
| Poor text quality | Medium | Medium | Fine-tune on domain corpus |
| Wear OS app restrictions | Low | High | Sideload via ADB, no Play Store needed |

---

## Important Note: Next-Gen Hardware

Samsung has confirmed the next Galaxy Watch will use **Qualcomm Snapdragon Wear Elite** with a dedicated **Hexagon NPU**. This would dramatically improve on-device ML capabilities. The current Exynos W1000 in the Galaxy Watch Ultra is CPU/GPU-only for inference, making it more challenging but still feasible for sub-200M parameter models.
