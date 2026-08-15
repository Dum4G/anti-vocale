# Result-Notification Paging (prev/next): Design

Date: 2026-08-15
Task: TASK-327
Status: approved by the user, pre-implementation

## Problem

Android clamps even expanded `BigTextStyle` notifications, so a long completed
transcript (the triggering case was 1073 chars) cannot be read fully from the
notification shade. The user must open the app. The in-progress notification
already supports chunk navigation (TASK-242, `ChunkNavState` in
`InferenceService`), but the completed result notification offers only Copy and
Share plus a static truncated preview with a "100 of 1073 chars" counter that
reads like a pager position but is purely informational.

## Decisions (from the design conversation)

- **Paging unit: character windows.** Word-aligned windows of at most
  `PAGE_CHARS = 400` chars. Chosen over sentence-aligned windows (needs
  punctuation heuristics, degrades on unpunctuated ASR output) and audio chunks
  (chunk state dies with `InferenceService`; single-chunk long transcripts
  would get no paging at all).
- **Approach A: stateless re-post.** The page shown is a pure function of
  (text, pageIndex); no cursor is stored anywhere. The full text travels in the
  nav broadcast intent extras, the same pattern `ACTION_COPY_TRANSCRIPTION`
  already uses. Rejected alternatives: persisted cursor on disk (cleanup
  wiring, dead-button failure mode, over-engineering for voice-message sizes)
  and keeping the service alive (violates the core constraint, wastes battery).
- **Action layout mirrors the in-progress notification** (explicit user
  choice): fixed anchor actions first, nav buttons after them with progressive
  disclosure.

## Components

### `TranscriptPager` (new, pure Kotlin, no Android imports)

- `pagesFor(text: String): List<String>` with word-aligned split at
  `PAGE_CHARS = 400`. A word longer than the limit is hard-cut into its own
  page. Whitespace-normalized concatenation of pages equals the original text.
- `MAX_PAGED_LENGTH = 50_000`. Above this, no nav actions are attached
  (binder-transaction guard for the nav intents) and the notification keeps
  today's truncated-preview + `char_counter` behavior.
- Paging is active iff `pagesFor(text).size >= 2` and
  `text.length <= MAX_PAGED_LENGTH`.

### `ResultNotificationFactory` (new, `service/`)

Single shared builder for result notifications, extracted from the two
near-identical `showResultNotification` implementations
(`InferenceService.kt` and `TranscriptionNotificationListener.kt`, whose KDoc
today documents the duplication as contained). Synchronous and testable:

- Inputs: context, an `AppNotificationPreferences` value (callers fetch it;
  the factory never touches DataStore), and a `ResultNotificationSpec` data
  class: `transcriptionText, taskId, sourcePackage, confidence,
  detectedLanguage, isPartial, failedChunkCount, pageIndex = 0,
  notificationId, firstPostedAt`.
- Output: the built `Notification`, posted by the caller via
  `notify(notificationId, …)`.
- Owns the notification-id allocator: one companion `AtomicInteger` seeded at
  `InferenceService.RESULT_NOTIFICATION_ID` (1002), replacing the per-class
  counters. Every notification post in both classes draws from it: result,
  error, and no-model notifications alike (six posting sites total, three per
  class), so no two posts can collide; the old instance counters are removed.
  Ids are unique within a process lifetime only: after process death the
  sequence restarts at 1002, so a fresh post can overwrite a pre-death
  notification (pre-existing behavior of both current counters, unchanged).
- The service and the listener delegate to it; their auto-copy side effects
  stay where they are.

### `NotificationActionReceiver` (extended)

- New actions `ACTION_PAGE_PREV` / `ACTION_PAGE_NEXT` with extras: full text,
  current page, notification id, firstPostedAt, and the spec fields needed to
  rebuild (taskId, sourcePackage, confidence, detectedLanguage, isPartial,
  failedChunkCount).
- Handler: clamp the page (prev: `max(0, i-1)`, next: `min(last, i+1)`),
  re-read per-app preferences (fresh Send-to label), rebuild through the
  factory, `notify(carriedId, …)`. `getCurrentPreferences` is a suspend
  DataStore call, so the handler uses `goAsync()` plus a coroutine (within the
  ~10 s window); on failure it falls back to `AppNotificationPreferences.default()`
  and the re-post happens from inside the coroutine. If the coroutine dies, the
  old notification simply stands.
