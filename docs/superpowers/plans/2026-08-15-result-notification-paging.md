# Result-Notification Paging Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user read a long completed transcript in pages, directly in the result notification, via prev/next actions that work without any service or process state.

**Architecture:** A pure `TranscriptPager` computes word-aligned 400-char pages deterministically from the text. A shared `ResultNotificationFactory` (extracted from the two duplicated `showResultNotification` implementations) builds the result notification for a given page and owns the notification-id allocator. Prev/next taps broadcast to `NotificationActionReceiver`, which recomputes the neighbor page from intent extras and re-posts to the same notification id. No state is stored anywhere.

**Tech Stack:** Kotlin, NotificationCompat, BroadcastReceiver + goAsync, DataStore (`PerAppPreferencesManager`), JUnit + Robolectric.

**Spec:** `docs/superpowers/specs/2026-08-15-result-notification-paging-design.md` (approved, review-passed). Read it before starting.

**Conventions:**
- Build flavors: unit tests run locally via `./gradlew :app:testPlayStoreDebugUnitTest`. Never plain `assembleDebug`.
- Install on device ONLY via `./scripts/install.sh`.
- Device serial (spaces included): `D=$(~/Android/Sdk/platform-tools/adb devices | sed -n 's/^\(.*_adb-tls-connect\._tcp\)[[:space:]]*device$/\1/p')`. Never `adb disconnect`.
- Fixture arithmetic: the test word "parola" plus its separator occupies 7 chars, so n words occupy `7n - 1` chars. All word-count fixtures derive from `TranscriptPager.PAGE_CHARS` via `(PAGE_CHARS + 1) / 7` words per page, so retuning the constant (Task 8 Step 7) does not break the suite.

---

## Chunk 1: Paging core and strings

### Task 1: TranscriptPager (pure Kotlin, TDD)

**Files:**
- Create: `app/src/main/java/com/antivocale/app/service/TranscriptPager.kt`
- Test: `app/src/test/java/com/antivocale/app/service/TranscriptPagerTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/antivocale/app/service/TranscriptPagerTest.kt`:

```kotlin
package com.antivocale.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the paging policy (TASK-327). */
class TranscriptPagerTest {

    /** "parola" + separator = 7 chars per word, so a page holds (PAGE_CHARS + 1) / 7 words (7n - 1 chars). */
    private fun wordsPerPage(): Int = (TranscriptPager.PAGE_CHARS + 1) / 7

    private fun words(times: Int, word: String = "parola"): String =
        List(times) { word }.joinToString(" ")

    @Test
    fun `empty text has no pages`() {
        assertTrue(TranscriptPager.pagesFor("").isEmpty())
        assertFalse(TranscriptPager.isPaged(""))
    }

    @Test
    fun `blank text is a single page and not paged`() {
        assertEquals(listOf("   "), TranscriptPager.pagesFor("   "))
        assertFalse(TranscriptPager.isPaged("   "))
    }

    @Test
    fun `text within one page is a single page`() {
        val text = words(wordsPerPage()) // 398 chars at PAGE_CHARS = 400
        assertEquals(listOf(text), TranscriptPager.pagesFor(text))
        assertFalse(TranscriptPager.isPaged(text))
    }

    @Test
    fun `text exactly at the limit does not split`() {
        val a = "a".repeat(TranscriptPager.PAGE_CHARS / 2)
        val b = "b".repeat(TranscriptPager.PAGE_CHARS - a.length - 1)
        val text = "$a $b"
        assertEquals(TranscriptPager.PAGE_CHARS, text.length)
        assertEquals(1, TranscriptPager.pagesFor(text).size)
        assertFalse(TranscriptPager.isPaged(text))
    }

    @Test
    fun `text just over the limit splits at a word boundary`() {
        val text = words(wordsPerPage() + 1) // 405 chars at PAGE_CHARS = 400
        val pages = TranscriptPager.pagesFor(text)
        assertEquals(2, pages.size)
        assertTrue(TranscriptPager.isPaged(text))
        pages.forEach { page ->
            assertTrue("page too long: ${page.length}", page.length <= TranscriptPager.PAGE_CHARS)
        }
        // Split must not cut inside a word: first page ends at a word end.
        assertTrue(pages[0].endsWith("parola"))
        assertEquals("parola", pages[1])
    }

    @Test
    fun `pages reassemble the original text`() {
        val text = words(500)
        val rejoined = TranscriptPager.pagesFor(text).joinToString(" ")
        assertEquals(
            text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" "),
            rejoined
        )
    }

    @Test
    fun `word longer than a page is hard cut`() {
        val text = "short " + "x".repeat(TranscriptPager.PAGE_CHARS + 50)
        val pages = TranscriptPager.pagesFor(text)
        assertEquals(3, pages.size)
        assertEquals("short", pages[0])
        assertEquals(TranscriptPager.PAGE_CHARS, pages[1].length)
        assertEquals(50, pages[2].length)
        assertTrue(TranscriptPager.isPaged(text))
    }

    @Test
    fun `oversized text is not paged`() {
        val text = words(9_000) // 62_999 chars, above MAX_PAGED_LENGTH
        assertFalse(TranscriptPager.isPaged(text))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testPlayStoreDebugUnitTest --tests "com.antivocale.app.service.TranscriptPagerTest"`
Expected: compilation FAIL with `unresolved reference: TranscriptPager`.

- [ ] **Step 3: Implement TranscriptPager**

Create `app/src/main/java/com/antivocale/app/service/TranscriptPager.kt`:

