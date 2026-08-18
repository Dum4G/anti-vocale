# Multi-family external-model importer and engine (TASK-331): Design

Date: 2026-08-17
Status: approved design, pre-implementation
Task: TASK-331 (Backlog.md)

## Goal

Extend the external-models platform (v2a) so imported sherpa-onnx models can belong to
families beyond the transducer. First iteration ships four tested families:
TRANSDUCER (existing), WHISPER, CTC, SENSE_VOICE. The engine stays a single
`ExternalSherpaBackend` (maintainer-approved decision); only the importer, the config
construction, and the UI become family-aware.

Concrete use cases: Arabic dialectal Whisper fine-tune (OpenVoiceOS, int8, ~1.3GB),
GigaAM v3 CTC (small CTC test case), MMS 1B-all (evaluation only, may fail), plus every
existing transducer import unchanged.

## Decisions (from brainstorming, user-approved)

1. **Family granularity**: coarse `ModelFamily` per architecture; the existing
   `modelType` string disambiguates the sherpa config subtype inside a family
   (exactly as TRANSDUCER + `nemo_transducer`/`conformer_transducer` already works).
   The enum does NOT mirror all 17 sherpa config fields.
2. **Pre-native validation**: per-family ONNX metadata keys, derived empirically from
   the real models during implementation (desktop inspection script). If a family's
   exports carry no metadata at all, that support degrades to a documented structural
   check, never a silent skip.
3. **Scope v1**: 4 families with real-model tests. FIRERED_ASR (named in AC #1) is
   deferred under the AC's own escape hatch ("and any other families validated during
   implementation") until a use case with a real model exists; the other sherpa
   families likewise stay out. The dispatch table is the extension point.
4. **Family-specific parameters**: a flat `options: Map<String, String>` on the record
   (JSON object, keys prefixed per family: `whisper.language`, `whisper.task`,
   `sensevoice.language`, `sensevoice.itn`). Extensible without schema migrations.
5. **Architecture**: a sealed `ModelFamilySupport` interface with one object per
   family, consulted by both importer and engine. Single place of family knowledge;
   no scattered `when(record.family)` dispatch sites.
6. **Language search**: catalog entries carry normalized language codes (`ar`,
   `pt-BR`); the import UI lets users search/filter entries by language. The record's
   first language doubles as the Whisper default language when no explicit option is set.

## Data model

```kotlin
enum class ModelFamily { TRANSDUCER, WHISPER, CTC, SENSE_VOICE }
```

`ExternalModelRecord` gains `options: Map<String, String>` (serialized as a flat JSON
object; absent key/object for legacy records, decodes to empty map). No other field
changes. `family` stays mandatory in the record JSON; backward compatibility applies
to catalog entry-JSON only, where a missing `family` defaults to TRANSDUCER.

## The family support table

New file `transcription/ModelFamilySupport.kt`:

```kotlin
sealed interface ModelFamilySupport {
    val family: ModelFamily
    fun requiredRoles(): List<String>                                  // canonical file names
    fun buildCopyPlan(files: List<String>): Map<String, String>?       // canonical -> source
    fun metadataFileRole(): String                                     // canonical file the metadata check reads
    fun metadataKeys(modelType: String): List<String>                  // pre-native validation
    fun validateImportedModel(file: File)                              // value-aware check, default no-op
    fun buildModelConfig(record: ExternalModelRecord, numThreads: Int,
                         provider: String): OfflineModelConfig

    companion object { fun forFamily(family: ModelFamily): ModelFamilySupport }
}
```

- `TransducerSupport`: extracts today's `buildCopyPlan` keyword logic (including the
  joiner/"joint" and rnnt-hinted-tokens matching) and
  `SherpaOnnxBackend.requiredTransducerMetadataKeys`. Record `modelType` values:
  `nemo_transducer`, `conformer_transducer`, or empty, passed straight through to
  `OfflineModelConfig.modelType` as today.
