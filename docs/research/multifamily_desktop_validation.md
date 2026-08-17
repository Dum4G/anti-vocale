# Multi-Family Model Import Desktop Validation (TASK-331)

**Date:** 2026-08-17
**Sherpa-onnx version:** 1.13.4
**ONNX version:** 1.22.0
**Purpose:** Validate model detection facts before app code changes

## Summary

Desktop validation probe completed successfully for GigaAM v3 CTC and Whisper base int8 models. All Whisper language sentinels tested construct valid configs (sherpa-onnx performs no language validation). ONNX metadata extraction working with onnx package. Split-file rename test VERIFIED-SYNTHETIC using ONNX API synthesis (canonical names + original basenames work correctly). Bounded residual gap: real OpenVoiceOS production model .data layout will be probed during Task 15 device import.

## (a) ONNX metadata_props Keys Per Family

### GigaAM v3 CTC
- **File:** `v3_ctc.int8.onnx` (224MB)
- **Metadata keys:** `['onnx.infer']`
- **Metadata values:** `{'onnx.infer': 'onnxruntime.quant'}`
- **Notes:** Minimal metadata, only quantization indicator. No model_type or family identifier in metadata.

### Whisper Base
- **Encoder file:** `base-encoder.int8.onnx` (29MB)
- **Decoder file:** `base-decoder.int8.onnx` (130MB)
- **Encoder metadata keys:** `['onnx.infer', 'model_type', 'version', 'maintainer', 'n_mels', 'n_audio_ctx', 'n_audio_state', 'n_audio_head', 'n_audio_layer', 'n_vocab', 'n_text_ctx', 'n_text_state', 'n_text_head', 'n_text_layer', 'sot_sequence', 'all_language_tokens', 'all_language_codes', 'sot', 'sot_index', 'eot', 'blank_id', 'is_multilingual', 'no_speech', 'non_speech_tokens', 'transcribe', 'translate', 'sot_prev', 'sot_lm', 'no_timestamps']`
- **Decoder metadata keys:** `['onnx.infer']`
- **Key metadata values:**
  - `model_type`: `whisper-base`
  - `is_multilingual`: `1`
  - `version`: `1`
  - `onnx.infer`: `onnxruntime.quant`
  - `all_language_codes`: 100 language codes (see `base-encoder.int8.onnx` metadata for full list)
  - `all_language_tokens`: token IDs for each language
- **Notes:** Encoder carries rich metadata including model_type, language support, architecture specs. Decoder has minimal metadata.

## (b) Whisper Language Sentinel Values

### Accepted Sentinels (5/5 tested)
All tested sentinels successfully construct `OfflineWhisperModelConfig` and `OfflineModelConfig`:

1. **Empty string `""`** - Auto-detect language from audio
2. **`"multi"`** - Explicit multilingual mode
3. **`"en"`** - English
4. **`"ar"`** - Arabic
5. **`"it"`** - Italian

**CRITICAL CAVEAT:** Sherpa-onnx 1.13.4 performs ZERO language validation. Garbage strings like `"xxzz"` also construct valid recognizer configs. Sentinel acceptance is NOT evidence of semantic validity - the app must implement its own language validation against supported codes. Sherpa-onnx will silently accept any language value.

### API Construction (Validated)
```python
config = sherpa_onnx.OfflineWhisperModelConfig(
    encoder=str(encoder_path),
    decoder=str(decoder_path),
    language=lang,  # Tested: "", "multi", "en", "ar", "it"
    task="transcribe",
    tail_paddings=-1,  # Default
)

model_config = sherpa_onnx.OfflineModelConfig(
    whisper=config,  # Note: parameter is "whisper", not "whisper_config"
)
```

### Key Findings
- **No tokens parameter** for Whisper models (unlike transducer models)
- **No rejection by sherpa-onnx** - all sentinels construct valid configs (including invalid ones)
- **Empty string accepted** - confirms auto-detect is valid
- **Language codes construct** - individual language codes (en, ar, it) are syntactically valid
- **App-side validation required** - UI/options validation is the only guard against bad values

## (c) Split-File Rename Verdict

### Status: ✓ VERIFIED-SYNTHETIC

**Method:** Synthetic external data generation using ONNX API
**Result:** PASSES - Renamed `encoder.onnx` loads successfully with un-renamed `original-encoder.onnx.data` sidecar

### Test Procedure
1. **Synthesize external data:** Used `onnx.save_model()` to convert single-file Whisper base encoder to external data format:
   ```python
   onnx.save_model(
       model,
       "encoder.onnx",
       save_as_external_data=True,
       all_tensors_to_one_file=True,
       location="original-encoder.onnx.data"
   )
   ```
