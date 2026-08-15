# Parakeet SmoothQuant OOM Mitigation Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the silent lowmemorykiller OOM when loading the Parakeet SmoothQuant model on low-RAM devices, by blocking the load when memory is low, explaining it on next launch when it still happens, and letting users force past the guard.

**Architecture:** Three components at distinct layers. (1) Diagnose: extend `NativeCrashDetector` to recognize `REASON_LOW_MEMORY` with a reason-distinguishing sealed return type and reason-scoped dedup, surfaced from `MainActivity`. (2) Prevent: a pre-flight memory check in the shared `configureSherpaBackend` orchestrator chokepoint (covers all sherpa backends), reading `availMem` and the model dir size. (3) Escape hatch: a `forceModelLoad` boolean preference, read at the check site, exposed as a Settings toggle.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt DI, DataStore Preferences, Robolectric for unit tests, Android `ApplicationExitInfo` / `ActivityManager.MemoryInfo`.

**Spec:** `docs/superpowers/specs/2026-08-13-parakeet-oom-mitigation-design.md`

---

## File Structure

**Create:**
- `app/src/test/java/com/antivocale/app/util/NativeCrashDetectorTest.kt`: Robolectric tests for the detector's reason matching, sealed return, and reason-scoped dedup.

**Modify:**
- `app/src/main/java/com/antivocale/app/util/NativeCrashDetector.kt`: return-type change to sealed result, add `REASON_LOW_MEMORY`, reason-scoped dedup.
- `app/src/main/java/com/antivocale/app/MainActivity.kt`: consume the sealed result with a `when`, show the OOM message for `LowMemory`.
- `app/src/main/java/com/antivocale/app/transcription/TranscriptionOrchestrator.kt`: pre-flight memory check at the top of `configureSherpaBackend`, gated by the force-load flag.
- `app/src/main/java/com/antivocale/app/data/PreferencesManager.kt` + `PreferencesManagerImpl.kt`: `forceModelLoad` flow + save (mirror `autoCopyEnabled`).
- `app/src/main/java/com/antivocale/app/ui/viewmodel/SettingsViewModel.kt`: `forceModelLoad` state + setter.
- `app/src/main/java/com/antivocale/app/ui/tabs/SettingsTab.kt`: toggle UI.
- `app/src/main/res/values/strings.xml` + `values-it/strings.xml`: OOM diagnosis message, OOM pre-flight error, force-load toggle label/description.

---

## Chunk 1: Diagnose, NativeCrashDetector recognizes low-memory kills

### Task 1: Failing test, detector returns a sealed result distinguishing native crash from low memory

**Files:**
- Create: `app/src/test/java/com/antivocale/app/util/NativeCrashDetectorTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.antivocale.app.util

import android.content.Context
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.antivocale.app.util.NativeCrashDetector.CrashCheckResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class NativeCrashDetectorTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `check returns None when no exit history`() {
        // Fresh app process has no historical exit reasons.
        val result = NativeCrashDetector.checkForRecentCrash(context)
        assertTrue("expected None", result is CrashCheckResult.None)
    }

    @Test
    fun `LowMemory and NativeCrash are distinct result types`() {
        // The two non-None variants must be constructible and carry a timestamp, so the
        // caller can pick a distinct message per reason. (Avoids kotlin-reflect, which is
        // not a project dependency.)
        val lowMem = CrashCheckResult.LowMemory(timestamp = 1234L)
        val nativeCrash = CrashCheckResult.NativeCrash(timestamp = 5678L)
        assertTrue("LowMemory is its own type", lowMem is CrashCheckResult.LowMemory)
        assertTrue("NativeCrash is its own type", nativeCrash is CrashCheckResult.NativeCrash)
        assertTrue("the two variants are not the same type",
            lowMem::class != nativeCrash::class)
    }
}
```

**Note:** `CrashCheckResult` is a nested class of `NativeCrashDetector`, so the test imports it with its fully-qualified nested path `com.antivocale.app.util.NativeCrashDetector.CrashCheckResult`. Inside the detector object itself the unqualified name resolves and the implementation snippets there use it unqualified. Do NOT use `kotlin.reflect.full.sealedSubclasses`: the project has no `kotlin-reflect` dependency, so reflection would fail at runtime. The instance-based check above needs no extra dependency.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testPlayStoreDebugUnitTest --tests "com.antivocale.app.util.NativeCrashDetectorTest" --console=plain`
Expected: FAIL with unresolved reference `CrashCheckResult` and `checkForRecentCrash` (the new names do not exist yet).

- [ ] **Step 3: Commit the failing test**

```bash
git add app/src/test/java/com/antivocale/app/util/NativeCrashDetectorTest.kt
git commit -m "test: failing tests for NativeCrashDetector sealed result (TASK-314)"
```

### Task 2: Implement the sealed result and REASON_LOW_MEMORY recognition

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/util/NativeCrashDetector.kt`

