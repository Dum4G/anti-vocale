package com.antivocale.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the bundled external-model catalog index: the pure query matcher
 * and the index parsing (TASK-331 Task 13). The matcher is deliberately dumb and
 * token-based: it must stay pure so the URL-dialog autocomplete is testable.
 */
class ExternalCatalogTest {

    @Test
    fun `query matches language code exactly and by prefix`() {
        assertTrue(ExternalCatalog.matchesQuery("Arabic Whisper", listOf("ar"), "ar"))
        assertTrue(ExternalCatalog.matchesQuery("Portuguese", listOf("pt-BR"), "pt"))
        assertTrue(ExternalCatalog.matchesQuery("Portuguese", listOf("pt-BR"), "pt-br"))
        assertFalse(ExternalCatalog.matchesQuery("Arabic Whisper", listOf("ar"), "ru"))
    }

    @Test
    fun `query matches name case-insensitively`() {
        assertTrue(ExternalCatalog.matchesQuery("Whisper Large v3 Turbo Arabic", listOf("ar"), "arabic"))
        assertTrue(ExternalCatalog.matchesQuery("Whisper Large v3 Turbo Arabic", listOf("ar"), "Whisper"))
        assertFalse(ExternalCatalog.matchesQuery("Whisper Large v3 Turbo Arabic", listOf("ar"), "gigaam"))
    }

    @Test
    fun `multi-token query requires every token to match`() {
        assertTrue(ExternalCatalog.matchesQuery("Whisper Arabic", listOf("ar"), "whisper ar"))
        assertFalse(ExternalCatalog.matchesQuery("Whisper Arabic", listOf("ar"), "whisper ru"))
    }

    @Test
    fun `blank query matches everything`() {
        assertTrue(ExternalCatalog.matchesQuery("Anything", emptyList(), ""))
        assertTrue(ExternalCatalog.matchesQuery("Anything", emptyList(), "   "))
    }

    @Test
    fun `index parses entries with name languages family and entry url`() {
        val index = """
            {"entries": [
              {"name": "Whisper Arabic", "languages": ["ar"], "family": "WHISPER",
               "entryUrl": "https://example.com/arabic.json"}
            ]}
        """.trimIndent()
        val entries = ExternalCatalog.parseIndex(index)
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("Whisper Arabic", e.name)
        assertEquals(listOf("ar"), e.languages)
        assertEquals(ModelFamily.WHISPER, e.family)
        assertEquals("https://example.com/arabic.json", e.entryUrl)
    }

    @Test
    fun `index entry without family defaults to transducer and malformed entries are skipped`() {
        val index = """
            {"entries": [
              {"name": "GigaAM", "languages": ["ru"], "entryUrl": "https://example.com/ru.json"},
              {"name": "broken"}
            ]}
        """.trimIndent()
        val entries = ExternalCatalog.parseIndex(index)
        assertEquals(1, entries.size)
        assertEquals(ModelFamily.TRANSDUCER, entries[0].family)
        assertEquals("ru", entries[0].languages.single())
    }

    @Test
    fun `unknown family string is skipped rather than crashing the dialog`() {
        val index = """
            {"entries": [
              {"name": "X", "languages": ["en"], "family": "FIRERED", "entryUrl": "https://example.com/x.json"}
            ]}
        """.trimIndent()
        assertEquals(0, ExternalCatalog.parseIndex(index).size)
    }

    @Test
    fun `filter returns entries matching the query in input order`() {
        val entries = listOf(
            ExternalCatalog.CatalogEntry("Whisper Arabic", listOf("ar"), "u1", ModelFamily.WHISPER),
            ExternalCatalog.CatalogEntry("GigaAM v3", listOf("ru"), "u2", ModelFamily.TRANSDUCER),
        )
        assertEquals(listOf("u1"), ExternalCatalog.filter(entries, "ar").map { it.entryUrl })
        assertEquals(entries, ExternalCatalog.filter(entries, ""))
        assertEquals(emptyList<ExternalCatalog.CatalogEntry>(), ExternalCatalog.filter(entries, "zh"))
    }

    @Test
    fun `bundled asset index parses and is empty pending a sherpa-compatible entry`() {
        // The OpenVoiceOS arabic export is NOT sherpa-onnx loadable (optimum decoder
        // signature, no k2-fsa metadata; device-verified 2026-08-17, TASK-331 Task 15),
        // so its entry was removed until a converted mirror ships (follow-up task).
        // The index must still parse; the empty list must not crash the autocomplete.
        val text = java.io.File("src/main/assets/external-catalog/index.json").readText()
        val entries = ExternalCatalog.parseIndex(text)
        assertEquals(emptyList<ExternalCatalog.CatalogEntry>(), entries)
        assertEquals(emptyList<ExternalCatalog.CatalogEntry>(), ExternalCatalog.filter(entries, "arabic"))
    }
}
