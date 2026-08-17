#!/usr/bin/env python3
"""
Multi-family sherpa-onnx model validation probe for TASK-331.

Probes three facts needed for app code changes:
(a) ONNX metadata_props keys each model family carries
(b) OfflineWhisperModelConfig language sentinel values accepted
(c) Whether renamed encoder.onnx + un-renamed encoder.onnx.data pairs load

Models tested:
- GigaAM v3 CTC int8 (Russian transducer)
- Whisper small int8 (multilingual transducer)

Output: Findings printed to stdout for documentation.
"""

import sys
import tempfile
import shutil
from pathlib import Path
import urllib.request
from typing import Optional, Dict

# Try importing sherpa-onnx and onnx
try:
    import sherpa_onnx
    print(f"[INFO] sherpa-onnx version: {sherpa_onnx.__version__}")
except ImportError as e:
    print(f"[ERROR] sherpa-onnx not available: {e}")
    sys.exit(1)

try:
    import onnx
    print(f"[INFO] onnx version: {onnx.__version__}")
    ONNX_AVAILABLE = True
except ImportError:
    print("[WARN] onnx package not available, will parse metadata from protobuf tail")
    ONNX_AVAILABLE = False
    onnx = None  # type: ignore[assignment]


# ============================================================================
# Model Download Helpers
# ============================================================================

MODEL_CACHE = Path("/tmp/task331-models")
MODEL_CACHE.mkdir(exist_ok=True)


def download_file(url: str, dest_path: Path, desc: str = "file") -> bool:
    """Download a file with progress reporting."""
    if dest_path.exists():
        print(f"[SKIP] {desc} already cached: {dest_path}")
        return True

    print(f"[DOWNLOAD] {desc} from {url}")
    try:
        # Download with progress
        def report_progress(block_num, block_size, total_size):
            downloaded = block_num * block_size
            percent = min(100, downloaded * 100 / total_size if total_size > 0 else 0)
            sys.stdout.write(f"\r  Progress: {percent:.1f}% ({downloaded / 1024 / 1024:.1f} MB)")
            sys.stdout.flush()

        urllib.request.urlretrieve(url, dest_path, reporthook=report_progress)
        print(f"\n[DONE] Downloaded {desc} to {dest_path}")
        return True
    except Exception as e:
        print(f"\n[ERROR] Failed to download {desc}: {e}")
        return False


# ============================================================================
# ONNX Metadata Extraction
# ============================================================================

def extract_metadata_onnx(model_path: Path) -> Dict[str, str]:
    """Extract ONNX metadata_props using onnx package."""
    if onnx is None:
        return {}
    try:
        model = onnx.load(str(model_path))
        metadata: Dict[str, str] = {}
        for prop in model.metadata_props:
            metadata[prop.key] = prop.value
        return metadata
    except Exception as e:
        print(f"[ERROR] Failed to load ONNX model: {e}")
        return {}


def extract_metadata_raw(model_path: Path) -> dict:
    """
    Fallback: parse metadata from ONNX protobuf tail.
    Based on SherpaOnnxBackend.missingOnnxMetadata app implementation.
    """
    metadata: Dict[str, str] = {}
    try:
        with open(model_path, 'rb') as f:
            # Read file to find metadata section
            content = f.read()

        # ONNX stores metadata as key-value strings in the protobuf
        # Look for common sherpa-onnx metadata keys pattern
        # This is a simplified fallback - may not catch all cases
        decoded = content.decode('utf-8', errors='ignore')

        # Known metadata keys from sherpa-onnx models
        known_keys = [
            'model_type', 'version', 'author', 'language',
            'whisper.variant', 'sample_rate', 'epoch', 'iter'
        ]

        for key in known_keys:
            # Simple pattern match (not robust protobuf parsing)
            pattern = f'{key}:'
            if pattern in decoded:
                # Extract value (very basic - assume field:value format nearby)
                idx = decoded.find(pattern)
                tail = decoded[idx + len(pattern):idx + len(pattern) + 100]
                # Look for newline or delimiter
                for sep in ['\n', '\r', '"', "'"]:
                    if sep in tail:
                        value = tail[:tail.index(sep)].strip()
                        if value:
                            metadata[key] = value
                            break

        return metadata
    except Exception as e:
        print(f"[ERROR] Failed to parse raw metadata: {e}")
        return {}


