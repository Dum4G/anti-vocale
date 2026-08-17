package com.antivocale.app.transcription

import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ModelFamily
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig

/**
 * The family support table (spec: multi-family external models): per-family copy
 * planning, metadata routing, and sherpa config construction behind one interface,
 * so the importer (import-time) and [ExternalSherpaBackend] (load-time) share a
 * single definition and cannot drift.
 *
 * Each support object's KDoc doubles as the per-family documentation the spec
 * requires: expected file sets and the record [ExternalModelRecord.modelType]
 * mapping.
 */
sealed interface ModelFamilySupport {
    val family: ModelFamily

    /** Canonical file names every import of this family must produce. */
    fun requiredRoles(): List<String>

    /** Maps source file names to canonical role names; null when any role has no candidate. */
    fun buildCopyPlan(files: List<String>): Map<String, String>?

    /** The canonical file the pre-native metadata check reads. */
    fun metadataFileRole(): String

    /** Metadata keys required for [modelType], for pre-native validation (exit(255) guard). */
    fun metadataKeys(modelType: String): List<String>

    /** Builds the sherpa [OfflineModelConfig] for [record] (engine-side). */
    fun buildModelConfig(record: ExternalModelRecord, numThreads: Int, provider: String): OfflineModelConfig

    companion object {
        fun forFamily(family: ModelFamily): ModelFamilySupport = when (family) {
            ModelFamily.TRANSDUCER -> TransducerSupport
            ModelFamily.WHISPER -> WhisperSupport
            ModelFamily.CTC -> CtcSupport
            ModelFamily.SENSE_VOICE -> SenseVoiceSupport
        }
    }
}

/**
 * Transducer (RNNT) family: the original v2a import shape.
 *
 * Expected file set: encoder + decoder + joiner .onnx plus a tokens/vocab .txt.
 * The joiner also answers to "joint" (GigaAM v3 ships
 * gigaam_v3_e2e_rnnt_joint.onnx: the RNNT file name, unlike sherpa's config key).
 * Tokens prefers exact names, then rnnt-hinted and ctc-free candidates (repos
 * shipping both CTC and RNNT variants have multiple vocab files).
 *
 * Record modelType: "nemo_transducer", "conformer_transducer", or empty, passed
 * straight through to [OfflineModelConfig.modelType].
 */
object TransducerSupport : ModelFamilySupport {
    override val family: ModelFamily = ModelFamily.TRANSDUCER

    override fun requiredRoles(): List<String> = listOf(
        SherpaOnnxBackend.CANONICAL_ENCODER,
        SherpaOnnxBackend.CANONICAL_DECODER,
        SherpaOnnxBackend.CANONICAL_JOINER,
        SherpaOnnxBackend.CANONICAL_TOKENS,
    )

    override fun buildCopyPlan(files: List<String>): Map<String, String>? {
        fun findByRole(vararg keywords: String) =
            files.firstOrNull { f -> f.endsWith(".onnx") && keywords.any { f.contains(it, ignoreCase = true) } }
        val encoder = findByRole("encoder") ?: return null
        val decoder = findByRole("decoder") ?: return null
        val joiner = findByRole("joiner", "joint") ?: return null
        // Tokens: prefer exact names, then family-aware matching. Repos that ship
        // both CTC and RNNT variants (istupakov) have multiple vocab files; a bare
        // contains("vocab") over an alphabetical listing picks the CTC one for an
        // RNNT import. The matcher prefers rnnt-hinted and ctc-free candidates.
        fun isTokensLike(name: String) = name.contains("tokens", ignoreCase = true) || name.contains("vocab", ignoreCase = true)
        val tokens = files.firstOrNull { it.equals("tokens.txt", ignoreCase = true) }
            ?: files.firstOrNull { it.equals("vocab.txt", ignoreCase = true) }
            ?: files.firstOrNull { it.endsWith(".txt") && isTokensLike(it) && it.contains("rnnt", ignoreCase = true) }
            ?: files.firstOrNull { it.endsWith(".txt") && isTokensLike(it) && !it.contains("ctc", ignoreCase = true) }
            ?: files.firstOrNull { it.endsWith(".txt") && isTokensLike(it) }
            ?: return null
        return linkedMapOf(
            SherpaOnnxBackend.CANONICAL_ENCODER to encoder,
            SherpaOnnxBackend.CANONICAL_DECODER to decoder,
            SherpaOnnxBackend.CANONICAL_JOINER to joiner,
            SherpaOnnxBackend.CANONICAL_TOKENS to tokens,
        )
    }

