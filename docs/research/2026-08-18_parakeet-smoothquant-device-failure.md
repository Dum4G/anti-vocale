# Research Report: Parakeet TDT SmoothQuant "Failed to create OfflineRecognizer" on device

**Date**: 2026-08-18
**Depth**: exhaustive
**Confidence**: MEDIUM-HIGH (mechanism identified with strong circumstantial evidence; final confirmation requires the A/B test on device, listed below)

## Executive Summary

The SmoothQuant variant's failure is almost certainly caused by the **sherpa-onnx 1.13.4 AAR's bundled onnxruntime 1.27.0**, which ships known ARM64-native defects in the quantized-kernel paths (KleidiAI/SME family), and NOT by the PR #28 catalog consolidation and NOT by the model files (they work on host with the same sherpa version on x86). The fix path exists upstream: **sherpa-onnx v1.13.5/1.13.6 bundle ORT 1.27.1** (verified by direct inspection of the released AAR), where the ARM quantized-path defects are fixed. Recommended action: bump the AAR to 1.13.5+ and re-run the device smoke; a same-session A/B with the old 1.13.3 AAR (still in app/libs) provides the decisive confirmation.

## Findings

### 1. The failure signature is native, arch-specific, and quant-format-correlated

- Device: Realme RMX3853 (Dimensity, arm64, Android 16), AAR sherpa-onnx 1.13.4. SmoothQuant encoder = 882MB, **QOperator static quantization** (verified: 217x `QLinearMatMul` + 217x `QuantizeLinear`/`DequantizeLinear`, opset 17, IR 8). Stock int8 variant (dynamic quantization) works on the same device.
- Host x86 (sherpa-onnx Python 1.13.4, CPU) creates the recognizer and transcribes perfectly from the byte-identical files pulled off the device (decode 1.68s for 13.8s audio) [local evidence, checkpoint /tmp/task331-smoke-checkpoint.md].
- So: same files + same sherpa version, different arch + different ORT build. The variable is the **ARM64 onnxruntime 1.27.0** in the AAR.

### 2. sherpa-onnx 1.13.4's ORT 1.27.0 has documented ARM64 quantized-path bugs

- k2-fsa/sherpa-onnx#3791 (open): 1.13.4 broke ARM64 inference paths via the ORT 1.27.0 bump (#3718); root cause isolated to ORT 1.27.0's new KleidiAI **SME** paths (Conv with asymmetric pads admitted to a uniform-pad-only kernel); **fixed in ORT 1.27.1** (microsoft/onnxruntime#28571, cherry-picked as #29628). Confirmed by sherpa collaborator: "We will release a new version with onnxruntime 1.27.1 soon."
- k2-fsa/sherpa-onnx#3754 (open): "Kokoro int8 on Android/ARM: garbage output; fp32 unaffected" - same pattern class: int8-on-Android-ARM broken in the 1.27.0 era.
- k2-fsa/sherpa-onnx#3850 (merged 2026-08-10): "Use onnxruntime 1.27.1 for Linux aarch64 and Android" - the Android build scripts were still on 1.27.0 after macOS had already moved (#3831), i.e. **every sherpa-onnx Android AAR up to and including 1.13.4 ships the defective ORT**.

### 3. Timeline exonerates the catalog consolidation and indicts the AAR bump

- SmoothQuant became the default Parakeet variant on **2026-06-06** (6319de7).
- The AAR was bumped v1.13.3 -> v1.13.4 (ORT 1.24.x-era -> 1.27.0) on **2026-07-16** (230a0c7, TASK-289).
- The model files on the failing device were downloaded Aug 14-15 and had worked in the 1.13.3 era. PR #28's consolidation (merged today) does not touch native libraries and its per-variant config was byte-verified against the old backends in review. The regression window is therefore the AAR bump, with the consolidation coincidentally present when the smoke first exercised SmoothQuant on 1.13.4.

### 4. The fix is available and verified present upstream

- sherpa-onnx **v1.13.5** (2026-08-11) and **v1.13.6** (2026-08-18) include the Android ORT 1.27.1 bump.
- Direct verification (this research): downloaded `sherpa-onnx-1.13.5.aar` from the release; its `jni/arm64-v8a/libonnxruntime.so` strings confirm **1.27.1**.

