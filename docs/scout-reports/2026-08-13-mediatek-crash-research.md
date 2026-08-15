# Research: Anti-Vocale crash on Oppo Reno 12 (MediaTek Dimensity 7300)

**Date**: 2026-08-13
**Depth**: exhaustive
**Confidence**: MEDIUM (multiple plausible causes, no device to reproduce on)

## Executive Summary

The crash on the Oppo Reno 12 (MediaTek Dimensity 7300, Android 16, ColorOS) is **not caused by 16KB page-size misalignment** (all .so verified aligned) or **ARM ISA incompatibility** (arm64-v8a is standard, Dimensity 7300 is 4x Cortex-A78 + 4x Cortex-A55, fully ARMv8.2-A compliant). Three plausible causes remain, ranked by likelihood:

1. **MediaTek NNAPI driver crash (MOST LIKELY)**: MediaTek's NeuroPilot NNAPI driver is notoriously buggy. It returns `ANEURALNETWORKS_BAD_DATA` or SIGABRT for many models on MediaTek chips. Our app defaults to CPU provider, but the user could have switched to NNAPI in Settings. Even on CPU, sherpa-onnx builds sometimes link the NNAPI provider factory, and onnxruntime's initialization can probe the NNAPI HAL at session creation even when CPU is selected.
2. **ColorOS foreground-service restriction / installation block**: ColorOS is among the most aggressive task-killers. The `foregroundServiceType="specialUse"` may be rejected or the sideloaded APK may be blocked from executing native libraries by ColorOS security policies.
3. **onnxruntime thread-pool crash on heterogeneous cores**: onnxruntime's thread pool has known SIGSEGV issues when CPU core availability is restricted (documented on Docker cpuset, but could also trigger on ColorOS's aggressive CPU governor that powers down big cores).

## Findings

### 1. 16KB page alignment: EXCLUDED
All 10 .so files (sherpa-onnx-jni, sherpa-onnx-c-api, sherpa-onnx-cxx-api, onnxruntime, LiteRT, litertlm, llm_inference_engine, LiteRtClGlAccelerator, androidx.graphics.path, datastore_shared_counter) have `PT_LOAD align=16384` (16KB). Verified via ELF program header parsing. This is NOT the cause.

### 2. ARM ISA compatibility: EXCLUDED
MediaTek Dimensity 7300 is 4x Cortex-A78 @ 2.5GHz + 4x Cortex-A55 @ 2.0GHz, ARMv8.2-A compliant. Our arm64-v8a .so files use standard ARMv8-A instructions. There is no vendor-specific ISA gap. NEON, FP-ARMv8, and Crypto are all supported. No SVE dependency in any of our native libraries. This is NOT the cause.

### 3. MediaTek NNAPI driver bugs (HIGHEST SUSPICION)

