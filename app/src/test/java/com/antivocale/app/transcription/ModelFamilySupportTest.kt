package com.antivocale.app.transcription

import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelSource
import com.antivocale.app.data.FilePin
import com.antivocale.app.data.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Family support table tests (plan v2b Tasks 4-7): one suite per support object,
 * covering role planning (including the real-world export namings), metadata
 * routing, and sherpa config construction.
 */
class ModelFamilySupportTest {

    private fun record(
        family: ModelFamily,
        modelType: String = "",
        languages: List<String> = emptyList(),
        options: Map<String, String> = emptyMap(),
    ) = ExternalModelRecord(
        id = "abc123", displayName = "test", dir = "/models/external/test-abc123",
        family = family, modelType = modelType, languages = languages,
        source = ExternalModelSource.LOCAL, sourceUrl = null,
        files = emptyMap(), sizeBytes = 0L, importedAt = 0L, options = options,
    )

    // ---- Task 4: TransducerSupport (behavior moved verbatim from the importer) ----

    @Test
    fun `transducer plan maps roles by keyword and rejects missing roles`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.TRANSDUCER)
        val plan = support.buildCopyPlan(listOf("gigaam_encoder_int8.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"))
        assertEquals("gigaam_encoder_int8.onnx", plan!!["encoder.int8.onnx"])
        assertEquals("decoder.onnx", plan["decoder.int8.onnx"])
        assertEquals("joiner.onnx", plan["joiner.int8.onnx"])
        assertEquals("tokens.txt", plan["tokens.txt"])

        assertNull(support.buildCopyPlan(listOf("tokens.txt")))
        assertNull(support.buildCopyPlan(emptyList()))
    }

    @Test
    fun `transducer plan accepts gigaam joint naming and rnnt-hinted vocab`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.TRANSDUCER)
        val gigaam = support.buildCopyPlan(listOf(
            "gigaam_v3_e2e_rnnt_encoder_int8.onnx",
            "gigaam_v3_e2e_rnnt_decoder.onnx",
            "gigaam_v3_e2e_rnnt_joint.onnx",
            "gigaam_v3_e2e_rnnt_tokens.txt",
        ))
        assertEquals("gigaam_v3_e2e_rnnt_joint.onnx", gigaam!!["joiner.int8.onnx"])

        val istupakov = support.buildCopyPlan(listOf(
            "v3_e2e_rnnt_encoder.int8.onnx",
            "v3_e2e_rnnt_decoder.int8.onnx",
            "v3_e2e_rnnt_joint.int8.onnx",
            "v3_e2e_rnnt_vocab.txt",
        ))
        assertEquals("v3_e2e_rnnt_vocab.txt", istupakov!!["tokens.txt"])
    }

    @Test
    fun `transducer metadata routing delegates to the sherpa rule`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.TRANSDUCER)
        assertEquals("encoder.int8.onnx", support.metadataFileRole())
        assertEquals(listOf("vocab_size", "subsampling_factor", "model_type"), support.metadataKeys("nemo_transducer"))
        assertEquals(listOf("vocab_size"), support.metadataKeys(""))
    }

    @Test
    fun `transducer requiredRoles lists the four canonical files`() {
        assertEquals(
            listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
            ModelFamilySupport.forFamily(ModelFamily.TRANSDUCER).requiredRoles(),
        )
    }

    @Test
    fun `transducer model config mirrors the external engine block`() {
        val config = ModelFamilySupport.forFamily(ModelFamily.TRANSDUCER)
            .buildModelConfig(record(ModelFamily.TRANSDUCER, modelType = "nemo_transducer"), numThreads = 4, provider = "cpu")
        assertEquals("/models/external/test-abc123/encoder.int8.onnx", config.transducer.encoder)
        assertEquals("/models/external/test-abc123/decoder.int8.onnx", config.transducer.decoder)
        assertEquals("/models/external/test-abc123/joiner.int8.onnx", config.transducer.joiner)
        assertEquals("/models/external/test-abc123/tokens.txt", config.tokens)
        assertEquals("nemo_transducer", config.modelType)
        assertEquals(4, config.numThreads)
        assertEquals("cpu", config.provider)
    }

    // ---- Task 5: WhisperSupport ----

    @Test
    fun `whisper plan maps encoder decoder tokens and rejects joiner files`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.WHISPER)
        val plan = support.buildCopyPlan(listOf("base-encoder.int8.onnx", "base-decoder.int8.onnx", "base-tokens.txt"))
        assertEquals("base-encoder.int8.onnx", plan!!["encoder.int8.onnx"])
        assertEquals("base-decoder.int8.onnx", plan["decoder.int8.onnx"])
        assertEquals("base-tokens.txt", plan["tokens.txt"])
        assertNull(support.buildCopyPlan(listOf("encoder.onnx", "decoder.onnx")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `whisper plan rejects files containing joiner or joint keywords`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.WHISPER)
        support.buildCopyPlan(listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"))
    }

    @Test
    fun `whisper requiredRoles lists encoder decoder and tokens`() {
        assertEquals(
            listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"),
            ModelFamilySupport.forFamily(ModelFamily.WHISPER).requiredRoles(),
        )
    }

    @Test
    fun `whisper metadata routing points at encoder and checks model_type`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.WHISPER)
        assertEquals("encoder.int8.onnx", support.metadataFileRole())
        assertEquals(listOf("model_type"), support.metadataKeys(""))
    }

    @Test
    fun `whisper model config builds OfflineWhisperModelConfig with language and task`() {
        val config = ModelFamilySupport.forFamily(ModelFamily.WHISPER)
            .buildModelConfig(
                record(ModelFamily.WHISPER, options = mapOf("whisper.language" to "it")),
                numThreads = 4, provider = "cpu",
            )
        assertEquals("/models/external/test-abc123/encoder.int8.onnx", config.whisper.encoder)
        assertEquals("/models/external/test-abc123/decoder.int8.onnx", config.whisper.decoder)
        assertEquals("it", config.whisper.language)
        assertEquals("transcribe", config.whisper.task)
        assertEquals("whisper", config.modelType)
        assertEquals(4, config.numThreads)
        assertEquals("cpu", config.provider)
    }

    @Test
    fun `whisper language defaults to record first language then empty string`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.WHISPER)
        // No option, no record language -> empty (auto)
        val autoConfig = support.buildModelConfig(record(ModelFamily.WHISPER), numThreads = 1, provider = "cpu")
        assertEquals("", autoConfig.whisper.language)

        // No option, record has language -> first language
        val langConfig = support.buildModelConfig(
            record(ModelFamily.WHISPER, languages = listOf("ar", "en")), numThreads = 1, provider = "cpu")
        assertEquals("ar", langConfig.whisper.language)

        // Option overrides record language
        val optConfig = support.buildModelConfig(
            record(ModelFamily.WHISPER, languages = listOf("ar"), options = mapOf("whisper.language" to "en")),
            numThreads = 1, provider = "cpu")
        assertEquals("en", optConfig.whisper.language)
    }
}
