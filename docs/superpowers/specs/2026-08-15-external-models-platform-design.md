# External Models Platform (v2) Design

**Date**: 2026-08-15
**Status**: maintainer-approved design, pending spec review
**Resolves**: issue #24 (generalized "consume external models without app releases")
**Related**: PR #25 (landed as the curated GigaAM backend on branch `gigaam-v3`), TASK-313 (custom-transducer sideload v1, to be absorbed), TASK-330 (streaming, deferred), HuggingFace mirror `pantinor/gigaam-v3`

## Decision record (maintainer session 2026-08-15)

1. Three import sources ship over time: local folder, URL, remote catalog. All three feed one pipeline.
2. Unlimited simultaneously-usable external models (dynamic registry entries, serialized store).
3. v1 of the platform engine is offline-only (`OfflineRecognizer` families). Streaming gets its own task (TASK-330).
4. The official catalog is a JSON file in this GitHub repo (raw URL from `main`), so catalog updates are ordinary reviewed commits. Users may add community-maintained catalogs (same schema) at their own risk, with the issuer prominently displayed.
5. Architecture: approach "dynamic registry + serialized store" (option A). A pre-registered fixed-slot design was rejected because it caps the model count; a parallel subsystem outside `BackendRegistry` was rejected because it re-creates the parallel-dispatch problem TASK-254..324 cured.
6. Share sheet: one static manifest alias `com.antivocale.app.ShareExternal` opening a chooser of imported external models.
7. v2a absorbs TASK-313's `custom-transducer` backend via a one-shot preference migration; the dedicated backend is removed.

## Goals

- A user can run any supported sherpa-onnx offline model (any language) without an app release, via folder import, URL import, or the catalog.
- External models behave like first-class backends everywhere the `BackendRegistry` already dispatches: orchestrator load, active-model repository, settings display, re-transcribe picker, model tab.
- Integrity: every imported byte set is pinned by SHA-256 in the store; catalog entries without hashes are rejected.

## Non-goals (v1/v2a)

- Streaming external models (TASK-330).
- Auto-detection beyond file-layout and ONNX metadata heuristics; family selection is always user-confirmed.
- Executing models from arbitrary remote URLs at transcription time (everything is copied into app storage first).

## Architecture

New components:

- `ExternalModelStore` (`data/`): JSON-serialized list of `ExternalModelRecord` in one DataStore key. Exposes `Flow<List<ExternalModelRecord>>` plus CRUD. Single source of truth for what is imported. Uses the existing top-level `preferencesDataStore` delegate in `PreferencesManagerImpl` (instance-level delegates double-activate the file; documented project trap).
- `BackendRegistry` extension: `backends` becomes the current static list plus descriptors derived from the store. **Wiring (binding decision):** the registry takes the store as a constructor dependency and recomputes its lookup maps from the current store snapshot (a `StateFlow` cached list refreshed on store emission). This retires the documented "stateless, fresh-instance-equivalent" property of `BackendRegistry()` in `TranscriptionOrchestrator` and `ActiveModelRepository` (both construct it bare today); their KDocs and `BackendRegistryTest` are updated accordingly, and the test constructs the registry with a fake store. `byModelType` keeps `associateBy` semantics: with N EXTERNAL descriptors it returns an arbitrary one, documented as undefined-for-the-family (no production consumer uses `byModelType(EXTERNAL)`; the orchestrator dispatches on the active record, not on the family lookup).

Derived descriptor: `backendId = "external:<record.id>"`, `modelType = ExtractionService.ModelType.EXTERNAL`, `shareAlias = ""` (no direct alias; see Share), `displayName` from the record, `modelPathFlow`/`saveModelPath`/`clearModelPath` bound to store methods. Records whose directory is missing or validation failed derive no descriptor at all (invalid records are invisible to every registry consumer).
- `ExtractionService.ModelType.EXTERNAL`: the single enum value for the whole family (persistence/bookkeeping role, per the registry KDoc contract).
- `ExternalSherpaBackend` (`transcription/`): one `@Singleton` engine configured per record at `initialize` (record carried in the config); builds the sherpa `OfflineRecognizer` according to family and modelType. Pre-native validation reuses `SherpaOnnxBackend.missingOnnxMetadata` with the per-family key list.

