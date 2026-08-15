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
