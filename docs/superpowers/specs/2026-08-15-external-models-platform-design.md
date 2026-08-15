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

- `ExternalModelStore` (`data/`): JSON-serialized list of `ExternalModelRecord` in one DataStore key. Exposes `Flow<List<ExternalModelRecord>>` plus CRUD. Single source of truth for what is imported.
- `BackendRegistry` extension: `backends` becomes the current static list plus descriptors derived from the store snapshot. Derived descriptor: `backendId = "external:<record.id>"`, `modelType = ExtractionService.ModelType.EXTERNAL`, `shareAlias = ""` (no direct alias; see Share), `displayName` from the record, `modelPathFlow`/`saveModelPath`/`clearModelPath` bound to store methods.
- `ExtractionService.ModelType.EXTERNAL`: the single enum value for the whole family (persistence/bookkeeping role, per the registry KDoc contract).
- `ExternalSherpaBackend` (`transcription/`): one `@Singleton` engine configured per record at `initialize` (record carried in the config); builds the sherpa `OfflineRecognizer` according to family and modelType; `id` returns the configured record's backend id. Pre-native validation reuses `SherpaOnnxBackend.missingOnnxMetadata` with the per-family key list.
- `ExternalModelImporter` (`data/`): the single pipeline (see Import pipelines). Generalizes `SherpaOnnxModelDownloader` to an arbitrary set of (url, fileName, sha256) triples without touching the existing static-backend paths.
- `CatalogRepository` (v2b): fetches and caches the official `catalog.json` plus user-added community catalog URLs; parses and validates the schema; exposes entries for the UI.

Unchanged: the five static backends and their preferences, the manifest aliases of static backends, the orphan-dir cleaner (external models live under a dedicated `models/external/` root it ignores), the share flow of static backends.

## Data model

`ExternalModelRecord`:

```
id: String (uuid)
displayName: String
dir: String            (under filesDir/models/external/<sanitized-name>/)
family: enum           (TRANSDUCER first; CTC, PARAFORMER, SENSE_VOICE, WHISPER in v2b)
modelType: String      (sherpa modelType: nemo_transducer, "", conformer_transducer, ...)
languages: List<String> (informational; from catalog entry or user)
source: enum + url     (LOCAL / URL / CATALOG, plus provenance URL or catalog id)
files: Map<String,String>  (fileName -> sha256; the integrity pin)
sizeBytes: Long
importedAt: Long (epoch millis)
```

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
- The alias stays `android:enabled="false"` until the store has at least one valid record; the enable/disable logic follows `ShareTargetManager`'s registry iteration.
- `TranscriptionOrchestrator.ensureBackendLoaded` gains one arm: `ModelType.EXTERNAL -> loadExternalBackend(context)`, which resolves the record (override id or the persisted active backend), validates it, and configures `ExternalSherpaBackend`.
- `BackendRegistryTest` extends to cover the static list plus N derived descriptors with a fake store (id round-trip, uniqueness, alias behavior, invalid-record exclusion).

## Import pipelines

One pipeline, three entries, one outcome: canonical files under `models/external/<name>/`, hashes in the record, pre-native validation, store registration.

1. **Local folder** (absorbs TASK-313): SAF folder pick; role-based file matching (TASK-313's `buildCopyPlan`); copy with hashes computed during the copy.
2. **URL (v2a)**: two forms. (a) HuggingFace repo URL: enumerate files via the HF API, propose the ones recognized for a family, adopt the LFS oids as sha256. (b) Catalog-entry JSON URL: the same schema as a catalog entry, single model; this is how third parties share one model with integrity.
3. **Catalog (v2b)**: browse entries from configured sources, filters by language, family and size; download with per-file hash verification. An entry whose hashes changed is offered as an update, never applied silently.

Family auto-detection (v2b) proposes, the user confirms: wrong modelType causes an uncatchable native `exit(255)`, so no silent selection. Detection inputs: canonical file roles, ONNX metadata keys (`model_type`, `subsampling_factor`, `vocab_size`).

Validation matrix per family (file set + metadata keys):

- TRANSDUCER: encoder/decoder/joiner(+tokens) or encoder/joint+decoder combined; keys `vocab_size`, `subsampling_factor`, `model_type` for the nemo family.
- CTC / PARAFORMER / SENSE_VOICE / WHISPER (v2b): single model or encoder/decoder pairs; family-specific keys documented at implementation time from sherpa-onnx behavior.

## Migration (absorbing TASK-313)

One-shot at first launch after the v2a update: read `customTransducerModelPath` + `customTransducerModelType`; if present and valid, create an `ExternalModelRecord` (family TRANSDUCER, source LOCAL, hashes computed from the copied files), point the active-backend preference at `external:<id>` if `custom-transducer` was active, then remove the `custom-transducer` backend and its preferences. The branch `feature/custom-transducer-sideload` (commit `100eef3`) is first rebased onto the registry main per TASK-313 criterion #11; v2a then builds on that landed code (import UI, copy plan, validation) rather than duplicating it.

## UI

- Model tab: "External models" section after the static backend cards. Per-record card: name, family, languages, size, active state, actions Use, Delete, Info, Correct-family. Import actions: Import from folder, Import from URL; Catalog browser in v2b.
- Settings: "Model catalogs" (v2b): active sources with issuer, add-community-URL, remove; a trust notice (the issuer is responsible for what you download).
- Standing notices on external-model cards (inherited from TASK-313): single-pass transcription may fail on very long audio with large models; a wrong family/modelType can crash transcription with no error, correct it from the card.

## Error handling and integrity

- SHA-256 mismatch at import: delete the partial directory, surface `DownloadState.Error` (existing HashVerifier behavior).
- Interrupted downloads: resumable via the existing sidecar `.size` mechanism.
- SAF files not yet indexed by MediaStore (observed on first-attempt imports of freshly copied large files): retry with an explanatory message.
- Record whose directory disappeared: descriptor derived as invalid; UI offers re-import or record deletion; never auto-selected as active.
- Disk-space pre-flight for imports above 1GB.
- Community catalogs carry no built-in trust: hashes are mandatory, issuer is displayed, and the UI states that third-party catalogs are consumed at the user's discretion.

## Testing

- Unit: store CRUD and JSON round-trip; descriptor derivation with a fake store; catalog parser (valid, wrong schemaVersion, missing hashes); URL resolution (HF repo, entry JSON); migration from `custom-transducer` preferences (present, absent, invalid path).
- Contract: `ExternalSherpaBackend` (dynamic id, not-ready before init, unload safety).
- Registry suite: static list plus N externals, alias resolution to the chooser path, invalid-record exclusion.
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
