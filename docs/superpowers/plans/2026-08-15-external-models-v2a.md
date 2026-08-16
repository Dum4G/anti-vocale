# External Models Platform v2a Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship v2a of the external-models platform: unlimited imported sherpa-onnx transducer models usable as first-class backends, imported from a local folder or a URL, absorbing the `custom-transducer` backend via one-shot migration.

**Architecture:** A serialized `ExternalModelStore` (records in one DataStore key) feeds dynamic `BackendDescriptor`s composed into `BackendRegistry`; one configurable `ExternalSherpaBackend` engine is routed by a `external:`-prefix arm in `TranscriptionBackendManager`; a single import pipeline serves folder and URL entries; one static manifest alias `ShareExternal` opens a chooser of imported models. Spec: `docs/superpowers/specs/2026-08-15-external-models-platform-design.md` (approved).

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, DataStore Preferences, kotlinx.serialization (already a dependency? verify in Task 1; if absent, hand-rolled JSON via `org.json` which Android provides), sherpa-onnx AAR 1.13.4, JUnit4 + MockK + Robolectric (existing suite).

---

## Prerequisites (blocking)

- [ ] **P0: TASK-313 rebased and landed on main.** Branch `feature/custom-transducer-sideload` (commit `100eef3`, base `17ffdef`) must first be rebased onto the registry-era main exactly the way `gigaam-v3` was: resolve the 5 dispatch-site conflicts by deleting the manual `when` arms, add the `custom-transducer` `BackendDescriptor`, update `FakePreferencesManager` and `BackendRegistryTest`, `./gradlew testFdroidDebugUnitTest` green. Acceptance criterion #11 of TASK-313. Verify before starting:
  ```bash
  git grep -l "CustomTransducerBackend" origin/main -- app/src | head -3   # must list files
  ```
  This plan builds on the landed `buildCopyPlan`, role-based import UI, and validation code rather than duplicating it.
- [ ] **P1: branch point.** Create `feature/external-models-v2a` from updated `origin/main`.

## Conventions for every task

- Build/test commands: `./gradlew testFdroidDebugUnitTest` (CI flavor; the suite is shared with playStore). Never `./gradlew assembleDebug` (flavor ambiguity).
- Commit after every green test run, message prefix `feat(external):` / `test(external):` / `refactor(external):`.
- After each task: run the full unit suite, not just the new test.
- The em-dash character must not appear in any authored prose (project rule).

---

## Chunk 1: Core platform (types, store, registry, engine, manager)

### Task 1: External model types and store

**Files:**
- Create: `app/src/main/java/com/antivocale/app/data/ExternalModels.kt` (record, pin, family, source types)
- Create: `app/src/main/java/com/antivocale/app/data/ExternalModelStore.kt`
- Modify: `app/src/main/java/com/antivocale/app/data/PreferencesManager.kt` (raw JSON flow + save; no clear accessor: deletion persists the shorter list)
- Modify: `app/src/main/java/com/antivocale/app/data/PreferencesManagerImpl.kt` (key + accessors, top-level `preferencesDataStore` delegate already at line ~19)
- Test: `app/src/test/java/com/antivocale/app/data/ExternalModelStoreTest.kt`
- Test: `app/src/test/java/com/antivocale/app/data/FakePreferencesManager.kt` (add the raw flow)

- [ ] **Step 1.1: Write the failing test**

```kotlin
package com.antivocale.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExternalModelStoreTest {

    private lateinit var fake: FakePreferencesManager
    private lateinit var store: ExternalModelStore

    @Before
    fun setUp() {
        fake = FakePreferencesManager()
        store = ExternalModelStore(fake)
    }

    private fun record(id: String = "a1b2c3d4e5f6", name: String = "GigaAM v3") = ExternalModelRecord(
        id = id,
        displayName = name,
        dir = "/data/user/0/com.antivocale.app/files/models/external/gigaam-v3-a1b2c3",
        family = ModelFamily.TRANSDUCER,
        modelType = "nemo_transducer",
        languages = listOf("ru"),
        source = ExternalModelSource.LOCAL,
        sourceUrl = null,
        files = mapOf(
            "encoder.int8.onnx" to FilePin("2cac62d0c270bd128f898f2be1a2d34780d524a6e9483888ebac7b00f97410f1", verified = true),
            "tokens.txt" to FilePin("7ddf22514c42c531358182c81446a8159771e9921019f09ae743ea622d40221d", verified = false),
        ),
        sizeBytes = 326_322_304L,
        importedAt = 1_755_000_000_000L,
    )

    @Test
    fun `empty store lists nothing and json round-trips through the preference`() = runTest {
        assertEquals(emptyList<ExternalModelRecord>(), store.records())
        val rec = record()
        store.add(rec)
        assertEquals(listOf(rec), store.records())
        // Round-trip proof: a second store instance over the same preference sees the record.
        assertEquals(listOf(rec), ExternalModelStore(fake).records())
    }

    @Test
    fun `update replaces by id and delete removes only the target`() = runTest {
        val a = record(id = "aaaaaaaaaaaa", name = "A")
        val b = record(id = "bbbbbbbbbbbb", name = "B")
        store.add(a); store.add(b)
        store.update(a.copy(displayName = "A2"))
        assertEquals("A2", store.records().first { it.id == "aaaaaaaaaaaa" }.displayName)
        store.delete("aaaaaaaaaaaa")
        assertEquals(listOf("bbbbbbbbbbbb"), store.records().map { it.id })
    }

    @Test
    fun `validity requires the directory to exist`() = runTest {
        val rec = record()
        store.add(rec)
        // Robolectric not needed: validity is injected as a dir-exists predicate in production wiring.
        val validity = ExternalModelStore(fake) { false }
        assertTrue(validity.invalidRecordIds().contains(rec.id))
        assertNull(validity.byId(rec.id))
    }

    @Test
    fun `validRecordsFlow filters records whose directory is missing`() = runTest {
        val rec = record()
        store.add(rec)
        val filtered = ExternalModelStore(fake) { false }
        assertEquals(emptyList(), filtered.validRecordsFlow.first())
        assertEquals(listOf(rec), ExternalModelStore(fake) { true }.validRecordsFlow.first())
    }
}
```

- [ ] **Step 1.2: Run it to verify it fails**

Run: `./gradlew :app:testFdroidDebugUnitTest --tests "com.antivocale.app.data.ExternalModelStoreTest"`
Expected: compilation failure, `ExternalModelRecord` unresolved.

- [ ] **Step 1.3: Implement types and store**

`ExternalModels.kt` (use `org.json` for serialization; do NOT add a serialization library for this):

