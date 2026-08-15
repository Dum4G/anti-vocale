# Anti-Vocale

Android application written in Kotlin for transcribing voice messages locally on-device.

## Project Info
- **GitHub:** `RisorseArtificiali/anti-vocale` (fork: `paoloantinor/anti-vocale`)
- **Language:** Kotlin
- **Platform:** Android

## Development
- Default branch: `main`
- Git protocol: SSH
- **adb path:** `~/Android/Sdk/platform-tools/adb`
- **Build & install on device:** `./scripts/install.sh` (ALWAYS use this — never `./gradlew installDebug`)
- **Device:** Realme RMX3853 (Android 16, wireless debugging, paired once and persistently connected). It shows up in `adb devices` automatically, with a long mDNS serial like `adb-b51d20e6-XDR829 (2)._adb-tls-connect._tcp`. Do NOT run `adb disconnect` (it breaks the existing connection and `adb connect ip:port` will not re-establish it on a stale/rotated port). To target it, pass the serial to `-s` exactly as `adb devices` prints it; you can capture it with `D=$(adb devices | sed -n 's/^\(.*_adb-tls-connect\._tcp\)[[:space:]]*device$/\1/p')` and then `adb -s "$D" ...` (the serial contains spaces: an awk `$1` capture truncates it and adb reports "device not found"). The IP port (e.g. 192.168.20.174:40079) rotates and is irrelevant for commands. If the device ever drops off entirely, the user re-enables wireless debugging on the phone; otherwise no user input is needed.

@import docs/BUILD.md

## Key Identifiers

- **Package:** `com.antivocale.app`

## Project Structure

- `app/src/main/java/com/antivocale/app/` — Main source
  - `transcription/` — Transcription backends + model managers:
    - `SherpaOnnxBackend` (Parakeet TDT — SmoothQuant recommended + Stock int8 fallback, via OfflineRecognizer)
    - `WhisperBackend` (Whisper Distil-IT/Small/Turbo/Medium, via OfflineRecognizer)
    - `Qwen3AsrBackend` (Qwen3-ASR 0.6B, via OfflineRecognizer)
    - `NemotronStreamingBackend` (Nemotron 3.5 multilingual, via OnlineRecognizer — the only streaming backend)
    - `GigaAmBackend` (GigaAM v3 Russian, via OfflineRecognizer; HF mirror `pantinor/gigaam-v3`, SHA-256 pinned)
    - `CustomTransducerBackend` (sideloaded user-imported transducer dirs; no manifest share target, blank alias in the registry)
    - `LlmTranscriptionBackend` (Gemma via LiteRT-LM)
    - Each backend has a `*ModelManager` (discovery/validation) + `*Downloader` (HF download). `OrphanedModelDirCleaner` reclaims stranded old-version dirs at startup.
  - `ui/` — Compose UI screens and view models
  - `receiver/` — Broadcast receivers + share-target aliases (ShareReceiverActivity)
  - `data/` — Preferences, ShareTargetManager, download infrastructure
  - `util/` — `CrashReporter` (flavor-split), `TranscriptFileSaver` (SAF auto-save), `AppNotificationChannel`, etc.
- `app/src/playStore/` — playStore-flavor source set: `CrashReporter` (Firebase-backed), `AndroidManifest.xml` (Firebase service suppression)
- `app/src/fdroid/` — fdroid-flavor source set: `CrashReporter` (logcat-only no-op). Firebase-free build for F-Droid.
- `app/libs/` — Prebuilt AAR (sherpa-onnx, NOT committed since v1.8.3): run `./scripts/fetch-sherpa-aar.sh` once after cloning, or Gradle fails resolving the runtime classpath
- `docs/` — Build guides, research notes, scout reports
- `scripts/` — Build/install helpers (`install.sh`)
- `eval/` — Desktop eval harness (`run_baseline.py`: WER/CER/loops via sherpa-onnx Python; `smoke_nemotron.py`: model validation). Uses `eval/.venv` with sherpa-onnx 1.13.3 Python.
- `fastlane/` — Store listing metadata (en-US + it-IT) for F-Droid
- `metadata/` — F-Droid build recipe (`com.antivocale.app.yml`)

## Architecture Gotchas

**Build flavors: playStore vs fdroid.** Two product flavors (`flavorDimensions += "store"`):
- `playStore` — includes Firebase Crashlytics + Analytics (scoped via `"playStoreImplementation"`). Firebase plugins applied conditionally based on `gradle.startParameter.taskNames` containing "Fdroid".
- `fdroid` — Firebase-free. `CrashReporter` is a logcat-only no-op. No `google-services.json` needed.
- Same `applicationId` (`com.antivocale.app`) for both — users can switch stores.
- Build commands: `./gradlew assemblePlayStoreDebug`, `./gradlew assembleFdroidRelease`, etc.
- `./gradlew assembleDebug` is ambiguous (must specify a flavor).

