# Scout Report: Google AI Edge Gallery (v1.0.15 → v1.0.18 follow-up)

**Date:** 2026-08-23
**Trigger:** Periodic re-check since the 2026-05-19 report (v1.0.14)
**Scope:** Transferable ideas only; core-ASR comparison unchanged (dedicated ASR still wins, see previous report)

---

## What changed (May → Aug 2026)

- **1.0.15 (May 21)**: MCP tool-aware default prompts (agent plumbing, not relevant).
- **1.0.16 (Jun 23)**: **Import and manage litert-lm models from Hugging Face.**
- **1.0.17 (Aug 3)**: More locales (Canada/Brazil), **in-app Feedback Collector** on model responses, ToS/analytics disclosure localization.
- **1.0.18 (Aug 10)**: Multimodal chat (images + audio), **HF import via model-card URLs**, per-commit: allowlist-gated model catalog, HF API model-size fetch, NPU `uses-feature` manifest declaration, session-resume reset after interruption, model-capability-driven UI gating.

## Relevance for Anti-Vocale

| Their feature | Our state | Verdict |
|---|---|---|
| HF litert-lm import (1.0.16/18) | We already have: curated Gemma list + HF auth + `.litertlm` manual import + external URL import | Validated, already ahead. Gap: they accept ANY HF model-card URL for litert-lm; our Gemma list is curated and the external platform imports sherpa-family, not litert-lm. **Idea A below.** |
| Allowlist gating of importable models | Curated catalog + open external platform | Our open approach is a feature (power users); their allowlist protects UX. Keep ours. |
| HF API model-size fetch | External importer already HEADs Content-Length and sums sizes | Already covered (ExternalModelImporter.kt:370,441). |
| Feedback Collector (1.0.17) | In-app email feedback (#34) exists | **Idea B below**: they collect instant per-response feedback; ours is app-level only. |
| NPU `uses-feature` declaration | We declare NO uses-feature | **Idea C**: hygiene item. |
| Session-resume reset | Interrupted-task sweep (TASK-336) | Same problem class, we solved it. |
| Capability-driven UI gating | ModelInfoProvider drives labels/limits | Same pattern, already have it. |
| Multimodal chat | Out of scope | No. |
| Locale expansion | 10 locales shipped in 1.11 | Ahead. |

## Transferable ideas

- **A. Generic litert-lm import via HF URL** (from 1.0.16/1.0.18): extend the Gemma section (or the external platform) to accept a Hugging Face model-card URL for any `.litertlm` asset, with size display from the HF API before download. Medium effort: importer plumbing exists (URL import, HF auth); new piece is litert-lm as an external family and validation of the file.
- **B. Per-transcription quick feedback** (from the Feedback Collector): a small action on result entries ("Bad transcript?") that pre-fills the existing feedback email with task id, model, duration and (truncated) transcript. Cheap: reuses #34 plumbing, gives us quality signal per model/locale. Decide telemetry policy first (email-only = no privacy review).
- **C. NPU `uses-feature` declaration**: `<uses-feature android:name="android.hardware.ai_npu" android:required="false"/>` (or the exact name they use; verify at implementation). Play-metadata hygiene for an app using NNAPI; required=false so no filtering impact.

## Non-ideas (recorded to avoid re-litigating)

- LLM-based transcription core: still 30s-capped, slower, bigger; our sherpa pipeline remains superior for the use case (previous report's verdict unchanged).
- MCP/agent skills, chat multimodality: different product.

## Sources

- Releases: https://github.com/google-ai-edge/gallery/releases (1.0.15-1.0.18)
- Commit scan since 2026-05-20 (api/repos/google-ai-edge/gallery/commits)