2. **File structure created:**
   - `encoder.onnx` (renamed canonical name)
   - `original-encoder.onnx.data` (un-renamed sidecar with original basename)
3. **Verification:** Loaded successfully via `sherpa_onnx.OfflineRecognizer.from_whisper(encoder="encoder.onnx", ...)`

### Key Finding
**ONNX external data references resolve relative to the model file location, not by basename.** The app's planned approach of renaming to canonical names (`encoder.onnx`, `decoder.onnx`) while keeping sidecars with original basenames is SAFE and will load correctly.

### Scope Limits
- **Synthetic test only:** Used ONNX 1.22 `save_model()` to create single-tensor external data blob. Real per-tensor external data layouts (as in some OpenVoiceOS models) remain unproven.
- **Runtime proven:** Desktop sherpa-onnx 1.13.4 Python, not the Android AAR. Device validation during Task 15 will confirm Android runtime behavior matches.
- **Bounded residual gap:** Real OpenVoiceOS production model .data layout will be probed during Task 15 device import to confirm synthetic test findings.

### Remaining Gap (Device Validation)
- **OpenVoiceOS production models:** Actual `.data` files from real OpenVoiceOS models have not been tested yet
- **Bounded deferral:** Will probe one real OpenVoiceOS model during Task 15 device import phase
- **Confidence:** HIGH - ONNX spec behavior is consistent across implementations

## Model Sources

### GigaAM v3 CTC int8
- **Repo:** `istupakov/gigaam-v3-onnx` on HuggingFace
- **Files used:**
  - `v3_ctc.int8.onnx` (224MB)
  - `v3_e2e_ctc_vocab.txt` (tokens)
- **URL:** https://huggingface.co/istupakov/gigaam-v3-onnx
- **Revision:** Main branch as of 2026-08-17

### Whisper Base int8
- **Repo:** `csukuangfj/sherpa-onnx-whisper-base` on HuggingFace
- **Files used:**
  - `base-encoder.int8.onnx` (29MB)
  - `base-decoder.int8.onnx` (130MB)
  - `base-tokens.txt` (798KB)
- **URL:** https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base
- **Revision:** Main branch as of 2026-08-17

## Technical Notes

### ONNX Metadata Parsing
- **Primary method:** `onnx.load()` + `model.metadata_props` (requires onnx package)
- **Fallback:** Raw protobuf tail parsing (implemented but not tested/needed)
- **Recommendation:** Keep onnx package dependency for metadata extraction

### Sherpa-onnx API Notes
- `OfflineWhisperModelConfig` constructor signature:
  - Required: `encoder`, `decoder`, `language`, `task`
  - Optional: `tail_paddings` (default -1), `enable_token_timestamps`, `enable_segment_timestamps`
  - **NOT included:** `tokens` parameter (transducer models only)
- `OfflineModelConfig` parameter naming:
  - Use `whisper=...` not `whisper_config=...`

## Action Items for App Implementation

1. **Family detection logic:**
   - Whisper: Check for `model_type` metadata key (starts with "whisper-")
   - GigaAM: No family metadata → detect by filename pattern (`gigaam` in path/filename)
   - Parakeet: Likely similar to GigaAM (minimal metadata)

2. **Language sentinels for Whisper:**
   - Accept empty string, "multi", and any valid language code
   - Validate against model's `all_language_codes` if available
   - Don't validate tokens path for Whisper

3. **External data handling:**
   - Preserve original basenames during import
   - Test on device with actual split-file model
   - Document outcome in device validation phase

## Deferrals

1. **Whisper large-v3-turbo Arabic metadata:** Deferred (model too large for desktop download)
2. **Parakeet family metadata:** Not tested (no Parakeet models downloaded in this spike)
3. **Real OpenVoiceOS .data layout:** Will probe one production OpenVoiceOS model during Task 15 device import phase to confirm synthetic test findings

## Conclusion

Desktop validation successfully established:
- ✓ ONNX metadata extraction working with onnx package
- ✓ Whisper family rich metadata in encoder files (model_type, language support)
- ✓ Whisper language sentinels construct valid configs (sherpa-onnx performs no validation)
- ✓ API signatures for Whisper model config construction
- ✓ **Split-file rename pattern VERIFIED-SYNTHETIC:** Canonical names + original basenames work correctly

**All three required facts (a, b, c) are now complete** and ready for consumption by subsequent app implementation tasks.

**Residual scope limits:**
- Synthetic external data test only (ONNX 1.22 single-tensor blob)
- Desktop sherpa-onnx 1.13.4 Python runtime (not Android AAR)
- Real OpenVoiceOS production model layout deferred to Task 15 device validation