    override fun metadataFileRole(): String = SherpaOnnxBackend.CANONICAL_ENCODER

    override fun metadataKeys(modelType: String): List<String> =
        SherpaOnnxBackend.requiredTransducerMetadataKeys(modelType)

    override fun buildModelConfig(record: ExternalModelRecord, numThreads: Int, provider: String): OfflineModelConfig =
        OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = "${record.dir}/${SherpaOnnxBackend.CANONICAL_ENCODER}",
                decoder = "${record.dir}/${SherpaOnnxBackend.CANONICAL_DECODER}",
                joiner = "${record.dir}/${SherpaOnnxBackend.CANONICAL_JOINER}"
            ),
            tokens = "${record.dir}/${SherpaOnnxBackend.CANONICAL_TOKENS}",
            modelType = record.modelType,
            numThreads = numThreads,
            debug = false,
            provider = provider
        )
}

/**
 * Whisper family: encoder + decoder + tokens; no joiner.
 *
 * Expected file set: one .onnx containing "encoder", one containing "decoder",
 * and one .txt tokens/vocab file. A joiner/joint .onnx among the candidates is
 * rejected as "looks like a transducer; pick the TRANSDUCER family" (structural
 * discriminator preventing a family mismatch from passing import and surfacing
 * as a runtime exit(255)).
 *
 * Language: [options]["whisper.language"], falling back to [languages][0], then
 * "" (auto; sherpa-onnx performs no language validation per desktop spike).
 * Task: [options]["whisper.task"] defaulting to "transcribe".
 * tailPaddings stays at the sherpa default (-1).
 *
 * Record modelType: ignored; OfflineModelConfig.modelType = "whisper".
 */
object WhisperSupport : ModelFamilySupport {
    override val family: ModelFamily = ModelFamily.WHISPER

    override fun requiredRoles(): List<String> = listOf(
        SherpaOnnxBackend.CANONICAL_ENCODER,
        SherpaOnnxBackend.CANONICAL_DECODER,
        SherpaOnnxBackend.CANONICAL_TOKENS,
    )

    override fun buildCopyPlan(files: List<String>): Map<String, String>? {
        fun findByRole(vararg keywords: String) =
            files.firstOrNull { f -> f.endsWith(".onnx") && keywords.any { f.contains(it, ignoreCase = true) } }
        val encoder = findByRole("encoder") ?: return null
        val decoder = findByRole("decoder") ?: return null
        // Structural discriminator: a joiner/joint file means this is a transducer.
        val hasJoiner = files.any { f ->
            f.endsWith(".onnx") && (f.contains("joiner", ignoreCase = true) || f.contains("joint", ignoreCase = true))
        }
        if (hasJoiner) throw IllegalArgumentException(
            "candidate set contains a joiner/joint file: looks like a transducer; pick the TRANSDUCER family")
        // Tokens: exact names first, then keyword match.
        val tokens = files.firstOrNull { it.equals("tokens.txt", ignoreCase = true) }
            ?: files.firstOrNull { it.endsWith(".txt") && (it.contains("tokens", ignoreCase = true) || it.contains("vocab", ignoreCase = true)) }
            ?: return null
        return linkedMapOf(
            SherpaOnnxBackend.CANONICAL_ENCODER to encoder,
            SherpaOnnxBackend.CANONICAL_DECODER to decoder,
            SherpaOnnxBackend.CANONICAL_TOKENS to tokens,
        )
    }

    override fun metadataFileRole(): String = SherpaOnnxBackend.CANONICAL_ENCODER

    override fun metadataKeys(modelType: String): List<String> = listOf("model_type")

