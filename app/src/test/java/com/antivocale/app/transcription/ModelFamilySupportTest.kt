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
}
