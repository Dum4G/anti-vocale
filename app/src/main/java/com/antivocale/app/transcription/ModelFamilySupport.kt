package com.antivocale.app.transcription

import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ModelFamily
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig

/** True for transducer joiner files, which also answer to GigaAM's "joint" naming. */
private fun isJoinerLike(name: String) =
    name.contains("joiner", ignoreCase = true) || name.contains("joint", ignoreCase = true)

/** True for tokens/vocab text files (the shared tokens-role keyword test). */
private fun isTokensLike(name: String) =
    name.contains("tokens", ignoreCase = true) || name.contains("vocab", ignoreCase = true)

/** True for files whose names mark them as transducer exports (rnnt/joiner/joint). */
private fun isTransducerHinted(name: String) =
    name.contains("rnnt", ignoreCase = true) || isJoinerLike(name)

/** Family-mismatch discriminator message shared by the non-transducer families. */
private const val TRANSDUCER_MISMATCH =
    "candidate set looks like a transducer; pick the TRANSDUCER family"

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

    /**
     * Family-specific value-aware validation on the file named by [metadataFileRole],
     * fired after import (registerImported) and before the first native load.
     * Default no-op: most families are covered by the key-presence metadata check
     * plus the copy-plan structural discriminators.
     */
    fun validateImportedModel(file: java.io.File) {}

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
 * and one .txt tokens/vocab file. Tokens are MANDATORY even though the sherpa
 * whisper config itself takes no tokens path: every real whisper export ships a
 * tokens.txt and the app's decode path needs it (plan decision, Task 1 finding).
 * A joiner/joint .onnx that entered encoder/decoder role matching is rejected as
 * "looks like a transducer; pick the TRANSDUCER family" (structural
 * discriminator preventing a family mismatch from passing import and surfacing
 * as a runtime exit(255)); a joiner elsewhere in the folder is ignored, since a
 * parent directory legitimately holding several models must still import.
 * Role selection prefers non-rnnt/non-joiner-hinted candidates (deterministic in
 * mixed folders), and a role whose only keyword matches are transducer-hinted
 * files is rejected outright: the model_type metadata check cannot catch a
 * transducer encoder, because NeMo transducer encoders also carry model_type as
 * a key (key-presence, not value).
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
        val onnxCandidates = files.filter { it.endsWith(".onnx") }
        // Role selection prefers non-transducer-hinted candidates, so a mixed
        // folder deterministically picks the whisper files regardless of listing
        // order. When only hinted candidates match a keyword, the folder holds a
        // bare transducer set: the model_type metadata check cannot catch that
        // (NeMo transducer encoders also carry model_type; the check is
        // key-presence, not value), so the copy plan itself must reject it.
        fun findByRole(vararg keywords: String): String? =
            onnxCandidates.firstOrNull { f -> !isTransducerHinted(f) && keywords.any { f.contains(it, ignoreCase = true) } }
        fun findTransducerHinted(vararg keywords: String): String? =
            onnxCandidates.firstOrNull { f -> keywords.any { f.contains(it, ignoreCase = true) } }
        val encoder = findByRole("encoder")
            ?: findTransducerHinted("encoder")?.let { throw IllegalArgumentException(TRANSDUCER_MISMATCH) }
            ?: return null
        val decoder = findByRole("decoder")
            ?: findTransducerHinted("decoder")?.let { throw IllegalArgumentException(TRANSDUCER_MISMATCH) }
            ?: return null
        // Tokens: exact names first, then keyword match preferring non-hinted
        // candidates so listing order cannot hand the role to the transducer vocab.
        val tokens = files.firstOrNull { it.equals("tokens.txt", ignoreCase = true) }
            ?: files.firstOrNull { it.endsWith(".txt") && !isTransducerHinted(it) && isTokensLike(it) }
            ?: files.firstOrNull { it.endsWith(".txt") && isTokensLike(it) }
            ?: return null
        return linkedMapOf(
            SherpaOnnxBackend.CANONICAL_ENCODER to encoder,
            SherpaOnnxBackend.CANONICAL_DECODER to decoder,
            SherpaOnnxBackend.CANONICAL_TOKENS to tokens,
        )
    }

    override fun metadataFileRole(): String = SherpaOnnxBackend.CANONICAL_ENCODER

    override fun metadataKeys(modelType: String): List<String> = listOf("model_type")

    override fun validateImportedModel(file: java.io.File) {
        // Value-aware discriminator: key presence cannot tell a whisper encoder
        // from a NeMo transducer encoder (both carry a model_type KEY), but the
        // values differ (whisper encoders are "whisper-*"). A missing key stays
        // with the key-presence chain (metadataKeys above).
        val value = SherpaOnnxBackend.onnxMetadataValue(file, "model_type")
        if (value != null && !value.startsWith("whisper", ignoreCase = true)) {
            throw IllegalArgumentException(
                "model_type metadata is \"$value\": not a whisper encoder; pick the TRANSDUCER family for transducer exports")
        }
    }

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
 * tokens/vocab file. A joiner/joint .onnx is rejected as "looks like a
 * transducer; pick the TRANSDUCER family" only when it entered encoder role
 * matching (the fallback tier, i.e. the folder holds a bare transducer set), and
 * a selected rnnt-hinted encoder, or a non-ctc-hinted encoder alongside a
 * joiner-like file in the pool (the generic sherpa-canonical names carry no rnnt
 * hint), is rejected the same way; ctc-hinted winners stay importable so the
 * mixed istupakov repo folder (joint included) still imports.
 *
 * Token and encoder selection mirror the transducer matcher but with CTC
 * preference: repos that ship both CTC and RNNT variants (istupakov) have
 * multiple vocab and encoder files; ctc-hinted tokens are picked first and
 * rnnt-hinted files deprioritized, so a GigaAM CTC import never accidentally
 * picks the RNNT files.
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
        val onnxCandidates = files.filter { it.endsWith(".onnx") }
        // Encoder tiers, rnnt-hinted files deprioritized (never selected when a
        // CTC-compatible candidate exists): keyword non-rnnt, any non-rnnt,
        // keyword, any. CTC exports may not contain "encoder" in the filename
        // (e.g. GigaAM's v3_ctc.int8.onnx), hence the keyword-free tiers.
        val eligible = onnxCandidates.filterNot(::isJoinerLike)
        val encoder = eligible.firstOrNull { it.contains("encoder", ignoreCase = true) && !it.contains("rnnt", ignoreCase = true) }
            ?: eligible.firstOrNull { !it.contains("rnnt", ignoreCase = true) }
            ?: eligible.firstOrNull { it.contains("encoder", ignoreCase = true) }
            ?: eligible.firstOrNull()
            // Structural discriminator over the fallback tier only: with no
            // joiner-free candidate left, a joiner/joint .onnx means the folder
            // holds a bare transducer set. A joiner elsewhere never entered CTC
            // role matching (a parent directory holding several models is
            // legitimate).
            ?: onnxCandidates.firstOrNull(::isJoinerLike)?.let {
                throw IllegalArgumentException(TRANSDUCER_MISMATCH)
            }
            ?: return null
        // A selected rnnt-hinted encoder is only reachable when the pool holds
        // nothing but a transducer set (the tiers above prefer every
        // non-rnnt candidate first), and the CTC metadata check is a no-op
        // (metadataKeys is empty), so reject it here instead of at exit(255).
        if (encoder.contains("rnnt", ignoreCase = true)) throw IllegalArgumentException(TRANSDUCER_MISMATCH)
        // Generic sherpa-canonical names carry no rnnt hint: a joiner in the pool
        // alongside a NON-ctc-hinted selected encoder is the transducer tell.
        // ctc-hinted winners stay importable (the istupakov mixed repo ships CTC
        // and RNNT variants, joint included, in one folder).
        if (onnxCandidates.any(::isJoinerLike) && !encoder.contains("ctc", ignoreCase = true)) {
            throw IllegalArgumentException(TRANSDUCER_MISMATCH)
        }
        // Tokens: prefer exact names, then ctc-hinted (mirror of transducer's rnnt-first).
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
 * Expected file set: one .onnx whose name contains "sense_voice" (sherpa
 * SenseVoice repos also ship the bare "model.onnx"/"model.int8.onnx" names,
 * matched by a basename "model" prefix) and one .txt tokens/vocab file. The model keyword
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
            f.endsWith(".onnx") && (
                f.contains("sense_voice", ignoreCase = true) ||
                    // sherpa SenseVoice repos ship the acoustic model as model.onnx or
                    // model.int8.onnx; a basename "model" prefix cannot match encoder
                    // or decoder files, so it is safe as a role keyword.
                    f.substringBeforeLast('.').startsWith("model", ignoreCase = true)
                )
        } ?: return null
        val tokens = files.firstOrNull { it.equals("tokens.txt", ignoreCase = true) }
            ?: files.firstOrNull { it.equals("vocab.txt", ignoreCase = true) }
            ?: files.firstOrNull { it.endsWith(".txt") && isTokensLike(it) }
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
