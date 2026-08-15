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

    /** Above this length no nav actions are attached: the nav intents carry the full text, and binder transactions cap around 1 MB. The guard also accounts for the text being embedded up to four times in one notification (copy, share, prev, next PendingIntents). */
    const val MAX_PAGED_LENGTH = 50_000

    private val WHITESPACE = Regex("\\s+")

    /** Paging is active iff there is more than one page and the text is under the binder guard. */
    fun isPaged(text: String): Boolean =
        text.length <= MAX_PAGED_LENGTH && pagesFor(text).size >= 2

    /**
     * Word-aligned split: accumulate whitespace-separated words while the page
     * stays within [PAGE_CHARS]; a single word longer than the limit is
     * hard-cut into [PAGE_CHARS]-sized pages (rejoin by direct concatenation,
     * no space inserted). Whitespace-normalized concatenation of the pages
     * equals the original text. Whitespace-only input returns the original
     * verbatim without paging.
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