- The two new actions are also declared in the receiver's manifest
  intent-filter alongside the existing five, for pattern consistency (the nav
  PendingIntents are component-explicit, so the filter is not load-bearing).
- Manifest receiver, so the process starts on demand: paging survives
  `InferenceService` destruction and later process death. Force-stop is out of
  scope by platform behavior: Android cancels the app's notifications and
  blocks manifest receivers for stopped packages, so there is nothing to page.

## Notification layout

- Actions in order: Copy, Send-to-Telegram/Share (unchanged; both always carry
  the full text, never the visible page), then Next when `page < last`, Prev
  when `page > 0`. Reuses `chunk_nav_prev`/`chunk_nav_next` resources.
- Collapsed budget: Android elides trailing actions beyond its display cap, so
  the order above means first page shows Copy + Share + Next; middle pages
  carry four actions with Prev collapsed-elided (Copy + Share + Next visible,
  Prev reachable expanded); last page shows Copy + Share + Prev. Whether the
  platform surfaces the fourth action when expanded on Android 16 is unproven
  in this repo and is an explicit on-device DoD check. Fallback if it does
  not: omit the Share action while on a middle page (progressive disclosure
  applied to Share instead of to paging) so paging stays fully functional
  within three actions.
- Single page: no nav actions, no truncation ellipsis, no counter. Medium
  transcripts (101-400 chars) that today show a truncated preview plus
  "100 of N chars" become fully readable in one page.
- Paged: `contentText` and BigTextStyle `bigText` both show the current page;
  subtext becomes `Page 2 of 3 · <language> · low confidence` via a new
  `page_counter` resource (en + it). `char_counter` survives only in the
  oversized fallback path.
- Re-posts use `setOnlyAlertOnce(true)` and carry `firstPostedAt` so paging
  never re-sounds the notification or bumps it to the top of the shade.

## Data flow on a Next tap

Android fires the broadcast (starting the process if needed), the receiver
reads the extras, clamps the page, re-reads per-app prefs with
fallback-to-default on error, rebuilds through the factory, and re-posts to
the carried notification id, replacing the notification in place. Nav
PendingIntent request codes derive from `(notificationId, pageIndex,
direction)` so the system cannot collapse distinct pages into one cached
intent.

## Error handling and edge cases

- Missing or blank text extra: log and no-op. A missing `notificationId` extra
  is also a no-op (re-posting under a fresh id would strand the old
  notification).
- Page index out of range: clamp.
- Rebuild exception: log and leave the old notification standing; a button tap
  must never crash.
- Word longer than a page: hard-cut into its own page.
- No character loss: pages reassemble to the original text (whitespace
  normalized).
- Per-app prefs read failure: defaults, exactly as both current
  implementations do.

## Testing

- `TranscriptPagerTest` (pure JVM): word alignment, page count, round-trip
  no-loss, overlong single word, empty/blank, exact boundary.
- `ResultNotificationFactoryTest` + receiver test (Robolectric, following
  `InferenceServiceNotificationTest`): Prev absent on page 0, Next absent on
  last page, Copy carries the full text even mid-paging, subtext page counter,
  short text unchanged, oversized fallback keeps `char_counter`, same-id
  re-post, page clamping.
- On-device (project DoD), Realme RMX3853 (Android 16): a >400-char voice note
  pages with the buttons; paging still works after `InferenceService` has
  stopped and after the app process has been killed (not force-stop, which the
  platform itself makes impossible); the fourth action is reachable when
  expanded (else the documented Share-omission fallback applies); a full
  400-char page is readable without clamping in the expanded BigText view
  (else lower `PAGE_CHARS` before shipping).

## Targeted fixes riding along

- The two duplicated `showResultNotification` implementations collapse into
  the factory (the duplication is exactly what paging would otherwise double).
- The 1002 id-collision between the service's and the listener's counters is
  fixed by the shared allocator.

## Out of scope

- Paging the oversized (>50k chars) path.
- Any change to the in-progress chunk navigation (TASK-242 behavior stays).
- Preferences for page size (constant is enough until someone asks).
