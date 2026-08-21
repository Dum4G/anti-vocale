# F-Droid 1.10.0 reproducibility failure: NDK mismatch (2026-08-21)

## Summary

The v1.10.0 reference APKs published in the GitHub release were built with a
different NDK than the one F-Droid's verifier uses, so every native library and
classes.dex differed byte-for-byte. Root cause found and fixed in
`.github/workflows/android-release.yml`; reference rebuild dispatched.

## Timeline

| When (UTC) | Event |
|---|---|
| Aug 18 19:54 | v1.10.0 release published; first reference APKs built |
| Aug 19 06:38 | checkupdates-bot opens fdroiddata MR !46215 ("Update to 374") |
| Aug 20 12:25 | fdroid-data-mirror av1100-slim updated ("recipe 1.10.0", srclib pin 142807252687d81b40d6315f23470a1512a00de3) |
| Aug 20 15:21 | reference APKs rebuilt (run 32382898656) and uploaded; still wrong (this doc's root cause) |
| Aug 21 07:56 | licaon-kter pushes 49334a82 on !46215 (srclib pin unified to 1428072526, `sdkmanager 'ndk;27.2.12479018'` line removed) |
| Aug 21 08:06 | fdroiddata job 16025936045 fails verification: classes.dex, libllm_inference_engine_jni.so, libonnxruntime.so, libsherpa-onnx-jni.so all differ (371) |
| Aug 21 08:17 | licaon-kter pings @paoloantinori ("what did I do wrong now?") |
| Aug 21 09:30 | Root cause identified in both job logs; workflow fixed; rebuild dispatched |

## Root cause (evidence)

- fdroiddata job 16025936045: `Set up NDK r27c (27.2.12479018)`. fdroidserver
  downloads android-ndk-r27c-linux.zip itself when the recipe says `ndk: r27c`.
- Our reference run 32382898656: `Set up NDK r27 (27.0.12077973)`. The job
  preinstalled `ndk;27.0.12077973`, which shadowed fdroid's own NDK setup, so
  the whole build used a different toolchain.
- Result: every `.so` compiled/stripped differently, and classes.dex differed
  too (the sherpa-onnx AAR's Java classes are dexed from the srclib build).

The workflow's old comment claimed fdroiddata CI "effectively resolved r27
(27.0.12077973)". That was true of the earlier builds it was written from, but
today's fdroiddata runner demonstrably resolves r27c (27.2.12479018). Never
pin a toolchain to match an observed resolution: match the resolution path
(let fdroidserver set it up), so it cannot drift.

## Fix

Commit on main (2026-08-21): remove the NDK preinstall from the
`reproducible-fdroid` job; fdroidserver now performs the same
"Set up NDK r27c" it does on the fdroiddata runners. Rebuild dispatched as
workflow_dispatch tag=v1.10.0 (run 32468367032).

## Collateral lessons (process)

- Our manually-opened MR !46398 duplicated the bot's !46215 and was closed by
  licaon-kter; before opening an update MR, check
  `https://gitlab.com/fdroid/fdroiddata/-/merge_requests?search=antivocale`.
- The `[ci skip]` we added (based on the wrong belief that fdroiddata CI is
  limited to 1h) prevented the maintainers from running CI on our MR. Their
  runners go up to 4h. Verify a limit before coding around it.
- The srclib pin in `.sherpa-version` (3dc7c569) and the recipe's pin
  (1428072526) are two different commits of the same sherpa-onnx v1.13.5;
  the recipe's is authoritative for reproducibility.

## Open

- After the reference rebuild completes and assets are re-uploaded, reply on
  !46215 so licaon-kter can re-run the verification.