- [ ] **Step 1: Add the sealed result type and reason-scoped dedup**

Replace the whole `NativeCrashDetector` object body. The public method is renamed `checkForRecentCrash` and returns `CrashCheckResult`. Dedup keys become reason-scoped.

```kotlin
package com.antivocale.app.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Log

/**
 * Detects whether the app's previous process died from a native crash (exit 255,
 * sherpa-onnx invalid model) or a low-memory kill (lmkd).
 *
 * On next launch, checks [ActivityManager.getHistoricalProcessExitReasons] for
 * [ApplicationExitInfo.REASON_CRASH_NATIVE] and [ApplicationExitInfo.REASON_LOW_MEMORY],
 * and reports which (if any) the UI should warn about, with a distinct message per reason.
 *
 * API 30+ only; earlier versions silently return [CrashCheckResult.None].
 *
 * Related: GitHub #21, TASK-314.
 */
object NativeCrashDetector {
    private const val TAG = "NativeCrashDetector"
    private const val PREFS_NAME = "native_crash_detection"
    private const val KEY_LAST_NATIVE_CRASH_TS = "last_native_crash_ts"
    private const val KEY_LAST_LOW_MEMORY_TS = "last_low_memory_ts"
    private const val RECENT_WINDOW_MS = 5 * 60 * 1000L // 5 minutes

    /**
     * Sealed result so the caller can pick a distinct message per cause. Each non-None
     * variant carries the exit timestamp for testing/diagnostics.
     */
    sealed class CrashCheckResult {
        data object None : CrashCheckResult()
        data class NativeCrash(val timestamp: Long) : CrashCheckResult()
        data class LowMemory(val timestamp: Long) : CrashCheckResult()
    }

    /**
     * Checks the app's most recent process death. Call exactly once per process launch,
     * from the first Activity's `onCreate`. Each detected reason is recorded in
     * SharedPreferences with a reason-scoped key, so a relaunch within [RECENT_WINDOW_MS]
     * does not nag again, AND a native crash then a low-memory kill (or vice versa) within
     * the window each surface once.
     */
    fun checkForRecentCrash(context: Context): CrashCheckResult {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            return CrashCheckResult.None // API 30+ only
        }

        return try {
            val am = context.getSystemService(ActivityManager::class.java)
            val exitInfos = am?.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                ?: return CrashCheckResult.None

            val mostRecent = exitInfos.firstOrNull() ?: return CrashCheckResult.None
            val crashTime = mostRecent.timestamp

            // Map the OS exit reason to our sealed variant. Only these two are actionable;
            // every other reason (ANR, user-initiated, etc.) maps to None.
            val matched: CrashCheckResult = when (mostRecent.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> CrashCheckResult.NativeCrash(crashTime)
                ApplicationExitInfo.REASON_LOW_MEMORY -> CrashCheckResult.LowMemory(crashTime)
                else -> return CrashCheckResult.None
            }

            val isRecent = (System.currentTimeMillis() - crashTime) < RECENT_WINDOW_MS
            Log.w(TAG, "Process exit detected: $matched (description=${mostRecent.description}, recent=$isRecent)")

            if (!isRecent) return CrashCheckResult.None

            // Reason-scoped dedup: each reason has its own last-shown timestamp key.
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val dedupKey = when (matched) {
                is CrashCheckResult.NativeCrash -> KEY_LAST_NATIVE_CRASH_TS
                is CrashCheckResult.LowMemory -> KEY_LAST_LOW_MEMORY_TS
                CrashCheckResult.None -> return CrashCheckResult.None
            }
            val lastShownTs = prefs.getLong(dedupKey, 0L)
            if (crashTime <= lastShownTs) return CrashCheckResult.None

            prefs.edit().putLong(dedupKey, crashTime).apply()
            matched
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for recent crash", e)
            CrashCheckResult.None
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :app:testPlayStoreDebugUnitTest --tests "com.antivocale.app.util.NativeCrashDetectorTest" --console=plain`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/antivocale/app/util/NativeCrashDetector.kt
git commit -m "feat: NativeCrashDetector recognizes REASON_LOW_MEMORY with sealed result (TASK-314)"
```

### Task 3: Add OOM diagnosis strings

**Files:**
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-it/strings.xml`