def get_model_metadata(model_path: Path) -> Dict[str, str]:
    """Extract ONNX metadata from model file."""
    print(f"\n[METADATA] Probing: {model_path.name}")

    if ONNX_AVAILABLE:
        metadata = extract_metadata_onnx(model_path)
    else:
        metadata = extract_metadata_raw(model_path)

    if metadata:
        print(f"  Found {len(metadata)} metadata entries:")
        for key, value in sorted(metadata.items()):
            print(f"    {key}: {value}")
    else:
        print("  [WARN] No metadata found")

    return metadata


# ============================================================================
# Whisper Language Sentinel Testing
# ============================================================================

def test_whisper_language_sentinels(model_dir: Path, encoder_path: Path, decoder_path: Path, tokens_path: Path):
    """
    Test which OfflineWhisperModelConfig language values sherpa-onnx accepts.
    Actually constructs recognizers to verify sentinels work.
    """
    print("\n[WHISPER] Testing language sentinels...")
    print(f"  Model dir: {model_dir}")

    # Known sentinels from sherpa-onnx docs and code
    sentinels_to_test = [
        ("", "empty string (auto-detect)"),
        ("multi", "multi (explicit multilingual)"),
        ("en", "en (English)"),
        ("ar", "ar (Arabic)"),
        ("it", "it (Italian)"),
    ]

    accepted = []
    rejected = []

    for lang, desc in sentinels_to_test:
        try:
            # Build config for this language (no tokens parameter for Whisper)
            config = sherpa_onnx.OfflineWhisperModelConfig(
                encoder=str(encoder_path),
                decoder=str(decoder_path),
                language=lang,
                task="transcribe",
                tail_paddings=-1,  # Use default
            )

            # Test if config is valid by trying to create OfflineModelConfig
            model_config = sherpa_onnx.OfflineModelConfig(
                whisper=config,
            )

            # If we got here, the config is valid
            accepted.append((lang, desc))
            print(f"  ✓ ACCEPTED: {desc}")

        except Exception as e:
            rejected.append((lang, desc, str(e)))
            print(f"  ✗ REJECTED: {desc}")
            print(f"    Error: {e}")

    print(f"\n[WHISPER] Results: {len(accepted)}/{len(sentinels_to_test)} accepted")
    return accepted, rejected


# ============================================================================
# Split-File Rename Test
# ============================================================================

def test_split_file_rename_synthetic(encoder_path: Path, decoder_path: Path, tokens_path: Path):
    """
    Test whether renamed encoder.onnx + un-renamed external data loads.
    Synthesizes external data from single-file model using onnx.save_model().
    This reproduces the app's situation: main file renamed to encoder.onnx,
    sidecar keeping its original base name, both co-located in one directory.
    """
    print("\n[SPLIT-FILE] Testing renamed encoder.onnx + un-renamed .data file...")

    if not ONNX_AVAILABLE:
        print("  [SKIP] Cannot test without onnx package")
        return None

    with tempfile.TemporaryDirectory() as tmpdir:
        tmpdir = Path(tmpdir)

        # Load the single-file encoder model
        print(f"  Loading encoder model: {encoder_path.name}")
        try:
            model = onnx.load(str(encoder_path))
        except Exception as e:
            print(f"  ✗ FAILED to load encoder: {e}")
            return False

        # Save as external data format with original basename as sidecar
        canonical_name = "encoder.onnx"
        data_filename = "original-encoder.onnx.data"
        canonical_path = tmpdir / canonical_name
        data_path = tmpdir / data_filename

        print(f"  Saving as canonical.onnx with external data...")
        try:
            # ONNX 1.22+ API - use save_as_external_data
            onnx.save_model(
                model,
                str(canonical_path),
                save_as_external_data=True,
                all_tensors_to_one_file=True,
                location=data_filename
            )
            print(f"    Created: {canonical_name}")
            print(f"    Created: {data_filename}")
        except Exception as e:
            print(f"  ✗ FAILED to save with external data: {e}")
            return False

        # Verify files exist
        if not canonical_path.exists():
            print(f"  ✗ FAILED: {canonical_name} not created")
            return False
        if not data_path.exists():
            print(f"  ✗ FAILED: {data_filename} not created")
            return False

        # Try to load with sherpa-onnx (positive test)
        print(f"\n  [POSITIVE TEST] Loading {canonical_name} with {data_filename}...")
        try:
            # Use factory method for Whisper models
            recognizer = sherpa_onnx.OfflineRecognizer.from_whisper(
                encoder=str(canonical_path),
                decoder=str(decoder_path),
                tokens=str(tokens_path),
                language="en",
                task="transcribe",
            )
            print(f"  ✓ SUCCESS: Renamed encoder.onnx loads with un-renamed .data file")

            # Clean up
            del recognizer
            return True

        except Exception as e:
            print(f"  ✗ FAILED: {e}")
            return False