**Evidence:**
- GitHub issue [MediaTek-NeuroPilot/tflite-neuron-delegate#12](https://github.com/MediaTek-NeuroPilot/tflite-neuron-delegate/issues/12): MediaTek's NNAPI driver returns `ANEURALNETWORKS_BAD_DATA` on MT6893 (Dimensity 9000 family). The NeuroPilot adapter crashes during NNAPI compilation for common ML models. This is a MediaTek-specific driver bug, not an app issue.
- GitHub issue [k2-fsa/sherpa-onnx#3611](https://github.com/k2-fsa/sherpa-onnx/issues/3611): sherpa-onnx SIGABRT when NNAPI is enabled on Android API 36. The `ANeuralNetworksModel_identifyInputsAndOutputs` call fails. Even on Pixel 8a (Qualcomm), NNAPI crashes.
- GitHub issue [k2-fsa/sherpa-onnx#2448](https://github.com/k2-fsa/sherpa-onnx/issues/2448): Android app crash when using NNAPI on QCM6490 (Qualcomm). NNAPI is broadly unreliable across vendors.
- A Russian tech article (habr.com) describes sherpa-onnx: "Uses NNAPI on Android 13+ and crashes on CPU if something goes wrong."

**Relevance to Anti-Vocale:**
- Our app defaults to `provider = "cpu"` (InferenceProvider.resolve("auto") = "cpu"). NNAPI is NOT the default.
- HOWEVER: the user can switch to NNAPI in Settings. If they did (curiosity, or following old advice), the MediaTek driver would crash.
- Even with CPU provider selected, sherpa-onnx's onnxruntime build may include the NNAPI provider factory linked in the .so. At session creation, onnxruntime may probe available providers (including NNAPI HAL), which could trigger a MediaTek driver bug.

**Actionable mitigation:** Verify that our sherpa-onnx build does NOT probe NNAPI when CPU is explicitly selected. If it does, suppress the probe. Also: disable the NNAPI option in Settings on MediaTek devices, or warn that it is experimental.

### 4. ColorOS foreground-service / installation restrictions (MEDIUM SUSPICION)

**Evidence:**
- ColorOS is widely documented as one of the most aggressive task-killers (Realme/OPPO/Oppo brands all share ColorOS base). Foreground services are frequently killed.
- Reddit post [r/androiddev](https://www.reddit.com/r/androiddev/comments/1ldparg/oppo_coloros_foreground_service/) specifically discusses ColorOS foreground service restrictions.
- The app uses `foregroundServiceType="specialUse"`, which on Android 14+ requires Play Console justification. ColorOS may reject specialUse FGS types that haven't been "approved" through its own ecosystem.
- Sideloaded APKs on ColorOS may face additional restrictions. Google's new Play Integrity API and anti-sideloading measures (Aug 2025) could compound with ColorOS's own security layer to block native library execution from non-Play-installed apps.

**Actionable mitigation:** Check whether the user installed from F-Droid (which should work) or sideloaded a raw APK. If sideloaded, ColorOS may be blocking. The F-Droid version with the same signing key should be fine.

### 5. onnxruntime thread-pool on heterogeneous cores (LOW SUSPICION)

**Evidence:**
- onnxruntime issue [#7207](https://github.com/microsoft/onnxruntime/issues/7207): SIGSEGV when running with CPU restrictions (cpuset). The thread pool crashes when cores are unavailable.
- ColorOS's aggressive CPU governor may power down big cores (Cortex-A78) to save battery, making them temporarily unavailable to the thread pool. This could trigger the same class of crash as the Docker cpuset restriction.
- Our app sets `numThreads` based on `Runtime.availableProcessors() - 2`, which queries the number of available processors. If ColorOS dynamically changes this (hotplugging cores), the thread pool may be created with a count that becomes invalid.

**Actionable mitigation:** Pin numThreads to 1 on MediaTek devices, or catch the SIGSEGV and retry with fewer threads (hard to do for native crashes). Alternatively, use a conservative thread count (1 or 2) that won't trigger the hotplug race.

## Recommendations (ranked)

1. **Ask the user for crash details** (when does it crash: on launch, after downloading a model, during transcription?) and whether they changed the inference provider to NNAPI in Settings.
2. **Check InferenceProvider setting**: if the user switched to NNAPI, that's almost certainly the cause on MediaTek. Recommend switching back to CPU (or Auto).
3. **Test the F-Droid version** if the user sideloaded: ColorOS may be blocking the sideloaded APK's native libs.
4. **Add a MediaTek guard**: on `Build.HARDWARE.contains("mt")` or `Build.BOARD` containing MediaTek identifiers, disable NNAPI in the Settings and force CPU. This is a blunt instrument but pragmatic.
5. **Investigate the onnxruntime NNAPI probe**: verify whether our sherpa-onnx build probes the NNAPI HAL at session creation even when CPU is selected. If so, build sherpa-onnx without the NNAPI provider linked.

## Sources

1. https://github.com/MediaTek-NeuroPilot/tflite-neuron-delegate/issues/12 - MediaTek NNAPI BAD_DATA crash
2. https://github.com/k2-fsa/sherpa-onnx/issues/3611 - sherpa-onnx NNAPI SIGABRT on Android API 36
3. https://github.com/k2-fsa/sherpa-onnx/issues/2448 - sherpa-onnx NNAPI crash on Android
4. https://github.com/microsoft/onnxruntime/issues/7207 - onnxruntime SIGSEGV with cpuset restrictions
5. https://github.com/microsoft/onnxruntime/issues/24991 - onnxruntime null pointer SIGSEGV on arm64 CPU
6. https://developer.android.com/about/versions/14/changes/fgs-types-required - FGS type requirements Android 14+
7. https://habr.com/ru/articles/1027884/ - sherpa-onnx description (NNAPI on Android 13+, CPU fallback)
8. https://www.reddit.com/r/androiddev/comments/1ldparg/oppo_coloros_foreground_service/ - ColorOS FGS restrictions
9. https://www.esper.io/blog/google-abandons-updatable-nnapi-drivers-android-13 - NNAPI driver updateability
10. https://www.androidpolice.com/google-making-it-easier-detect-block-sideloading/ - Play Integrity sideload detection