```kotlin
package com.antivocale.app.data

import org.json.JSONArray
import org.json.JSONObject

enum class ModelFamily { TRANSDUCER }  // CTC, PARAFORMER, SENSE_VOICE, WHISPER arrive in v2b

enum class ExternalModelSource { LOCAL, URL, CATALOG }

data class FilePin(val sha256: String, val verified: Boolean)

data class ExternalModelRecord(
    val id: String,                 // uuid; also the dir-fragment source
    val displayName: String,
    val dir: String,                // models/external/<sanitized-name>-<id-fragment>/
    val family: ModelFamily,
    val modelType: String,          // sherpa modelType: nemo_transducer, "", conformer_transducer
    val languages: List<String>,
    val source: ExternalModelSource,
    val sourceUrl: String?,
    val files: Map<String, FilePin>,
    val sizeBytes: Long,
    val importedAt: Long,
) {
    val backendId: String get() = "external:$id"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("displayName", displayName); put("dir", dir)
        put("family", family.name); put("modelType", modelType)
        put("languages", JSONArray(languages)); put("source", source.name)
        put("sourceUrl", sourceUrl ?: JSONObject.NULL)
        put("files", JSONObject().apply { files.forEach { (n, p) -> put(n, JSONObject().put("sha256", p.sha256).put("verified", p.verified)) } })
        put("sizeBytes", sizeBytes); put("importedAt", importedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): ExternalModelRecord? = try {
            val filesObj = o.getJSONObject("files")
            val files = buildMap {
                for (name in filesObj.keys()) {
                    val p = filesObj.getJSONObject(name)
                    put(name, FilePin(p.getString("sha256"), p.getBoolean("verified")))
                }
            }
            ExternalModelRecord(
                id = o.getString("id"), displayName = o.getString("displayName"), dir = o.getString("dir"),
                family = ModelFamily.valueOf(o.getString("family")), modelType = o.getString("modelType"),
                languages = buildList { val a = o.getJSONArray("languages"); for (i in 0 until a.length()) add(a.getString(i)) },
                source = ExternalModelSource.valueOf(o.getString("source")),
                sourceUrl = if (o.isNull("sourceUrl")) null else o.getString("sourceUrl"),
                files = files, sizeBytes = o.getLong("sizeBytes"), importedAt = o.getLong("importedAt"),
            )
        } catch (e: Exception) { null }
    }
}

object ExternalModelListJson {
    fun encode(records: List<ExternalModelRecord>): String =
        JSONArray(records.map { it.toJson() }).toString()

    fun decode(raw: String?): List<ExternalModelRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            buildList { for (i in 0 until a.length()) add(ExternalModelRecord.fromJson(a.getJSONObject(i)) ?: return emptyList()) }
        }.getOrDefault(emptyList())  // malformed entry: whole list rejected, never a crash
    }
}
```

`ExternalModelStore.kt`:

```kotlin
package com.antivocale.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for imported external models (spec: External models
 * platform v2a). Persists the record list as one JSON preference via
 * [PreferencesManager]; derives nothing else. Directory validity is injected
 * so the class stays JVM-testable.
 */
// No @Inject here: Dagger cannot satisfy the defaulted dirExists lambda (Kotlin defaults
// are invisible to it, MissingBinding on Function1). Constructed via an AppModule
// @Provides @Singleton provider; tests use the defaulted constructor directly.
class ExternalModelStore(
    private val preferencesManager: PreferencesManager,
    private val dirExists: (String) -> Boolean = { java.io.File(it).exists() },
) {
    val recordsFlow: Flow<List<ExternalModelRecord>> =
        preferencesManager.externalModelsJson.map(ExternalModelListJson::decode)

    suspend fun records(): List<ExternalModelRecord> = recordsFlow.first()

    /** Valid records only: a record whose directory vanished derives no descriptor anywhere. */
    suspend fun validRecords(): List<ExternalModelRecord> = records().filter { dirExists(it.dir) }

    suspend fun byId(id: String): ExternalModelRecord? =
        validRecords().firstOrNull { it.id == id }

    suspend fun add(record: ExternalModelRecord) = mutate { it + record }
    suspend fun update(record: ExternalModelRecord) = mutate { list -> list.map { if (it.id == record.id) record else it } }
    suspend fun delete(id: String): ExternalModelRecord? {
        val removed = records().firstOrNull { it.id == id }
        mutate { list -> list.filterNot { it.id == id } }
        return removed
    }

    suspend fun invalidRecordIds(): List<String> = records().filterNot { dirExists(it.dir) }.map { it.id }

    private suspend fun mutate(transform: (List<ExternalModelRecord>) -> List<ExternalModelRecord>) {
        val current = records()
        preferencesManager.saveExternalModelsJson(ExternalModelListJson.encode(transform(current)))
    }
}
```

In `PreferencesManager.kt` add next to the other flows (read-only raw JSON; the store owns semantics):

```kotlin
    val externalModelsJson: Flow<String?>
    suspend fun saveExternalModelsJson(json: String)
```

In `PreferencesManagerImpl.kt`: key `private val EXTERNAL_MODELS_JSON = stringPreferencesKey("external_models_json")`, cache field `val externalModelsJson: String? = null` in the Cache data class, flow mapping + `onStart { emit(cache.get().externalModelsJson) }`, and save with `cache.updateAndGet` following the exact `gigaamModelPath` accessor pattern (the save/clear block sits near line 200 on the gigaam-v3 branch, not in the `toCached()` mapping). Also add to `ExternalModelStore` a validity-filtered flow (the registry consumes only this):

```kotlin
    val validRecordsFlow: Flow<List<ExternalModelRecord>> =
        preferencesManager.externalModelsJson.map { js -> ExternalModelListJson.decode(js).filter { dirExists(it.dir) } }
```

In `FakePreferencesManager.kt`: `val _externalModelsJson = MutableStateFlow<String?>(null)`, `override val externalModelsJson: Flow<String?> get() = _externalModelsJson`, `override suspend fun saveExternalModelsJson(json: String) { _externalModelsJson.value = json }`.

- [ ] **Step 1.4: Run the test to verify it passes**

Run: `./gradlew :app:testFdroidDebugUnitTest --tests "com.antivocale.app.data.ExternalModelStoreTest"`
Expected: 4 tests PASS.

- [ ] **Step 1.5: Full suite + commit**

```bash
./gradlew testFdroidDebugUnitTest
git add app/src/main/java/com/antivocale/app/data/ app/src/test/java/com/antivocale/app/data/
git commit -m "feat(external): external model record types and store"
```

