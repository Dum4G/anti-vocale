# Multi-family external-model importer and engine (TASK-331) Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the external-models platform family-aware: sherpa-onnx imports in TRANSDUCER, WHISPER, CTC, SENSE_VOICE families flow through one importer and one engine, with import-time validation that prevents native exit(255).

**Architecture:** A sealed `ModelFamilySupport` interface with one object per family is the single place of family knowledge (copy plan, metadata keys, sherpa config construction). `ExternalModelImporter` and `ExternalSherpaBackend` consult the table instead of branching. The record gains a flat `options` map for family-specific parameters; the UI architecture selector becomes a family dropdown with a conditional options panel and language search over catalog entries.

**Tech Stack:** Kotlin, Jetpack Compose (ModelTab), sherpa-onnx AAR 1.13.4 (`app/libs/`, fetched by `./scripts/fetch-sherpa-aar.sh`), JUnit+Robolectric JVM tests, `eval/.venv` sherpa-onnx Python for desktop validation.

**Spec:** `docs/superpowers/specs/2026-08-17-multi-family-external-models-design.md` (read it first; every decision below traces to it).

**Conventions for every task:** unit tests run with `./gradlew :app:testPlayStoreDebugUnitTest --tests "<pattern>"`; on repeat failures read the full Gradle output before changing anything; commit after each green test run. Never run `./gradlew installDebug`: device installs go through `./scripts/install.sh` only.

---

## Chunk 1: Data model groundwork

### Task 1: Desktop validation spike (eval/)

**Files:**
- Create: `eval/multifamily_probe.py`
- Create: `docs/research/multifamily_desktop_validation.md` (findings)

This task produces the facts the rest of the plan consumes: per-family ONNX metadata keys, the Whisper `language` sentinel the loader accepts, and `.onnx.data` resolution under renamed canonical files. It is a spike, not app code; findings are recorded before Tasks 4-6 are finalized.

- [ ] **Step 1: Write the probe script**

```python
# eval/multifamily_probe.py — loads GigaAM v3 CTC and a small Whisper ONNX pair
# with sherpa-onnx Python, printing (a) which ONNX metadata_props each file carries,
# (b) which OfflineWhisperModelConfig.language values are accepted (try "", "multi",
# "ar", "en"), (c) whether a renamed encoder.onnx + un-renamed encoder.onnx.data pair
# loads (ONNX external-data references are basenames relative to the model file).
# Reuse eval/.venv (sherpa-onnx 1.13.3). Model sources: istupakov/gigaam-v3-onnx
# (v3_ctc files, small) and any small whisper-onnx pair from k2-fsa releases.
```

- [ ] **Step 2: Run it and record findings**

