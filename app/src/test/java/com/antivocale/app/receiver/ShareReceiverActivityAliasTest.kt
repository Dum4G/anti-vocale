package com.antivocale.app.receiver

import com.antivocale.app.transcription.CustomTransducerBackend
import org.junit.Assert.*
import org.junit.Test

class ShareReceiverActivityAliasTest {

    @Test
    fun `parakeet alias maps to sherpa-onnx backend`() {
        assertEquals("sherpa-onnx", ShareReceiverActivity.backendIdForAlias("com.antivocale.app.ShareParakeet"))
    }

    @Test
    fun `whisper alias maps to whisper backend`() {
        assertEquals("whisper", ShareReceiverActivity.backendIdForAlias("com.antivocale.app.ShareWhisper"))
    }

    @Test
    fun `gemma alias maps to llm backend`() {
        assertEquals("llm", ShareReceiverActivity.backendIdForAlias("com.antivocale.app.ShareGemma"))
    }

    @Test
    fun `default activity class returns null`() {
        assertNull(ShareReceiverActivity.backendIdForAlias("com.antivocale.app.receiver.ShareReceiverActivity"))
    }

    @Test
    fun `unknown class returns null`() {
        assertNull(ShareReceiverActivity.backendIdForAlias("com.antivocale.app.UnknownActivity"))
    }

    @Test
    fun `qwen3 alias maps to qwen3-asr backend`() {
        assertEquals("qwen3-asr", ShareReceiverActivity.backendIdForAlias("com.antivocale.app.ShareQwen3"))
    }

    @Test
    fun `nemotron alias maps to nemotron-streaming backend`() {
        assertEquals(
            "nemotron-streaming",
            ShareReceiverActivity.backendIdForAlias("com.antivocale.app.ShareNemotron")
        )
    }

    @Test
    fun `empty string returns custom-transducer backend id`() {
        assertEquals(
            CustomTransducerBackend.BACKEND_ID,
            ShareReceiverActivity.backendIdForAlias("")
        )
    }
}
