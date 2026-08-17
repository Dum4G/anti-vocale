package com.antivocale.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.antivocale.app.data.download.DownloadConfig
import com.antivocale.app.data.download.HashVerifier
import com.antivocale.app.data.download.ResumeDownloadHelper
import com.antivocale.app.transcription.ModelFamilySupport
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
    private val repoListing: HuggingFaceRepoListing = HuggingFaceRepoListing(),
) {

    companion object {
        private const val TAG = "ExternalModelImporter"
        private const val COPY_BUFFER = 64 * 1024
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
     * Maps source file names to canonical role names. The keyword logic lives in the
     * family support table ([ModelFamilySupport.TransducerSupport], single definition
     * shared with the engine); this delegate keeps the import call sites unchanged.
     */
    internal fun buildCopyPlan(files: List<String>): Map<String, String>? =
        ModelFamilySupport.forFamily(ModelFamily.TRANSDUCER).buildCopyPlan(files)

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

    /**
     * HuggingFace repo import (plan Task 8): file names map to canonical roles via
     * [buildCopyPlan] so downloads land under canonical names; LFS files carry a
     * server-side sha256 pin, plain files get a computed trust-on-first-use pin.
     */
    suspend fun importFromHuggingFaceRepo(
        repoUrl: String,
        modelType: String = "nemo_transducer",
    ): ExternalModelRecord {
        val repoId = HuggingFaceRepoListing.parseRepoId(repoUrl)
            ?: throw IllegalArgumentException("not a HuggingFace repository URL: $repoUrl")
        val files = repoListing.listFiles(repoId)
        val plan = buildCopyPlan(files.map { it.name })
            ?: throw IllegalArgumentException(
                "repository $repoId has no complete transducer role set (encoder/decoder/joiner/tokens)")
        val triples = plan.map { (canonical, sourceName) ->
            val source = files.first { it.name == sourceName }
            val url = repoListing.resolveUrl(repoId, sourceName)
            when (source) {
                is HuggingFaceRepoListing.HfFile.Lfs -> DownloadTriple(url, canonical, source.sha256, source.size)
                is HuggingFaceRepoListing.HfFile.Plain -> DownloadTriple(url, canonical, null, source.size)
            }
        }
        return downloadCore(triples, modelType, repoId.substringAfter('/'), ExternalModelSource.URL, repoUrl)
    }

    /** Catalog-entry JSON import: every file must carry a sha256 pin (hashless entries rejected). */
    suspend fun importFromEntryJson(
        entryUrl: String,
        modelType: String = "nemo_transducer",
    ): ExternalModelRecord {
        val text = repoListing.fetchText(entryUrl)
        val entry = ExternalModelEntryJson.parse(text)
        val plan = buildCopyPlan(entry.files.map { it.name })
            ?: throw IllegalArgumentException(
                "entry ${entry.name} has no complete transducer role set (encoder/decoder/joiner/tokens)")
        val byName = entry.files.associateBy { it.name }
        val triples = plan.map { (canonical, sourceName) ->
            val f = byName.getValue(sourceName)
            DownloadTriple(f.url, canonical, f.sha256, f.size)
        }
        return downloadCore(triples, entry.modelType, entry.name, ExternalModelSource.URL, entryUrl, entry.languages)
    }

    /**
     * URL import: classifies the url (a HuggingFace repo URL, or a catalog-entry JSON
     * url otherwise) and delegates to the matching entry. The classification lives
     * here, next to the two entries it picks between, so callers pass the url through.
     */
    suspend fun importFromUrl(
        url: String,
        modelType: String = "nemo_transducer",
    ): ExternalModelRecord =
        if (url.trim().endsWith(".json") || HuggingFaceRepoListing.parseRepoId(url) == null) {
            importFromEntryJson(url, modelType)
        } else {
            importFromHuggingFaceRepo(url, modelType)
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
        requireDiskSpace(root, totalBytes)

        // 3. Id-fragment target dir, clean-replace on collision (TASK-313 lesson).
        val targetDir = freshTargetDir(root, displayName)

        return importCleaningUpOnFailure(targetDir) {
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

            registerImported(targetDir, pins, ModelFamily.TRANSDUCER, modelType, emptyList(),
                ExternalModelSource.LOCAL, null, displayName)
        }
    }

    /** One downloadable file: source url, canonical destination, optional server-side pin, mandatory size (feeds the disk pre-flight). */
    internal data class DownloadTriple(val url: String, val canonicalName: String, val sha256: String?, val size: Long)

    /**
     * Shared download core for the URL entries (plan Task 8): canonical-name landing,
     * unconditional pre-flight over known sizes, resumable per-file download
     * ([com.antivocale.app.data.download.ResumeDownloadHelper]), sha256 verification
     * when a pin exists and a computed trust-on-first-use pin when not, then the same
     * registration tail as the local entry (metadata validation, dedupe, store.add).
     */
    private suspend fun downloadCore(
        triples: List<DownloadTriple>,
        modelType: String,
        displayName: String,
        source: ExternalModelSource,
        sourceUrl: String?,
        languages: List<String> = emptyList(),
    ): ExternalModelRecord {
        val root = filesRoot()
        // Unconditional pre-flight (spec binding): callers must supply sizes (the HF
        // listing always has them; entry JSON rejects sizeless files at parse time).
        val unknownSizes = triples.filter { it.size <= 0L }
        require(unknownSizes.isEmpty()) {
            "cannot pre-flight disk space: no size for ${unknownSizes.joinToString { it.canonicalName }}"
        }
        requireDiskSpace(root, triples.sumOf { it.size })
        val targetDir = freshTargetDir(root, displayName)

        return importCleaningUpOnFailure(targetDir) {
            val pins = HashMap<String, FilePin>()
            for (triple in triples) {
                val target = File(targetDir, triple.canonicalName)
                val result = ResumeDownloadHelper.downloadWithResume(
                    DownloadConfig(
                        url = triple.url,
                        tempFile = target,
                        targetFile = target,
                        estimatedSizeBytes = triple.size,
                    ),
                )
                // The resume machinery leaves a .size sidecar next to the target; model
                // directories must stay clean (the engine walks them for size and users
                // browse them), so drop it once the file is complete and verified.
                ResumeDownloadHelper.sizeSidecar(target).delete()
                val file = result.getOrThrow()
                val actual = HashVerifier.sha256(file)
                val pin = when (triple.sha256) {
                    null -> FilePin(actual, verified = false)  // TOFU: computed on first download
                    else -> {
                        if (!actual.equals(triple.sha256, ignoreCase = true)) {
                            throw IllegalArgumentException(
                                "integrity check failed for ${triple.canonicalName}: " +
                                    "expected ${triple.sha256}, got $actual")
                        }
                        FilePin(actual, verified = true)
                    }
                }
                pins[triple.canonicalName] = pin
            }

            registerImported(targetDir, pins, ModelFamily.TRANSDUCER, modelType, languages, source, sourceUrl, displayName)
        }
    }

    /** Shared registration tail: metadata validation, same-hash dedupe, store.add. */
    private suspend fun registerImported(
        targetDir: File,
        pins: Map<String, FilePin>,
        family: ModelFamily,
        modelType: String,
        languages: List<String>,
        source: ExternalModelSource,
        sourceUrl: String?,
        displayName: String,
    ): ExternalModelRecord {
        // Pre-native metadata validation BEFORE persisting: a wrong family is an
        // import-time error, never a transcription-time exit(255). The key rule is
        // the engine's own: [SherpaOnnxBackend.requiredTransducerMetadataKeys].
        val requiredKeys = SherpaOnnxBackend.requiredTransducerMetadataKeys(modelType)
        val missingMeta = SherpaOnnxBackend.missingOnnxMetadata(File(targetDir, SherpaOnnxBackend.CANONICAL_ENCODER), requiredKeys)
        if (missingMeta.isNotEmpty()) {
            throw IllegalArgumentException(
                "the encoder is missing required ONNX metadata ($missingMeta): " +
                    "the files may be corrupt, an incompatible export, or the wrong family")
        }

        // Same-hash dedupe BEFORE creating a new record. The fresh copy is removed
        // unless it landed on the existing record's own directory (same-path
        // re-import: the files are identical, deleting them would destroy the record).
        // A dedupe-matched record whose directory is GONE is repointed at the fresh
        // copy instead (otherwise the re-import would "succeed" while leaving the
        // record pointing at nothing, and the fresh copy would be deleted).
        val sizeBytes = targetDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        val existing = store.records().firstOrNull { it.files == pins }
        if (existing != null) {
            val existingDirValid = File(existing.dir).exists()
            if (existing.dir != targetDir.absolutePath && existingDirValid) {
                targetDir.deleteRecursively()
            }
            val updated = existing.copy(
                displayName = displayName.trim(),
                modelType = modelType,
                dir = if (existingDirValid) existing.dir else targetDir.absolutePath,
            )
            store.update(updated)
            Log.i(TAG, "Re-import deduped onto existing record ${existing.backendId} (dirValid=$existingDirValid)")
            return updated
        }

        val record = ExternalModelRecord(
            id = uuid(),
            displayName = displayName.trim(),
            dir = targetDir.absolutePath,
            family = family,
            modelType = modelType,
            languages = languages,
            source = source,
            sourceUrl = sourceUrl,
            files = pins,
            sizeBytes = sizeBytes,
            importedAt = System.currentTimeMillis(),
        )
        store.add(record)
        Log.i(TAG, "Imported external model ${record.backendId} from $displayName ($sizeBytes bytes)")
        return record
    }

    /** Unconditional disk pre-flight (spec binding): the import doubles disk usage. */
    private fun requireDiskSpace(root: File, totalBytes: Long) {
        // A non-existent directory reports usableSpace == 0 on Android: create the
        // root first so the pre-flight measures the real volume (device-test catch).
        root.mkdirs()
        if (totalBytes > root.usableSpace) {
            throw IllegalArgumentException(
                "not enough disk space: need ${totalBytes / (1024 * 1024)}MB, available ${root.usableSpace / (1024 * 1024)}MB")
        }
    }

    /** Id-fragment target dir under [root], clean-replaced on collision (TASK-313 lesson). */
    private fun freshTargetDir(root: File, displayName: String): File {
        root.mkdirs()
        val targetDir = File(root, sanitizeDirName(displayName) + "-" + uuid().take(6))
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()
        return targetDir
    }

    /** Runs [block]; on failure removes [targetDir] so no half-imported dir survives. */
    private suspend fun <R> importCleaningUpOnFailure(targetDir: File, block: suspend () -> R): R =
        try {
            block()
        } catch (e: Exception) {
            targetDir.deleteRecursively()
            throw e
        }

    private fun sanitizeDirName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-', '.')
            .takeIf { it.isNotBlank() } ?: "model"
}
