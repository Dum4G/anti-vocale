package com.antivocale.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.antivocale.app.transcription.SherpaOnnxBackend
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Singleton

/**
 * The single import pipeline for external models (spec: external models platform v2a).
 * Two entries share one core: [importFromTreeUri] (SAF folder picker, the primary v2a
 * path: a SAF tree URI is not a filesystem path, files are copied through
 * ContentResolver) and [importFromDirectory] (direct files, used by tests and tooling).
 *
 * Core steps: role-based copy plan (encoder/decoder/joiner/tokens keywords), unconditional
 * disk pre-flight, clean-replace into an id-fragment directory, copy with streaming
 * SHA-256 pins, pre-native metadata validation BEFORE persisting (a wrong family is an
 * import-time error, never a transcription-time exit(255)), same-hash dedupe, then
 * [ExternalModelStore.add].
 *
 * No @Inject here: the defaulted lambda parameters are invisible to Dagger (the Task-1
 * MissingBinding lesson); constructed via an AppModule @Provides @Singleton provider.
 */
class ExternalModelImporter(
    private val store: ExternalModelStore,
    private val filesRoot: () -> File,
    private val uuid: () -> String = { java.util.UUID.randomUUID().toString().replace("-", "") },
) {

    companion object {
        private const val TAG = "ExternalModelImporter"
        private const val COPY_BUFFER = 64 * 1024

        // Canonical role order pinned by SherpaOnnxBackend.REQUIRED_MODEL_FILES.
        private val CANONICAL_ENCODER get() = SherpaOnnxBackend.REQUIRED_MODEL_FILES[0]
        private val CANONICAL_DECODER get() = SherpaOnnxBackend.REQUIRED_MODEL_FILES[1]
        private val CANONICAL_JOINER get() = SherpaOnnxBackend.REQUIRED_MODEL_FILES[2]
        private val CANONICAL_TOKENS get() = SherpaOnnxBackend.REQUIRED_MODEL_FILES[3]
    }

    /** One importable source file, from either the filesystem or SAF. */
    private interface SourceFile {
        val name: String
        val size: Long
        fun open(): InputStream
    }

    private class FileSource(private val file: File) : SourceFile {
        override val name: String get() = file.name
        override val size: Long get() = file.length()
        override fun open(): InputStream = file.inputStream()
    }

    private class SafSource(
        private val document: DocumentFile,
        private val resolver: android.content.ContentResolver,
    ) : SourceFile {
        override val name: String get() = document.name ?: ""
        override val size: Long get() = document.length()
        override fun open(): InputStream =
            resolver.openInputStream(document.uri)
                ?: throw IllegalArgumentException("Cannot open ${document.uri}")
    }

    /**
     * Maps source file names to canonical role names by keyword: encoder/decoder/joiner
     * match any .onnx containing the role keyword (so non-canonical exports like GigaAM's
     * gigaam_v3_e2e_rnnt_encoder_int8.onnx match); tokens matches tokens.txt. Returns
     * null when any role has no candidate.
     */
    internal fun buildCopyPlan(files: List<String>): Map<String, String>? {
        fun findByRole(role: String) =
            files.firstOrNull { it.endsWith(".onnx") && it.contains(role, ignoreCase = true) }
        val encoder = findByRole("encoder") ?: return null
        val decoder = findByRole("decoder") ?: return null
        val joiner = findByRole("joiner") ?: return null
        val tokens = files.firstOrNull { it.equals("tokens.txt", ignoreCase = true) } ?: return null
        return linkedMapOf(
            CANONICAL_ENCODER to encoder,
            CANONICAL_DECODER to decoder,
            CANONICAL_JOINER to joiner,
            CANONICAL_TOKENS to tokens,
        )
    }

    /** SAF folder import: the primary v2a entry point. */
    suspend fun importFromTreeUri(
        context: Context,
        treeUri: Uri,
        modelType: String = "nemo_transducer",
    ): ExternalModelRecord {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("Cannot open the selected folder")
        val children = tree.listFiles()
            .filter { it.isFile }
            .map { SafSource(it, context.contentResolver) }
        val displayName = tree.name ?: "imported-model"
        return importCore(children, modelType, displayName)
    }

    /** Direct-file import: tests and tooling. The Task 9 migration does NOT use this
     *  (it hand-computes pins over the already-copied TASK-313 directory). */
    suspend fun importFromDirectory(
        src: File,
        modelType: String = "nemo_transducer",
    ): ExternalModelRecord {
        val children = src.listFiles()?.filter { it.isFile }?.map(::FileSource) ?: emptyList()
        return importCore(children, modelType, src.name)
    }

    private suspend fun importCore(
        children: List<SourceFile>,
        modelType: String,
        displayName: String,
    ): ExternalModelRecord {
        // 1. Copy plan by role.
        val names = children.map { it.name }
        val plan = buildCopyPlan(names)
            ?: throw IllegalArgumentException(
                "missing required model files (encoder/decoder/joiner/tokens); found: $names")

        // 2. Unconditional disk pre-flight (spec binding): the import doubles disk usage.
        val root = filesRoot()
        val totalBytes = plan.values.sumOf { sourceName -> children.first { it.name == sourceName }.size }
        if (totalBytes > root.usableSpace) {
            throw IllegalArgumentException(
                "not enough disk space: need ${totalBytes / (1024 * 1024)}MB, available ${root.usableSpace / (1024 * 1024)}MB")
        }

        // 3. Id-fragment target dir, clean-replace on collision (TASK-313 lesson).
        root.mkdirs()
        val targetDir = File(root, sanitizeDirName(displayName) + "-" + uuid().take(6))
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()

        try {
            // 4. Copy with streaming SHA-256 pins.
            val pins = HashMap<String, FilePin>()
            for ((canonical, sourceName) in plan) {
                val source = children.first { it.name == sourceName }
                val digest = MessageDigest.getInstance("SHA-256")
                File(targetDir, canonical).outputStream().use { out ->
                    source.open().use { input ->
                        val buffer = ByteArray(COPY_BUFFER)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            out.write(buffer, 0, read)
                        }
                    }
                }
                pins[canonical] = FilePin(digest.digest().joinToString("") { "%02x".format(it) }, verified = true)
            }

            // 5. Pre-native metadata validation BEFORE persisting: a wrong family is an
            //    import-time error, never a transcription-time exit(255). Key rule mirrors
            //    the engine: vocab_size always; the nemo keys only for the nemo family.
            val requiredKeys = mutableListOf("vocab_size")
            if (modelType == "nemo_transducer") {
                requiredKeys += "subsampling_factor"
                requiredKeys += "model_type"
            }
            val missingMeta = SherpaOnnxBackend.missingOnnxMetadata(File(targetDir, CANONICAL_ENCODER), requiredKeys)
            if (missingMeta.isNotEmpty()) {
                throw IllegalArgumentException(
                    "the encoder is missing required ONNX metadata ($missingMeta): " +
                        "the files may be corrupt, an incompatible export, or the wrong family")
            }

            // 6. Same-hash dedupe BEFORE creating a new record. The fresh copy is removed
            //    unless it landed on the existing record's own directory (same-path
            //    re-import: the files are identical, deleting them would destroy the record).
            val sizeBytes = targetDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            val existing = store.records().firstOrNull { it.files == pins }
            if (existing != null) {
                if (existing.dir != targetDir.absolutePath) {
                    targetDir.deleteRecursively()
                }
                val updated = existing.copy(displayName = sanitizeDirName(displayName), modelType = modelType)
                store.update(updated)
                Log.i(TAG, "Re-import deduped onto existing record ${existing.backendId}")
                return updated
            }

            // 7. Register.
            val record = ExternalModelRecord(
                id = uuid(),
                displayName = sanitizeDirName(displayName),
                dir = targetDir.absolutePath,
                family = ModelFamily.TRANSDUCER,
                modelType = modelType,
                languages = emptyList(),
                source = ExternalModelSource.LOCAL,
                sourceUrl = null,
                files = pins,
                sizeBytes = sizeBytes,
                importedAt = System.currentTimeMillis(),
            )
            store.add(record)
            Log.i(TAG, "Imported external model ${record.backendId} from $displayName ($sizeBytes bytes)")
            return record
        } catch (e: Exception) {
            targetDir.deleteRecursively()
            throw e
        }
    }

    private fun sanitizeDirName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-', '.')
            .takeIf { it.isNotBlank() } ?: "model"
}