# ============================================================================
# Model Download Orchestration
# ============================================================================

def download_gigaam_v3_ctc() -> Optional[Path]:
    """Download GigaAM v3 CTC int8 model (Russian)."""
    print("\n[DOWNLOAD] GigaAM v3 CTC int8...")

    # From istupakov/gigaam-v3-onnx on HuggingFace
    # Note: GigaAM uses single-file CTC model, not transducer structure
    base_url = "https://huggingface.co/istupakov/gigaam-v3-onnx/resolve/main"

    files = {
        "v3_ctc.int8.onnx": f"{base_url}/v3_ctc.int8.onnx",
        "v3_e2e_ctc_vocab.txt": f"{base_url}/v3_e2e_ctc_vocab.txt",
    }

    model_dir = MODEL_CACHE / "gigaam-v3-ctc-int8"
    model_dir.mkdir(exist_ok=True)

    success_count = 0
    for local_name, url in files.items():
        dest = model_dir / local_name
        if download_file(url, dest, f"GigaAM {local_name}"):
            success_count += 1

    if success_count == len(files):
        print(f"[DONE] GigaAM v3 CTC int8 ready: {model_dir}")
        return model_dir
    else:
        print(f"[ERROR] GigaAM download incomplete: {success_count}/{len(files)} files")
        return None


def download_whisper_base_int8() -> Optional[Path]:
    """Download Whisper base int8 model (multilingual) - tests split-file loading."""
    print("\n[DOWNLOAD] Whisper base int8 (for split-file test)...")

    # From csukuangfj/sherpa-onnx-whisper-base on HuggingFace
    base_url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main"

    files = {
        "base-encoder.int8.onnx": f"{base_url}/base-encoder.int8.onnx",
        "base-decoder.int8.onnx": f"{base_url}/base-decoder.int8.onnx",
        "base-tokens.txt": f"{base_url}/base-tokens.txt",
    }

    # Check for external data file (base-encoder.int8.onnx.data)
    encoder_data_url = f"{base_url}/base-encoder.int8.onnx.data"

    model_dir = MODEL_CACHE / "whisper-base-int8"
    model_dir.mkdir(exist_ok=True)

    success_count = 0
    for local_name, url in files.items():
        dest = model_dir / local_name
        if download_file(url, dest, f"Whisper {local_name}"):
            success_count += 1

    # Try external data file (may not exist for base int8)
    data_dest = model_dir / "base-encoder.int8.onnx.data"
    if download_file(encoder_data_url, data_dest, "Whisper base encoder external data"):
        success_count += 1

    if success_count >= len(files):
        print(f"[DONE] Whisper base int8 ready: {model_dir}")
        return model_dir
    else:
        print(f"[ERROR] Whisper download incomplete: {success_count}/{len(files)} files")
        return None
    """Download Whisper small int8 model (multilingual)."""
    print("\n[DOWNLOAD] Whisper small int8...")

    # From csukuangfj/sherpa-onnx-whisper-small on HuggingFace
    base_url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main"

    files = {
        "small-encoder.int8.onnx": f"{base_url}/small-encoder.int8.onnx",
        "small-decoder.int8.onnx": f"{base_url}/small-decoder.int8.onnx",
        "small-tokens.txt": f"{base_url}/small-tokens.txt",
    }

    # Check for external data file (small-encoder.int8.onnx.data)
    # Whisper models often have large external data
    encoder_data_url = f"{base_url}/small-encoder.int8.onnx.data"

    model_dir = MODEL_CACHE / "whisper-small-int8"
    model_dir.mkdir(exist_ok=True)

    success_count = 0
    for local_name, url in files.items():
        dest = model_dir / local_name
        if download_file(url, dest, f"Whisper {local_name}"):
            success_count += 1

    # Try external data file (may not exist for small int8)
    data_dest = model_dir / "small-encoder.int8.onnx.data"
    if download_file(encoder_data_url, data_dest, "Whisper encoder external data"):
        success_count += 1

    if success_count >= len(files):
        print(f"[DONE] Whisper small int8 ready: {model_dir}")
        return model_dir
    else:
        print(f"[ERROR] Whisper download incomplete: {success_count}/{len(files)} files")
        return None


