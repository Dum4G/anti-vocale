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
@Singleton
class ExternalModelStore @Inject constructor(
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
- Modify: `app/src/test/java/com/antivocale/app/receiver/ShareReceiverActivityAliasTest.kt` (nine static `backendIdForAlias(...)` call sites gain the registry parameter; construct `BackendRegistry(fakeStore, providerWith())` in the fixture and pass it)
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
        val dir = createTempModelDir("external")  // then write the four canonical files into it
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

Also a negative test: `backendOverride = "external:unknown"` fails the request with a `NotInitialized` error and never calls `setActiveBackend`, EVEN when the persisted `transcriptionBackend` preference points at a valid external record (this pins the override-over-preference resolution the spec requires). Invocation shape: copy the existing override test's `processRequest` call verbatim, changing only the `backendOverride` argument (the real signature: taskId, requestType, prompt, filePath, source, sourcePackage, backendOverride, trackIndex, queuePosition, queueTotal, context, cacheDir, listener, coroutineScope). Populate the temp dir via the existing `createTempModelDir(prefix)` helper followed by writing the four canonical file names into it.

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
        val record = externalModelStore.byId(backendId.removePrefix("external:"))
            ?: // TranscriptionException.NotInitialized takes no arguments (fixed message); log the id before failing.
            Log.w(TAG, "no external model record for $backendId")
            return Result.failure(TranscriptionException.NotInitialized())
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
- Modify: `app/src/test/java/com/antivocale/app/transcription/TranscriptionBackendManagerTest.kt` (its `createManager(llmManager, backends.toSet())` helper breaks at the new constructor arity: extend it with the fake store and a real `ExternalSherpaBackend()`)
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
        val dir = createTempDir("external-handle")
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

(covered in the next plan chunk: Task 7 local import absorbing TASK-313 machinery, Task 8 URL import with HF API + entry JSON + TOFU pins, Task 9 one-shot migration from custom-transducer with idempotence and backend removal, Task 10 ShareExternal alias + ShareTargetManager sync rule + chooser, Task 11 ModelTab external section + URL dialog + strings en/it, Task 12 full verification incl. device import of the GigaAM mirror files and Russian transcription, /code-review + /simplify gates)