- [ ] **Step 1: Add the strings for the OOM diagnosis message**

In `values/strings.xml` (near `native_crash_model_warning`):

```xml
<string name="oom_crash_title">App closed for low memory</string>
<string name="oom_crash_warning">The app was closed because the device was running low on memory. Try closing other apps or using a smaller model before transcribing again.</string>
```

In `values-it/strings.xml`:

```xml
<string name="oom_crash_title">App chiusa per memoria insufficiente</string>
<string name="oom_crash_warning">L\'app è stata chiusa perché il dispositivo aveva poca memoria libera. Prova a chiudere le altre app o a usare un modello più piccolo prima di trascrivere di nuovo.</string>
```

- [ ] **Step 2: Commit strings**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml
git commit -m "feat: OOM diagnosis dialog strings (TASK-314)"
```

### Task 4: Update MainActivity to consume the sealed result

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/MainActivity.kt:75-84`

- [ ] **Step 1: Replace the `if (Boolean)` with a `when` over the sealed result**

In `MainActivity.onCreate`, replace the existing `if (NativeCrashDetector.checkForRecentNativeCrash(this)) { ... }` block with:

```kotlin
when (val crash = NativeCrashDetector.checkForRecentCrash(this)) {
    is NativeCrashDetector.CrashCheckResult.NativeCrash -> {
        AlertDialog.Builder(this)
            .setTitle(R.string.native_crash_title)
            .setMessage(R.string.native_crash_model_warning)
            .setPositiveButton(R.string.native_crash_go_to_model) { _, _ ->
                _navigateToModelTab.value = true
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
    is NativeCrashDetector.CrashCheckResult.LowMemory -> {
        AlertDialog.Builder(this)
            .setTitle(R.string.oom_crash_title)
            .setMessage(R.string.oom_crash_warning)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
    NativeCrashDetector.CrashCheckResult.None -> { /* no-op */ }
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew :app:compilePlayStoreDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/antivocale/app/MainActivity.kt
git commit -m "feat: MainActivity shows OOM diagnosis for low-memory kills (TASK-314)"
```

---

## Chunk 2: Prevent, pre-flight memory check at the orchestrator chokepoint

### Task 5: Add the forceModelLoad preference (needed by the check)

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/data/PreferencesManager.kt`
- Modify: `app/src/main/java/com/antivocale/app/data/PreferencesManagerImpl.kt`

- [ ] **Step 1: Declare the flow + setter in the interface**

In `PreferencesManager.kt`, add near the other boolean flows (e.g. after `showRetranscribeButton`):

```kotlin
val forceModelLoad: Flow<Boolean>
```

and in the setter section:

```kotlin
suspend fun saveForceModelLoad(enabled: Boolean)
```

In the companion object defaults:

```kotlin
const val DEFAULT_FORCE_MODEL_LOAD = false
```

- [ ] **Step 2: Implement in PreferencesManagerImpl**

Add the key (near `SHOW_RETRANSCRIBE_BUTTON`):

```kotlin
private val FORCE_MODEL_LOAD = booleanPreferencesKey("force_model_load")
```

Add to the cache data class (near `showRetranscribeButton`):

```kotlin
val forceModelLoad: Boolean = PreferencesManager.DEFAULT_FORCE_MODEL_LOAD,
```

Add to the cache builder mapping (near `showRetranscribeButton = this[...]`):

```kotlin
forceModelLoad = this[FORCE_MODEL_LOAD] ?: PreferencesManager.DEFAULT_FORCE_MODEL_LOAD,
```

Add the flow + setter (near the other boolean flows):

```kotlin
override val forceModelLoad: Flow<Boolean> = context.dataStore.data.map { it[FORCE_MODEL_LOAD] ?: PreferencesManager.DEFAULT_FORCE_MODEL_LOAD }
    .onStart { emit(cache.get().forceModelLoad) }