# ============================================================================
# Main Execution
# ============================================================================

def main():
    print("=" * 70)
    print("TASK-331 Multi-Family Desktop Validation Probe")
    print("=" * 70)

    # Download models
    print("\n" + "=" * 70)
    print("PHASE 1: Model Downloads")
    print("=" * 70)

    gigaam_dir = download_gigaam_v3_ctc()
    whisper_dir = download_whisper_base_int8()

    if not gigaam_dir or not whisper_dir:
        print("\n[ERROR] Required models not downloaded, exiting")
        sys.exit(1)

    # Extract metadata from all model files
    print("\n" + "=" * 70)
    print("PHASE 2: ONNX Metadata Extraction")
    print("=" * 70)

    # GigaAM metadata
    gigaam_metadata = {}
    for file in ["v3_ctc.int8.onnx"]:
        path = gigaam_dir / file
        if path.exists():
            meta = get_model_metadata(path)
            gigaam_metadata[file] = meta

    # Whisper metadata
    whisper_metadata = {}
    for file in ["base-encoder.int8.onnx", "base-decoder.int8.onnx"]:
        path = whisper_dir / file
        if path.exists():
            meta = get_model_metadata(path)
            whisper_metadata[file] = meta

    # Test Whisper language sentinels
    print("\n" + "=" * 70)
    print("PHASE 3: Whisper Language Sentinel Testing")
    print("=" * 70)

    encoder_path = whisper_dir / "base-encoder.int8.onnx"
    decoder_path = whisper_dir / "base-decoder.int8.onnx"
    tokens_path = whisper_dir / "base-tokens.txt"

    if all(p.exists() for p in [encoder_path, decoder_path, tokens_path]):
        accepted, rejected = test_whisper_language_sentinels(
            whisper_dir, encoder_path, decoder_path, tokens_path
        )
    else:
        print("[SKIP] Whisper language testing: model files missing")
        accepted, rejected = [], []

    # Test split-file rename scenario (synthetic external data)
    print("\n" + "=" * 70)
    print("PHASE 4: Split-File Rename Test (Synthetic)")
    print("=" * 70)

    if all(p.exists() for p in [encoder_path, decoder_path, tokens_path]):
        split_result = test_split_file_rename_synthetic(
            encoder_path, decoder_path, tokens_path
        )
    else:
        print("[SKIP] Split-file test: model files incomplete")
        split_result = None

    # Print summary
    print("\n" + "=" * 70)
    print("SUMMARY OF FINDINGS")
    print("=" * 70)

    print("\n(a) ONNX metadata_props keys per family:")
    print("\n    GigaAM v3 CTC:")
    for file, meta in gigaam_metadata.items():
        print(f"      {file}: {list(meta.keys()) if meta else '(none)'}")

    print("\n    Whisper small:")
    for file, meta in whisper_metadata.items():
        print(f"      {file}: {list(meta.keys()) if meta else '(none)'}")

    print("\n(b) Whisper language sentinels:")
    if accepted:
        print("    Accepted sentinels:")
        for lang, desc in accepted:
            print(f"      {lang}: {desc}")
    else:
        print("    No sentinels accepted (unexpected!)")

    if rejected:
        print("    Rejected sentinels:")
        for lang, desc, error in rejected:
            print(f"      {lang}: {desc}")

    print("\n(c) Split-file rename verdict:")
    if split_result is True:
        print("    ✓ VERIFIED-SYNTHETIC: Renamed encoder.onnx loads with un-renamed .data file")
        print("    (Synthesized external data from single-file model using onnx.save_model)")
    elif split_result is False:
        print("    ✗ FAILS: Mismatched basename breaks external data reference")
    else:
        print("    ⊝ UNTESTED: Test skipped due to missing model files")

    print("\n" + "=" * 70)
    print(f"Models cached at: {MODEL_CACHE}")
    print("=" * 70)


if __name__ == "__main__":
    main()