- `WhisperSupport`: roles encoder + decoder (+ tokens); no joiner. Structural
  discriminator: a joiner/joint file among the role-matched candidate pool is
  rejected as "looks like a transducer" even on the degraded-validation path, so a
  family mismatch can never pass import and surface as a runtime exit(255). The
  check inspects only the files that entered role matching, not every file in the
  picked SAF folder (a parent directory holding several models is legitimate).
  Builds `OfflineWhisperModelConfig(encoder, decoder, language, task)` with
  `language = options["whisper.language"] ?: record.languages.firstOrNull() ?: ""`
  (empty string as the auto-detect sentinel; the desktop validation confirmed sherpa
  performs zero language validation, so the app's default chain is the only guard).
  `tailPaddings` stays at its sherpa default; the engine's 1s silence pad remains
  the single tuning point, so the two mechanisms are never independently adjusted.
  Record `modelType`: empty; `OfflineModelConfig.modelType` = `"whisper"`.
- `CtcSupport`: roles encoder + tokens. Token selection is the mirror of the
  transducer matcher: ctc-hinted candidates first, rnnt-free fallback (the existing
  rnnt-first bias would pick the RNNT vocab for a GigaAM CTC import, since that repo
  ships both variants). Same structural discriminator as Whisper: joiner/joint files
  or rnnt-hinted encoders/vocabs in the candidate pool are rejected as transducer
  files (rnnt-hinted files are deprioritized, not rejected, so a parent folder
  holding several models still imports; the joiner rule is what rejects a full
  transducer set). `modelType` selects the sherpa subtype: `nemo_ctc` →
  `OfflineNemoEncDecCtcModelConfig`, zipformer-style →
  `OfflineZipformerCtcModelConfig` (fed by `OfflineModelConfig.modelType`), unknown
  → import-time error naming valid values.
- `SenseVoiceSupport`: roles model + tokens; metadata validation reads the model
  file (`metadataFileRole()` = model.onnx, since this family has no encoder). Builds
  `OfflineSenseVoiceModelConfig` with optional language and ITN from options.
  Record `modelType`: empty; `OfflineModelConfig.modelType` = `"sense_voice"`.

Metadata keys per family are derived by inspecting the real ONNX files (script under
`eval/`, sherpa-onnx Python environment already present) and documented in the file.

## Importer changes

`ExternalModelImporter`:

- `importFromTreeUri`/`importFromDirectory`/`importFromHuggingFaceRepo` gain a
  `family: ModelFamily = ModelFamily.TRANSDUCER` parameter; copy planning and error
  messages ("missing encoder/tokens for CTC family; found: …") come from the support.
- `registerImported` validates metadata via `support.metadataKeys(modelType)` on
  `support.metadataFileRole()` instead of the transducer-only helper. The dedupe
  update path extends to the new fields: a same-hash re-import refreshes `family`,
  `options`, and `languages` on the existing record (otherwise changing
  `whisper.language` via re-import would log "deduped" while silently keeping the
  old value).
- **ONNX split files (AC #9)**: a sibling `*.onnx.data` (and similar external-data
  sidecars) of any planned `.onnx` file is added to the copy plan as an extra
  non-role entry, copied next to the canonical destination keeping its source base
  name so sherpa resolves it by co-location. In URL imports it becomes an extra
  `DownloadTriple` (pin optional for plain files, LFS sha256 when present).
- `importFromEntryJson`: `family` from the entry with TRANSDUCER default; optional
  `options` object and mandatory-for-new-entries `languages` (normalized codes)
  flow into the record.
- Dedupe, disk pre-flight, clean-replace, cleanup-on-failure: unchanged.

## Engine changes (`ExternalSherpaBackend`)

`initialize` builds the model config through
`ModelFamilySupport.forFamily(record.family).buildModelConfig(...)`; the pre-flight
file/metadata checks use the same `requiredRoles()`/`metadataKeys()` (single
definition shared with the importer). `transcribeAudio`, `unload`, the `external:`
identity contract, and manager routing are unchanged: all four families are offline
single-pass (`maxChunkDurationSeconds` stays null), and sherpa's stream/decode API is
family-agnostic. The existing 1s silence padding stays for all families.

## UI and catalog

- The architecture selector becomes a family dropdown with readable labels and a
  help line listing expected files per family. `modelType` is derived from the family
  except for CTC, where a small text override picks the subtype.
- Conditional options panel: Whisper → optional language (default auto/first record
  language); SenseVoice → optional language + ITN toggle. All new strings in
  `strings.xml` (en + it).
- Catalog/entry search by language: import-by-URL/catalog UI offers a free-text
  search over displayName and `languages` (so "arabic" or "ar" finds the Arabic
  Whisper entry). SAF-folder import gains an optional languages field stored on the
  record.
- New catalog entry `arabic.json`: OpenVoiceOS
  `whisper-large-v3-turbo-arabic-dialectal-v2-onnx`, `family: "WHISPER"`,
  `languages: ["ar"]`, encoder and decoder int8 files plus their `.onnx.data`
  sidecars listed as separate files.
- `docs/external-models.md`: table of family → expected files → options → examples.

## UI style conformance (binding, not best-effort)

Every new or modified UI section (family dropdown, options panel, catalog search,
language field) must match the visual style of the pre-existing import/model screens
exactly: same nesting of containers (no extra nested Boxes/Columns that existing rows
don't have), same spacing values and paddings (reuse the screen's existing spacing
tokens, not new literals), same alignment patterns, and the same icon usage rules
(icons only where sibling rows use them, same icon size/tint). This is enforced, not
remembered:

1. The implementation plan carries an explicit task step "UI conformance pass" that
   diffs each new Composable against its closest existing sibling (by reading the
   sibling's source), before any device test.
2. The code-review checklist for this feature includes a UI-conformance item
   (nested containers, spacing literals, alignment, icons) and the reviewer must
   cite the sibling screen each new element was compared against.
3. Device verification uses screenshots compared against the existing screens (the
   project rule: certify with pixels, never by eye); the family dropdown and options
   panel must be visually indistinguishable in style from the current import UI.

## Testing

- JVM unit tests: per-family copy plans (Whisper set without joiner accepted,
  transducer files rejected as Whisper and vice versa), `.onnx.data` co-planning,
  entry-JSON family default, options round-trip, `buildModelConfig` subtype selection,
  unknown CTC modelType rejection, metadata keys per family.
- Desktop validation (`eval/`, sherpa-onnx 1.13.3+ Python): load GigaAM v3 CTC and
  the Arabic Whisper encoder/decoder with the intended configs BEFORE app-side
  implementation; these runs also produce the per-family metadata keys, confirm
  `.onnx.data` resolution under renamed canonical files, and verify the Whisper
  language-default behavior (which sentinel `language` value the loader accepts,
  and what the UI's "auto" maps to).
- Device: SAF import of the Arabic Whisper model (~1.3GB, existing disk pre-flight)
  and transcription of an Arabic voice message; GigaAM CTC as the small case.
- MMS 1B-all: evaluation documented in the task; if the sherpa CTC config cannot load
  it (adapters), the outcome is recorded and support is not forced.

## Risks and mitigations

1. OpenVoiceOS exports may lack sherpa-standard ONNX metadata → degrade to a
   documented structural check for that family (explicit, not silent), which must
   still include a family discriminator (see the WhisperSupport joiner rule).
2. Renaming split-file ONNX may break external-data references → verified on desktop
   before app implementation; fallback is keeping the source base name for the whole
   family's files.
3. Free-form CTC modelType from users → import-time readable error, never exit(255).
4. NNAPI on int8 Whisper models is untested on-device → existing crash-recovery
   fallback to CPU (issue #26) already covers it; no extra work.

## Out of scope

MMS adapter handling (beyond the documented evaluation), the other 13 sherpa
families, Whisper `translate` task UI, per-model runtime option editing after import.