override suspend fun saveForceModelLoad(enabled: Boolean) {
    context.dataStore.edit { preferences ->
        preferences[FORCE_MODEL_LOAD] = enabled
    }
    cache.updateAndGet { it.copy(forceModelLoad = enabled) }
}
```

- [ ] **Step 3: Build to verify**

Run: `./gradlew :app:compilePlayStoreDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/antivocale/app/data/PreferencesManager.kt app/src/main/java/com/antivocale/app/data/PreferencesManagerImpl.kt
git commit -m "feat: forceModelLoad preference (TASK-314)"
```

### Task 6: Add the pre-flight memory check to configureSherpaBackend

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/transcription/TranscriptionOrchestrator.kt` (`configureSherpaBackend`, ~line 366)
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-it/strings.xml`

- [ ] **Step 1: Add the OOM pre-flight error strings**

`values/strings.xml`:

```xml
<string name="model_load_low_memory">Not enough free memory for this model (%1$s available, about %2$s needed). Close other apps or use a smaller model. You can also enable \"Force model load\" in Settings to try anyway.</string>
```

`values-it/strings.xml`:

```xml
<string name="model_load_low_memory">Memoria libera insufficiente per questo modello (%1$s disponibili, circa %2$s necessari). Chiudi altre app o usa un modello più piccolo. Puoi anche attivare \"Forza caricamento modello\" nelle Impostazioni per riprovare comunque.</string>
```

- [ ] **Step 2: Add a memory helper and call it at the top of configureSherpaBackend**

At the top of the `configureSherpaBackend` body (after the `modelDir` existence check, before the `Log.i` "Auto-loading" line), insert:

```kotlin
// Pre-flight memory check: refuse to load if free memory is below the model size + headroom.
// Gated by the forceModelLoad preference so a determined user can bypass it.
if (!preferencesManager.forceModelLoad.first()) {
    val modelSizeBytes = modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    val availBytes = availableMemoryBytes(context)
    val requiredBytes = modelSizeBytes + MEMORY_HEADROOM_BYTES
    if (availBytes < requiredBytes) {
        Log.w(TAG, "Blocking $label load: avail=${availBytes / MB}MB < required=${requiredBytes / MB}MB (model=${modelSizeBytes / MB}MB + headroom=${MEMORY_HEADROOM_BYTES / MB}MB)")
        return Result.failure(TranscriptionException.ModelLoadError(
            context.getString(R.string.model_load_low_memory, formatMb(availBytes), formatMb(requiredBytes))
        ))
    }
}
```

Add the companion-object constants and the private helpers (in the orchestrator companion object and as private funs):

```kotlin
private const val MB = 1024L * 1024L
// Headroom over the on-disk model size: absorbs sherpa inference buffers and reclaimable-cache
// noise in availMem. Tunable; see spec. ~300MB derived from the SmoothQuant incident.
private const val MEMORY_HEADROOM_BYTES = 300L * MB
```

```kotlin
/** Returns the system's available memory (the closest user-space analog to MemAvailable). */
private fun availableMemoryBytes(context: Context): Long {
    val info = ActivityManager.MemoryInfo()
    val am = context.getSystemService(ActivityManager::class.java)
    am?.getMemoryInfo(info)
    return info.availMem
}

private fun formatMb(bytes: Long): String = "${bytes / MB}MB"
```

Add the imports at the top of the file:

```kotlin
import android.app.ActivityManager
import android.content.Context
```

(`Context` is likely already imported; add only what's missing.)

- [ ] **Step 3: Build to verify**

Run: `./gradlew :app:compilePlayStoreDebugKotlin :app:compileFdroidDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL on both flavors.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/antivocale/app/transcription/TranscriptionOrchestrator.kt app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml
git commit -m "feat: pre-flight memory check in configureSherpaBackend (TASK-314)"
```

---

## Chunk 3: Escape hatch, force-load toggle in Settings

### Task 7: Expose forceModelLoad in SettingsViewModel

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/ui/viewmodel/SettingsViewModel.kt`

- [ ] **Step 1: Add a StateFlow + setter mirroring an existing boolean setting**

Find how another boolean setting (e.g. `autoCopyEnabled`) is exposed as state + setter in this ViewModel, and mirror it exactly for `forceModelLoad`:

```kotlin
// In the UiState data class, add:
val forceModelLoad: Boolean = PreferencesManager.DEFAULT_FORCE_MODEL_LOAD,

// A setter:
fun setForceModelLoad(enabled: Boolean) {
    viewModelScope.launch {
        preferencesManager.saveForceModelLoad(enabled)
    }
}
```

Hydrate it from preferences in the same place the other boolean settings are loaded into `_uiState` (look for where `autoCopyEnabled` is read and copy the pattern).