Run: `eval/.venv/bin/python eval/multifamily_probe.py`
Expected: printed metadata key sets per family; accepted language sentinel(s); split-file load verdict. Record all three in `docs/research/multifamily_desktop_validation.md`. If a family's files carry NO metadata, record that explicitly: that family's `metadataKeys()` returns an empty list and relies on the structural discriminators (spec decision #2).

- [ ] **Step 3: Commit**

```bash
git add eval/multifamily_probe.py docs/research/multifamily_desktop_validation.md
git commit -m "eval: multi-family desktop validation probe (TASK-331)"
```

### Task 2: ModelFamily enum + options field on the record

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/data/ExternalModels.kt`
- Test: `app/src/test/java/com/antivocale/app/data/ExternalModelStoreTest.kt` (add cases)

- [ ] **Step 1: Write failing tests** (in ExternalModelStoreTest.kt, following its existing style)

Test cases: (a) a legacy record JSON without `options` decodes with `options == emptyMap()`; (b) a record with `"options":{"whisper.language":"ar"}` round-trips encode→decode with the pair intact; (c) `ModelFamily.valueOf("WHISPER")` resolves.

- [ ] **Step 2: Run, verify FAIL**

Run: `./gradlew :app:testPlayStoreDebugUnitTest --tests "com.antivocale.app.data.ExternalModelStoreTest"`
Expected: compile error on `ModelFamily.WHISPER` / missing `options`.

- [ ] **Step 3: Implement**

`ModelFamily` already exists with only `TRANSDUCER` (ExternalModels.kt:7, stale "arrive in v2b" comment): EXTEND it, do not redeclare it, and update the comment to drop the stale note.

```kotlin
enum class ModelFamily { TRANSDUCER, WHISPER, CTC, SENSE_VOICE }
```

`ExternalModelRecord` gains `val options: Map<String, String> = emptyMap()` (declared after `languages`); `toJson` writes `put("options", JSONObject(options))` (an empty JSON object for new records); `fromJson` reads it with `o.optJSONObject("options")` null-tolerant. Keep the whole-record rejection behavior unchanged.

- [ ] **Step 4: Run, verify PASS. Commit** `feat: ModelFamily enum and record options map (TASK-331)`

### Task 3: Entry-JSON family/options

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/data/HuggingFaceRepoListing.kt` (`ExternalModelEntryJson`)
- Test: `app/src/test/java/com/antivocale/app/data/HuggingFaceRepoListingTest.kt`

- [ ] **Step 1: Failing tests:** entry without `family` parses as TRANSDUCER; entry with `"family":"WHISPER"` parses as WHISPER; entry with unknown family string throws `IllegalArgumentException`; entry with `"options":{"whisper.task":"transcribe"}` parses into the map; modelType default is family-aware: a WHISPER entry without `modelType` parses with `modelType == ""` (NOT the legacy `"nemo_transducer"` default, which today would leak into non-transducer records), a TRANSDUCER entry without it keeps `"nemo_transducer"`; entries with a `family` present but no `languages` array are rejected with `IllegalArgumentException("entries must declare languages")` (spec: mandatory for new entries; entries without `family` keep the legacy optional behavior for backward compat).

- [ ] **Step 2: Verify FAIL.**

- [ ] **Step 3: Implement:** `Entry` gains `family: ModelFamily` (default TRANSDUCER via `optString("family", "TRANSDUCER")` + `runCatching { ModelFamily.valueOf(...) }.getOrElse { throw IllegalArgumentException("unknown family: ...") }`) and `options: Map<String, String>` (null-tolerant `optJSONObject`). The `modelType` default becomes family-resolved: absent field → `"nemo_transducer"` only for TRANSDUCER, `""` otherwise; an explicitly inconsistent pair (non-empty modelType against a non-matching family) is rejected naming the conflict.

- [ ] **Step 4: Verify PASS. Commit** `feat: entry-JSON family and options fields (TASK-331)`

---

## Chunk 2: The family support table

### Task 4: ModelFamilySupport interface + TransducerSupport extraction

**Files:**
- Create: `app/src/main/java/com/antivocale/app/transcription/ModelFamilySupport.kt`
- Modify: `app/src/main/java/com/antivocale/app/data/ExternalModelImporter.kt` (delegate `buildCopyPlan`)
- Test: `app/src/test/java/com/antivocale/app/transcription/ModelFamilySupportTest.kt` (new)

- [ ] **Step 1: Failing tests:** `forFamily(TRANSDUCER)` returns the transducer support; its `buildCopyPlan` reproduces today's behavior verbatim (existing `ExternalModelImporterTest` copy-plan cases must stay green because behavior is identical); `metadataFileRole()` returns `SherpaOnnxBackend.CANONICAL_ENCODER`; `metadataKeys("nemo_transducer")` equals `SherpaOnnxBackend.requiredTransducerMetadataKeys("nemo_transducer")`.

- [ ] **Step 2: Verify FAIL.**

- [ ] **Step 3: Implement** the sealed interface exactly as the spec's code block (family, requiredRoles, buildCopyPlan, metadataFileRole, metadataKeys, buildModelConfig, companion `forFamily` returning the matching object). `TransducerSupport` holds the moved keyword logic (encoder/decoder/joiner+"joint"/rnnt-hinted tokens) and the transducer `buildModelConfig` body lifted from `ExternalSherpaBackend.initialize` (`OfflineTransducerModelConfig(encoder, decoder, joiner)`, `modelType = record.modelType`). In `ExternalModelImporter`, `buildCopyPlan(files)` becomes `ModelFamilySupport.forFamily(ModelFamily.TRANSDUCER).buildCopyPlan(files)`; the importer's `internal` wrapper stays so the existing tests keep compiling.

- [ ] **Step 4: Run BOTH suites** (`ExternalModelImporterTest`, `ModelFamilySupportTest`), verify PASS (regression: importer behavior unchanged).

- [ ] **Step 5: Commit** `refactor: extract TransducerSupport into ModelFamilySupport table (TASK-331)`

### Task 5: WhisperSupport

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/transcription/ModelFamilySupport.kt`
- Test: `app/src/test/java/com/antivocale/app/transcription/ModelFamilySupportTest.kt`

- [ ] **Step 1: Failing tests:** copy plan maps encoder+decoder+tokens from a whisper-style file set (no joiner needed); a candidate pool containing a joiner/joint `.onnx` throws the transducer discriminator error even when encoder+decoder+tokens are all present; language resolution order `options["whisper.language"]` → `record.languages[0]` → sentinel from Task 1 findings; `buildModelConfig` sets `OfflineWhisperModelConfig.language/task` and `OfflineModelConfig.modelType = "whisper"` (assert via the built config's fields); `metadataKeys()` from Task 1 findings.

- [ ] **Step 2: Verify FAIL.**

- [ ] **Step 3: Implement** `WhisperSupport` per spec: roles encoder/decoder/tokens; tokens optional if Task 1 shows sherpa whisper needs no tokens file, otherwise mandatory (record which in the KDoc); the discriminator throws `IllegalArgumentException("candidate set contains a joiner/joint file: looks like a transducer; pick the TRANSDUCER family")` (the importer already surfaces `IllegalArgumentException` messages to the UI); `tailPaddings` untouched (sherpa default).

- [ ] **Step 4: Verify PASS. Commit** `feat: WhisperSupport family (TASK-331)`

### Task 6: CtcSupport

**Files:** same as Task 5.

- [ ] **Step 1: Failing tests:** istupakov-style file set (both `v3_ctc_*` and `v3_e2e_rnnt_*` present) planned as CTC selects the ctc-hinted encoder and ctc-hinted vocab, never the rnnt ones; joiner/joint in pool throws the transducer discriminator error; `modelType "nemo_ctc"` → `OfflineNemoEncDecCtcModelConfig` populated; `"zipformer_ctc"` → ZipformerCtc config; unknown modelType → `IllegalArgumentException` naming valid values; `metadataFileRole()` = encoder; `metadataKeys()` from Task 1.

- [ ] **Step 2: Verify FAIL.**

- [ ] **Step 3: Implement** `CtcSupport`: encoder+tokens roles; token preference is the mirror of the transducer matcher (ctc-hinted first, rnnt-free fallback); encoder matching also prefers ctc-hinted candidates; discriminator shared with WhisperSupport (extract a private helper only if it keeps DRY without over-abstracting); `OfflineModelConfig.modelType` per the mapping validated in Task 1.

- [ ] **Step 4: Verify PASS. Commit** `feat: CtcSupport family (TASK-331)`

### Task 7: SenseVoiceSupport

**Files:** same as Task 5.

- [ ] **Step 1: Failing tests:** roles model+tokens (`model.int8.onnx`-style names match by "model" keyword without matching "encoder"); `metadataFileRole()` = the model role; config carries language/ITN from options (`sensevoice.language`, `sensevoice.itn` with ITN parsed as boolean-like string); `OfflineModelConfig.modelType = "sense_voice"`.

- [ ] **Step 2: Verify FAIL. Step 3: Implement. Step 4: Verify PASS. Commit** `feat: SenseVoiceSupport family (TASK-331)`

---

## Chunk 3: Importer integration

### Task 8: family parameter on import entries + metadata dispatch

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/data/ExternalModelImporter.kt`
- Test: `app/src/test/java/com/antivocale/app/data/ExternalModelImporterTest.kt`

- [ ] **Step 1: Failing tests:** `importFromDirectory(src, family = WHISPER)` on a whisper file set produces a record with `family == WHISPER`, `modelType == ""`, files pinned under canonical names; importing the SAME directory as TRANSDUCER fails with the role-set error naming transducer expectations; `importFromEntryJson` uses the entry's family (default TRANSDUCER); SenseVoice import validates metadata on model.onnx (assert via the missing-file/missing-metadata error path).

- [ ] **Step 2: Verify FAIL.**

- [ ] **Step 3: Implement:** add `family: ModelFamily = ModelFamily.TRANSDUCER`, `options: Map<String, String> = emptyMap()`, and `languages: List<String> = emptyList()` to `importFromTreeUri`/`importFromDirectory`/`importFromHuggingFaceRepo`/`importFromUrl` (the languages parameter feeds the record's `languages` field, today hardcoded `emptyList()` at importCore for local imports); the entry-JSON variant reads family/options/languages from the parsed entry (no parameters there; the entry drives). Thread all three through `importCore`/`downloadCore` into `registerImported` (this is the whole UI → VM → importer → record plumbing for options; Task 10's dedupe refresh binds to the same parameters). `importCore`/`downloadCore` call `ModelFamilySupport.forFamily(family)` for planning, error messages ("missing required files for WHISPER (encoder/decoder/tokens); found: …"), and `registerImported`; `registerImported` validates on `support.metadataFileRole()` with `support.metadataKeys(modelType)` AND calls `support.validateImportedModel(File(targetDir, support.metadataFileRole()))` (the value-aware whisper check: without this call the generic-name transducer-as-whisper hole silently reopens with green tests); the existing `modelType: String = "nemo_transducer"` parameters keep their defaults (callers in `ModelViewModel` pass through unchanged for now).

- [ ] **Step 4: Run the FULL data test package** `--tests "com.antivocale.app.data.*"`, verify PASS.

- [ ] **Step 5: Commit** `feat: family-aware import pipeline (TASK-331)`

### Task 9: ONNX split-file sidecars

**Files:** same as Task 8.

- [ ] **Step 1: Failing tests:** a whisper set with `encoder.int8.onnx` + `encoder.int8.onnx.data` plans the `.data` as an extra entry landing under its SOURCE base name in the target dir (not renamed); the disk pre-flight total includes the sidecar size; URL import produces a `DownloadTriple` for the sidecar with the LFS sha256 when present.

- [ ] **Step 2: Verify FAIL.**

- [ ] **Step 3: Implement:** after role planning, for each planned source ending in `.onnx`, find sibling files matching `<source>.data` / `<source>.weights` in the pool; add `sidecarBaseName -> sourceName` entries to the plan map (copied and pinned like roles, but not roles: `requiredRoles()` unchanged). In `downloadCore` the same extension applies when building triples. Pins include sidecars (dedupe stays same-hash-map equality).

- [ ] **Step 4: Verify PASS. Commit** `feat: ONNX split-file sidecar handling on import (TASK-331)`

### Task 10: dedupe refresh of new fields

**Files:** same as Task 8.

- [ ] **Step 1: Failing test:** import record X (whisper, options A) → re-import same files with options B → returned record has options B (and family/languages refreshed), dir unchanged, no duplicate record in store.

- [ ] **Step 2: Verify FAIL.**

- [ ] **Step 3: Implement:** extend the `existing.copy(...)` in the dedupe path with `family = family, options = options, languages = languages` (all three are the parameters threaded in Task 8).

- [ ] **Step 4: Verify PASS. Commit** `fix: dedupe re-import refreshes family/options/languages (TASK-331)`

---

## Chunk 4: Engine dispatch

### Task 11: ExternalSherpaBackend dispatch through the support table

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/transcription/ExternalSherpaBackend.kt`
- Test: `app/src/test/java/com/antivocale/app/transcription/ExternalSherpaBackendTest.kt` (create if absent)

- [ ] **Step 1: Failing tests:** `initialize` with a record whose dir is missing files fails with `ModelLoadError` naming the missing role per family (drive `requiredRoles()` for at least TRANSDUCER and SENSE_VOICE); the pre-flight metadata check reads `metadataFileRole()` (SenseVoice record with a missing model-role file fails before any config construction). Where a real `OfflineRecognizer` cannot be constructed in JVM tests, assert the failure-mode ordering instead: do not mock the native layer.

- [ ] **Step 2: Verify FAIL.**

- [ ] **Step 3: Implement:** replace the hardcoded transducer block in `initialize` with `ModelFamilySupport.forFamily(record.family).buildModelConfig(record, externalConfig.numThreads, externalConfig.provider)`; replace `SherpaOnnxBackend.REQUIRED_MODEL_FILES` with `support.requiredRoles()`; replace the metadata validation target/keys with `support.metadataFileRole()`/`support.metadataKeys(record.modelType)`. Nothing else in the class changes (spec: transcribe/unload/identity contract untouched).

- [ ] **Step 4: Run** `--tests "com.antivocale.app.transcription.*"`, verify PASS (the transducer path must stay green: regression guard).

- [ ] **Step 5: Commit** `feat: engine dispatches model config per family (TASK-331)`

---

## Chunk 5: UI, catalog, docs

### Task 12: family dropdown + options panel in ModelTab

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/ui/tabs/ModelTab.kt` (lines ~122-134: `selectedExternalModelType`; ~1079: modelType label map; ~1231: URL import)
- Modify: `app/src/main/java/com/antivocale/app/ui/viewmodel/ModelViewModel.kt:2180-2185` (import wrappers gain family + options params)
- Modify: `app/src/main/res/values/strings.xml` + `values-it/strings.xml`
- Test: create `app/src/test/java/com/antivocale/app/ui/viewmodel/ModelViewModelExternalImportTest.kt` (new file; there is NO existing external-import VM test suite or fake importer seam. The only VM test constructing ModelViewModel, `ModelViewModelActiveModelTest.kt`, injects a real `ExternalModelImporter` over a temp filesRoot; follow that pattern)

- [ ] **Step 1: Failing tests:** `importExternalFromFolder(context, uri, family = WHISPER, options, languages)` forwards family, options, and languages to the importer. Two viable seams, pick one: (a) drive the real importer with on-disk whisper fixture files in a temp dir and assert the resulting record's fields (follows the `ModelViewModelActiveModelTest` precedent, no mocking), or (b) relax `ExternalModelImporter` to an injectable interface the VM takes (only if (a) proves unwieldy; keep the change minimal and document it in the commit).

- [ ] **Step 2: Verify FAIL.**

- [ ] **Step 3: Implement:** replace `selectedExternalModelType: String` with `selectedFamily: ModelFamily` + a CTC-only `ctcModelType: String` field (default `nemo_ctc`); the existing modelType label row becomes a family dropdown (4 entries: Transducer (NeMo/Zipformer), Whisper, CTC, SenseVoice) with a help line listing expected files (`R.string.external_family_*_help`); below it, a conditional options panel: WHISPER gets an optional language field (task fixed to transcribe), SENSE_VOICE an optional language + ITN switch, CTC a subtype selector (nemo/zipformer). Additionally the import section gains an optional languages text field (comma/space separated codes) that flows into the importer's new `languages` parameter for ALL families (spec: SAF-folder import gains a languages field stored on the record; it is also the Whisper default-language source). VM wrappers pass family+options+languages through to the importer. All strings en+it (DoD #8).

- [ ] **Step 4: Run full unit suite** `./gradlew :app:testPlayStoreDebugUnitTest`, verify PASS.

- [ ] **Step 5: UI conformance pass (spec-binding, not optional):** read the closest existing sibling section of ModelTab (the current modelType selector row and the URL import section) and diff the new composables against them: same container nesting (no extra Box/Column layers), same spacing/padding values (reuse the file's existing `dp` literals, introduce none), same alignment, icons only where sibling rows use them. Fix any divergence before committing.

- [ ] **Step 6: Commit** `feat: family dropdown and options panel in import UI (TASK-331)`

### Task 13: catalog search by language + arabic.json + docs

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/ui/tabs/ModelTab.kt` (URL import dialog)
- Create: `app/src/main/assets/external-catalog/arabic.json` plus a small index `app/src/main/assets/external-catalog/index.json` (NOTE: no catalog consumer exists in app code today; `ExternalModelSource.CATALOG` is declared but unused, and the JSONs under `docs/test-catalog/` are test fixtures with no runtime consumer. This task creates the minimal runtime surface rather than pointing at the docs fixtures.)
- Test: unit tests for the pure matcher + index parsing under `app/src/test/java/com/antivocale/app/data/`

- [ ] **Step 1:** catalog surface + search: bundle a tiny catalog index (name, languages, entry-JSON URL per model; initially just the Arabic entry) as assets; the URL import dialog offers autocomplete suggestions filtered by a free-text query over `displayName` + `languages` ("ar" and "arabic" both surface the Arabic entry); tapping a suggestion fills the URL field with the entry URL and the family from the entry. Keep the matcher pure and testable, e.g. `fun matchesQuery(name: String, languages: List<String>, query: String): Boolean`, with unit tests; index parsing gets its own test. Scope note: this is an autocomplete assist on the existing URL dialog, NOT a full catalog browser screen (YAGNI; a real catalog listing can grow later).
- [ ] **Step 2:** `arabic.json`: `family: "WHISPER"`, `languages: ["ar"]`, `modelType: ""`, files = encoder int8 + decoder int8 + their `.onnx.data` sidecars, each with sha256 + size (source: OpenVoiceOS/whisper-large-v3-turbo-arabic-dialectal-v2-onnx; fetch hashes with `curl -s https://huggingface.co/api/models/<repo>/tree/main` and verify the oids are the LFS sha256, not the git blob ids). Ship it both in assets and as a shareable URL the docs point at: the URL is the raw.githubusercontent.com location of the committed assets file (verify it resolves with curl AFTER the commit is pushed; the index entry must point at that reachable URL before the Task 15 device run).
- [ ] **Step 3:** `docs/external-models.md`: add the family table (family → expected files → record modelType → options) and the split-file note; update the entry-JSON schema section with `family`/`options`/`languages`.
- [ ] **Step 4: Run full unit suite, verify PASS. Commit** `feat: language search, arabic catalog entry, family docs (TASK-331)`

---

## Chunk 6: Device verification and closure

### Task 14: device test (GigaAM CTC)

- [ ] Build+install via `./scripts/install.sh`; import the GigaAM v3 CTC files (side-load the istupakov `v3_ctc` set into a folder, SAF-import as CTC family); transcribe a Russian voice note; verify the transcript is sane and `adb logcat` shows `External backend initialized: external:... (family=CTC)`. Screenshot the import UI and the model row for the UI conformance record.
- [ ] Commit any fix that surfaces; nothing to commit if green.

### Task 15: device test (Arabic Whisper, ~1.3GB)

- [ ] Same flow via the `arabic.json` catalog entry (URL import exercises Task 9 sidecars + Task 13 search); happy path plus one cancelled-midway import that must leave no partial dir; transcribe an Arabic voice note.
- [ ] Screenshot family dropdown + options panel; compare with the existing import section screenshots (certify with pixels per the spec's UI-conformance item 3).

### Task 16: MMS evaluation (documented, not forced)

- [ ] Try importing OpenVoiceOS/mms-1b-all-onnx as CTC (desktop first via the probe script, then device if desktop loads). Record the outcome (loadable, or which adapter error) in `docs/research/multifamily_desktop_validation.md` and as a comment on TASK-331. No special adapter handling if it fails (out of scope).

### Task 17: closure

- [ ] `/code-review` (high) on the full diff; the review checklist MUST include the UI-conformance item (nested containers, spacing, alignment, icons) with the reviewer citing the sibling screen each element was compared against.
- [ ] `/simplify` pass on the changed files.
- [ ] Full suite green: `./gradlew :app:testPlayStoreDebugUnitTest`.
- [ ] R8 audit per CLAUDE.md release checklist (`ModelFamilySupport` is pure Kotlin under the already-kept `transcription.**` rule; confirm no new keep rules needed).
- [ ] Update TASK-331: check ACs and DoD (including UI-conformance #10) with evidence links, mark Done via Backlog MCP.
