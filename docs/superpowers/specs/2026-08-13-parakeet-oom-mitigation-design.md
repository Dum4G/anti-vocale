# Design: Parakeet SmoothQuant OOM mitigation (TASK-314)

## Context

The official app crashes silently when loading the Parakeet SmoothQuant model (~862MB) on low-memory devices. Diagnosed on the Realme RMX3853 test device (2026-08-13): the device has 11.3GB RAM total but only ~390MB free at crash time, swap nearly saturated. The kernel `lowmemorykiller` terminates the process during sherpa-onnx `OfflineRecognizer` init, with no Java exception and no crash dialog. The user sees the app vanish.

Root cause is OOM during model load, not a code bug and not a regression of the custom-transducer branch (the same model loaded fine via the custom backend in a lower-contention window).

Two facts from the codebase shape the design:
- `NativeCrashDetector` already runs on launch (`MainActivity:75`) and matches `ApplicationExitInfo.REASON_CRASH_NATIVE` only. OOM kills (`REASON_LOW_MEMORY`) go undetected and unexplained.
- The pre-native metadata guard exists for corrupt-model `exit(255)`, but there is no memory pre-flight before the native model load.

Goal: convert the silent OOM into either a prevented attempt (clear in-the-moment error) or an accurately explained event (diagnosis on next launch), with a user-controlled escape hatch. The user stays in command of model choice (no automatic switch to a different model variant).

## Design: three components

### 1. Diagnosis on next launch (ApplicationExitInfo + REASON_LOW_MEMORY)

Extend `NativeCrashDetector.checkForRecentNativeCrash` to also recognize `ApplicationExitInfo.REASON_LOW_MEMORY`, in addition to `REASON_CRASH_NATIVE`.

- `getHistoricalProcessExitReasons` already returns the recent exit reasons. The matcher needs a second accepted reason value.
- **Return-type change:** the method currently returns a single `Boolean`. To let the caller pick a distinct message, change the return to a sealed result (e.g. `None | NativeCrash(ts) | LowMemory(ts)`). `MainActivity` consumes this with a `when` that picks the corrupt-model message for `NativeCrash` and a memory message for `LowMemory`.
- **Reason-scoped dedup:** the current 5-minute dedup records a single timestamp key regardless of reason. A user who hits a native crash then an OOM within 5 minutes would have the second suppressed. The dedup key must become reason-scoped (track the last-shown timestamp per reason), not a single global timestamp.
- **Message precision:** `REASON_LOW_MEMORY` tells us this process was killed for memory, but not that it happened specifically during model load. The dialog message should say the app was closed because the device ran low on memory (factual), suggest closing other apps or using a smaller model, and not assert a causal link to model loading that the exit reason does not provide.
- `getHistoricalProcessExitReasons` is scoped to the calling package, so another app being killed for memory does not produce a false positive here.
- **Latency limitation:** this diagnosis runs only when the user manually relaunches the app. The OOM is explained on the next open, not immediately. The exit reasons live in a ring buffer, so a user who reboots or force-stops before relaunching could lose the record; this is an inherent constraint of the ApplicationExitInfo approach, accepted as the cost of zero false positives.

Files: `app/src/main/java/com/antivocale/app/util/NativeCrashDetector.kt` (return-type change + reason-scoped dedup), `MainActivity.kt` (when-branch on the result), new strings in `values/strings.xml` + `values-it/strings.xml`.

### 2. Pre-flight memory check before model load

Before the native `OfflineRecognizer(...)` constructor, read available memory and refuse the load if insufficient.

- **Placement: the shared `configureSherpaBackend` chokepoint in the orchestrator.** All four sherpa backends (Parakeet, Whisper, Qwen3, CustomTransducer) flow through `TranscriptionOrchestrator.configureSherpaBackend` before their `initialize` is called. Placing the check there covers every sherpa model load in one place, including user-imported custom models of arbitrary size. It must NOT go in `SherpaOnnxBackend.initialize`: that method belongs to one peer backend class (Parakeet only), and each backend has its own `initialize` and its own `OfflineRecognizer` construction. The orchestrator also already has `PreferencesManager` injected, which the force-load flag (component 3) needs; the backend classes do not.
- Memory source: `ActivityManager.MemoryInfo.availMem`. This is the closest user-space analog to `/proc/meminfo` MemAvailable and the right coarse observable. It is not a literal replica of the kernel's kill decision (modern lmkd uses PSI and oom_score_adj, not a MemAvailable comparison), so treat it as a coarse guard that predicts the kill, not a guarantee.
- Required memory: the real model size on disk, computed by summing `File.length()` over every file in the model directory (the `BackendConfig.SherpaOnnxConfig.modelDir`), plus a headroom. Summing all files in the dir, rather than assuming a fixed encoder/decoder/joiner triplet, generalizes across backends: Whisper has no joiner, Qwen3 has a conv_frontend plus a tokenizer dir, custom imports can hold anything. The model dir is known at the chokepoint.
- Headroom: a constant, conservatively tuned. For reference (session observations from the 2026-08-13 diagnosis, not reproducible artifacts in the repo): SmoothQuant is ~862MB on disk; at the observed crash the device reportedly had ~390MB free (which the check would block); after process recovery ~3.1GB free (which the check would allow). With ~300MB headroom the threshold is ~1.16GB. These numbers are the rationale for the 300MB starting value, but the value is a tuning constant to confirm empirically on device, not a derived figure. `availMem` includes reclaimable page cache, so a device under cache pressure can report high free memory yet still be killed; the headroom is intended to absorb both inference overhead and cache-pressure noise.
- When blocked: `configureSherpaBackend` returns `Result.failure(TranscriptionException.ModelLoadError(...))` with a localized message: not enough free memory for this model (X available, Y needed). Close other apps or use a smaller model. The orchestrator surfaces this as an in-app error. No process death.

