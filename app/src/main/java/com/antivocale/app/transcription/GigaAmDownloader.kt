package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.data.download.DownloadState
import com.antivocale.app.data.download.SherpaOnnxModelConfig
import com.antivocale.app.data.download.SherpaOnnxModelDownloader
import java.io.File

/**
 * Downloads the GigaAM v3 model for sherpa-onnx from the project's HuggingFace mirror.
 *
 * The files are mirrored (byte-identical) at `pantinor/gigaam-v3`, the default HF
 * namespace the shared downloader derives from [modelDirName], so no repo override
 * is needed. Original source: the govorun-lite GitHub release `model-gigaam-v3`
 * (int8 sherpa-onnx export of Sber's GigaAM v3, MIT). Every file is pinned by
 * SHA-256 below.
 *
 * Single-variant (like [NemotronDownloader]). Delegates to [SherpaOnnxModelDownloader].
 */
object GigaAmDownloader {

    /**
     * The directory name used for the GigaAM model. Read-only exposure so
     * [GigaAmModelManager.validModelDirNames] can feed [cleanOrphanedModelDirs].
     */
    val modelDirName: String get() = GigaAmModelManager.GIGAAM_MODEL_DIR

    private val config = SherpaOnnxModelConfig(
        tag = "GigaAmDownloader",
        modelDirNames = mapOf(Unit to modelDirName),
        hfFileNames = mapOf(
            Unit to GigaAmModelManager.REQUIRED_FILES
        ),
        estimatedSizeMB = { GigaAmModelManager.ESTIMATED_SIZE_MB },
        modelStorageDir = { context -> GigaAmModelManager.getModelStorageDir(context) },
        isValidModel = { dir -> GigaAmModelManager.validateModelDirectory(dir) != null },
        // Pinned because the bytes transit from a third-party export: the mirror is
        // ours, but the hashes document exactly which artifacts were validated.
        expectedSha256 = mapOf(
            Unit to mapOf(
                "gigaam_v3_e2e_rnnt_encoder_int8.onnx" to "2cac62d0c270bd128f898f2be1a2d34780d524a6e9483888ebac7b00f97410f1",
                "gigaam_v3_e2e_rnnt_decoder.onnx" to "781971998e6a355d6a714f6932a30eab295e7ba0d14fd7e0f78c83b87e811860",
                "gigaam_v3_e2e_rnnt_joint.onnx" to "602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c",
                "gigaam_v3_e2e_rnnt_tokens.txt" to "7ddf22514c42c531358182c81446a8159771e9921019f09ae743ea622d40221d"
            )
        )
    )

    private val delegate = SherpaOnnxModelDownloader(config)

    fun detectPartialDownload(context: Context): DownloadState.PartiallyDownloaded? =
        delegate.detectPartialDownload(context, Unit)

    fun clearPartialDownload(context: Context): Boolean =
        delegate.clearPartialDownload(context, Unit)

    suspend fun downloadModel(
        context: Context,
        onProgress: (Float) -> Unit = {},
        onStateChange: (DownloadState) -> Unit = {}
    ): Result<File> = delegate.downloadModel(context, Unit, onProgress, onStateChange)

    fun cancel() = delegate.cancel(Unit)

    fun isModelDownloaded(context: Context): Boolean =
        delegate.isModelDownloaded(context, Unit)

    fun getModelPath(context: Context): String? =
        delegate.getModelPath(context, Unit)

    fun getEstimatedSizeMB(): Long = delegate.getEstimatedSizeMB(Unit)

    fun deleteModel(context: Context): Boolean =
        delegate.deleteModel(context, Unit)
}
