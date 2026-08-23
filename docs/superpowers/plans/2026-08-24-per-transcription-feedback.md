# Per-transcription quick feedback, implementation plan (TASK-374)

> **For agentic workers:** execute with subagent-driven-development or executing-plans; steps use checkbox syntax.

**Goal:** a lightweight "report this transcription" action on log entries that pre-fills the existing feedback email (issue #34 plumbing) with task facts and a truncated transcript excerpt.

**Architecture:** no new telemetry. One pure builder function `FeedbackHelper.buildTranscriptFeedbackBody(...)` (JVM-testable), one LogEntry action wired into the existing context menu (buildContextMenuActions) and, only on ERROR entries, a visible action chip. Email-only via the existing ACTION_SENDTO flow.

**Design decisions (locked):**
- Excerpt TRUNCATED to 300 chars with an ellipsis marker; full transcript never auto-attached (privacy: the user sees and edits the email before sending).
- Includes: task id, model display name, audio duration, processing time, status, excerpt.
- Placement: the GH #52 long-press context menu for ALL entries + nothing new on collapsed cards (keeps the UI budget; menu already exists and is a11y-reachable after TASK-386).
- Subject: "[Anti-Vocale feedback] task <taskId>" so threads group in the mailbox.

## Task 1: Body builder (TDD, pure JVM)

Files: Modify app/src/main/java/com/antivocale/app/util/FeedbackHelper.kt; Test app/src/test/java/com/antivocale/app/util/FeedbackHelperTest.kt (exists, extend).

- [ ] Failing tests: body contains taskId/model/duration/time/status; excerpt truncated at 300 chars + ellipsis; empty transcript handled (placeholder line, no crash).
- [ ] Implement `buildTranscriptFeedbackBody(entry: TranscriptFacts, labels: TranscriptLabels): String` with a small data class mirroring Diagnostics/BodyLabels style.
- [ ] Green, commit.

## Task 2: Context-menu action + intent wiring

Files: Modify app/src/main/java/com/antivocale/app/ui/tabs/LogsTab.kt (buildContextMenuActions) and the menu handler; reuse the existing feedback Intent launcher (search ACTION_SENDTO / FeedbackHelper usage in SettingsTab and copy the pattern).

- [ ] New menu entry "Report transcription" (localized, 11 locales: feedback_report_transcription) on every entry; on tap: build the body from the LogEntry and launch the email intent with the task-id subject.
- [ ] No new state, no ViewModel changes (all data already in the LogEntry).
- [ ] Compile + full suite, commit.

## Task 3: Gates + device

- [ ] /review-local + /simplify on the diff.
- [ ] Device: long-press a SUCCESS entry, tap Report, verify the email composer opens with the task facts (uiautomator dump + OCR of the composer text), on both a SUCCESS and an ERROR entry.
- [ ] Close TASK-374 with evidence.

## Notes
- The 300-char cap constant lives next to the builder and is referenced in the test (no magic number in two places).
- i18n: labels via string resources like BodyLabels; the excerpt marker is a plain ellipsis character, not localized.
