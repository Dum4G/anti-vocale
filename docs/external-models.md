# External Models

Anti-Vocale supports importing user-provided sherpa-onnx transducer models alongside the built-in backends. This document describes the import formats and how to share models with other users.

## Import sources

### 1. Folder import (SAF)

Pick a directory containing the four required files (exact names don't matter, roles are matched by keyword):

| Role | Matches | Example |
|---|---|---|
| Encoder | any `.onnx` containing `encoder` | `gigaam_v3_e2e_rnnt_encoder_int8.onnx` |
| Decoder | any `.onnx` containing `decoder` | `decoder.int8.onnx` |
| Joiner | any `.onnx` containing `joiner` or `joint` | `joiner.int8.onnx` |
| Tokens | `tokens.txt`, `vocab.txt`, or any `.txt` containing `tokens`/`vocab` (preferring `rnnt`-hinted, non-`ctc`) | `tokens.txt` |

Files are copied to app storage under canonical names (`encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx`, `tokens.txt`) and pinned by SHA-256.

### 2. HuggingFace repo URL

Paste a repo URL; the app lists the files via the HF API and downloads them:

```
https://huggingface.co/pantinor/gigaam-v3
https://huggingface.co/istupakov/gigaam-v3-onnx
```

LFS-backed files are verified against the server-side SHA-256. Plain (non-LFS) files get a computed trust-on-first-use pin (marked as unverified; upgraded on re-import).

### 3. Catalog-entry JSON URL

A single-model manifest with integrity pins. This is how third parties share a model:

```json
{
  "name": "GigaAM v3",
  "modelType": "nemo_transducer",
  "languages": ["ru"],
  "files": [
    {
      "name": "gigaam_v3_e2e_rnnt_encoder_int8.onnx",
      "url": "https://huggingface.co/pantinor/gigaam-v3/resolve/main/gigaam_v3_e2e_rnnt_encoder_int8.onnx",
      "sha256": "2cac62d0c270bd128f898f2be1a2d34780d524a6e9483888ebac7b00f97410f1",
      "size": 318995997
    },
    {
      "name": "gigaam_v3_e2e_rnnt_decoder.onnx",
      "url": "https://huggingface.co/pantinor/gigaam-v3/resolve/main/gigaam_v3_e2e_rnnt_decoder.onnx",
      "sha256": "781971998e6a355d6a714f6932a30eab295e7ba0d14fd7e0f78c83b87e811860",
      "size": 4600058
    },
    {
      "name": "gigaam_v3_e2e_rnnt_joint.onnx",
      "url": "https://huggingface.co/pantinor/gigaam-v3/resolve/main/gigaam_v3_e2e_rnnt_joint.onnx",
      "sha256": "602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c",
      "size": 2712896
    },
    {
      "name": "gigaam_v3_e2e_rnnt_tokens.txt",
      "url": "https://huggingface.co/pantinor/gigaam-v3/resolve/main/gigaam_v3_e2e_rnnt_tokens.txt",
      "sha256": "7ddf22514c42c531358182c81446a8159771e9921019f09ae743ea622d40221d",
      "size": 13353
    }
  ]
}
```

#### Schema

| Field | Required | Description |
|---|---|---|
| `name` | yes | Display name shown in the Model tab |
| `modelType` | no (default `nemo_transducer`) | sherpa architecture: `nemo_transducer`, `""` (zipformer), or `conformer_transducer` |
| `languages` | no | ISO codes for display (`["ru"]`) |
| `files` | yes | Array, one entry per file |
| `files[].name` | yes | Source file name (role-matched by keyword) |
| `files[].url` | yes | Direct download URL (must support HTTP Range for resume) |
| `files[].sha256` | yes | 64-hex SHA-256 pin; **hashless entries are rejected** |
| `files[].size` | yes | Size in bytes (feeds the disk pre-flight) |

Host the JSON anywhere reachable (GitHub gist, HF repo, personal site); share the URL.

## Architecture selector

The dropdown above the import buttons sets the sherpa `modelType`:

- **NeMo transducer** (default): covers GigaAM, Parakeet, most NVIDIA NeMo exports. Requires `vocab_size`, `subsampling_factor`, `model_type` metadata in the encoder ONNX.
- **Zipformer transducer**: for zipformer-family models. Requires only `vocab_size`.
- **Conformer transducer**: for conformer-family exports.

A wrong choice fails cleanly at import time (metadata validation) or at first transcription (native crash with no error). If the latter happens, delete and re-import with the other architecture selected.

## Verification

Every import is verified:

- All four files present and complete before registration
- Encoder ONNX metadata checked against the selected architecture's requirements
- SHA-256 pins verified on download (or computed trust-on-first-use for HF plain files)
- Disk space pre-flight before any download or copy

Re-importing the same files (same hashes) updates the existing record instead of creating a duplicate.