```kotlin
package com.antivocale.app.service

/**
 * Splits a transcript into word-aligned pages for the result notification
 * (TASK-327). Pure Kotlin on purpose: the page shown is always recomputed
 * from (text, pageIndex) and never stored, because the result notification
 * outlives InferenceService and paging must not depend on any process state
 * surviving between taps.
 */
object TranscriptPager {

    /** Max chars per page. Tuned to fit the expanded BigText clamp on the target device; verify on device before shipping. */
    const val PAGE_CHARS = 400

    /** Above this length no nav actions are attached: the nav intents carry the full text, and binder transactions cap around 1 MB. */
    const val MAX_PAGED_LENGTH = 50_000

    private val WHITESPACE = Regex("\\s+")

    /** Paging is active iff there is more than one page and the text is under the binder guard. */
    fun isPaged(text: String): Boolean =
        text.length <= MAX_PAGED_LENGTH && pagesFor(text).size >= 2

    /**
     * Word-aligned split: accumulate whitespace-separated words while the page
     * stays within [PAGE_CHARS]; a single word longer than the limit is
     * hard-cut into [PAGE_CHARS]-sized pages. Whitespace-normalized
     * concatenation of the pages equals the original text.
     */
    fun pagesFor(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val words = text.split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf(text)
        val pages = mutableListOf<String>()
        val current = StringBuilder()
        for (rawWord in words) {
            var word = rawWord
            while (word.length > PAGE_CHARS) {
                if (current.isNotEmpty()) {
                    pages.add(current.toString())
                    current.setLength(0)
                }
                pages.add(word.substring(0, PAGE_CHARS))
                word = word.substring(PAGE_CHARS)
            }
            val wouldOverflow = current.isNotEmpty() &&
                current.length + 1 + word.length > PAGE_CHARS
            if (wouldOverflow) {
                pages.add(current.toString())
                current.setLength(0)
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
        if (current.isNotEmpty()) pages.add(current.toString())
        return pages
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testPlayStoreDebugUnitTest --tests "com.antivocale.app.service.TranscriptPagerTest"`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/antivocale/app/service/TranscriptPager.kt \
        app/src/test/java/com/antivocale/app/service/TranscriptPagerTest.kt
git commit -m "feat: TranscriptPager word-aligned paging core (TASK-327)"
```

### Task 2: page_counter string resources

**Files:**
- Modify: `app/src/main/res/values/strings.xml:197` (after the `char_counter` line)
- Modify: `app/src/main/res/values-it/strings.xml:197` (after the `char_counter` line)

- [ ] **Step 1: Add the resource to both locales**

In `values/strings.xml`, directly after the `char_counter` line:

```xml
    <string name="page_counter">Page %1$d of %2$d</string>
```

In `values-it/strings.xml`, directly after its `char_counter` line:

```xml
    <string name="page_counter">Pagina %1$d di %2$d</string>
```

The nav button labels reuse the existing `chunk_nav_prev` ("◀") and `chunk_nav_next` ("▶"); no new resources for them.

- [ ] **Step 2: Verify the resource resolves**

Run: `./gradlew :app:processPlayStoreDebugResources`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml
git commit -m "feat: page_counter string for paged result notification (TASK-327)"
```

---

## Chunk 2: Shared factory and call-site migration

### Task 3: ResultNotificationFactory (TDD)

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/receiver/NotificationActionReceiver.kt` (companion constants only)
- Create: `app/src/main/java/com/antivocale/app/service/ResultNotificationFactory.kt`
- Test: `app/src/test/java/com/antivocale/app/service/ResultNotificationFactoryTest.kt`

- [ ] **Step 1: Add the receiver action and extra constants**

The factory and its tests reference these, so they land first (pure constants, no behavior). In `NotificationActionReceiver`'s companion object add:

```kotlin
        const val ACTION_PAGE_PREV = "com.antivocale.app.PAGE_PREV"
        const val ACTION_PAGE_NEXT = "com.antivocale.app.PAGE_NEXT"
        const val EXTRA_PAGE_INDEX = "page_index"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_FIRST_POSTED_AT = "first_posted_at"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_CONFIDENCE = "confidence"
        const val EXTRA_DETECTED_LANGUAGE = "detected_language"
        const val EXTRA_IS_PARTIAL = "is_partial"
        const val EXTRA_FAILED_CHUNK_COUNT = "failed_chunk_count"