    override fun buildModelConfig(record: ExternalModelRecord, numThreads: Int, provider: String): OfflineModelConfig {
        val language = record.options["whisper.language"]
            ?: record.languages.firstOrNull()
            ?: ""
        val task = record.options["whisper.task"] ?: "transcribe"
        return OfflineModelConfig(
            whisper = OfflineWhisperModelConfig(
                encoder = "${record.dir}/${SherpaOnnxBackend.CANONICAL_ENCODER}",
                decoder = "${record.dir}/${SherpaOnnxBackend.CANONICAL_DECODER}",
                language = language,
                task = task,
            ),
            modelType = "whisper",
            numThreads = numThreads,
            debug = false,
            provider = provider,
        )
    }
}

/**
 * CTC family: encoder + tokens; no decoder or joiner.
 *
 * Expected file set: one .onnx acoustic model (preferably named with "encoder",
 * but CTC exports like GigaAM's v3_ctc.int8.onnx omit the keyword) and one .txt
 * tokens/vocab file. A joiner/joint .onnx among the candidates is rejected as
 * "looks like a transducer; pick the TRANSDUCER family".
 *
 * Token selection mirrors the transducer matcher but with CTC preference: repos
 * that ship both CTC and RNNT variants (istupakov) have multiple vocab files;
 * ctc-hinted candidates are picked first, rnnt-free as fallback, so a GigaAM CTC
 * import never accidentally picks the RNNT vocab.
 *
 * Record modelType selects the sherpa config subtype:
 * - "nemo_ctc" -> [OfflineNemoEncDecCtcModelConfig] (NeMo encoder-decoder CTC)
 * - "zipformer_ctc" -> [OfflineZipformerCtcModelConfig] (Zipformer CTC)
 * - any other value -> [IllegalArgumentException] naming valid values.
 *
 * Metadata: empty (GigaAM CTC exports carry only "onnx.infer" per desktop
 * validation; no family-identifying metadata to check).
 */
object CtcSupport : ModelFamilySupport {
    override val family: ModelFamily = ModelFamily.CTC

    override fun requiredRoles(): List<String> = listOf(
        SherpaOnnxBackend.CANONICAL_ENCODER,
        SherpaOnnxBackend.CANONICAL_TOKENS,
    )

    override fun buildCopyPlan(files: List<String>): Map<String, String>? {
        // Structural discriminator: a joiner/joint file means this is a transducer.
        val hasJoiner = files.any { f ->
            f.endsWith(".onnx") && (f.contains("joiner", ignoreCase = true) || f.contains("joint", ignoreCase = true))
        }
        if (hasJoiner) throw IllegalArgumentException(
            "candidate set contains a joiner/joint file: looks like a transducer; pick the TRANSDUCER family")
        // Encoder: CTC exports may not contain "encoder" in the filename (e.g.
        // GigaAM's v3_ctc.int8.onnx). Prefer keyword match, then any non-joiner .onnx.
        fun isJoinerLike(name: String) = name.contains("joiner", ignoreCase = true) || name.contains("joint", ignoreCase = true)
        val encoder = files.firstOrNull { f -> f.endsWith(".onnx") && f.contains("encoder", ignoreCase = true) && !isJoinerLike(f) }
            ?: files.firstOrNull { f -> f.endsWith(".onnx") && !isJoinerLike(f) }
            ?: return null
        // Tokens: prefer exact names, then ctc-hinted (mirror of transducer's rnnt-first).
        fun isTokensLike(name: String) = name.contains("tokens", ignoreCase = true) || name.contains("vocab", ignoreCase = true)
        val tokens = files.firstOrNull { it.equals("tokens.txt", ignoreCase = true) }
            ?: files.firstOrNull { it.equals("vocab.txt", ignoreCase = true) }
            ?: files.firstOrNull { it.endsWith(".txt") && isTokensLike(it) && it.contains("ctc", ignoreCase = true) }
            ?: files.firstOrNull { it.endsWith(".txt") && isTokensLike(it) && !it.contains("rnnt", ignoreCase = true) }
            ?: files.firstOrNull { it.endsWith(".txt") && isTokensLike(it) }
            ?: return null
        return linkedMapOf(
            SherpaOnnxBackend.CANONICAL_ENCODER to encoder,
            SherpaOnnxBackend.CANONICAL_TOKENS to tokens,
        )
    }

