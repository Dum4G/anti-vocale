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
}