```

Do NOT wire them into `onReceive` yet; that is Task 6.

- [ ] **Step 2: Write the failing tests**

Create `app/src/test/java/com/antivocale/app/service/ResultNotificationFactoryTest.kt`:

```kotlin
package com.antivocale.app.service

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.data.AppNotificationPreferences
import com.antivocale.app.receiver.NotificationActionReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Robolectric tests for the shared result-notification builder (TASK-327). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ResultNotificationFactoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val factory = ResultNotificationFactory(context)

    /** Share action on, quick share back to Telegram, matching the triggering user scenario. */
    private val prefs = AppNotificationPreferences(
        autoCopy = false, showShareAction = true,
        notificationSound = "default", quickShareBack = true
    )

    /** Whole pages of "parola" words: n words occupy 7n - 1 chars (see plan conventions). */
    private fun longText(pages: Int): String {
        val perPage = (TranscriptPager.PAGE_CHARS + 1) / 7
        return List(pages * perPage) { "parola" }.joinToString(" ")
    }

    private fun spec(text: String, page: Int = 0, repost: Boolean = false) = ResultNotificationSpec(
        transcriptionText = text,
        taskId = "task-1",
        sourcePackage = "org.telegram.messenger",
        confidence = 0.9f,
        detectedLanguage = null,
        notificationId = 5_000,
        pageIndex = page,
        firstPostedAt = 1_000L,
        repost = repost
    )

    private fun Notification.titles(): List<String> =
        actions?.map { it.title.toString() }.orEmpty()

    private fun Notification.contentViewText(): String? =
        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun Notification.subTextCompat(): String? =
        extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

    @Test
    fun `allocator hands out consecutive unique ids`() {
        val first = ResultNotificationFactory.nextNotificationId()
        val second = ResultNotificationFactory.nextNotificationId()
        assertEquals(first + 1, second)
        // Not asserting the absolute value: earlier tests in the same process may draw ids.
    }

    @Test
    fun `short single page text keeps today's layout`() {
        val text = "ciao come stai"
        val n = factory.build(spec(text), prefs)
        assertEquals(listOf("Copy", "Send to Telegram"), n.titles())
        assertEquals(text, n.contentViewText())
        assertNull(n.subTextCompat())
    }

    @Test
    fun `medium text fits one page without truncation or counter`() {
        val text = List(45) { "parola" }.joinToString(" ") // 314 chars (7n - 1)
        val n = factory.build(spec(text), prefs)
        assertEquals(text, n.contentViewText())
        assertNull(n.subTextCompat())
        assertEquals(listOf("Copy", "Send to Telegram"), n.titles())
    }

    @Test
    fun `first page shows Copy Share Next in that order`() {
        val n = factory.build(spec(longText(3)), prefs)
        assertEquals(listOf("Copy", "Send to Telegram", "▶"), n.titles())
    }

    @Test
    fun `middle page shows Next before Prev`() {
        val n = factory.build(spec(longText(3), page = 1), prefs)
        assertEquals(listOf("Copy", "Send to Telegram", "▶", "◀"), n.titles())
    }

    @Test
    fun `last page has Prev and no Next`() {
        val n = factory.build(spec(longText(3), page = 2), prefs)
        assertEquals(listOf("Copy", "Send to Telegram", "◀"), n.titles())
    }

    @Test
    fun `paged subtext shows page counter`() {
        val n = factory.build(spec(longText(3), page = 1), prefs)
        assertEquals("Page 2 of 3", n.subTextCompat())
    }

    @Test
    fun `copy action carries the full text even mid paging`() {
        val text = longText(3)
        val n = factory.build(spec(text, page = 1), prefs)
        val intent = Shadows.shadowOf(n.actions!!.first { it.title == "Copy" }.actionIntent).savedIntent
        assertEquals(NotificationActionReceiver.ACTION_COPY_TRANSCRIPTION, intent.action)
        assertEquals(text, intent.getStringExtra(NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT))
    }

    @Test
    fun `nav intent carries full text page and notification id`() {
        val text = longText(2)
        val n = factory.build(spec(text), prefs)
        val intent = Shadows.shadowOf(n.actions!!.first { it.title == "▶" }.actionIntent).savedIntent
        assertEquals(NotificationActionReceiver.ACTION_PAGE_NEXT, intent.action)
        assertEquals(text, intent.getStringExtra(NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT))
        assertEquals(0, intent.getIntExtra(NotificationActionReceiver.EXTRA_PAGE_INDEX, -1))
        assertEquals(5_000, intent.getIntExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, -1))
    }

    @Test
    fun `oversized text falls back to truncated preview and char counter`() {
        val text = List(9_000) { "parola" }.joinToString(" ")
        val n = factory.build(spec(text), prefs)
        assertTrue(n.contentViewText()!!.endsWith("…"))
        assertTrue(n.subTextCompat()!!.startsWith("100 of"))
        assertEquals(listOf("Copy", "Send to Telegram"), n.titles())
    }

    @Test
    fun `repost never re-alerts and keeps firstPostedAt`() {
        val n = factory.build(spec(longText(2), page = 1, repost = true), prefs)
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(1_000L, n.`when`)
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :app:testPlayStoreDebugUnitTest --tests "com.antivocale.app.service.ResultNotificationFactoryTest"`
Expected: compilation FAIL with `unresolved reference: ResultNotificationFactory` (and `ResultNotificationSpec`, same cause).

- [ ] **Step 4: Implement spec and factory**

Create `app/src/main/java/com/antivocale/app/service/ResultNotificationFactory.kt`:

```kotlin
package com.antivocale.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.antivocale.app.MainActivity
import com.antivocale.app.R
import com.antivocale.app.data.AppNotificationPreferences
import com.antivocale.app.receiver.NotificationActionReceiver
import com.antivocale.app.transcription.Language
import com.antivocale.app.util.AppInfoUtils
import com.antivocale.app.util.AppNotificationChannel
import java.util.concurrent.atomic.AtomicInteger

/** Everything needed to (re)build one result notification (TASK-327). */
data class ResultNotificationSpec(
    val transcriptionText: String,
    val taskId: String?,
    val sourcePackage: String?,
    val confidence: Float?,
    val detectedLanguage: String?,
    val isPartial: Boolean = false,
    val failedChunkCount: Int = 0,
    val pageIndex: Int = 0,
    val notificationId: Int,
    val firstPostedAt: Long = System.currentTimeMillis(),
    /** True when rebuilding after a prev/next tap: suppresses re-alerting. */
    val repost: Boolean = false
)

/**
 * The single builder for completed-transcription result notifications
 * (TASK-327). Extracted from the two previously duplicated
 * showResultNotification implementations (InferenceService and
 * TranscriptionNotificationListener); both now delegate here.
 *
 * Synchronous by design: callers fetch [AppNotificationPreferences] (a suspend
 * DataStore read) on their own scheduler and pass the value in, so this class
 * stays trivially testable.
 *
 * Also owns the process-wide notification-id allocator: every post in both
 * delegating classes (result, error, no-model) draws from [nextNotificationId],
 * replacing the two per-class counters that both seeded at 1002 and could
 * collide. Ids are unique within a process lifetime only; after process death
 * the sequence restarts at 1002 (pre-existing behavior, unchanged).
 */
class ResultNotificationFactory(private val context: Context) {

    init {
        // Idempotent; the receiver path can run in a fresh process where no
        // service ever created the channel.
        AppNotificationChannel.TRANSCRIPTION_RESULT.create(context)
    }

    fun build(spec: ResultNotificationSpec, prefs: AppNotificationPreferences): Notification {
        val text = spec.transcriptionText
        val paged = TranscriptPager.isPaged(text)
        val pages = if (paged) TranscriptPager.pagesFor(text) else listOf(text)
        val pageIndex = spec.pageIndex.coerceIn(0, pages.size - 1)

        val title = if (spec.isPartial) {
            context.getString(R.string.transcription_partial, spec.failedChunkCount)
        } else {
            context.getString(R.string.transcription_complete)
        }

        // Legacy truncation applies only to texts too big to page (binder
        // guard): everything pageable is fully readable, single page or paged.
        val oversized = text.length > TranscriptPager.MAX_PAGED_LENGTH
        val contentText: String
        val bigText: String
        if (paged) {
            contentText = pages[pageIndex]
            bigText = pages[pageIndex]
        } else if (oversized) {
            contentText = text.take(CHAR_PREVIEW_LIMIT) + "…"
            bigText = text
        } else {
            contentText = text
            bigText = text
        }

        val builder = NotificationCompat.Builder(context, AppNotificationChannel.TRANSCRIPTION_RESULT.id)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(buildLaunchPendingIntent(spec.taskId))
            .setWhen(spec.firstPostedAt)
            .setOnlyAlertOnce(spec.repost)
            .setAutoCancel(true)
            .addAction(
                android.R.drawable.ic_menu_save,
                context.getString(R.string.copy),
                copyPendingIntent(text)
            )

        if (prefs.showShareAction) {
            addShareAction(builder, spec, prefs)
        }

        // Nav actions mirror the in-progress notification's structure (user
        // decision): fixed anchors first, nav after, progressive disclosure.
        // Android elides trailing actions beyond its collapsed cap, so a middle
        // page keeps Copy + Share + Next visible and elides Prev.
        if (paged && pageIndex < pages.size - 1) {
            builder.addAction(
                android.R.drawable.ic_media_next,
                context.getString(R.string.chunk_nav_next),
                navPendingIntent(spec, direction = +1)
            )
        }
        if (paged && pageIndex > 0) {
            builder.addAction(
                android.R.drawable.ic_media_previous,
                context.getString(R.string.chunk_nav_prev),
                navPendingIntent(spec, direction = -1)
            )
        }

        val subTextParts = mutableListOf<String>()
        when {
            paged -> subTextParts.add(
                context.getString(R.string.page_counter, pageIndex + 1, pages.size)
            )
            oversized -> subTextParts.add(
                context.getString(R.string.char_counter, CHAR_PREVIEW_LIMIT, text.length)
            )
        }
        val langLabel = spec.detectedLanguage?.let { lang ->
            Language.FILTER_ENTRIES.find { it.code == lang }
                ?.let { context.getString(it.nameResId) }
        }
        if (langLabel != null) {
            subTextParts.add(context.getString(R.string.detected_language, langLabel))
        }
        if (spec.confidence != null && spec.confidence < CONFIDENCE_MEDIUM_THRESHOLD) {
            subTextParts.add(context.getString(R.string.confidence_low))
        }
        if (subTextParts.isNotEmpty()) {
            builder.setSubText(subTextParts.joinToString(" · "))
        }

        return builder.build()
    }

    private fun addShareAction(
        builder: NotificationCompat.Builder,
        spec: ResultNotificationSpec,
        prefs: AppNotificationPreferences
    ) {
        val useQuickShareBack = prefs.quickShareBack && spec.sourcePackage != null
        if (useQuickShareBack) {
            val shareBackIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, spec.transcriptionText)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val targetPackage = when {
                    spec.sourcePackage == "com.whatsapp" || spec.sourcePackage!!.startsWith("com.whatsapp") -> "com.whatsapp"
                    spec.sourcePackage == "org.telegram.messenger" || spec.sourcePackage!!.startsWith("org.telegram") -> "org.telegram.messenger"
                    spec.sourcePackage == "org.thoughtcrime.securesms" -> "org.thoughtcrime.securesms"
                    else -> spec.sourcePackage
                }
                setPackage(targetPackage)
            }
            val shareBackPendingIntent = PendingIntent.getActivity(
                context,
                System.currentTimeMillis().toInt() + 1,
                shareBackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_revert,
                AppInfoUtils.getSendToText(context, spec.sourcePackage),
                shareBackPendingIntent
            )
        } else {
            val shareChooserIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, spec.transcriptionText)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val sharePickerIntent = Intent.createChooser(
                shareChooserIntent,
                context.getString(R.string.share_transcription)
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            val sharePendingIntent = PendingIntent.getActivity(
                context,
                System.currentTimeMillis().toInt() + 1,
                sharePickerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_share,
                context.getString(R.string.share),
                sharePendingIntent
            )
        }
    }

    private fun copyPendingIntent(text: String): PendingIntent {
        val copyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_COPY_TRANSCRIPTION
            putExtra(NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT, text)
        }
        return PendingIntent.getBroadcast(
            context,
            System.currentTimeMillis().toInt(),
            copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Nav intents carry everything needed to rebuild the neighbor page. The
     * request code is distinct per (notification, page, direction): PendingIntent
     * equality ignores extras, so shared codes would collapse distinct pages
     * into one cached intent.
     */
    private fun navPendingIntent(spec: ResultNotificationSpec, direction: Int): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = if (direction < 0) {
                NotificationActionReceiver.ACTION_PAGE_PREV
            } else {
                NotificationActionReceiver.ACTION_PAGE_NEXT
            }
            putExtra(NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT, spec.transcriptionText)
            putExtra(NotificationActionReceiver.EXTRA_PAGE_INDEX, spec.pageIndex)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, spec.notificationId)
            putExtra(NotificationActionReceiver.EXTRA_FIRST_POSTED_AT, spec.firstPostedAt)
            putExtra(NotificationActionReceiver.EXTRA_IS_PARTIAL, spec.isPartial)
            putExtra(NotificationActionReceiver.EXTRA_FAILED_CHUNK_COUNT, spec.failedChunkCount)
            spec.taskId?.let { putExtra(NotificationActionReceiver.EXTRA_TASK_ID, it) }
            spec.sourcePackage?.let { putExtra(NotificationActionReceiver.EXTRA_SOURCE_PACKAGE, it) }
            spec.confidence?.let { putExtra(NotificationActionReceiver.EXTRA_CONFIDENCE, it) }
            spec.detectedLanguage?.let { putExtra(NotificationActionReceiver.EXTRA_DETECTED_LANGUAGE, it) }
        }
        val requestCode = spec.notificationId * 1000 + spec.pageIndex * 2 + if (direction < 0) 0 else 1
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildLaunchPendingIntent(highlightTaskId: String?): PendingIntent {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            if (highlightTaskId != null) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_HIGHLIGHT_TASK_ID, highlightTaskId)
            } else {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        }
        return PendingIntent.getActivity(
            context,
            highlightTaskId?.hashCode() ?: 0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        /** Preview truncation for the non-pageable oversized path, unchanged from the previous implementations. */
        const val CHAR_PREVIEW_LIMIT = 100

        private const val CONFIDENCE_MEDIUM_THRESHOLD = 0.5f

        /** Process-wide id allocator: fixes the 1002 collision between the old per-class counters. */
        private val idCounter = AtomicInteger(InferenceService.RESULT_NOTIFICATION_ID)

        fun nextNotificationId(): Int = idCounter.getAndIncrement()
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testPlayStoreDebugUnitTest --tests "com.antivocale.app.service.ResultNotificationFactoryTest"`
Expected: PASS, 11 tests. If a Robolectric accessor needs adjusting, the sanctioned replacements are: subtext via `extras.getCharSequence(Notification.EXTRA_SUB_TEXT)`, only-alert-once via `flags and Notification.FLAG_ONLY_ALERT_ONCE`, wrapped intents via `Shadows.shadowOf(pendingIntent).savedIntent`. Assertions must not change.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/antivocale/app/service/ResultNotificationFactory.kt \
        app/src/test/java/com/antivocale/app/service/ResultNotificationFactoryTest.kt \
        app/src/main/java/com/antivocale/app/receiver/NotificationActionReceiver.kt
git commit -m "feat: shared ResultNotificationFactory with paging and id allocator (TASK-327)"
```

### Task 4: Migrate InferenceService to the factory

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/service/InferenceService.kt` (showResultNotification ~667-801, showErrorNotification ~803, showNoModelNotification ~816, postUniqueNotification ~838, resultNotificationCounter ~99, RESULT_NOTIFICATION_ID ~61 stays)

- [ ] **Step 1: Replace showResultNotification body**

Keep the method signature and its prefs-fetch preamble (lines ~676-685) unchanged, then replace everything from the `copyIntent` declaration through the end of the method with:

```kotlin
        val id = ResultNotificationFactory.nextNotificationId()
        val spec = ResultNotificationSpec(
            transcriptionText = transcriptionText,
            taskId = taskId,
            sourcePackage = sourcePackage,
            confidence = confidence,
            detectedLanguage = detectedLanguage,
            isPartial = isPartial,
            failedChunkCount = failedChunkCount,
            notificationId = id,
            firstPostedAt = System.currentTimeMillis()
        )
        val notification = resultNotificationFactory.build(spec, prefs)
        notificationManager.notify(id, notification)
        Log.i(TAG, "Showed result notification (${transcriptionText.length} chars), source=$sourcePackage, showShare=${prefs.showShareAction} (id=$id)")
```

Add the factory field next to the other notification helpers:

```kotlin
    private val resultNotificationFactory = ResultNotificationFactory(this)
```

The method stays `suspend`: the prefs preamble's `getCurrentPreferences` call survives. The replaced region also drops the `prefs.notificationSound` "not yet implemented" `Log.d` (lines ~777-779); that is intentional, the sound preference is unused by the result path.

- [ ] **Step 2: Migrate error and no-model notifications off the local counter**

In `showErrorNotification` and `showNoModelNotification`, replace each `postUniqueNotification(notification, "…")` call with:

```kotlin
        val id = ResultNotificationFactory.nextNotificationId()
        notificationManager.notify(id, notification)
        Log.i(TAG, "<existing description string carried over verbatim> (id=$id)")
```

Carry each call site's existing description string verbatim ("Showed error notification: $errorMessage" and "Showed no-model notification"); do not invent new ones.

- [ ] **Step 3: Remove the now-dead counter and helper**

First confirm they have no other callers:

Run: `grep -n "postUniqueNotification\|resultNotificationCounter" app/src/main/java/com/antivocale/app/service/InferenceService.kt`
Expected: only the definitions and the uses replaced in Steps 1-2.

Then delete `postUniqueNotification` and the `resultNotificationCounter` field. Keep `RESULT_NOTIFICATION_ID` (the factory seeds from it). Also remove `CONFIDENCE_MEDIUM_THRESHOLD` from InferenceService's companion if the grep shows no other user:

Run: `grep -n "CONFIDENCE_MEDIUM_THRESHOLD" app/src/main/java/com/antivocale/app/service/InferenceService.kt`

Remove imports the compiler flags as unused after the migration (likely `AppInfoUtils`, `Language`, `NotificationActionReceiver` if no other use in the file).

- [ ] **Step 4: Compile and run the existing suite**

Run: `./gradlew :app:testPlayStoreDebugUnitTest`
Expected: PASS (589+ existing tests plus the new ones).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/antivocale/app/service/InferenceService.kt
git commit -m "refactor: InferenceService delegates result notifications to the shared factory (TASK-327)"
```

### Task 5: Migrate TranscriptionNotificationListener to the factory

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/service/TranscriptionNotificationListener.kt` (showResultNotification ~181-297, showErrorNotification ~299, showNoModelNotification ~311, postUniqueNotification ~329, resultNotificationCounter ~64)

- [ ] **Step 1: Replace showResultNotification body**

Same shape as Task 4 Step 1: keep the prefs preamble, replace the builder code with:

```kotlin
        val id = ResultNotificationFactory.nextNotificationId()
        val spec = ResultNotificationSpec(
            transcriptionText = transcriptionText,
            taskId = taskId,
            sourcePackage = sourcePackage,
            confidence = confidence,
            detectedLanguage = detectedLanguage,
            isPartial = isPartial,
            failedChunkCount = failedChunkCount,
            notificationId = id,
            firstPostedAt = System.currentTimeMillis()
        )
        val notification = resultNotificationFactory.build(spec, prefs)
        notificationManager.notify(id, notification)
        Log.i(TAG, "Worker showed result notification (${transcriptionText.length} chars) (id=$id)")
```

Add the field:

```kotlin
    private val resultNotificationFactory = ResultNotificationFactory(appContext)
```

The class KDoc's "acceptable, contained duplication" paragraph (lines ~41-47) must be updated: the duplication is now healed, both paths delegate to `ResultNotificationFactory`.

- [ ] **Step 2: Migrate error and no-model, remove the local counter and helper**

Exactly as Task 4 Steps 2-3 (grep first, then delete `postUniqueNotification` and `resultNotificationCounter`; carry description strings verbatim). The listener's `CONFIDENCE_MEDIUM_THRESHOLD` (companion, sole use inside the body Step 1 replaces) becomes dead too: remove it after the same grep gate. The `init` block's channel creation stays (idempotent, minimal diff). Remove unused imports.

- [ ] **Step 3: Compile and run the suite**

Run: `./gradlew :app:testPlayStoreDebugUnitTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/antivocale/app/service/TranscriptionNotificationListener.kt
git commit -m "refactor: worker listener delegates result notifications to the shared factory (TASK-327)"
```

---

## Chunk 3: Receiver wiring and verification

### Task 6: NotificationActionReceiver prev/next actions

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/receiver/NotificationActionReceiver.kt` (constants already added in Task 3)
- Create: `app/src/main/java/com/antivocale/app/receiver/ResultNotificationRefresher.kt`
- Modify: `app/src/main/AndroidManifest.xml` (receiver intent-filter, ~line 336)
- Test: `app/src/test/java/com/antivocale/app/receiver/ResultNotificationRefresherTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/antivocale/app/receiver/ResultNotificationRefresherTest.kt`:

```kotlin
package com.antivocale.app.receiver

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.service.TranscriptPager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Tests the prev/next re-post logic, called directly (the goAsync wrapper is covered on-device). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ResultNotificationRefresherTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Whole pages of "parola" words: n words occupy 7n - 1 chars (see plan conventions). */
    private fun longText(pages: Int): String {
        val perPage = (TranscriptPager.PAGE_CHARS + 1) / 7
        return List(pages * perPage) { "parola" }.joinToString(" ")
    }

    private fun pageIntent(action: String, text: String, page: Int, id: Int = 7): Intent =
        Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT, text)
            putExtra(NotificationActionReceiver.EXTRA_PAGE_INDEX, page)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, id)
            putExtra(NotificationActionReceiver.EXTRA_SOURCE_PACKAGE, "org.telegram.messenger")
            putExtra(NotificationActionReceiver.EXTRA_FIRST_POSTED_AT, 123_456L)
        }

    private fun shadowNm() =
        Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))

    private fun postedNotification(id: Int): Notification =
        requireNotNull(shadowNm().getNotification(id)) { "no notification posted with id $id" }

    private fun subTextOf(n: Notification): String? =
        n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

    @Test
    fun `next from page 0 re-posts same id at page 1`() = runBlocking {
        val text = longText(3)
        ResultNotificationRefresher.refresh(context, pageIntent(NotificationActionReceiver.ACTION_PAGE_NEXT, text, 0))
        val n = postedNotification(7)
        assertEquals("Page 2 of 3", subTextOf(n))
        assertTrue(n.actions!!.map { it.title }.contains("◀"))
    }

    @Test
    fun `prev from page 0 stays at page 0`() = runBlocking {
        val text = longText(2)
        ResultNotificationRefresher.refresh(context, pageIntent(NotificationActionReceiver.ACTION_PAGE_PREV, text, 0))
        assertEquals("Page 1 of 2", subTextOf(postedNotification(7)))
    }

    @Test
    fun `next clamps at the last page`() = runBlocking {
        val text = longText(2)
        ResultNotificationRefresher.refresh(context, pageIntent(NotificationActionReceiver.ACTION_PAGE_NEXT, text, 1))
        assertEquals("Page 2 of 2", subTextOf(postedNotification(7)))
    }

    @Test
    fun `missing notification id is a no-op`() = runBlocking {
        val intent = pageIntent(NotificationActionReceiver.ACTION_PAGE_NEXT, longText(2), 0, id = -1)
        ResultNotificationRefresher.refresh(context, intent)
        assertEquals(0, shadowNm().allNotifications.size)
    }

    @Test
    fun `missing text is a no-op`() = runBlocking {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_PAGE_NEXT
            putExtra(NotificationActionReceiver.EXTRA_PAGE_INDEX, 0)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, 7)
        }
        ResultNotificationRefresher.refresh(context, intent)
        assertEquals(0, shadowNm().allNotifications.size)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testPlayStoreDebugUnitTest --tests "com.antivocale.app.receiver.ResultNotificationRefresherTest"`
Expected: compilation FAIL with `unresolved reference: ResultNotificationRefresher`.

- [ ] **Step 3: Wire the receiver and add the refresher**

In `onReceive`'s `when`, add before the `else` branch (bare names, matching the surrounding branches):

```kotlin
            ACTION_PAGE_PREV, ACTION_PAGE_NEXT -> handlePageAction(context, intent)
```

And the handler (uses goAsync because `getCurrentPreferences` is a suspend DataStore read; the ~10 s window is ample):

```kotlin
    /**
     * Pages the result notification. The work runs in a coroutine because
     * [com.antivocale.app.data.PerAppPreferencesManager.getCurrentPreferences]
     * is a suspend DataStore call; if it fails, the old notification simply
     * stands (a button tap must never crash).
     */
    private fun handlePageAction(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                ResultNotificationRefresher.refresh(appContext, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rebuild paged notification", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
```

Add imports `kotlinx.coroutines.CoroutineScope`, `kotlinx.coroutines.Dispatchers`, and `kotlinx.coroutines.launch`, and update the class KDoc's "Handles" list.

Create `app/src/main/java/com/antivocale/app/receiver/ResultNotificationRefresher.kt`:

```kotlin
package com.antivocale.app.receiver

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.antivocale.app.data.AppNotificationPreferences
import com.antivocale.app.data.PerAppPreferencesManager
import com.antivocale.app.service.ResultNotificationFactory
import com.antivocale.app.service.ResultNotificationSpec
import com.antivocale.app.service.TranscriptPager

/**
 * Recomputes the neighbor page for a prev/next tap and re-posts the result
 * notification under its original id (TASK-327). All inputs come from the
 * intent extras the factory embedded; nothing is read from process state, so
 * paging works after InferenceService died and after process death (not
 * force-stop: the platform cancels notifications and blocks receivers for
 * stopped packages).
 */
object ResultNotificationRefresher {

    private const val TAG = "ResultNotificationRefresher"

    suspend fun refresh(appContext: Context, intent: Intent) {
        val text = intent.getStringExtra(NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT)
        val notificationId = intent.getIntExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, -1)
        if (text.isNullOrBlank() || notificationId == -1 || !TranscriptPager.isPaged(text)) {
            Log.w(TAG, "Page action with unusable extras (id=$notificationId, ${text?.length ?: 0} chars); ignoring")
            return
        }
        val pageCount = TranscriptPager.pagesFor(text).size
        val current = intent.getIntExtra(NotificationActionReceiver.EXTRA_PAGE_INDEX, 0)
            .coerceIn(0, pageCount - 1)
        val target = if (intent.action == NotificationActionReceiver.ACTION_PAGE_PREV) {
            (current - 1).coerceAtLeast(0)
        } else {
            (current + 1).coerceAtMost(pageCount - 1)
        }
        val sourcePackage = intent.getStringExtra(NotificationActionReceiver.EXTRA_SOURCE_PACKAGE)
        val prefs = try {
            if (sourcePackage != null) {
                PerAppPreferencesManager(appContext).getCurrentPreferences(sourcePackage)
            } else {
                AppNotificationPreferences.default()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get per-app preferences for $sourcePackage, using defaults", e)
            AppNotificationPreferences.default()
        }
        val spec = ResultNotificationSpec(
            transcriptionText = text,
            taskId = intent.getStringExtra(NotificationActionReceiver.EXTRA_TASK_ID),
            sourcePackage = sourcePackage,
            confidence = if (intent.hasExtra(NotificationActionReceiver.EXTRA_CONFIDENCE)) {
                intent.getFloatExtra(NotificationActionReceiver.EXTRA_CONFIDENCE, 0f)
            } else null,
            detectedLanguage = intent.getStringExtra(NotificationActionReceiver.EXTRA_DETECTED_LANGUAGE),
            isPartial = intent.getBooleanExtra(NotificationActionReceiver.EXTRA_IS_PARTIAL, false),
            failedChunkCount = intent.getIntExtra(NotificationActionReceiver.EXTRA_FAILED_CHUNK_COUNT, 0),
            pageIndex = target,
            notificationId = notificationId,
            firstPostedAt = intent.getLongExtra(
                NotificationActionReceiver.EXTRA_FIRST_POSTED_AT,
                System.currentTimeMillis()
            ),
            repost = true
        )
        val notification = ResultNotificationFactory(appContext).build(spec, prefs)
        NotificationManagerCompat.from(appContext).notify(notificationId, notification)
        Log.i(TAG, "Paged result notification to page ${target + 1}/$pageCount (id=$notificationId)")
    }
}
```

- [ ] **Step 4: Declare the actions in the manifest**

In `app/src/main/AndroidManifest.xml`, inside the `NotificationActionReceiver` intent-filter (~line 336), add:

```xml
                <action android:name="com.antivocale.app.PAGE_PREV" />
                <action android:name="com.antivocale.app.PAGE_NEXT" />
```

(Pattern consistency; the PendingIntents are component-explicit so the filter is not load-bearing.)

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testPlayStoreDebugUnitTest --tests "com.antivocale.app.receiver.ResultNotificationRefresherTest"`
Expected: PASS, 5 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/antivocale/app/receiver/NotificationActionReceiver.kt \
        app/src/main/java/com/antivocale/app/receiver/ResultNotificationRefresher.kt \
        app/src/test/java/com/antivocale/app/receiver/ResultNotificationRefresherTest.kt \
        app/src/main/AndroidManifest.xml
git commit -m "feat: prev/next paging actions on the result notification (TASK-327)"
```

### Task 7: Full verification (gates before device)

- [ ] **Step 1: Full unit suite**

Run: `./gradlew :app:testPlayStoreDebugUnitTest`
Expected: PASS, zero failures.

- [ ] **Step 2: Compile the app**

Run: `./gradlew assemblePlayStoreDebug`
Expected: BUILD SUCCESSFUL. No new native/JNI code: no proguard-rules changes are needed for this feature (existing keep rules cover the packages; nothing here uses reflection).

- [ ] **Step 3: Code review and simplification gates**

Run `/code-review` (high) and then `/simplify` on the diff (`git diff 0534c5d..HEAD` covers the whole feature). Fix what they surface; re-run Step 1 after any change. These gates are mandatory before on-device work.

- [ ] **Step 4: Commit any review fixes**

```bash
git add -A app/src/main app/src/test
git commit -m "refactor: review and simplification pass on notification paging (TASK-327)"
```

### Task 8: On-device verification (Realme RMX3853, Android 16)

- [ ] **Step 1: Install**

Run: `./scripts/install.sh`
Expected: installs the debug build. Remember: the debug package is `com.antivocale.app.debug`, distinct from the user's production install; drive the debug one.

- [ ] **Step 2: Trigger a paged transcription**

Share a voice note longer than ~1 minute (enough for >400 chars) from Telegram to Anti-Vocale. Pick the share target explicitly labeled "Anti-Vocale (Debug)" so the pick cannot land on the production install. Wait for the result notification (the service has now stopped; paging must not care).

Expected: subtext "Page 1 of N", actions Copy, Send to Telegram, ▶.

- [ ] **Step 3: Page forward and back**

Tap ▶ repeatedly to the last page, then ◀ back.
Expected: subtext updates (Page 2 of N, ...), no alert sound or vibration on page changes, the notification does not jump to the top of the shade (firstPostedAt preserved), ◀ appears from page 2 on, ▶ disappears on the last page.

- [ ] **Step 4: Verify copy copies the FULL text**

On any middle page, tap Copy and paste somewhere.
Expected: the entire transcript, not just the visible page.

- [ ] **Step 5: Verify survival of process death**

Run: `D=$(~/Android/Sdk/platform-tools/adb devices | sed -n 's/^\(.*_adb-tls-connect\._tcp\)[[:space:]]*device$/\1/p')` then `~/Android/Sdk/platform-tools/adb -s "$D" shell am kill com.antivocale.app.debug` (kills the background process WITHOUT the stopped state of force-stop). Confirm the kill took effect: `~/Android/Sdk/platform-tools/adb -s "$D" shell pidof com.antivocale.app.debug` must print nothing (`am kill` is a no-op on a foreground process; if a pid prints, put the app in the background first and re-run).
Then tap ▶ again.
Expected: the process restarts via the receiver and paging still works.

- [ ] **Step 6: Check the expanded fourth action**

Expand a middle-page notification.
Expected: all four actions (Copy, Send to Telegram, ▶, ◀) visible. RECORD THE OUTCOME in the task notes. If the platform does not surface the fourth action: apply the documented fallback (omit the Share action on middle pages, paging stays fully functional) as a follow-up commit and note the decision in TASK-327.

- [ ] **Step 7: Check the page fits the expanded view**

With a notification expanded, read a full page.
Expected: the entire page visible without system clamping. If clamped: lower `TranscriptPager.PAGE_CHARS` (try 300), re-run the unit suite (fixtures are parameterized on the constant), reinstall, re-check; record the final value in TASK-327.

- [ ] **Step 8: Regression spot-check**

Transcribe a SHORT voice note (<100 chars) and a medium one (~250 chars).
Expected: short one identical to today; medium one now fully readable in one page with no "100 of N chars" counter.

### Task 9: Finalization

- [ ] **Step 1: /pa:reflect** (did we solve the right problem: reading long transcripts from the shade)
- [ ] **Step 2: superpowers:verification-before-completion** (run every proving command from Tasks 7-8 once more, read the outputs, only then claim done)
- [ ] **Step 3: Update TASK-327** via backlog MCP: acceptance criteria checked off with evidence (commands + outcomes), final summary, any PAGE_CHARS or fallback decisions recorded.
- [ ] **Step 4: Final commit** if anything remains (docs, strings), then report.

---

## Risks and contingencies

- **Robolectric accessors**: the plan uses `extras.getCharSequence(Notification.EXTRA_SUB_TEXT)` for subtext, `flags and Notification.FLAG_ONLY_ALERT_ONCE` for the alert-once flag, `Shadows.shadowOf(pendingIntent).savedIntent` for wrapped intents, and `Shadows.shadowOf(notificationManager).getNotification(id)` / `.allNotifications` for posts. If the pinned Robolectric version names any of these differently, adjust the accessor only; assertions are the contract.
- **Expanded 4th action on Android 16**: unproven; Task 8 Step 6 is the decision point, fallback documented in the spec.
- **PAGE_CHARS vs expanded clamp**: Task 8 Step 7 tunes the constant. All word-count fixtures derive from `TranscriptPager.PAGE_CHARS` (see plan-header conventions), so retuning does not break the suite; only the two tests that intentionally pin exact-boundary shapes use `PAGE_CHARS`-relative arithmetic as well.
- **No new R8 surface**: no reflection, no native code; existing keep rules are unaffected (Task 7 Step 2 note).