    override fun metadataFileRole(): String = SherpaOnnxBackend.CANONICAL_ENCODER

    override fun metadataKeys(modelType: String): List<String> = emptyList()

    override fun buildModelConfig(record: ExternalModelRecord, numThreads: Int, provider: String): OfflineModelConfig {
        val encoderPath = "${record.dir}/${SherpaOnnxBackend.CANONICAL_ENCODER}"
        return when (record.modelType) {
            "nemo_ctc" -> OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = encoderPath),
                tokens = "${record.dir}/${SherpaOnnxBackend.CANONICAL_TOKENS}",
                modelType = "nemo_ctc",
                numThreads = numThreads,
                debug = false,
                provider = provider,
            )
            "zipformer_ctc" -> OfflineModelConfig(
                zipformerCtc = OfflineZipformerCtcModelConfig(model = encoderPath),
                tokens = "${record.dir}/${SherpaOnnxBackend.CANONICAL_TOKENS}",
                modelType = "zipformer_ctc",
                numThreads = numThreads,
                debug = false,
                provider = provider,
            )
            else -> throw IllegalArgumentException(
                "unknown CTC modelType \"${record.modelType}\"; valid values: nemo_ctc, zipformer_ctc")
        }
    }
}

/**
 * SenseVoice family: a single model .onnx plus a tokens file; no encoder/decoder
 * split and no joiner.
 *
 * Expected file set: one .onnx whose name contains "sense_voice" (or the bare
 * "model.onnx" sherpa ships) and one .txt tokens/vocab file. The model keyword
 * match deliberately does NOT answer to "encoder": an encoder-only candidate
 * pool means the wrong family was picked, and returning null surfaces that at
 * import time instead of as a runtime exit(255).
 *
 * Language: [options]["sensevoice.language"], defaulting to "" (sherpa performs
 * no language validation per desktop spike; "" is the auto-detect sentinel).
 * ITN: [options]["sensevoice.itn"] where "true"/"1" enable inverse text
 * normalization and anything else (including absent) leaves it off.
 *
 * Record modelType: ignored; OfflineModelConfig.modelType = "sense_voice".
 */
object SenseVoiceSupport : ModelFamilySupport {
    /** Canonical single-model file name (the .int8.onnx convention of the table). */
    const val CANONICAL_MODEL = "model.int8.onnx"

    override val family: ModelFamily = ModelFamily.SENSE_VOICE

    override fun requiredRoles(): List<String> = listOf(CANONICAL_MODEL, SherpaOnnxBackend.CANONICAL_TOKENS)

    override fun buildCopyPlan(files: List<String>): Map<String, String>? {
        val model = files.firstOrNull { f ->
            f.endsWith(".onnx") && (f.contains("sense_voice", ignoreCase = true) || f.equals("model.onnx", ignoreCase = true))
        } ?: return null
        val tokens = files.firstOrNull { it.equals("tokens.txt", ignoreCase = true) }
            ?: files.firstOrNull { it.equals("vocab.txt", ignoreCase = true) }
            ?: files.firstOrNull { it.endsWith(".txt") && (it.contains("tokens", ignoreCase = true) || it.contains("vocab", ignoreCase = true)) }
            ?: return null
        return linkedMapOf(
            CANONICAL_MODEL to model,
            SherpaOnnxBackend.CANONICAL_TOKENS to tokens,
        )
    }

    override fun metadataFileRole(): String = CANONICAL_MODEL

    override fun metadataKeys(modelType: String): List<String> = emptyList()

    override fun buildModelConfig(record: ExternalModelRecord, numThreads: Int, provider: String): OfflineModelConfig {
        val language = record.options["sensevoice.language"] ?: ""
        val itn = record.options["sensevoice.itn"]?.let { it == "true" || it == "1" } ?: false
        return OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(
                model = "${record.dir}/$CANONICAL_MODEL",
                language = language,
                useInverseTextNormalization = itn,
            ),
            tokens = "${record.dir}/${SherpaOnnxBackend.CANONICAL_TOKENS}",
            modelType = "sense_voice",
            numThreads = numThreads,
            debug = false,
            provider = provider,
        )
    }
}