### Task 2: Dynamic descriptors in BackendRegistry

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/transcription/BackendRegistry.kt`
- Create: `app/src/main/java/com/antivocale/app/data/ExternalModelRecordsProvider.kt` (the StateFlow seam between store and registry)
- Modify: `app/src/main/java/com/antivocale/app/di/TranscriptionModule.kt` (provides the records provider)
- Modify the FIVE production construction sites of bare `BackendRegistry()` (default parameter values that relied on statelessness; four become constructor injection, the Activity one uses the EntryPoint path below):
  - `app/src/main/java/com/antivocale/app/transcription/TranscriptionOrchestrator.kt:44`
  - `app/src/main/java/com/antivocale/app/data/ActiveModelRepository.kt:38`
  - `app/src/main/java/com/antivocale/app/receiver/ShareReceiverActivity.kt:76`
  - `app/src/main/java/com/antivocale/app/ui/viewmodel/LogsViewModel.kt:70`
  - `app/src/main/java/com/antivocale/app/ui/viewmodel/ModelViewModel.kt:75`
- Modify: `app/src/test/java/com/antivocale/app/transcription/BackendRegistryTest.kt:53` and `app/src/test/java/com/antivocale/app/transcription/TranscriptionOrchestratorTestBase.kt` (construct with a fake provider)
- Modify: `app/src/test/java/com/antivocale/app/receiver/ShareReceiverActivityAliasTest.kt` (nine static `backendIdForAlias(...)` call sites gain the registry parameter; construct `BackendRegistry(fakeStore, localAdapter())` in the fixture (a local adapter with the same shape as BackendRegistryTest's snippet-local `providerWith`; no shared helper exists) and pass it)
- Modify: `app/src/main/java/com/antivocale/app/service/ExtractionService.kt` (add `EXTERNAL("external")` enum value HERE, not in Task 3; see Step 2.3 for the two mandatory arms)

- [ ] **Step 2.1: Write the failing tests (add to BackendRegistryTest)**

```kotlin
    // ---- dynamic external descriptors (spec v2a) ----

    private fun externalRecord(id: String = "a1b2c3d4e5f6") = com.antivocale.app.data.ExternalModelRecord(
        id = id, displayName = "GigaAM v3", dir = "/x/gigaam-v3-$id",
        family = com.antivocale.app.data.ModelFamily.TRANSDUCER, modelType = "nemo_transducer",
        languages = listOf("ru"), source = com.antivocale.app.data.ExternalModelSource.LOCAL, sourceUrl = null,
        files = mapOf("encoder.int8.onnx" to com.antivocale.app.data.FilePin("00", verified = true)),
        sizeBytes = 1L, importedAt = 0L,
    )

    private fun providerWith(vararg records: com.antivocale.app.data.ExternalModelRecord): com.antivocale.app.data.ExternalModelRecordsProvider =
        object : com.antivocale.app.data.ExternalModelRecordsProvider {
            override val records = kotlinx.coroutines.flow.MutableStateFlow(records.toList())
        }

    @Test
    fun `external records derive descriptors with no share alias`() = runTest {
        val fake = FakePreferencesManager()
        val store = com.antivocale.app.data.ExternalModelStore(fake, dirExists = { true })
        store.add(externalRecord())
        val registry = BackendRegistry(store, providerWith(externalRecord()))

        val descriptor = registry.byBackendId("external:a1b2c3d4e5f6")
        assertNotNull(descriptor)
        assertEquals(ExtractionService.ModelType.EXTERNAL, descriptor!!.modelType)
        assertEquals("", descriptor.shareAlias)
        assertEquals("GigaAM v3", descriptor.deriveDisplayName(mockk(), "/anywhere"))
    }

    @Test
    fun `provider with no records derives nothing`() = runTest {
        val fake = FakePreferencesManager()
        val store = com.antivocale.app.data.ExternalModelStore(fake, dirExists = { false })
        store.add(externalRecord())
        val registry = BackendRegistry(store, providerWith())   // empty: the real provider filters invalid records out
        assertNull(registry.byBackendId("external:a1b2c3d4e5f6"))
    }

    @Test
    fun `static six plus N external backends coexist and stay unique`() = runTest {
        val fake = FakePreferencesManager()
        val store = com.antivocale.app.data.ExternalModelStore(fake, dirExists = { true })
        store.add(externalRecord("111111111111")); store.add(externalRecord("222222222222"))
        val registry = BackendRegistry(store, providerWith(externalRecord("111111111111"), externalRecord("222222222222")))
        val ids = registry.backends.map { it.backendId }
        assertEquals(expectedIds.size + 2, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(expectedIds, ids.take(expectedIds.size))  // static first, canonical order preserved
    }

    @Test
    fun `model-path accessors delegate to the store record`() = runTest {
        val fake = FakePreferencesManager()
        val store = com.antivocale.app.data.ExternalModelStore(fake, dirExists = { true })
        store.add(externalRecord())
        val registry = BackendRegistry(store, providerWith(externalRecord()))
        val descriptor = registry.byBackendId("external:a1b2c3d4e5f6")!!
        descriptor.saveModelPath(fake, "/new/dir")
        // Store records are keyed by identity, not a path preference: saving redirects the record's dir.
        assertEquals("/new/dir", store.records().first().dir)
    }
```

Also update the existing six-backend tests to construct `BackendRegistry(store, providerWith())` (empty adapter), and rename the count test's wording from "six" to "static six (dynamic externals counted separately)".

- [ ] **Step 2.2: Run to verify they fail**

Run: `./gradlew :app:testFdroidDebugUnitTest --tests "com.antivocale.app.transcription.BackendRegistryTest"`
Expected: compile error, `BackendRegistry` has no store parameter.

- [ ] **Step 2.3: Implement**

Determinism seam first, `ExternalModelRecordsProvider.kt` (the registry must NOT collect a Flow on a hidden scope: tests read `backends` immediately after a store mutation and would race the collector):

```kotlin
package com.antivocale.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Valid external-model records as a StateFlow, for BackendRegistry's dynamic descriptors. */
interface ExternalModelRecordsProvider {
    val records: StateFlow<List<ExternalModelRecord>>
}

@Singleton
class DefaultExternalModelRecordsProvider @Inject constructor(
    store: ExternalModelStore,
) : ExternalModelRecordsProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _records = MutableStateFlow<List<ExternalModelRecord>>(emptyList())
    override val records: StateFlow<List<ExternalModelRecord>> = _records
    init { scope.launch { store.validRecordsFlow.collect { _records.value = it } } }
}
```

`TranscriptionModule` gains `@Provides @Singleton fun provideExternalModelRecordsProvider(impl: DefaultExternalModelRecordsProvider): ExternalModelRecordsProvider = impl` (or `@Binds` if the module has an abstract section; follow the module's existing style).

In `BackendRegistry.kt` (both dependencies injected: the provider for reads, the store for the save/clear mutations):

```kotlin
@Singleton
class BackendRegistry @Inject constructor(
    private val externalModelStore: ExternalModelStore,
    private val recordsProvider: ExternalModelRecordsProvider,   // val: referenced in the backends getter
) {
    private val staticBackends: List<BackendDescriptor> = listOf( /* the existing six, unchanged */ )

    val backends: List<BackendDescriptor>
        get() = staticBackends + recordsProvider.records.value.map(::descriptorFor)

    private fun descriptorFor(record: ExternalModelRecord): BackendDescriptor = BackendDescriptor(
        backendId = record.backendId,
        modelType = ExtractionService.ModelType.EXTERNAL,
        shareAlias = "",  // spec: the ShareExternal family alias is synced separately
        deriveDisplayName = { _, _ -> record.displayName },
        modelPathFlow = { prefs -> prefs.externalModelsJson.map { js ->
            ExternalModelListJson.decode(js).firstOrNull { r -> r.id == record.id }?.dir } },
        saveModelPath = { _, path ->
            // Identity is the uuid, not a path preference: a save redirects the record's dir.
            externalModelStore.update(record.copy(dir = path)) },
        clearModelPath = { externalModelStore.delete(record.id) },
    )
    // byId/byType/byAlias become functions over `backends` (recomputed per call; the lists
    // are tiny). Keep the existing public signatures. Update the class KDoc: "the static six
    // plus dynamic descriptors derived from the external model store; no longer stateless".
}
```

Tests construct `BackendRegistry(store, providerWith(...))` via the snippet-local `providerWith` helper (a synchronous `MutableStateFlow` adapter, set before each assertion, no collector race). The `provider with no records derives nothing` test seeds an empty adapter: validity filtering is the provider's contract and is pinned where it lives (the `validRecordsFlow` test lives in Task 1's Step 1.1). The store instance passed in tests is the same fake-backed store, so `saveModelPath` mutations are observable.

`ModelType.EXTERNAL` is added in THIS task (the test references it), and it is not a one-line change: `ExtractionService.resolveDisplayName` (~line 117) is an exhaustive `when` with no `else`; add `ModelType.EXTERNAL -> "External model"` (the per-model name comes from the registry descriptor, this is the fallback for bookkeeping contexts). `executeDownload` (~line 216) enumerates every value; add `ModelType.EXTERNAL -> { /* no service-driven download: imports run through the importer in the ViewModel */ }`. Also fix the existing pin test `every active ModelType except GEMMA4_GGUF maps to a descriptor`: change its set to `entries - GEMMA4_GGUF - EXTERNAL` for the `assertNotNull` loop (with an empty store there is no EXTERNAL descriptor by design) and add one assertion that EXTERNAL maps once the fake provider holds a record.

Construction-site retrofit: remove the `= BackendRegistry()` default values and inject `backendRegistry: BackendRegistry` via constructor (Hilt resolves it) in the four Hilt-injected classes (`TranscriptionOrchestrator:44`, `ActiveModelRepository:38`, `LogsViewModel:70`, `ModelViewModel:75`). The fifth site is structurally different: `ShareReceiverActivity:76` holds the registry in a companion object consumed by the static `backendIdForAlias` (that class is deliberately NOT `@AndroidEntryPoint`, its own comment at lines 73-75 explains Hilt needs ComponentActivity), so constructor injection is impossible. Retrofit that one with an entry-point lookup on the instance path: define `@EntryPoint @InstallIn(SingletonComponent::class) interface BackendRegistryEntryPoint { fun backendRegistry(): BackendRegistry }` in the file (or the di package), and in the instance method that calls `backendIdForAlias` (around line 246) obtain the registry via `EntryPointAccessors.fromApplication(applicationContext, BackendRegistryEntryPoint::class.java).backendRegistry()` and pass it as a parameter to `backendIdForAlias(alias, registry)`; the companion function becomes stateless-with-a-parameter; also rewrite the stale companion comment (lines 73-75) that documents the now-dead "stateless so companion-held is equivalent" rationale. In `TranscriptionOrchestratorTestBase`, construct `BackendRegistry(fakeStore, fakeProvider)` explicitly. Run `git grep -n "BackendRegistry()" app/src` after the change: it must return zero hits.

- [ ] **Step 2.4: Run the tests**

Run: `./gradlew :app:testFdroidDebugUnitTest --tests "com.antivocale.app.transcription.BackendRegistryTest"`
Expected: all PASS including the four new ones.

- [ ] **Step 2.5: Full suite + commit**

```bash
./gradlew testFdroidDebugUnitTest
git add -A app/src
git commit -m "feat(external): dynamic BackendRegistry descriptors from the external model store"
```

### Task 3: Orchestrator external arm and ExternalConfig

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/transcription/TranscriptionBackend.kt` (add `ExternalConfig` to the sealed `BackendConfig`)
- Modify: `app/src/main/java/com/antivocale/app/transcription/TranscriptionOrchestrator.kt` (dispatch arm + loader)
- Modify: `app/src/test/java/com/antivocale/app/transcription/TranscriptionOrchestratorTestBase.kt` (SECOND arity retrofit: the orchestrator gains `externalModelStore`; the base also gains a `fakeStore` field (FakePreferencesManager-backed `ExternalModelStore`, `dirExists = { true }`) and an `externalRecord(id, dir)` helper used by Step 3.1)
- Test: extend `app/src/test/java/com/antivocale/app/transcription/TranscriptionOrchestratorBackendOverrideTest.kt`