**Engine dispatch through `TranscriptionBackendManager` (binding decision).** The manager's id-keyed map is built at construction from the injected static set, so a dynamic-id engine cannot be registered there like the static backends. Instead: the manager gains an explicit `external:`-prefix arm. `setActiveBackend(backendId, config)` and `getBackend(backendId)` resolve any `external:<id>` against `ExternalModelStore` and route to the single `ExternalSherpaBackend`, carrying the record in the config; unknown or invalid records fail with the existing "Unknown backend" error. The engine's `id` returns `"external"` (unconfigured) before `initialize` and after `unload`, and the manager never registers the engine under that placeholder, so no consumer can address a half-configured engine. `getAvailableBackends()` appends one lightweight per-record backend handle (id `external:<id>`, displayName from the record, `isReady()` from record validity) so id-keyed consumers that enumerate instances, notably the LogsViewModel re-transcribe picker (which filters them via `byBackendId(backend.id)`), see every imported model. Concurrency contract: N external models installed and switchable, exactly one backend loaded at a time (the manager's existing unload-before-load discipline; memory constraints documented in its KDoc).
- `ExternalModelImporter` (`data/`): the single pipeline (see Import pipelines). Generalizes `SherpaOnnxModelDownloader` to an arbitrary set of (url, fileName, sha256) triples without touching the existing static-backend paths.
- `CatalogRepository` (v2b): fetches and caches the official `catalog.json` plus user-added community catalog URLs; parses and validates the schema; exposes entries for the UI.

Unchanged: the five static backends and their preferences, the manifest aliases of static backends, the orphan-dir cleaner (external models live under a dedicated `models/external/` root it ignores), the share flow of static backends.

## Data model

`ExternalModelRecord`:

```
id: String (uuid)
displayName: String
dir: String            (under filesDir/models/external/<sanitized-name>-<id-fragment>/)
family: enum           (TRANSDUCER first; CTC, PARAFORMER, SENSE_VOICE, WHISPER in v2b)
modelType: String      (sherpa modelType: nemo_transducer, "", conformer_transducer, ...)
languages: List<String> (informational; from catalog entry or user)
source: enum + url     (LOCAL / URL / CATALOG, plus provenance URL or catalog id)
files: Map<String,String>  (fileName -> sha256; the integrity pin)
sizeBytes: Long
importedAt: Long (epoch millis)
```

**Directory uniqueness rule (binding decision):** identity is the uuid, the directory embeds a short id fragment (`sanitized-name` + first 6 hex chars of the id), so two imports with the same display name never share a directory and deleting one record can never destroy another's files. A re-import of the same file set (same hashes) into an existing record is offered as an update to that record, not a second directory.

Catalog schema (`catalog.json`, versioned):

```json
{
  "schemaVersion": 1,
  "issuer": "Anti-Vocale official",
  "models": [
    {
      "id": "gigaam-v3",
      "name": "GigaAM v3",
      "description": "Russian ASR, native punctuation",
      "family": "transducer",
      "modelType": "nemo_transducer",
      "languages": ["ru"],
      "sizeMB": 326,
      "license": "MIT",
      "homepage": "https://github.com/salute-developers/GigaAM",
      "files": [
        {"name": "gigaam_v3_e2e_rnnt_encoder_int8.onnx", "url": "https://huggingface.co/pantinor/gigaam-v3/resolve/main/gigaam_v3_e2e_rnnt_encoder_int8.onnx", "sha256": "2cac62..."}
      ]
    }
  ]
}
```

Parsing rules: `schemaVersion` above the supported maximum rejects the catalog with a clear error (not a crash); entries missing any `sha256` are rejected individually with the reason shown; `issuer` is always rendered in the UI for community catalogs.

## Registry integration and share

- `ShareReceiverActivity` resolves the `ShareExternal` alias to the chooser: bottom sheet listing imported external models (name, languages). Selection dispatches transcription with `backendOverride = "external:<id>"` (the override path already exists in the orchestrator).
- **Component sync rule (binding decision):** `ShareTargetManager`'s per-descriptor iteration skips descriptors with an empty `shareAlias` (an empty `ComponentName` per derived record would otherwise be toggled on every sync). The family-level `ShareExternal` component is enabled if and only if the store holds at least one valid record, disabled when the last one is removed or invalidated (the `onModelDeleted` analog of the static backends, driven by the store flow).
- `TranscriptionOrchestrator.ensureBackendLoaded` gains one arm: `ModelType.EXTERNAL -> loadExternalBackend(context)`, which resolves the record (override id or the persisted active backend), validates it, and configures `ExternalSherpaBackend`.
- `BackendRegistryTest` extends to cover the static list plus N derived descriptors with a fake store (id round-trip, uniqueness, alias behavior, invalid-record exclusion).

## Import pipelines

One pipeline, three entries, one outcome: canonical files under `models/external/<name>/`, hashes in the record, pre-native validation, store registration.

1. **Local folder** (absorbs TASK-313): SAF folder pick; role-based file matching (TASK-313's `buildCopyPlan`); copy with hashes computed during the copy.
2. **URL (v2a)**: two forms. (a) HuggingFace repo URL: enumerate files via the HF API, propose the ones recognized for a family, adopt the LFS oids as sha256. Non-LFS files (typically small ones such as `tokens.txt`) expose only a git blob sha1 server-side, so their sha256 is computed on first download and recorded (trust-on-first-use, the source is the user-chosen repo); the record marks such pins as `computed`, and a later re-import upgrades them to verified values. (b) Catalog-entry JSON URL: the same schema as a catalog entry, single model; this is how third parties share one model with integrity.
3. **Catalog (v2b)**: browse entries from configured sources, filters by language, family and size; download with per-file hash verification. An entry whose hashes changed is offered as an update, never applied silently.

Family auto-detection (v2b) proposes, the user confirms: wrong modelType causes an uncatchable native `exit(255)`, so no silent selection. Detection inputs: canonical file roles, ONNX metadata keys (`model_type`, `subsampling_factor`, `vocab_size`).

Validation matrix per family (file set + metadata keys):

- TRANSDUCER: encoder/decoder/joiner(+tokens) or encoder/joint+decoder combined; keys `vocab_size`, `subsampling_factor`, `model_type` for the nemo family.
- CTC / PARAFORMER / SENSE_VOICE / WHISPER (v2b): single model or encoder/decoder pairs; family-specific metadata keys documented at implementation time from the sherpa-onnx source of truth (the per-modelType required config and metadata each family's loader reads in `sherpa-onnx`'s offline model code), never derived from observed native crashes.

## Migration (absorbing TASK-313)

One-shot at first launch after the v2a update: read `customTransducerModelPath` + `customTransducerModelType`; if present and valid, create an `ExternalModelRecord` (family TRANSDUCER, source LOCAL, hashes computed from the copied files), point the active-backend preference at `external:<id>` if `custom-transducer` was active, then remove the `custom-transducer` backend and its preferences. **Idempotence:** the migration writes its done-marker (a DataStore flag) before creating the record, and on later launches skips when the flag is set or when a record with the same directory and hash set already exists; a crash mid-migration cannot duplicate records on relaunch. Audience note for the planner: the `customTransducer*` preferences exist only where the TASK-313 branch ran (the maintainer's device) unless that branch ships in a store release first; if it never ships, the migration is dead code exercised only by tests, and the planner should confirm which case holds. The branch `feature/custom-transducer-sideload` (commit `100eef3`) is first rebased onto the registry main per TASK-313 criterion #11; v2a then builds on that landed code (import UI, copy plan, validation) rather than duplicating it.

## UI

- Model tab: "External models" section after the static backend cards. Per-record card: name, family, languages, size, active state, actions Use, Delete, Info, Correct-family. Import actions: Import from folder, Import from URL; Catalog browser in v2b.
- Settings: "Model catalogs" (v2b): active sources with issuer, add-community-URL, remove; a trust notice (the issuer is responsible for what you download).
- Standing notices on external-model cards (inherited from TASK-313): single-pass transcription may fail on very long audio with large models; a wrong family/modelType can crash transcription with no error, correct it from the card.

## Error handling and integrity

- SHA-256 mismatch at import: delete the partial directory, surface `DownloadState.Error` (existing HashVerifier behavior).
- Interrupted downloads: resumable via the existing sidecar `.size` mechanism.
- SAF files not yet indexed by MediaStore (observed on first-attempt imports of freshly copied large files): retry with an explanatory message.
- Record whose directory disappeared: descriptor derived as invalid (no descriptor at all; see Registry integration); UI offers re-import or record deletion; never auto-selected as active.
- **Delete while active (binding decision):** deleting a record whose id is the persisted `transcriptionBackend` value resets the preference to the default backend (Parakeet path) in the same store transaction; otherwise the orchestrator's registry lookup would return null and silently fall through to the LLM loader.
- Disk-space pre-flight runs unconditionally on every import (the existing downloader pre-flights every download with a known size; the generalized importer keeps that behavior, with the estimated size taken from the file list or the catalog entry).
- Community catalogs carry no built-in trust: hashes are mandatory, issuer is displayed, and the UI states that third-party catalogs are consumed at the user's discretion.

## Testing

- Unit: store CRUD and JSON round-trip; descriptor derivation with a fake store; catalog parser (valid, wrong schemaVersion, missing hashes); URL resolution (HF repo incl. the non-LFS trust-on-first-use path, entry JSON); migration from `custom-transducer` preferences (present, absent, invalid path, crash-idempotence).
- Contract: `ExternalSherpaBackend` (dynamic id, unconfigured placeholder id before init and after unload, not-ready, unload safety); `TranscriptionBackendManager` external-prefix routing (valid record, unknown id, invalid record) and per-record handles in `getAvailableBackends`.
- Registry suite: static list plus N externals, empty-alias skip plus `ShareExternal` enable/disable at the 1-record and 0-record boundaries in `ShareTargetManager` sync, delete-while-active preference reset, invalid-record exclusion.
- Device: import the GigaAM mirror files locally and transcribe Russian; import via URL from `pantinor/gigaam-v3`; one real official catalog entry end to end.
- No regression path for static backends: the downloader changes are additive parameters, `run_baseline.py` optional as a golden check.

## Phasing

- **v2a**: store, dynamic descriptors, `ExternalSherpaBackend` (TRANSDUCER only), local + URL import, migration from `custom-transducer`, share alias + chooser. Depends on: TASK-313 rebase onto registry main (criterion #11) landing first.
- **v2b**: official catalog + community catalogs, catalog browser UI, auto-detection and support for CTC, PARAFORMER, SENSE_VOICE, WHISPER.
- **TASK-330**: streaming external models.

## Open questions (non-blocking, decide at plan time)

- Catalog refresh cadence (on-open vs manual refresh only).
- i18n of catalog entry descriptions (single language from the entry vs device-locale field in schema v2).
- Soft disk-space warning threshold beyond the 1GB hard pre-flight.
- Whether the official catalog ships a bundled snapshot inside the APK for first-launch offline browsing, in addition to the remote fetch.
