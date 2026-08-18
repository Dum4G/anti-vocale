package com.antivocale.app.data

import android.util.Log
import com.antivocale.app.data.download.DownloadConfig
import com.antivocale.app.data.download.HashVerifier
import com.antivocale.app.data.download.ResumeDownloadHelper
import com.antivocale.app.transcription.SherpaBackend
import java.io.File
import java.security.MessageDigest
import javax.inject.Singleton

/**
 * The single import pipeline for external models (spec: external models platform
 * v2a, JSON-only revision). External import accepts ONLY serialized catalog-entry
 * JSON — pasted text ([importFromEntryJsonText]) or a URL to one
 * ([importFromEntryJson] / [importFromUrl]); the SAF folder and HF repo-list
 * entries were retired by the JSON-only decision. The JSON is the SAME unified
 * schema as the built-in catalog ([com.antivocale.app.data.catalog.ModelCatalogJson]),
 * and the external branch of that parser enforces url+sha256+size per file.
 *
 * Core steps: role-based copy plan (encoder/decoder/joiner/tokens keywords),
 * unconditional disk pre-flight, clean-replace into an id-fragment directory,
 * download with canonical landing + SHA-256 verification, pre-native metadata
 * validation BEFORE persisting (a wrong family is an import-time error, never a
 * transcription-time exit(255)), same-hash dedupe, then [ExternalModelStore.add].
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

    /**
     * Maps source file names to canonical role names by keyword: encoder/decoder/joiner
     * match any .onnx containing the role keyword (so non-canonical exports like GigaAM's
     * gigaam_v3_e2e_rnnt_encoder_int8.onnx match). The joiner also answers to "joint"
     * (GigaAM v3 ships gigaam_v3_e2e_rnnt_joint.onnx: the RNNT file name, unlike
     * sherpa's config key; Dum4G's 2026-08-13 report on the prototype was exactly this).
     * Tokens answers to tokens.txt or any *vocab* file (istupakov's export uses
     * v3_e2e_rnnt_vocab.txt). Returns null when any role has no candidate.
     */
    internal fun buildCopyPlan(files: List<String>): Map<String, String>? {
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
            SherpaBackend.CANONICAL_ENCODER to encoder,
            SherpaBackend.CANONICAL_DECODER to decoder,
            SherpaBackend.CANONICAL_JOINER to joiner,
            SherpaBackend.CANONICAL_TOKENS to tokens,
        )
    }

    /** Paste/direct catalog-entry JSON import (the JSON-only external entry). */
    suspend fun importFromEntryJsonText(text: String): ExternalModelRecord {
        val entry = ExternalModelEntryJson.parse(text)
        return downloadEntry(entry, null)
    }

    /** Catalog-entry JSON import by URL: fetch, parse, download. */
    suspend fun importFromEntryJson(entryUrl: String): ExternalModelRecord {
        val text = repoListing.fetchText(entryUrl)
        val entry = ExternalModelEntryJson.parse(text)
        return downloadEntry(entry, entryUrl)
    }

    /**
     * URL import: the url must point at a catalog-entry JSON (external import is
     * JSON-only — no more folder pickers or HF repo listings).
     */
    suspend fun importFromUrl(url: String): ExternalModelRecord = importFromEntryJson(url)

    /** Shared download tail for the JSON entries: canonical-name landing, verified pins, registration. */
    private suspend fun downloadEntry(
        entry: ExternalModelEntryJson.Entry,
        sourceUrl: String?,
    ): ExternalModelRecord {
        val plan = buildCopyPlan(entry.files.map { it.name })
            ?: throw IllegalArgumentException(
                "entry ${entry.name} has no complete transducer role set (encoder/decoder/joiner/tokens)")
        val byName = entry.files.associateBy { it.name }
        val triples = plan.map { (canonical, sourceName) ->
            val f = byName.getValue(sourceName)
            DownloadTriple(f.url, canonical, f.sha256, f.size)
        }
        return downloadCore(
            triples, entry.modelType, entry.name, entry.description,
            ExternalModelSource.URL, sourceUrl, entry.languages,
        )
    }

    /** One downloadable file: source url, canonical destination, server-side pin, size. */
    internal data class DownloadTriple(val url: String, val canonicalName: String, val sha256: String?, val size: Long)

    /**
     * Shared download core: canonical-name landing, unconditional pre-flight over
     * known sizes, resumable per-file download
     * ([com.antivocale.app.data.download.ResumeDownloadHelper]), sha256 verification,
     * then the same registration tail as the local entry (metadata validation,
     * dedupe, store.add).
     */
    private suspend fun downloadCore(
        triples: List<DownloadTriple>,
        modelType: String,
        displayName: String,
        description: String?,
        source: ExternalModelSource,
        sourceUrl: String?,
        languages: List<String> = emptyList(),
    ): ExternalModelRecord {
        val root = filesRoot()
        // Unconditional pre-flight (spec binding): the JSON parser rejects sizeless files,
        // so every triple carries its size here.
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

            registerImported(targetDir, pins, ModelFamily.TRANSDUCER, modelType, languages,
                source, sourceUrl, displayName, description)
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
        description: String?,
    ): ExternalModelRecord {
        // Pre-native metadata validation BEFORE persisting: a wrong family is an
        // import-time error, never a transcription-time exit(255). The key rule is
        // the engine's own: [SherpaBackend.requiredTransducerMetadataKeys].
        val requiredKeys = SherpaBackend.requiredTransducerMetadataKeys(modelType)
        val missingMeta = SherpaBackend.missingOnnxMetadata(File(targetDir, SherpaBackend.CANONICAL_ENCODER), requiredKeys)
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
                description = description?.trim()?.takeIf { it.isNotEmpty() },
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
            description = description?.trim()?.takeIf { it.isNotEmpty() },
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