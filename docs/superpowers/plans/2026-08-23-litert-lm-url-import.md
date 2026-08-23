# LiteRT-LM Hugging Face URL Import, implementation plan (TASK-373)

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users import any `.litertlm` model from a Hugging Face repository URL, downloaded into app storage and activated as the LLM (Gemma) backend.

**Architecture:** NOT an external-sherpa-platform family. A litert-lm asset is a single file consumed by `LlmManager` via the generic `model_path` preference, so the import reuses the manual-import tail of `onModelSelected` (saveModelPath + `saveTranscriptionBackend("llm")`). New `LitertLmUrlImporter` class owns URL parsing, repo listing, file selection and download; the ViewModel wires it to UI state.

**Tech Stack:** Kotlin, HuggingFaceRepoListing (existing), ResumeDownloadHelper (existing), HuggingFaceTokenManager (existing, gated repos), Compose dialog, DataStore preferences.

**Design decision (locked):** multiple `.litertlm` files in one repo are NOT auto-picked; the user chooses from a list (repo listings commonly carry variants). Zero `.litertlm` files fails loudly.

---

## File Structure

- Create: `app/src/main/java/com/antivocale/app/data/LitertLmUrlImporter.kt` (pure planner + download orchestration, constructor-injected deps for JVM testability).
- Modify: `app/src/main/java/com/antivocale/app/ui/viewmodel/ModelViewModel.kt` (adds `importLitertLmFromUrl(url)` + UI state for the picker dialog).
- Modify: `app/src/main/java/com/antivocale/app/ui/tabs/ModelTab.kt:426-460` (adds "Import from URL" button in the LiteRT-LM card + URL dialog + file-picker dialog).
- Modify: `app/src/main/res/values/strings.xml` + all 10 `values-*/strings.xml` (4 new strings).
- Test: `app/src/test/java/com/antivocale/app/data/LitertLmUrlImporterTest.kt`.

---

## Chunk 1: Importer core (TDD)

### Task 1: Planner, pick the .litertlm file from a repo listing

**Files:**
- Create: `app/src/main/java/com/antivocale/app/data/LitertLmUrlImporter.kt`
- Test: `app/src/test/java/com/antivocale/app/data/LitertLmUrlImporterTest.kt`

- [ ] **Step 1: Write failing tests for the planner**

```kotlin
package com.antivocale.app.data

import com.antivocale.app.data.HuggingFaceRepoListing.HfFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LitertLmUrlImporterTest {

    private fun lfs(name: String, size: Long = 2_600_000_000L) =
        HfFile.Lfs(name, "a".repeat(64), size)

    @Test(expected = IllegalArgumentException::class)
    fun `planner rejects repo without litertlm files`() {
        LitertLmUrlImporter.planDownload(
            files = listOf(HfFile.Plain("README.md", 100L), lfs("model.onnx")),
            repoId = "owner/repo")
    }

    @Test
    fun `planner returns single litertlm file directly`() {
        val plan = LitertLmUrlImporter.planDownload(
            files = listOf(HfFile.Plain("README.md", 100L), lfs("model.litertlm")),
            repoId = "owner/repo")
        assertEquals("model.litertlm", plan.single().fileName)
        assertEquals(2_600_000_000L, plan.single().sizeBytes)
    }

    @Test
    fun `planner returns all litertlm files when multiple exist`() {
        val plan = LitertLmUrlImporter.planDownload(
            files = listOf(lfs("e2b.litertlm", 1), lfs("e4b.litertlm", 2)),
            repoId = "owner/repo")
        assertEquals(listOf("e2b.litertlm", "e4b.litertlm"), plan.map { it.fileName })
    }

    @Test
    fun `planner rejects non hf url`() {
        assertTrue(LitertLmUrlImporter.parseRepoIdOrThrow("https://example.com/x") == null)
    }
}
```

- [ ] **Step 2: Run, expect compile failure**

Run: `./gradlew :app:testPlayStoreDebugUnitTest --tests '*LitertLmUrlImporterTest*'`
Expected: FAIL (unresolved reference `LitertLmUrlImporter`).

- [ ] **Step 3: Minimal implementation**

