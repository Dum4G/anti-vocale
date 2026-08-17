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

    // ---- Task 6: CtcSupport ----

    @Test
    fun `ctc plan maps encoder and tokens`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.CTC)
        val plan = support.buildCopyPlan(listOf("v3_ctc.int8.onnx", "v3_e2e_ctc_vocab.txt"))
        assertEquals("v3_ctc.int8.onnx", plan!!["encoder.int8.onnx"])
        assertEquals("v3_e2e_ctc_vocab.txt", plan["tokens.txt"])
        assertNull(support.buildCopyPlan(listOf("encoder.onnx")))
    }

    @Test
    fun `ctc plan picks ctc-hinted vocab over rnnt-hinted in mixed pool`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.CTC)
        // istupakov repo ships both v3_e2e_rnnt_vocab.txt and v3_e2e_ctc_vocab.txt
        val plan = support.buildCopyPlan(listOf(
            "v3_ctc.int8.onnx",
            "v3_e2e_ctc_vocab.txt",
            "v3_e2e_rnnt_vocab.txt",
        ))
        assertEquals("v3_e2e_ctc_vocab.txt", plan!!["tokens.txt"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ctc plan rejects files containing joiner or joint keywords`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.CTC)
        support.buildCopyPlan(listOf("encoder.onnx", "joiner.onnx", "tokens.txt"))
    }

    @Test
    fun `ctc requiredRoles lists encoder and tokens`() {
        assertEquals(
            listOf("encoder.int8.onnx", "tokens.txt"),
            ModelFamilySupport.forFamily(ModelFamily.CTC).requiredRoles(),
        )
    }

    @Test
    fun `ctc metadata routing returns empty keys`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.CTC)
        assertEquals("encoder.int8.onnx", support.metadataFileRole())
        assertEquals(emptyList<String>(), support.metadataKeys(""))
    }

    @Test
    fun `ctc nemo model config builds OfflineNemoEncDecCtcModelConfig`() {
        val config = ModelFamilySupport.forFamily(ModelFamily.CTC)
            .buildModelConfig(record(ModelFamily.CTC, modelType = "nemo_ctc"), numThreads = 4, provider = "cpu")
        assertEquals("/models/external/test-abc123/encoder.int8.onnx", config.nemo.model)
        assertEquals("nemo_ctc", config.modelType)
        assertEquals(4, config.numThreads)
        assertEquals("cpu", config.provider)
    }

    @Test
    fun `ctc zipformer model config builds OfflineZipformerCtcModelConfig`() {
        val config = ModelFamilySupport.forFamily(ModelFamily.CTC)
            .buildModelConfig(record(ModelFamily.CTC, modelType = "zipformer_ctc"), numThreads = 2, provider = "nnpapi")
        assertEquals("/models/external/test-abc123/encoder.int8.onnx", config.zipformerCtc.model)
        assertEquals("zipformer_ctc", config.modelType)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ctc buildModelConfig rejects unknown modelType`() {
        ModelFamilySupport.forFamily(ModelFamily.CTC)
            .buildModelConfig(record(ModelFamily.CTC, modelType = "bad_type"), numThreads = 1, provider = "cpu")
    }

    // ---- Task 7: SenseVoiceSupport ----

    @Test
    fun `sensevoice plan maps model and tokens`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.SENSE_VOICE)
        val plan = support.buildCopyPlan(listOf("sense_voice.onnx", "tokens.txt"))
        assertEquals("sense_voice.onnx", plan!!["model.int8.onnx"])
        assertEquals("tokens.txt", plan["tokens.txt"])
        assertNull(support.buildCopyPlan(listOf("tokens.txt")))
    }

    @Test
    fun `sensevoice model keyword does not match encoder files`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.SENSE_VOICE)
        // If only an encoder .onnx is present (no sense_voice/model keyword), plan is null.
        assertNull(support.buildCopyPlan(listOf("encoder.int8.onnx", "tokens.txt")))
    }

    @Test
    fun `sensevoice requiredRoles lists model and tokens`() {
        assertEquals(
            listOf("model.int8.onnx", "tokens.txt"),
            ModelFamilySupport.forFamily(ModelFamily.SENSE_VOICE).requiredRoles(),
        )
    }

    @Test
    fun `sensevoice metadata routing points at model file`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.SENSE_VOICE)
        assertEquals("model.int8.onnx", support.metadataFileRole())
        assertEquals(emptyList<String>(), support.metadataKeys(""))
    }

    @Test
    fun `sensevoice model config builds OfflineSenseVoiceModelConfig`() {
        val config = ModelFamilySupport.forFamily(ModelFamily.SENSE_VOICE)
            .buildModelConfig(
                record(ModelFamily.SENSE_VOICE, options = mapOf("sensevoice.language" to "zh", "sensevoice.itn" to "true")),
                numThreads = 4, provider = "cpu",
            )
        assertEquals("/models/external/test-abc123/model.int8.onnx", config.senseVoice.model)
        assertEquals("zh", config.senseVoice.language)
        assertEquals(true, config.senseVoice.useInverseTextNormalization)
        assertEquals("sense_voice", config.modelType)
        assertEquals(4, config.numThreads)
        assertEquals("cpu", config.provider)
    }

    @Test
    fun `sensevoice language defaults to empty and itn defaults to false`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.SENSE_VOICE)
        val config = support.buildModelConfig(record(ModelFamily.SENSE_VOICE), numThreads = 1, provider = "cpu")
        assertEquals("", config.senseVoice.language)
        assertEquals(false, config.senseVoice.useInverseTextNormalization)
    }

    @Test
    fun `sensevoice itn parses true and 1 as enabled`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.SENSE_VOICE)
        val trueConfig = support.buildModelConfig(
            record(ModelFamily.SENSE_VOICE, options = mapOf("sensevoice.itn" to "true")), numThreads = 1, provider = "cpu")
        assertEquals(true, trueConfig.senseVoice.useInverseTextNormalization)

        val oneConfig = support.buildModelConfig(
            record(ModelFamily.SENSE_VOICE, options = mapOf("sensevoice.itn" to "1")), numThreads = 1, provider = "cpu")
        assertEquals(true, oneConfig.senseVoice.useInverseTextNormalization)

        val falseConfig = support.buildModelConfig(
            record(ModelFamily.SENSE_VOICE, options = mapOf("sensevoice.itn" to "0")), numThreads = 1, provider = "cpu")
        assertEquals(false, falseConfig.senseVoice.useInverseTextNormalization)
    }
}
