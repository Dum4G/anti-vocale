package com.antivocale.app.receiver

import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.transcription.BackendRegistry
import com.antivocale.app.transcription.emptyRecordsProvider
import org.junit.Assert.*
import org.junit.Test

class ShareReceiverActivityAliasTest {

    /** Registry with no external records: the static alias set only. */
    private val registry = BackendRegistry(
        ExternalModelStore(FakePreferencesManager(), dirExists = { true }),
        emptyRecordsProvider(),
    )

    @Test
    fun `parakeet alias maps to sherpa-onnx backend`() {
        assertEquals("sherpa-onnx", ShareReceiverActivity.backendIdForAlias("com.antivocale.app.ShareParakeet", registry))
    }

    @Test
    fun `whisper alias maps to whisper backend`() {
        assertEquals("whisper", ShareReceiverActivity.backendIdForAlias("com.antivocale.app.ShareWhisper", registry))
    }

    @Test
    fun `gemma alias maps to llm backend`() {
        assertEquals("llm", ShareReceiverActivity.backendIdForAlias("com.antivocale.app.ShareGemma", registry))
    }

    @Test
    fun `default activity class returns null`() {
        assertNull(ShareReceiverActivity.backendIdForAlias("com.antivocale.app.receiver.ShareReceiverActivity", registry))
    }

    @Test
    fun `unknown class returns null`() {
        assertNull(ShareReceiverActivity.backendIdForAlias("com.antivocale.app.UnknownActivity", registry))
    }

    @Test
    fun `qwen3 alias maps to qwen3-asr backend`() {
        assertEquals("qwen3-asr", ShareReceiverActivity.backendIdForAlias("com.antivocale.app.ShareQwen3", registry))
    }

    @Test
    fun `nemotron alias maps to nemotron-streaming backend`() {
        assertEquals(
            "nemotron-streaming",
            ShareReceiverActivity.backendIdForAlias("com.antivocale.app.ShareNemotron", registry)
        )
    }

    @Test
    fun `empty string returns null (no static backend carries the blank alias)`() {
        assertNull(ShareReceiverActivity.backendIdForAlias("", registry))
    }

    @Test
    fun `share external family alias resolves to the sentinel`() {
        assertEquals(
            "external",
            ShareReceiverActivity.backendIdForAlias("com.antivocale.app.ShareExternal", registry)
        )
    }
}