```kotlin
package com.antivocale.app.data

import com.antivocale.app.data.HuggingFaceRepoListing.HfFile
import javax.inject.Inject
import javax.inject.Singleton

/** One downloadable .litertlm asset discovered in a HF repo. */
data class LitertLmFile(val fileName: String, val sizeBytes: Long)

/**
 * TASK-373: import any .litertlm model from a Hugging Face repo URL. Deliberately
 * NOT part of the external-sherpa platform: a litert-lm asset is a single file
 * consumed via the generic model_path preference and the "llm" backend, exactly
 * like the manual SAF import (onModelSelected) and the curated Gemma downloads.
 */
@Singleton
class LitertLmUrlImporter @Inject constructor(
    private val listing: HuggingFaceRepoListing,
) {
    companion object {
        /** Pure, JVM-testable: which .litertlm files does this repo offer? */
        fun planDownload(files: List<HfFile>, repoId: String): List<LitertLmFile> {
            val candidates = files
                .filter { it.name.endsWith(".litertlm") }
                .map { LitertLmFile(it.name, it.size) }
            require(candidates.isNotEmpty()) {
                "no .litertlm file in $repoId (this importer is for LiteRT-LM models)"
            }
            return candidates
        }

        /** Same tolerance as the external URL importer: full URL or owner/repo. */
        fun parseRepoIdOrThrow(url: String): String? =
            HuggingFaceRepoListing.parseRepoId(url)
    }

    /** Lists the repo's .litertlm files; throws IllegalArgumentException on bad URL/empty repo. */
    fun listModels(url: String): List<LitertLmFile> {
        val repoId = parseRepoIdOrThrow(url)
            ?: throw IllegalArgumentException(
                "unsupported URL: $url (expected https://huggingface.co/<owner>/<repo>)")
        return planDownload(listing.listFiles(repoId), repoId)
    }

    fun downloadUrl(url: String, fileName: String): String {
        val repoId = parseRepoIdOrThrow(url) ?: error("unparsed repo id")
        return listing.resolveUrl(repoId, fileName)
    }
}
```

Note: `HuggingFaceRepoListing` is a plain class with default constructor args (same Dagger trap as `ExternalModelStore`, see CLAUDE.md); provide it via `AppModule`:

```kotlin
// AppModule.kt (add near the other data providers)
@Provides @Singleton
fun provideHuggingFaceRepoListing(): HuggingFaceRepoListing = HuggingFaceRepoListing()
```

- [ ] **Step 4: Run tests, expect PASS**

Run: `./gradlew :app:testPlayStoreDebugUnitTest --tests '*LitertLmUrlImporterTest*'`
Expected: 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/antivocale/app/data/LitertLmUrlImporter.kt \
  app/src/main/java/com/antivocale/app/di/AppModule.kt \
  app/src/test/java/com/antivocale/app/data/LitertLmUrlImporterTest.kt
git commit -m "feat(litert-lm): repo listing planner for HF url import (TASK-373)"
```

### Task 2: Download orchestration with resume + auth + disk pre-flight

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/data/LitertLmUrlImporter.kt`
- Test: extend `LitertLmUrlImporterTest.kt`

- [ ] **Step 1: Failing tests.** `importFromUrl` takes everything it needs as function parameters (modelsDir, freeBytes, token, download lambda), so the JVM tests pass fakes directly with no network or filesystem:

```kotlin
@Test
fun `download passes auth header for gated repos and resolve url form`() {
    var capturedUrl: String? = null
    var capturedAuth: String? = null
    val importer = LitertLmUrlImporter(FakeListing())
    val result = importer.importFromUrl(
        url = "https://huggingface.co/o/r",
        fileName = "m.litertlm", sizeBytes = 10L,
        modelsDir = File("/models"),
        freeBytes = { 1_000_000L }, token = "tok",
        download = { url, _, _, auth -> capturedUrl = url; capturedAuth = auth;
            Result.success(File("/models/m.litertlm")) })
    assertTrue(result.isSuccess)
    assertEquals("Bearer tok", capturedAuth)
    assertTrue(capturedUrl!!.endsWith("/o/r/resolve/main/m.litertlm"))
}

@Test
fun `download refuses when free space below 2x size`() {
    val importer = LitertLmUrlImporter(FakeListing())
    val result = importer.importFromUrl(
        url = "https://huggingface.co/o/r",
        fileName = "m.litertlm", sizeBytes = 100L,
        modelsDir = File("/models"),
        freeBytes = { 150L }, token = null,
        download = { _, _, _, _ -> error("must not be called") })
    assertTrue(result.isFailure)
}
```

(`FakeListing` is a tiny subclass of `HuggingFaceRepoListing` overriding nothing; importFromUrl never touches it in these tests because the download lambda is injected. Assert the empty-file rejection with a third test where the lambda returns a zero-length temp file.)