**Unit tests:** `./gradlew :app:testPlayStoreDebugUnitTest` (CI runs the fdroid flavor, `testFdroidDebugUnitTest`: same shared suite, because the playStore debug build carries the `.debug` applicationIdSuffix, which the Firebase google-services.json has no client for). That suffix is a standing trap: the debug package is `com.antivocale.app.debug`, NOT `com.antivocale.app` (the user's real installed app). Whatever touches package ids, shares, or notifications: verify which of the two you are driving.

**Adding a transcription backend → start from BackendRegistry (TASK-254..324 migrated the dispatch sites).** Add a `BackendDescriptor` in `transcription/BackendRegistry.kt` (backend id, ModelType, share alias, preference accessors, display-name derivation). The registry's KDoc carries the live checklist of what consumes it and what legitimately remains separate. Since the migrations:
- `ActiveModelRepository` (active model name/path), `TranscriptionOrchestrator` (backend loading + saved-path lookup), `ShareTargetManager`/`ShareReceiverActivity` (share targets and alias resolution) all dispatch through the registry.
- `SettingsViewModel` collects `ActiveModelRepository` (the old dual-state root smell is gone); `ModelViewModel`'s file-validity check keys on the descriptor's ModelType (its benchmark-config when and other BACKEND_ID constant uses are documented in the registry KDoc).
- Deliberately separate: `ExtractionService.ModelType` stays the persistence/bookkeeping enum (its download dispatch carries no registry data); the manifest `activity-alias` names stay literal strings (pinned by `BackendRegistryTest`); `PreferencesManager` is the data source the descriptors delegate to; `TranscriptionModule`'s `@IntoSet` DI registration is its own concern.
- The disabled GGUF backend (`"gemma4_gguf"`) has NO descriptor: its literal id is matched explicitly at the fallback sites (orchestrator, repository, ModelViewModel). If it is ever re-enabled, give it a BACKEND_ID constant and a descriptor instead.
- After adding a backend, still `grep -rE "BACKEND_ID|gemma4_gguf" app/src/main` to confirm the GGUF fallback sites and any constant uses are coherent.

## Skills

- **`/model-scout [scope]`** -- Scout HuggingFace, GitHub releases, and the ASR landscape for new models, framework updates, and techniques that could improve on-device transcription. Scopes: `full`, `asr`, `llm`, `frameworks`, `parakeet`, `whisper`, `qwen`. Reports saved to `docs/scout-reports/`.

## Release Checklist: New Models / Native Libraries / Architectures

Whenever integrating a new model, native library, JNI bridge, or supporting a new CPU architecture, **always** verify ProGuard/R8 rules before shipping a release build:

1. **Check `app/proguard-rules.pro`** — does the new code have JNI reflection, `@Keep` annotations, or dynamically-loaded classes that R8 could strip?
2. **Add keep rules** for any new native-facing classes:
   ```proguard
   -keep class com.antivocale.app.<new_package>.** { *; }
   ```
3. **Build a release APK** (`./gradlew assemblePlayStoreRelease` or `assembleFdroidRelease`) and test on a real device — debug builds don't apply R8, so JNI crashes only surface in release.
4. **Key symptom**: model or native component works in debug but crashes immediately in release → almost always an R8 stripping issue.

**Context**: The distil-large-v3 Whisper model crashed on the v1.1.1 Play Store release because R8 stripped Kotlin metadata and transcription backend classes needed for JNI reflection. The fix was adding keep rules for `*Annotation*/InnerClasses/Signature`, `com.antivocale.app.transcription.**`, and `@androidx.annotation.Keep`.

### Pre-Release R8 Audit Procedure

Before every release, run this audit to catch R8 stripping issues:

1. **Find all JNI/native dependencies** — scan `app/build.gradle.kts` for native library dependencies (AARs with `.so` files, JNI bridges)
2. **Cross-reference with proguard-rules.pro** — every native library package MUST have a `-keep class` entry
3. **Check for stale rules** — if a library was replaced (e.g., `de.kherud.llama` → `com.suhel.llamabro`), update the keep rule to match the new package
4. **Verify dynamically-registered classes** — classes registered via Hilt multibinding, map lookups, or string-based instantiation need keep rules. The existing `com.antivocale.app.transcription.**` rule covers backend classes
5. **Audit command**: `grep -E 'import (com\.|de\.|org\.)' app/src/main/java/ -rh | sed 's/.*import //' | sed 's/\..*//' | sort -u` — compare output against keep rule packages

**Known native libraries and their keep rule packages**:
| Library | Keep Package | Notes |
|---------|-------------|-------|
| sherpa-onnx | `com.k2fsa.sherpa.onnx.**` | ONNX inference via JNI |
| LiteRT-LM | `com.google.ai.edge.litertlm.**` | Gemma inference via JNI |
| llama-bro | `com.suhel.llamabro.**` | GGUF inference via llama.cpp JNI |

<CRITICAL_INSTRUCTION>

## BACKLOG WORKFLOW INSTRUCTIONS

This project uses Backlog.md MCP for all task and project management activities.

**CRITICAL RESOURCE**: Read `backlog://workflow/overview` to understand when and how to use Backlog for this project.

- **First time working here?** Read the overview resource IMMEDIATELY to learn the workflow
- **Already familiar?** You should have the overview cached ("## Backlog.md Overview (MCP)")
- **When to read it**: BEFORE creating tasks, or when you're unsure whether to track work

The overview resource contains:
- Decision framework for when to create tasks
- Search-first workflow to avoid duplicates
- Links to detailed guides for task creation, execution, and completion
- MCP tools reference

You MUST read the overview resource to understand the complete workflow. The information is NOT summarized here.

</CRITICAL_INSTRUCTION>
