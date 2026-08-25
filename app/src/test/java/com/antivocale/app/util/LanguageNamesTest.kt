package com.antivocale.app.util

import com.antivocale.app.transcription.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageNamesTest {

    @Test
    fun `every filter entry code resolves to a real native name`() {
        Language.FILTER_ENTRIES.forEach { code ->
            val name = LanguageNames.nativeLanguageName(code)
            assertTrue("Expected non-empty name for $code", name.isNotBlank())
            assertNotEquals(
                "Expected ICU resolution for $code, got the raw code back", code, name
            )
        }
    }

    @Test
    fun `capitalizes the first character of lowercase CLDR forms`() {
        assertEquals("Русский", LanguageNames.nativeLanguageName("ru"))
        assertEquals("Deutsch", LanguageNames.nativeLanguageName("de"))
    }

    @Test
    fun `region qualified tag keeps the region suffix`() {
        val name = LanguageNames.nativeLanguageName("pt-BR")
        assertTrue("expected region suffix in '$name'", name.startsWith("Português") && name.contains("("))
    }

    @Test
    fun `unresolvable codes fall back to the raw code`() {
        assertEquals("zz", LanguageNames.nativeLanguageName("zz"))
        assertEquals("", LanguageNames.nativeLanguageName(""))
        assertEquals("  ", LanguageNames.nativeLanguageName("  "))
    }
}
