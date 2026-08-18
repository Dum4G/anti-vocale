# Research Report: verification of the notification teardown race fix (issue #31)

**Date**: 2026-08-19
**Depth**: exhaustive
**Confidence**: HIGH

## Executive Summary

External sources confirm every element of the diagnosed mechanism: untracked coroutines killed by the service-scope cancellation at teardown are a documented coroutine trap; the canonical preventions are exactly the two idioms available (join pending work before `stopSelf`, or post critical notifications under `NonCancellable`); and the parallel "finishes only when I open the app" symptom maps precisely onto AOSP's cached-apps freezer, which freezes a process ~10 seconds after it becomes cached and instantly unfreezes it when the user resumes an Activity. Our fix (tracked jobs + join before teardown) is idiomatic; the two symptoms are likely connected by the same teardown.

## Findings

### 1. The race is a documented bug class; our fix matches canonical patterns (HIGH)

- Kotlin's official cancellation docs define prompt cancellation: a cancelled coroutine stops at the next suspension point, and launching on a scope that gets cancelled drops not-yet-started work (kotlinlang.org, Cancellation and timeouts; NonCancellable API reference).
- The trap is called out by name in practitioner literature: unstructured/untracked launches inside a Service whose scope is cancelled in `onDestroy` is one of the "cancellation traps" (ProAndroidDev, "Coroutine Cancellation Looks Simple Until It Breaks Your App"); lifecycle-aware service articles prescribe scope cancellation in `onDestroy` (ProAndroidDev, lifecycle-aware services) WITHOUT noting that this kills any late launches, which is exactly the foot-gun we hit.
- Canonical preventions found, both legitimate:
  a. **Join/drain pending jobs before `stopSelf`** (structured-concurrency-pure; preserves ordering of autocopy -> save -> notify). This is what cda1353 implements.
  b. **`withContext(NonCancellable)` around the critical posting** (official docs: NonCancellable's typical usage is "ensure cleanup code is executed even if the parent job is cancelled"; practitioner guides restrict it to critical sections like transaction commits/notifications).
- Assessment of our fix: idiomatic. Option (b) alone would still let auto-copy/save be cancelled (acceptable for a notification but loses side effects); the join keeps the whole chain and the teardown ordered. No superior third pattern surfaced.

### 2. Notification posting inside cancellable coroutines (MEDIUM-HIGH)

- No official Android doc mandates NonCancellable for `notify()` specifically; the guidance is indirect: `NotificationManager.notify` is a binder call that can be dropped if the process dies between service teardown and posting, and coroutine best-practice articles uniformly recommend NonCancellable for must-happen side effects at the end of a lifecycle (Kotlin docs + two Android-focused articles).
- WorkManager's long-running worker docs show the alternative architecture (worker-managed foreground service with `setForeground`), where WorkManager owns the lifetime and this class of teardown race does not exist; see finding 4.

### 3. The "only finishes when I open the app" symptom = AOSP cached-apps freezer (HIGH)

- AOSP "Cached apps freezer" (source.android.com): Android 11+ freezes CACHED processes via cgroup; **Android 14+: frozen 10 seconds after entering the cached state**; the system **immediately unfreezes when the user resumes an Activity** (lifecycle event). That is a word-for-word match for "the moment I open the app it finishes the task".
- Connection to our bug: once the teardown race stopped the InferenceService (and with the result notification never posted), the process dropped to cached state and froze 10s later; any work still in flight stalled until an Activity resume unfroze it. The notification fix removes the premature teardown for share requests; if the freezer symptom persists on device despite the fix, the service must be kept alive until transcription truly completes (it already is a foreground service while processing, and a process with a running foreground service is not cached).
- OEM caveat (MEDIUM): ColorOS/Realme belong to the aggressive group; practitioner reports document OEMs killing even foreground services ("Foreground service is a privilege, not a loophole", ProAndroidDev "Beyond Doze: ... OEM realities": custom task killers and background process freezing listed for Xiaomi/Huawei/Realme class devices, mitigations = autostart + no battery restriction). So a residual ColorOS-specific risk exists that no app-side fix fully removes; worth checking the reporter's battery settings if the symptom survives.

### 4. Alternative architecture (for completeness)

- WorkManager long-running workers with `setForegroundAsync` (developer.android.com, "Support for long-running workers") move the foreground-service lifetime into WorkManager. It removes this race class but adds WorkManager constraints/latency to a share-sheet-triggered flow where the user expects immediate processing; not recommended as a replacement now, but the right shape if transcription ever moves to a queue with retries.

## Confidence Assessment

- HIGH: the mechanism (untracked launch killed by onDestroy scope cancel) is real and documented; AOSP freezer explains the "opens when I launch the app" symptom including the 10-second and unfreeze-on-resume details; join-before-stopSelf is an idiomatic fix.
- MEDIUM: ColorOS-specific killing of foreground services cannot be excluded as an additional factor on the reporter's device (practitioner sources, not official).
- Not verifiable externally: whether the fix fully resolves the reporter's case, pending tomorrow's on-device reproduction.

## Sources

1. Kotlin docs, Cancellation and timeouts: https://kotlinlang.org/docs/coroutines-cancellation.html
2. kotlinx.coroutines NonCancellable reference: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-non-cancellable/
3. ProAndroidDev, "Coroutine Cancellation Looks Simple Until It Breaks Your App": https://proandroiddev.com/coroutine-cancellation-looks-simple-until-it-breaks-your-app-the-hidden-traps-every-android-023e0b2b2e26
4. ProAndroidDev, lifecycle-aware services (scope cancel in onDestroy): https://proandroiddev.com/exploring-lifecycle-aware-service-and-firebasemessagingservice-on-android-fcc89a2e9528
5. AOSP, Cached apps freezer: https://source.android.com/docs/core/perf/cached-apps-freezer
6. ProAndroidDev, "Beyond Doze: Building Reliable Background Execution on Modern Android (including OEM realities)": https://proandroiddev.com/beyond-doze-building-reliable-background-execution-on-modern-android-including-oem-realities-5fa0a6e05672
7. Android developers, Support for long-running workers (WorkManager setForeground): https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
8. Android 15 behavior changes (mediaProcessing FGS type, background enforcement): https://developer.android.com/about/versions/15/behavior-changes-15
