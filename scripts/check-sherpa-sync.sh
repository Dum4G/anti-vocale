#!/usr/bin/env bash
# Check that the three sherpa version sync points are consistent (issue #38).
# Run from anywhere; exits 1 with a message if out of sync.
#
# The three sync points that MUST carry the same sherpa-onnx version:
#   1. .sherpa-version (repo root marker file: tag + srclib commit)
#   2. scripts/fetch-sherpa-aar.sh (SHERPA_ONNX_VERSION variable)
#   3. app/build.gradle.kts (SRCLIB PIN comment)
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
failures=0

# Extract version from each sync point
marker_ver=$(grep -oE 'v[0-9.]+' "$REPO_DIR/.sherpa-version" 2>/dev/null | head -1 || echo "")
script_ver="v$(grep -oE 'SHERPA_ONNX_VERSION="[0-9.]+"' "$REPO_DIR/scripts/fetch-sherpa-aar.sh" 2>/dev/null | grep -oE '[0-9.]+' || echo "")"
gradle_ver=$(grep -oE 'sherpa-onnx v[0-9.]+' "$REPO_DIR/app/build.gradle.kts" 2>/dev/null | grep -oE 'v[0-9.]+' | head -1 || echo "")

if [ -z "$marker_ver" ]; then
    echo "FAIL: .sherpa-version not found or has no version tag"
    failures=$((failures+1))
fi
if [ -z "$script_ver" ] || [ "$script_ver" = "v" ]; then
    echo "FAIL: scripts/fetch-sherpa-aar.sh has no SHERPA_ONNX_VERSION"
    failures=$((failures+1))
fi
if [ -z "$gradle_ver" ]; then
    echo "FAIL: app/build.gradle.kts has no SRCLIB PIN comment with version"
    failures=$((failures+1))
fi

if [ "$failures" -gt 0 ]; then
    echo "SHERPA SYNC CHECK: $failures file(s) missing version info"
    exit 1
fi

if [ "$marker_ver" != "$script_ver" ] || [ "$marker_ver" != "$gradle_ver" ]; then
    echo "FAIL: sherpa version out of sync:"
    echo "  .sherpa-version:          $marker_ver"
    echo "  fetch-sherpa-aar.sh:     $script_ver"
    echo "  build.gradle.kts (PIN):  $gradle_ver"
    echo "Update ALL THREE when bumping sherpa (see CLAUDE.md and docs/BUILD.md)."
    exit 1
fi

echo "OK: sherpa $marker_ver in sync across marker, script, and build file"