(The enum value and its two mandatory arms land in Task 2; `TranscriptionModule` needs NO change: the engine is constructed by the manager, never Hilt-injected into the backend set, per the spec's no-half-configured-engine rule.)

- [ ] **Step 3.1: Failing test.** Extend `TranscriptionOrchestratorBackendOverrideTest` with the existing fixture style (its `createTempModelDir` helper repopulated with the four canonical transducer file names):

```kotlin
    @Test
    fun `backend override routes external id to the engine with the record config`() = runTest {
        val dir = createTempModelDir("external")  // the helper already writes ParakeetModelManager.REQUIRED_FILES (encoder/decoder/joiner/tokens)
        val record = externalRecord(id = "abc123def456", dir = dir.absolutePath)
        fakeStore.add(record)                       // FakePreferencesManager-backed store, dirExists = { true }
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.inferenceProvider } returns flowOf("cpu")

        // Invoke processRequest with the SAME full parameter list the existing override
        // test in this file uses (copy its call verbatim, changing only backendOverride).
        orchestrator.processRequest(/* existing-test params */, backendOverride = record.backendId)

        coVerify {
            backendManager.setActiveBackend(
                backendId = record.backendId,
                config = match { c ->
                    (c as BackendConfig.ExternalConfig).record == record && c.numThreads == 4
                },
            )
        }
    }
```

Also a negative test: `backendOverride = "external:unknown"` fails the request with a `NotInitialized` error and never calls `setActiveBackend`, EVEN when the persisted `transcriptionBackend` preference points at a valid external record (this pins the override-over-preference resolution the spec requires). Invocation shape: copy the existing override test's `processRequest` call verbatim, changing only the `backendOverride` argument (the real signature: taskId, requestType, prompt, filePath, source, sourcePackage, backendOverride, trackIndex, queuePosition, queueTotal, context, cacheDir, listener, coroutineScope). The existing `createTempModelDir(prefix)` helper already writes `ParakeetModelManager.REQUIRED_FILES`, exactly the four files the engine needs.

- [ ] **Step 3.2: Run to see it fail** (`ExternalConfig` unresolved).

- [ ] **Step 3.3: Implement.** In `TranscriptionBackend.kt`:

```kotlin
    data class ExternalConfig(
        val record: ExternalModelRecord,
        val numThreads: Int,
        val provider: String,
    )
```

Orchestrator: in `ensureBackendLoaded`, special-case the `external:` prefix BEFORE the registry lookup (`if (preferredBackendId.startsWith("external:")) -> loadExternalBackend(context, preferredBackendId)`, the same early-special-case `GGUF_BACKEND_ID` gets today): this removes the cold-start race where the provider's background collection has not delivered the record yet and the registry-keyed `when` would momentarily fall through to `loadLlmBackend`. The loader receives the EFFECTIVE id (override or preference; `ensureBackendLoaded` already computed it, pass it down instead of re-reading the preference, otherwise the share-chooser flow loads the wrong record) and resolves thread/provider exactly the way `loadSherpaOnnxModel` does:

```kotlin
    private suspend fun loadExternalBackend(context: Context, backendId: String): Result<Unit> {
        // TranscriptionException.NotInitialized takes no arguments (fixed message); log the id before failing.
        val record = externalModelStore.byId(backendId.removePrefix("external:"))
            ?: run {
                Log.w(TAG, "no external model record for $backendId")
                return Result.failure(TranscriptionException.NotInitialized())
            }
        return configureBackend(
            backendId = record.backendId,
            label = record.displayName,
            context = context,
        ) { threadCount, provider ->
            BackendConfig.ExternalConfig(record = record, numThreads = threadCount, provider = provider)
        }
    }
```

where `configureBackend` is the shared resolve-thread/provider-then-setActiveBackend body: extract it from `configureSherpaBackend` (TASK-313-era, has `modelType` param) so both loaders share one preference-resolution path. The extraction KEEPS the `forceModelLoad`-gated OOM memory pre-flight that `configureSherpaBackend` performs today (`TranscriptionOrchestrator.kt` lines ~404-425): external models are the riskiest loads (unknown sizes, the flagship import is 326MB) and must get the same guard via the shared body, not a weaker path. `configureSherpaBackend` keeps its signature and delegates. Inject `externalModelStore: ExternalModelStore` into the orchestrator constructor next to `backendRegistry`.

- [ ] **Step 3.4: Green run, full suite, commit** `feat(external): orchestrator external load arm with ExternalConfig`.

### Task 4: ExternalSherpaBackend engine

**Files:**
- Create: `app/src/main/java/com/antivocale/app/transcription/ExternalSherpaBackend.kt`
- Test: `app/src/test/java/com/antivocale/app/transcription/ExternalSherpaBackendContractTest.kt`

- [ ] **Step 4.1: Failing contract test**

```kotlin
class ExternalSherpaBackendContractTest {
    private val backend = ExternalSherpaBackend()

    @Test fun `placeholder id before init and after unload`() {
        assertEquals("external", backend.id)
        backend.unload()
        assertEquals("external", backend.id)
    }

    @Test fun `not ready before initialize`() {
        assertFalse(backend.isReady())
    }

    @Test fun `wrong config type fails cleanly`() = runTest {
        val result = backend.initialize(mockk(), BackendConfig.LiteRTConfig(modelPath = "/x"))
        assertTrue(result.isFailure)
    }

    @Test fun `blank transcription before init fails with NotInitialized`() = runTest {
        val result = backend.transcribeAudio(FloatArray(1600), 16000, "")
        assertTrue(result.exceptionOrNull() is TranscriptionException.NotInitialized)
    }
}
```

- [ ] **Step 4.2: Run to fail.**

- [ ] **Step 4.3: Implement.** Model the file on `GigaAmBackend.kt` (on the landed main after the gigaam-v3 merge) with these differences, all spec bindings:

```kotlin
@Singleton
class ExternalSherpaBackend @Inject constructor() : TranscriptionBackend {

    // Placeholder id: the engine is never registered under this value (the manager routes
    // the external: prefix to this singleton and re-points id at initialize).
    @Volatile private var configuredId: String = "external"
    override val id: String get() = configuredId

    override val displayName: String get() = "External model"
    override val supportsAudio: Boolean = true
    override val supportsText: Boolean = false
    override val maxChunkDurationSeconds: Int? = null   // spec: single-pass, notices in UI

    // initialize(context, BackendConfig.ExternalConfig(record, numThreads, provider)):
    //   - resolves the four canonical transducer file names from
    //     SherpaOnnxBackend.REQUIRED_MODEL_FILES (public; the same symbol
    //     CustomTransducerBackend uses on the landed TASK-313 branch; do NOT reach for
    //     ModelViewModel.buildCopyPlan, which maps SAF DocumentFiles and is unreachable here)
    //   - runs the pre-native metadata scan on the encoder with a family rule that
    //     deliberately differs from both templates; keep this comment in the code so a
    //     later reviewer does not "simplify" it back:
    //       vocab_size ALWAYS; subsampling_factor + model_type ONLY when
    //       record.modelType == "nemo_transducer" (those keys are what the nemo loader
    //       reads; a zipformer import with modelType "" does not carry them and must not
    //       be rejected for their absence)
    //   - builds OfflineRecognizer exactly like GigaAmBackend (OfflineTransducerModelConfig
    //     over the canonical names in record.dir, FeatureConfig(16000, 80), greedy_search,
    //     modelType = record.modelType) inside withContext(Dispatchers.IO)
    //   - transcribeAudio: var stream + finally release, 1s silence pad,
    //     computeConfidence on samples.size (all three are the GigaAmBackend patterns)
    // On success: configuredId = record.backendId; recognizer/modelDir state as GigaAmBackend.
    // unload(): release recognizer AND reset configuredId to "external".
}
```

The full method bodies are mechanical ports of `GigaAmBackend.kt` with the file paths coming from the record's directory plus the canonical role names; write them out in full in the implementation (do not leave TODOs).

- [ ] **Step 4.4: Green, full suite, commit** `feat(external): configurable external sherpa engine`.

### Task 5: TranscriptionBackendManager external routing

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/transcription/TranscriptionBackendManager.kt`
- Modify: `app/src/test/java/com/antivocale/app/transcription/TranscriptionBackendManagerTest.kt` (its `createManager(llmManager, backends.toSet())` helper breaks at the new constructor arity: extend it with a records-provider adapter and a real `ExternalSherpaBackend()`; NO store, the manager does not take one)
- Test: `app/src/test/java/com/antivocale/app/transcription/TranscriptionBackendManagerExternalTest.kt`

- [ ] **Step 5.1: Failing tests**

```kotlin
class TranscriptionBackendManagerExternalTest {
    // Fixture: one ExternalModelRecord; an ExternalModelRecordsProvider adapter seeded
    // with that record (the manager reads the SNAPSHOT, see 5.3) with a seedProvider(...)
    // helper to re-seed; sherpaConfig() builds a BackendConfig.SherpaOnnxConfig(modelDir =
    // "/x", numThreads = 4, provider = "cpu") for the failure-path tests; the manager's
    // engine is a
    // mockk<ExternalSherpaBackend>() with an explicit
    //   coEvery { engine.initialize(any(), any()) } returns Result.success(Unit)
    // (do not rely on the relaxed strategy for a Result return); llmManager mockk relaxed.

    @Test fun `setActiveBackend routes external ids to the engine with the exact config`() = runTest {
        val config = BackendConfig.ExternalConfig(record, numThreads = 4, provider = "cpu")
        val result = manager.setActiveBackend(record.backendId, context, config)
        assertTrue(result.isSuccess)
        coVerify { engine.initialize(context, config) }          // the config passes through UNCHANGED
        assertEquals(record.backendId, manager.activeBackendId.first())
    }

    @Test fun `external id with a non-external config fails, no silent defaults`() = runTest {
        val result = manager.setActiveBackend(record.backendId, context, BackendConfig.SherpaOnnxConfig(modelDir = "/x", numThreads = 4, provider = "cpu"))
        assertTrue(result.isFailure)                              // user thread/provider prefs must never be invented here
        coVerify(exactly = 0) { engine.initialize(any(), any()) }
    }

    @Test fun `unknown external id fails with Unknown backend`() = runTest {
        assertTrue(manager.setActiveBackend("external:nosuch", context, sherpaConfig()).isFailure)
    }

    @Test fun `invalid external id (dir gone) fails the same way`() = runTest {
        // Provider seeded empty: that is what the real provider emits once validity filters the record out.
        val coldManager = createManager(providerSeeded = emptyList())
        assertTrue(coldManager.setActiveBackend(record.backendId, context, sherpaConfig()).isFailure)
    }

    @Test fun `getAvailableBackends appends one handle per valid record`() {
        // isReady() is a real File(dir).exists() check: the record's dir must be an actual temp dir.
        val dir = createTempModelDir("external-handle")  // same helper the suite already uses; kotlin.io.createTempDir is deprecated
        val present = record.copy(dir = dir.absolutePath)
        seedProvider(present)
        val handles = manager.getAvailableBackends().filter { it.id == present.backendId }
        assertEquals(1, handles.size)
        assertEquals(present.displayName, handles[0].displayName)
        assertTrue(handles[0].isReady())
    }

    @Test fun `getBackend resolves provider-known external ids to the engine`() = runTest {
        assertSame(engine, manager.getBackend(record.backendId))   // "known" = the provider snapshot holds the record, not engine state
        assertNull(manager.getBackend("external:nosuch"))
    }
}
```

- [ ] **Step 5.2: Run to fail.**

- [ ] **Step 5.3: Implement** (manager constructor gains `externalRecordsProvider: ExternalModelRecordsProvider` and an `externalEngine: ExternalSherpaBackend` parameter defaulting to `ExternalSherpaBackend()` so tests inject the mock; NO store parameter, nothing reads it anymore; `llmManager` stays. Read APIs stay NON-SUSPEND by reading the provider's snapshot):

```kotlin
    // Not multibound into the backend set; Hilt satisfies this parameter via the engine's
    // own @Singleton @Inject constructor (Kotlin defaults are invisible to Dagger, so keep
    // the @Inject). The engine is routed only through the external: prefix, so no consumer
    // can address the unconfigured placeholder.
    private val externalEngine: ExternalSherpaBackend,

    suspend fun setActiveBackend(backendId: String, context: Context, config: BackendConfig): Result<Unit> {
        val backend: TranscriptionBackend
        val effectiveConfig: BackendConfig
        if (backendId.startsWith("external:")) {
            if (externalRecordsProvider.records.value.none { it.backendId == backendId }) {
                return Result.failure(IllegalArgumentException("Unknown backend: $backendId"))
            }
            effectiveConfig = config as? BackendConfig.ExternalConfig
                ?: return Result.failure(IllegalArgumentException(
                    "External backend requires ExternalConfig (threads/provider are resolved by the orchestrator): $backendId"))
            backend = externalEngine
        } else {
            backend = backends[backendId]
                ?: return Result.failure(IllegalArgumentException("Unknown backend: $backendId"))
            effectiveConfig = config
        }
        // ... unchanged unload-then-initialize body, using backend/effectiveConfig ...
    }

    // getAvailableBackends(): append ExternalBackendHandle(record) for every entry of
    // externalRecordsProvider.records.value (snapshot; non-suspend).
    // getBackend(backendId): for external ids return externalEngine when
    // externalRecordsProvider.records.value.any { it.backendId == backendId }, else null.
```

Per-record handles: a private `class ExternalBackendHandle(val record: ExternalModelRecord) : TranscriptionBackend` implementing every member inertly (`initialize` returns failure, `transcribeAudio` returns `NotInitialized`, `id = record.backendId`, `displayName = record.displayName`, `isReady() = File(record.dir).exists()`, `unload()` no-op). The manager KDoc documents: handles are enumeration-only (pickers), the engine is the single loadable instance, and `getBackend` "known" for an external id means the provider snapshot holds the record (engine state is irrelevant). No change to the init duplicate-id warning (handles never enter the injected set; the placeholder registration is prevented by the engine never being multibound).

- [ ] **Step 5.4: Green, full suite, commit** `feat(external): manager routing for external ids with per-record handles`.

### Task 6: Chunk 1 verification gate

- [ ] `./gradlew testFdroidDebugUnitTest` green; `./gradlew assembleFdroidDebug` green.
- [ ] `grep -rn "external:" app/src/main/java | wc -l` reviewed: every hit is one of the designed sites (store, registry, manager, orchestrator, share).
- [ ] No new strings added in Chunk 1 (UI is Chunk 2; keep `displayName` fallbacks non-user-facing for now).
- [ ] Commit any residue; note in the task tracker that Chunk 1 is done.

---

## Chunk 2: Import pipelines, migration, UI, share, verification

### Task 7: ExternalModelImporter, local entry

**Files:**
- Create: `app/src/main/java/com/antivocale/app/data/ExternalModelImporter.kt`
- Modify: `app/src/main/java/com/antivocale/app/ui/viewmodel/ModelViewModel.kt` (repoint the TASK-313 folder-picker flow at the importer; keep the OpenDocumentTree launcher)
- Test: `app/src/test/java/com/antivocale/app/data/ExternalModelImporterTest.kt`

- [ ] **Step 7.1: Failing tests** (fixture: temp source dir with the four canonical files as bytes; temp filesRoot)

```kotlin
class ExternalModelImporterTest {
    // importer = ExternalModelImporter(store); filesRoot injected as a constant lambda returning the temp root.

    @Test fun `local import copies to id-fragment dir, records pins, registers the record`() = runTest {
        val src = createTempModelDir("src")                       // writes REQUIRED_FILES
        val record = importer.importFromDirectory(src)
        assertEquals(4, record.files.size)
        assertTrue(record.files.values.all { it.verified })       // hashes computed during the copy
        assertTrue(File(record.dir).isDirectory)
        assertEquals(1, store.records().size)
        // Dir uniqueness: the id fragment is embedded.
        assertTrue(record.dir.endsWith(record.id.take(6)))
    }

    @Test fun `missing role fails with a clean error and registers nothing`() = runTest {
        val src = createTempDirWithOnly("tokens.txt")
        val result = runCatching { importer.importFromDirectory(src) }
        assertTrue(result.isFailure)
        assertEquals(0, store.records().size)
    }

    @Test fun `same-hash reimport offers no second directory`() = runTest {
        val src = createTempModelDir("src")
        val first = importer.importFromDirectory(src)
        val second = importer.importFromDirectory(src)
        assertEquals(first.id, second.id)                          // update path returns the same record
        assertEquals(1, store.records().size)                     // no duplicate record either
    }
}
```

- [ ] **Step 7.2: Run to fail.** `./gradlew :app:testFdroidDebugUnitTest --tests "com.antivocale.app.data.ExternalModelImporterTest"` (compile error).

- [ ] **Step 7.3: Implement.** `ExternalModelImporter` constructor: `(private val store: ExternalModelStore, private val filesRoot: (android.content.Context) -> File = { File(it.filesDir, "models/external") })` plus an injectable `uuid: () -> String = { java.util.UUID.randomUUID().toString().replace("-", "") }` for determinism in tests (unit tests pass a constant lambda returning the temp root). TWO entry points share one core; the SAF one is the primary v2a path:
  - `importFromTreeUri(context: Context, treeUri: Uri, modelType: String): ExternalModelRecord`: enumerate `DocumentFile.fromTreeUri(context, treeUri).listFiles()` and copy via `context.contentResolver.openInputStream(srcFile.uri)` (the landed TASK-313 `onCustomModelDirSelected` loop; port it, do NOT use `File(uri.path)`, a SAF tree URI is not a filesystem path).
  - `importFromDirectory(src: File, modelType: String = "nemo_transducer"): ExternalModelRecord`: direct-file variant used by tests. The Task 9 migration deliberately does NOT call it: the migrator hand-computes pins over the already-copied TASK-313 directory and keeps it at its legacy `models/custom-transducer/` location (the record's dir points there; no re-copy, no doubling of ~326MB, matching the spec's "hashes computed from the copied files" where copied refers to TASK-313's original import).
  Both run:
  1. Build the copy plan with the ROLE-BASED matcher lifted from TASK-313's `ModelViewModel.buildCopyPlan` (move it INTO the importer as `internal fun buildCopyPlan(files: List<String>): Map<String, String>?` mapping source name to canonical role name; keyword match encoder/decoder/joiner + tokens; null when any role is missing). Null plan = clean import error, nothing registered (the Step 7.1 missing-role test).
  2. Unconditional disk pre-flight (spec binding, BOTH entries): sum the source file sizes and require `filesRoot(ctx).usableSpace` above it, mirroring the downloader's pre-flight (`SherpaOnnxModelDownloader.kt` ~line 111); failure surfaces a clear error before any copy.
  3. Target dir `File(filesRoot(ctx), sanitize(displayName) + "-" + uuid().take(6))`; clean-replace if it exists (deleteRecursively + mkdirs, the TASK-313 collision fix).
  4. Copy each file, computing SHA-256 while streaming (`java.security.MessageDigest`), record `FilePin(hex, verified = true)`.
  5. Pre-native validation BEFORE persisting (spec pipeline outcome): `SherpaOnnxBackend.missingOnnxMetadata(encoder, keys)` with the modelType-dependent key list (`vocab_size` always; `subsampling_factor` + `model_type` when modelType is `"nemo_transducer"`); failure deletes the copied dir and surfaces a clean import error, so a wrong family is an import-time error, never a transcription-time exit(255).
  6. Same-hash dedupe BEFORE creating: if an existing record's files map equals the computed one, `store.update(existing.copy(displayName = ...))` and return it (the Step 7.1 reimport test also asserts `store.records().size == 1`, not just id equality).
  7. Build the record (family TRANSDUCER, modelType from the caller's selection, default `"nemo_transducer"`), `store.add`, return.
  The ViewModel folder-picker flow calls `importer.importFromTreeUri(context, uri, modelType)` inside its existing IO scope; the modelType dropdown state passes through as the parameter.

- [ ] **Step 7.4: Green, full suite, commit** `feat(external): local import pipeline with role-based copy and pins`.

### Task 8: URL import (HuggingFace repo and entry JSON)

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/data/ExternalModelImporter.kt` (URL entries)
- Create: `app/src/main/java/com/antivocale/app/data/HuggingFaceRepoListing.kt` (API client: file list + LFS oid map)
- Test: `app/src/test/java/com/antivocale/app/data/HuggingFaceRepoListingTest.kt` (parsing only, no network in unit tests)
- Test: extend `ExternalModelImporterTest`

- [ ] **Step 8.1: Failing tests.** Parsing tests with embedded JSON fixtures: (a) `https://huggingface.co/pantinor/gigaam-v3` parses to repo id `pantinor/gigaam-v3`; (b) tree JSON with one LFS file (64-hex oid, `lfs.size`) and one plain file (git sha1 oid) yields `LfsFile(name, sha256, verified=true)` and `PlainFile(name)`; (c) entry-JSON (the catalog single-model schema) parses to a download plan with hashes; missing sha256 in any file entry raises `IllegalArgumentException`. Importer tests: `importFromHuggingFaceRepo` with a MockWebServer serving the tree JSON and FOUR files satisfying the transducer roles under arbitrary names (one LFS-flavored encoder name like `gigaam_v3_e2e_rnnt_encoder_int8.onnx`, one plain tokens.txt; the plain file's hash computed at download, `verified=false`), asserting the download lands under CANONICAL role names; `importFromEntryJson` downloads all files and rejects an entry without hashes.

- [ ] **Step 8.2: Run to fail.**

- [ ] **Step 8.3: Implement.** `HuggingFaceRepoListing` uses OkHttp with the injected `OkHttpClient` from `AppModule` (matching `data/HuggingFaceApiClient.kt`; mockwebserver is already a test dependency) against `https://huggingface.co/api/models/<repo>/tree/main`, mapping the JSON as in the tests. The importer's URL entries FIRST run `buildCopyPlan` over the listing's file names so downloads land under canonical role names (HF repos ship arbitrary names; the engine resolves `SherpaOnnxBackend.REQUIRED_MODEL_FILES`), then produce the same `(url, canonicalName, sha256?)` triple list and share one download core with the catalog path (Chunk-of-v2b will reuse it): resume via the existing `ResumeDownloadHelper`, per-file `DownloadState` progress callback, sha256 verification when a pin exists, TOFU compute-and-record when not (`FilePin(hex, verified = false)`), and the Task 7 registration tail. Disk pre-flight runs unconditionally using the summed Content-Lengths (spec binding).

- [ ] **Step 8.4: Green, full suite, commit** `feat(external): URL import from HF repos and catalog-entry JSON with TOFU pins`.

### Task 9: One-shot migration from custom-transducer

**Files:**
- Create: `app/src/main/java/com/antivocale/app/data/CustomTransducerMigrator.kt`
- Modify: `app/src/main/java/com/antivocale/app/BridgeApplication.kt` (call the migrator in `onCreate` BEFORE `ShareTargetManager.syncAll()` (~line 40); ModelViewModel is created lazily on first Model-tab visit, which would leave a session where a persisted `custom-transducer` id resolves against a registry that no longer has it, silently falling through to the LLM loader)
- Modify: delete the `custom-transducer` backend wiring absorbed by v2a: `CustomTransducerBackend.kt`, its descriptor in `BackendRegistry`, its arms in Orchestrator/ExtractionService, the `customTransducer*` PreferencesManager accessors (the migration is their last reader; same commit), `FakePreferencesManager` fields, `BackendRegistryTest` entries, `TranscriptionModule` provider, ModelTab import card, ModelViewModel members (`_customTransducerModelPath`/`_customTransducerModelType` flows and their init hydration, `setCustomModelType`, `useCustomTransducerModel`, `activateCustomTransducerModel`, `deleteCustomTransducerModel`, the SAF handler superseded by Task 7's importer call), related strings (keep strings the external section reuses). Without the ModelViewModel removals the same-commit deletion of the accessors fails compilation; SettingsViewModel needs nothing (the P0 rebase already deleted its manual arm).
- Test: `app/src/test/java/com/antivocale/app/data/CustomTransducerMigratorTest.kt`

- [ ] **Step 9.1: Failing tests**

```kotlin
class CustomTransducerMigratorTest {
    @Test fun `migrates a valid custom-transducer preference into an external record`() = runTest {
        val dir = createTempModelDir("custom")            // the four canonical files
        fake._customTransducerModelPath.value = dir.absolutePath
        fake._customTransducerModelType.value = ""
        fake._transcriptionBackend.value = "custom-transducer"
        migrator.migrate()
        val record = store.records().single()
        assertEquals(ModelFamily.TRANSDUCER, record.family)
        assertEquals("", record.modelType)
        assertEquals(record.backendId, fake._transcriptionBackend.value)   // active pointer rewritten
        assertTrue(fake._externalMigrationDone.value == true)              // idempotence marker
    }

    @Test fun `done marker prevents re-migration and duplication`() = runTest {
        fake._externalMigrationDone.value = true
        migrator.migrate()
        assertEquals(0, store.records().size)
    }

    @Test fun `marker is written before the record is created`() = runTest {
        // Ordering pin: a migrator that creates the record first would duplicate on a crash
        // between the two writes. Assert via a store wrapper that fails on add-after-marker.
        val failingStore = StoreThatRejectsAddsAfterMarker(fake)
        assertFailsWith<IllegalStateException> { CustomTransducerMigrator(fake, failingStore).migrate() }
        assertTrue(fake._externalMigrationDone.value == true)
    }

    @Test fun `absent preference is a no-op`() = runTest {
        migrator.migrate()
        assertEquals(0, store.records().size)
        assertTrue(fake._externalMigrationDone.value == true)
    }

    @Test fun `invalid directory marks done and skips`() = runTest {
        fake._customTransducerModelPath.value = "/gone"
        migrator.migrate()
        assertEquals(0, store.records().size)
    }
}
```

- [ ] **Step 9.2: Run to fail.**

- [ ] **Step 9.3: Implement.** `CustomTransducerMigrator(preferencesManager, store)`: read the done-marker preference (`external_migration_done`, boolean, plus its FakePreferencesManager field); if set, return. Write the marker. Read `customTransducerModelPath`/`Type`; if absent or the dir is invalid, return. Compute pins from the files on disk, build the record (source LOCAL, `displayName` from the dir name), `store.add`, and if `transcriptionBackend == "custom-transducer"` rewrite it to `record.backendId`. Ordering is marker-before-record (pinned by the third test). Deliberate deviation from the spec's idempotence wording: the second disjunct ("or a record with the same directory and hash set already exists") is unreachable under marker-before-record ordering and is NOT implemented; this note exists so a later reviewer does not re-add it or flag the spec. Then DELETE the custom-transducer wiring listed in Files; the migration is the last reader of those preferences, so remove the PreferencesManager members in the same commit. Audience note (spec): if TASK-313 never shipped to a store, the migration is exercised only by tests; the code stays because the maintainer's own device ran the branch.

- [ ] **Step 9.4: Green, full suite, commit** `feat(external): absorb custom-transducer via one-shot idempotent migration`.

### Task 10: ShareExternal alias, sync rule, chooser

**Files:**
- Modify: `app/src/main/AndroidManifest.xml` (new `activity-alias` `com.antivocale.app.ShareExternal`, `android:enabled="false"`, same intent-filter set as `.ShareGigaam`, label from `@string/share_target_external`)
- Modify: `app/src/main/java/com/antivocale/app/data/ShareTargetManager.kt`
- Modify: `app/src/main/java/com/antivocale/app/di/AppModule.kt` (`provideShareTargetManager` at ~lines 49-54: the constructor arity changes with the store injection below; ShareTargetManager is NOT Hilt-annotated, this module is its construction site)
- Modify: `app/src/main/java/com/antivocale/app/receiver/ShareReceiverActivity.kt`
- Modify: `app/src/test/java/com/antivocale/app/transcription/BackendRegistryTest.kt` (alias set pin: ShareExternal is NOT in the descriptor alias set; it is family-level)
- Test: create `app/src/test/java/com/antivocale/app/data/ShareTargetManagerExternalTest.kt` (no existing ShareTargetManager test to extend)

- [ ] **Step 10.1: Failing tests.** (a) `syncAll` with advanced sharing ON and one valid record enables exactly the `ShareExternal` component; (b) advanced sharing OFF disables it even with records present; (c) zero valid records disables it; (d) per-descriptor sync never issues a `setComponentEnabledSetting` with an empty className (mock PackageManager, verify the className list). (e) `backendIdForAlias("com.antivocale.app.ShareExternal", registry)` returns the sentinel `EXTERNAL_FAMILY_BACKEND_ID` constant (define it in ShareReceiverActivity's companion, value `"external"`; the chooser resolves it).

- [ ] **Step 10.2: Run to fail.**

- [ ] **Step 10.3: Implement.** ShareTargetManager: implement the empty-alias skip INSIDE `setComponentEnabled` (so both `syncAll`'s per-descriptor loop and `setAdvancedSharingEnabled(false)`'s own forEach at ~line 74 are covered by one guard); after the loop, sync the family component: `setComponentEnabledSetting(ComponentName(ctx, "com.antivocale.app.ShareExternal"), advancedEnabled && externalRecordsPresent)`. `externalRecordsPresent` reads `externalModelStore.validRecords()` in the manager's existing `runBlocking` style (inject the STORE, not the provider: the provider's StateFlow starts empty and is filled by a background collector, so reading it from `BridgeApplication.onCreate`'s main-thread `syncAll()` would intermittently disable ShareExternal at startup despite valid records). The "disable all" paths also disable the family component. ShareReceiverActivity: when the resolved backend id equals `EXTERNAL_FAMILY_BACKEND_ID`, show the chooser as a plain `android.app.AlertDialog` with `setItems` over `externalModelStore.validRecords()` (the store comes from the Chunk 1 `BackendRegistryEntryPoint` pattern: extend that `@EntryPoint` interface with `fun externalModelStore(): ExternalModelStore`, mirroring the existing `SubtitlePrefsEntryPoint` in the same file; the class stays non-@AndroidEntryPoint) (name + languages; the activity is deliberately NOT a ComponentActivity, so no Compose `setContent`/`ModalBottomSheet`: a platform Dialog keeps it alive, no precedent exists for bottom sheets in this repo); resolve the sentinel to a concrete `external:<id>` BEFORE the branch that calls `postSubtitleChoiceNotification`/`enqueueChoiceTimeoutWorker` (~lines 256-275), so the sentinel never leaks into the notification, the timeout worker, or the service override; on selection continue the existing flow with `backendOverride = record.backendId`; with zero records the alias is disabled so the path is unreachable, but guard anyway with a friendly toast + finish. Strings: `share_target_external` = "Anti-Vocale (External)" / it "Anti-Vocale (Esterno)".

- [ ] **Step 10.4: Green, full suite, commit** `feat(external): ShareExternal family alias with chooser and sync rule`.

### Task 11: Model tab external section and strings

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/ui/tabs/ModelTab.kt`
- Modify: `app/src/main/java/com/antivocale/app/ui/viewmodel/ModelViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-it/strings.xml`

- [ ] **Step 11.1: Strings first** (en + it, both files, `external_` prefix): `external_section_title` ("External models" / "Modelli esterni"), `external_import_folder` ("Import from folder" / "Importa da cartella"), `external_import_url` ("Import from URL" / "Importa da URL"), `external_url_hint` ("HuggingFace repo or catalog-entry JSON URL" / "URL repo HuggingFace o voce di catalogo JSON"), `external_importing` ("Importing model..." / "Importazione modello..."), `external_import_failed` ("Import failed: %1$s" / "Importazione non riuscita: %1$s"), `external_delete_confirm` ("Delete external model %1$s?" / "Eliminare il modello esterno %1$s?"), `external_notice_single_pass` and `external_notice_family` (the two standing notices inherited from TASK-313: single-pass long-audio risk; wrong family can crash transcription with no error, correct it from the card), `share_target_external` (Task 10). StringResourceParityTest enforces en/it parity automatically.

- [ ] **Step 11.2: ViewModel surface**: `externalModels: StateFlow<List<ExternalModelRecord>>` (from the store), `importState` (idle/importing/error(message)), `importFromFolder(uri, modelType)` (delegates to the importer; on success snackbar + auto-select if nothing active), `importFromUrl(url, modelType)`, `deleteExternalModel(id)` (store.delete + directory deleteRecursively + reset `transcriptionBackend` to the default when it pointed at the record, spec binding; then `shareTargetManager.syncAll()` so the ShareExternal alias disables immediately when the last record goes, matching every static delete's `onModelDeleted` discipline; snackbar), `useExternalModel(id)` (set transcriptionBackend preference; mirrors `useGigaAmModel` minus downloader lookups), `correctExternalFamily(id, modelType)` (store.update + orchestrator unload hint).

- [ ] **Step 11.3: ModelTab section**: after the static backend cards, an "External models" section: header + the two import action buttons + one card per record (name, family chip, languages, size via `formatFileSize`, Use / Correct family / Delete actions, the two standing notices as info rows, `DeleteConfirmationDialog` wired at the ModelTab top level like the six existing instances (lines ~209-358), not inside the card composable). Importing state disables the buttons and shows `external_importing`. Follow the `GigaAmDownloadSection` card structure; a single `ExternalModelCard(record, ...)` composable in the same file keeps it cohesive.

- [ ] **Step 11.4: Full suite (StringResourceParityTest included), manual smoke of the tab in the debug build, commit** `feat(external): external models UI section with import actions`.

### Task 12: Verification and landing gates

- [ ] `./gradlew testFdroidDebugUnitTest` green; `./gradlew assembleFdroidDebug` green; `./gradlew assemblePlayStoreRelease` compiles (R8: new classes live under `com.antivocale.app.transcription.**` and `com.antivocale.app.data.**`, both covered by existing keep rules; verify with the pre-release audit greps in CLAUDE.md).
- [ ] Device test (Realme RMX3853, `./scripts/install.sh`): (1) import the GigaAM mirror files from local storage (`/tmp/gigaam-mirror` pushed to the device), transcribe a Russian voice message, verify output and active-model display in both Model and Settings tabs; (2) import via URL from `https://huggingface.co/pantinor/gigaam-v3`, same verification; (3) share-sheet: ShareExternal appears after import, chooser lists the model, transcription runs via the override path; (4) delete while active: active model falls back to the default backend, no LLM fallthrough error; (5) negative: folder missing the decoder fails with the clean error.
- [ ] `/code-review high` on the branch diff, fix findings; then `/simplify`, fix findings (project standing rule before device-done).
- [ ] `pa:reflect`, then verification-before-completion: every "green" claim above backed by a command output read this session.
- [ ] Update TASK-313 notes (absorption complete) and mark the v2a backlog task accordingly; close issue #24 referencing both deliverables once merged to main.