- [ ] **Step 2: Run, expect FAIL** (`importFromUrl` unresolved).

- [ ] **Step 3: Implement.** Invariants under test: 2x disk pre-flight (a download doubles usage, same binding as the external importer), Bearer passthrough, resolveUrl form, empty-file rejection:

```kotlin
fun importFromUrl(
    url: String,
    fileName: String,
    sizeBytes: Long,
    modelsDir: File,
    freeBytes: () -> Long,
    token: String?,
    download: (url: String, targetFile: File, sizeBytes: Long, authHeader: String?) -> Result<File>,
): Result<File> = runCatching {
    require(freeBytes() >= sizeBytes * 2) { "not enough free space (need ${sizeBytes * 2} bytes)" }
    modelsDir.mkdirs()
    val downloaded = download(
        listing.resolveUrl(parseRepoIdOrThrow(url)!!, fileName),
        File(modelsDir, fileName), sizeBytes, token?.let { "Bearer $it" }).getOrThrow()
    require(downloaded.length() > 0) { "downloaded file is empty" }
    downloaded
}
```

The signature above is THE seam: the ViewModel passes the production download lambda (Task 3), the tests pass fakes. Do not reshape it during implementation.

- [ ] **Step 4: Run tests, expect PASS.**
- [ ] **Step 5: Commit** `git commit -m "feat(litert-lm): resumable download with auth + disk pre-flight (TASK-373)"`

---

## Chunk 2: ViewModel + UI

### Task 3: ViewModel import flow

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/ui/viewmodel/ModelViewModel.kt` (near `onModelSelected`, ~line 662)

Thin composition over the tested importer (no new unit tests; covered by Tasks 1-2 plus the device gate).

- [ ] **Step 1: Add state + functions**

```kotlin
// UiState additions (nested data class, ModelViewModel.kt:134):
val litertLmUrlInput: String = "",
val litertLmCandidates: List<com.antivocale.app.data.LitertLmFile> = emptyList(),
val litertLmImporting: Boolean = false,

fun updateLitertLmUrl(url: String) { _uiState.update { it.copy(litertLmUrlInput = url) } }

fun listLitertLmModels(url: String) = viewModelScope.launch {
    _uiState.update { it.copy(litertLmImporting = true) }
    runCatching { litertLmUrlImporter.listModels(url) }
        .fold(
            onSuccess = { cands -> _uiState.update {
                it.copy(litertLmCandidates = cands, litertLmImporting = false) } },
            onFailure = { e ->
                _uiState.update { it.copy(litertLmImporting = false) }
                _snackbarEvent.tryEmit(SnackbarEvent.Message(
                    e.message ?: ctx.getString(R.string.litertlm_no_models))) })
}

fun importLitertLmFile(url: String, file: com.antivocale.app.data.LitertLmFile) =
    viewModelScope.launch(Dispatchers.IO) {
        _uiState.update { it.copy(litertLmImporting = true) }
        val modelsDir = File(ctx.filesDir, "models")
        val result = litertLmUrlImporter.importFromUrl(
            url, file.fileName, file.sizeBytes,
            freeBytes = { modelsDir.usableSpace },
            download = { dlUrl, target, size, auth ->
                ResumeDownloadHelper.downloadWithResume(
                    com.antivocale.app.data.download.DownloadConfig(
                        url = dlUrl,
                        tempFile = File(target.path + ".tmp"),
                        targetFile = target,
                        estimatedSizeBytes = size,
                        authHeader = auth))
            })
        _uiState.update { it.copy(
            litertLmImporting = false, litertLmCandidates = emptyList(), litertLmUrlInput = "") }
        result.fold(
            onSuccess = { f ->
                // Same tail as onModelSelected (route 3 of GH #23): path + backend switch.
                preferencesManager.saveModelPath(f.absolutePath)
                preferencesManager.saveTranscriptionBackend(LlmTranscriptionBackend.BACKEND_ID)
                _uiState.update { it.copy(
                    modelPath = f.absolutePath, modelName = f.name,
                    status = ModelStatus.UNLOADED,
                    statusMessage = ctx.getString(R.string.model_selected, f.name)) }
                _snackbarEvent.tryEmit(SnackbarEvent.Message(
                    ctx.getString(R.string.model_selected, f.name)))
            },
            onFailure = { e ->
                _snackbarEvent.tryEmit(SnackbarEvent.Message(e.message ?: "import failed")) })
    }
