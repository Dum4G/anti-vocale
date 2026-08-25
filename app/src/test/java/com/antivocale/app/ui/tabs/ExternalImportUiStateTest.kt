package com.antivocale.app.ui.tabs

import com.antivocale.app.data.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Defaults of the external-import selection state: the SenseVoice ITN switch must
 * default OFF, matching sherpa's default and the record semantics (an absent or
 * "false" option means no inverse text normalization).
 */
class ExternalImportUiStateTest {

    @Test
    fun `sensevoice itn defaults to false and the default options record it off`() {
        val state = ExternalImportUiState(family = ModelFamily.SENSE_VOICE)
        assertEquals(false, state.sensevoiceItn)
        assertEquals(mapOf("sensevoice.itn" to "false"), state.options())
    }

    @Test
    fun `decode language feeds the whisper option and derives the record tags (TASK-401)`() {
        val state = ExternalImportUiState(family = ModelFamily.WHISPER, decodeLanguage = "de")
        assertEquals(mapOf("whisper.language" to "de"), state.options())
        assertEquals(listOf("de"), state.languageCodes())
    }

    @Test
    fun `blank decode language means auto-detect with no option and no tags`() {
        val state = ExternalImportUiState(family = ModelFamily.WHISPER)
        assertEquals(emptyMap<String, String>(), state.options())
        assertEquals(emptyList<String>(), state.languageCodes())
        // families without a language option never emit one
        assertEquals(emptyMap<String, String>(), ExternalImportUiState(family = ModelFamily.TRANSDUCER, decodeLanguage = "de").options())
    }
}