- [ ] **Step 2: Build to verify**

Run: `./gradlew :app:compilePlayStoreDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/antivocale/app/ui/viewmodel/SettingsViewModel.kt
git commit -m "feat: expose forceModelLoad in SettingsViewModel (TASK-314)"
```

### Task 8: Add the toggle in SettingsTab

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/ui/tabs/SettingsTab.kt`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-it/strings.xml`

- [ ] **Step 1: Add the toggle label/description strings**

`values/strings.xml`:

```xml
<string name="force_model_load">Force model load</string>
<string name="force_model_load_desc">Load the model even when memory seems insufficient. May close the app on devices with little RAM.</string>
```

`values-it/strings.xml`:

```xml
<string name="force_model_load">Forza caricamento modello</string>
<string name="force_model_load_desc">Carica il modello anche quando la memoria sembra insufficiente. Può far chiudere l\'app sui dispositivi con poca RAM.</string>
```

- [ ] **Step 2: Add a Switch row mirroring another boolean toggle in SettingsTab**

Find an existing boolean toggle in `SettingsTab` (e.g. the `autoCopyEnabled` or `vadEnabled` switch) and copy its structure for `forceModelLoad`:

```kotlin
// Switch bound to uiState.forceModelLoad, onCheckedChange = { viewModel.setForceModelLoad(it) }
// Title: stringResource(R.string.force_model_load)
// Subtitle/supporting text: stringResource(R.string.force_model_load_desc)
```

Place it in the same section/group as the other transcription-related toggles.

- [ ] **Step 3: Build to verify**

Run: `./gradlew :app:assemblePlayStoreDebug --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/antivocale/app/ui/tabs/SettingsTab.kt app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml
git commit -m "feat: force-load toggle in Settings (TASK-314)"
```

---

## Chunk 4: Verify on device

### Task 9: On-device verification

**Files:** none (manual + adb)

- [ ] **Step 1: Install the debug build on the Realme RMX3853**

Run: `./scripts/install.sh` (after ensuring the wireless debugging port is current).

- [ ] **Step 2: Verify the pre-flight blocks under memory pressure**

With SmoothQuant downloaded and the device under memory pressure (free memory below ~1.16GB), attempt a transcription. Expected: in-app error "Not enough free memory for this model..." with no crash.

- [ ] **Step 3: Verify force-load bypasses the check**

Enable "Force model load" in Settings, repeat the transcription under the same pressure. Expected: the app attempts the load; if it OOMs, it closes silently.

- [ ] **Step 4: Verify the diagnosis dialog on next launch**

After a force-load OOM, relaunch the app. Expected: "App closed for low memory" dialog with the OOM message.

- [ ] **Step 5: Verify no false positive with adequate memory**

With the device recovered (~3GB free, force-load off), transcribe. Expected: SmoothQuant loads and transcribes normally.

- [ ] **Step 6: Verify reason-scoped dedup**

Force a native crash (if reproducible with a corrupt model) then an OOM within 5 minutes; relaunch. Expected: both dialogs surface on their respective launches, not suppressed by the other.

- [ ] **Step 7: Run the full unit test suite**

Run: `./gradlew :app:testPlayStoreDebugUnitTest --console=plain`
Expected: all tests PASS, including `NativeCrashDetectorTest`.

---

## Notes for the implementer

- **Why the orchestrator, not the backend:** each sherpa backend (Parakeet, Whisper, Qwen3, CustomTransducer) has its own `initialize` and its own `OfflineRecognizer` construction. `configureSherpaBackend` is the single shared chokepoint they all pass through, and it already has `preferencesManager` injected. Do not move this check into `SherpaOnnxBackend.initialize` (it would only cover Parakeet and has no preference access).
- **Why sum all files in the dir, not a fixed triplet:** Whisper has no joiner, Qwen3 has a conv_frontend + tokenizer dir, custom imports can hold anything. `modelDir.walkTopDown()` is layout-agnostic.
- **availMem is a coarse predictor:** modern lmkd uses PSI + oom_score_adj, not a literal MemAvailable comparison. The headroom constant absorbs both inference overhead and reclaimable-cache noise. The 300MB starting value is a session observation, confirm empirically.
- **The OOM diagnosis message must not say "during model load":** `REASON_LOW_MEMORY` says the process was killed for memory, not when. The message states the cause factually.
- **em-dashes are banned** in all prose per the project writing rule. The string snippets above avoid them.
