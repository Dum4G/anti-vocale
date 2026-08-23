package com.antivocale.app.transcription

/**
 * TASK-370: prompt routing for chunked audio on the LLM backend.
 *
 * A user's Default Transcription Prompt can be generative ("summarize and
 * rewrite this voice note"): applying it per 30s chunk would process each
 * fragment independently and concatenate the results, mangling the output.
 * Chunk mode therefore transcribes every chunk with a plain instruction and,
 * only when the user prompt is custom, runs it ONCE as a final text-only
 * pass over the concatenated transcript. With the built-in default prompt the
 * transcript already is the deliverable, so the final pass is skipped.
 */
object ChunkPromptPolicy {

    /** Fallback used by TranscriptionOrchestrator.resolvePrompt when nothing is set. */
    const val DEFAULT_AUDIO_PROMPT = "Transcribe the following audio recording."

    /** Verbatim, no-commentary instruction used for LLM chunks in multi-chunk mode. */
    const val PLAIN_TRANSCRIPTION_PROMPT =
        "Transcribe the following audio segment verbatim. Output only the transcription."

    /** Prompt each chunk's transcribeAudio call receives. */
    fun perChunkPrompt(backendId: String, resolvedPrompt: String): String =
        if (backendId == LlmTranscriptionBackend.BACKEND_ID && isCustom(resolvedPrompt)) {
            PLAIN_TRANSCRIPTION_PROMPT
        } else {
            resolvedPrompt
        }

    /** True when the final text-only pass must run after concatenation. */
    fun shouldRunFinalGenerativePass(backendId: String, resolvedPrompt: String): Boolean =
        backendId == LlmTranscriptionBackend.BACKEND_ID && isCustom(resolvedPrompt)

    fun finalPrompt(userPrompt: String, transcript: String): String =
        "$userPrompt\n\nTranscript:\n$transcript"

    private fun isCustom(resolvedPrompt: String): Boolean =
        resolvedPrompt.isNotBlank() && resolvedPrompt != DEFAULT_AUDIO_PROMPT
}