```

Wire `litertLmUrlImporter` via constructor injection. `ResumeDownloadHelper` is a Kotlin `object` (ResumeDownloadHelper.kt:41): call it statically, no injection.

- [ ] **Step 2: Compile** `./gradlew assemblePlayStoreDebug`. Expected: success.
- [ ] **Step 3: Commit** `git commit -m "feat(litert-lm): viewmodel import flow reusing the llm selection tail (TASK-373)"`

### Task 4: UI, button + URL dialog + candidate picker

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/ui/tabs/ModelTab.kt:444-461` (LiteRT-LM card)

- [ ] **Step 1: Add button under "Select from device"**

```kotlin
OutlinedButton(
    onClick = { showLitertLmUrlDialog = true },
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    enabled = !state.litertLmImporting,
) {
    Icon(Icons.Default.CloudDownload, contentDescription = null)
    Spacer(modifier = Modifier.width(8.dp))
    Text(stringResource(R.string.litertlm_import_from_url))
}
```

- [ ] **Step 2: URL dialog.** `AlertDialog` with an `OutlinedTextField` (state.litertLmUrlInput, hint = R.string.litertlm_url_hint); Confirm triggers `viewModel.listLitertLmModels(url)`. When `litertLmCandidates` becomes non-empty, a second `AlertDialog` lists the candidates (fileName + size in GB, `RadioButton` rows); selection triggers `viewModel.importLitertLmFile(url, file)`. If exactly one candidate, skip the picker and import directly. Show `CircularProgressIndicator` while `litertLmImporting`.

- [ ] **Step 3: Strings (values/strings.xml + all 10 locales)**

```xml
<string name="litertlm_import_from_url">Import from Hugging Face URL</string>
<string name="litertlm_url_hint">https://huggingface.co/owner/repo</string>
<string name="litertlm_pick_file">Choose model file</string>
<string name="litertlm_no_models">No .litertlm file found in this repository</string>
```

Italian: `Importa da URL Hugging Face` / hint unchanged / `Scegli il file del modello` / `Nessun file .litertlm in questo repository`. Translate the remaining 8 locales consistently with the TASK-350-352 pattern.

- [ ] **Step 4: Compile + full unit suite**

Run: `./gradlew :app:testPlayStoreDebugUnitTest`
Expected: BUILD SUCCESSFUL. (Known flake: if HuggingFaceAuthManagerTest fails with UnsatisfiedLinkError, re-run once; GH #58.)

- [ ] **Step 5: Commit** `git commit -m "feat(litert-lm): url import UI with candidate picker, 10 locales (TASK-373)"`

---

## Chunk 3: Verification gates

### Task 5: Device verification + review

- [ ] **Step 1: Build & install** `./gradlew assemblePlayStoreDebug && ./scripts/install.sh`
- [ ] **Step 2: Device test** (Realme, debug app): Model tab, Advanced, External models, LiteRT-LM, Import from URL, paste `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm`, expect candidate `gemma-4-E2B-it.litertlm` (~2.6GB; this file already exists on the debug device from the GH #23 tests, delete it via `run-as` first or accept the overwrite), import, then verify via `run-as ... cat files/datastore/localai_preferences.preferences_pb` that `model_path` points at files/models/<file> and `transcription_backend` is `llm`. Fire a short transcription via the Tasker broadcast harness and confirm `Gemma (LiteRT-LM)` as modelName in the logs DB.
- [ ] **Step 3: Negative test**: URL of an onnx-only sherpa repo; expect the loud "no .litertlm file" error and no download started.
- [ ] **Step 4: Review gates**: /review-local on the diff, /simplify, device re-check if either changed code.
- [ ] **Step 5: Close TASK-373 with evidence; restore Parakeet as active model after the test.**

---

## Notes for the implementer

- No NEW BackendRegistry descriptor and no `external:` family: the existing `llm` descriptor (BackendRegistry.kt:178) suffices, litert-lm lives on the generic `model_path` + `"llm"` backend (decision recorded in TASK-373 and validated by the GH #23 fixes: all three selection routes converge on `saveTranscriptionBackend("llm")`).
- `HuggingFaceRepoListing` and `ResumeDownloadHelper` are the only network touchpoints; no new permission, no ProGuard surface change (no JNI).
- If the repo is gated and no token is configured, the download fails with HF's 401: surface `e.message` in the snackbar and point the user to Settings (same behavior as curated Gemma downloads).