Files: `app/src/main/java/com/antivocale/app/transcription/TranscriptionOrchestrator.kt` (the check, at the top of `configureSherpaBackend`), new strings.

### 3. Feature flag: force model load (user escape hatch)

A boolean setting, default off, that when on skips the pre-flight memory check entirely.

- Rationale: removes the harm of a false positive. A user who knows their device can handle it (or wants to try regardless) can bypass the guard explicitly, aware of the risk. Default stays protective.
- Exposure: a toggle in the Settings tab. Label and short description explaining that it forces loading even when memory looks insufficient and may close the app on low-RAM devices. Localized en + it.
- Wiring: a new preference `forceModelLoad: Flow<Boolean>` (default false) in `PreferencesManager` / `PreferencesManagerImpl`, mirroring the existing boolean-preference pattern (e.g. `autoCopyEnabled`). Read by the orchestrator's `configureSherpaBackend` (which already has `PreferencesManager` injected) right before the memory check; when true, skip the check and proceed to the native load unconditionally. If the load then OOMs, component 1 still explains it on next launch.

Files: `app/src/main/java/com/antivocale/app/data/PreferencesManager.kt` + `PreferencesManagerImpl.kt` (flow + save), `TranscriptionOrchestrator.kt` (read the flag at the check site), `ui/tabs/SettingsTab.kt` (toggle), `ui/viewmodel/SettingsViewModel.kt` (state + handler), new strings.

## How the components cover each other

- Component 2 catches the clear cases in the moment (blocked before attempt), for every sherpa backend because it sits at the shared orchestrator chokepoint.
- Component 1 catches the cases component 2 misses: a load that passes the check but OOMs anyway because memory dropped meanwhile, because `availMem` overcounts reclaimable cache, or because sherpa needs more RAM than the file sizes suggest. This residual is not rare on cache-heavy devices, which is why component 1 is load-bearing and not just a fallback.
- Component 3 ensures the guard is never a dead end for a determined user: they can force past it, and component 1 still explains the outcome.

## Out of scope

- Automatic fallback to the Stock int8 variant (464MB) on low-RAM devices. Deliberately excluded to keep the user in command of model choice.
- Changing sherpa provider or memory-mapping options: no mmap provider is exposed by the sherpa config the app uses, so footprint reduction via sherpa options is not available.
- Reducing the SmoothQuant model size itself.

## Verification

1. Reproduce on the Realme RMX3853 under memory pressure: with force-load off and memory low, SmoothQuant load is blocked with the in-app message (no crash).
2. With force-load on under the same pressure: the app attempts the load, OOMs, and on next launch the diagnosis dialog explains the low-memory kill.
3. With adequate free memory (force-load off): SmoothQuant loads and transcribes normally (no false positive).
4. Coverage check: confirm the pre-flight runs for Whisper and for a custom-transducer import too (the chokepoint is shared), not only Parakeet. A custom-imported large model under memory pressure must be blocked with the same message.
5. `NativeCrashDetector` unit tests: `REASON_LOW_MEMORY` is recognized and distinguished from `REASON_CRASH_NATIVE` (different result variants); reason-scoped dedup means a native-crash then an OOM within 5 minutes both surface, not just the first.
6. Existing native-crash path (corrupt model exit(255)) still detected and shows its original message, not the OOM message.
7. The OOM diagnosis message does not assert "during model load" (the exit reason does not provide that causal link); it states the low-memory cause factually.
8. Negative test: the pre-flight does NOT fire for the non-sherpa LLM/GGUF paths (they never call `configureSherpaBackend`); hardens against a future refactor moving the check up into `ensureBackendLoaded`.