### 5. Why SmoothQuant fails at session creation while stock int8 works

- QOperator static quant (`QLinearMatMul`) and dynamic quant take **different MLAS ARM64 kernel routes**; ORT 1.27.0 reworked the S8U8 QGEMM routing (see microsoft/onnxruntime#29787 "Route ARM64 S8U8 QGEMM to the UDOT kernel") and the KleidiAI integration work (#25187, #23627). A defect hit during graph partitioning/kernel selection for the QOperator graph is consistent with a session-creation failure, while the dynamic-quant graph avoids the defective route.
- #3791's symptom (wrong results, not creation failure) is a different manifestation of the same 1.27.0 ARM defect family; the report explicitly notes offline Parakeet was not affected *on macOS M4* (SME path), but Android arm64 uses KleidiAI paths that do not exist on the x86 host, so non-reproducibility on host is expected either way.
- Residual uncertainty (MEDIUM confidence on this sub-point): the exact ORT call that throws on device is invisible (ColorOS suppresses native logs). The A/B tests below resolve it empirically without needing the internal stack.

## Confidence Assessment

- HIGH: the failure is not caused by the model files (host proof), not by PR #28 (timeline + review), and the 1.13.4 AAR's ORT 1.27.0 has documented ARM64 quantized-path defects fixed in ORT 1.27.1, which ships in sherpa-onnx >= 1.13.5 (AAR inspected).
- MEDIUM: that the SmoothQuant QOperator graph specifically trips one of those 1.27.0 ARM defects (mechanism inferred, creation-time stack invisible on device).
- Not yet verified: whether 1.13.5 actually fixes THIS device's failure (needs the device test).

## Decisive experiments (when the phone is back), in order

1. **Primary fix attempt**: bump `SHERPA_ONNX_VERSION` to 1.13.5 in scripts/fetch-sherpa-aar.sh, rebuild, install, load SmoothQuant variant, transcribe. Green = fixed; ship the bump in 1.10.0.
2. **Confirmation A/B (if 1 fails or for the record)**: build once with the retained `sherpa-onnx-v1.13.3.aar` (ORT 1.24-era) and the same files. Green = ORT-version regression proven; red = escalate to sherpa-onnx with the 1.13.5 AAR evidence.
3. If both red: capture native failure despite logd (adb bugreport or tombstones under /data/tombstones via run-as/root-free paths) and open a sherpa-onnx issue with the repro bundle.
4. Guardrail regardless of outcome: the fallback already works on device (stock int8); keep auto-fallback as the safety net for 1.10.0 and consider making "stock int8" the default variant until the bump is device-verified.

## Sources

1. k2-fsa/sherpa-onnx#3791 - SME Conv bug in 1.13.4's ORT 1.27.0, fixed in 1.27.1 (https://github.com/k2-fsa/sherpa-onnx/issues/3791)
2. k2-fsa/sherpa-onnx#3850 - "Use onnxruntime 1.27.1 for Linux aarch64 and Android", merged 2026-08-10 (https://github.com/k2-fsa/sherpa-onnx/pull/3850)
3. k2-fsa/sherpa-onnx#3718 - the 1.13.4 ORT 1.24.4->1.27.0 bump (referenced in #3791)
4. k2-fsa/sherpa-onnx#3754 - int8-on-Android-ARM garbage output, fp32 unaffected (https://github.com/k2-fsa/sherpa-onnx/issues/3754)
5. microsoft/onnxruntime#28571 + #29628 - the SME kernel gating fix, in ORT 1.27.1
6. microsoft/onnxruntime#29787 - ARM64 S8U8 QGEMM UDOT kernel routing (context for QOperator path changes)
7. Local evidence: /tmp/task331-smoke-checkpoint.md (device repro + host counter-proof), /tmp/i30-pk-smoothquant (files pulled from device), AAR inspections (1.13.4 arm64 ORT = 1.27.0; 1.13.5 arm64 ORT = 1.27.1)
8. Repo history: 6319de7 (SmoothQuant default, 2026-06-06), 230a0c7 (AAR 1.13.3->1.13.4, 2026-07-16)
