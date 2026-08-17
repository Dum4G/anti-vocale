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

        /** External-data suffixes a split ONNX references inside its protobuf. */
        val SIDECAR_SUFFIXES = listOf("data", "weights")
        val SIDECAR_MARKERS = SIDECAR_SUFFIXES.map { ".onnx.$it".toByteArray(Charsets.US_ASCII) }
        const val SIDECAR_MAX_NAME = 256
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
     * family support table ([ModelFamilySupport.forFamily], single definition shared
     * with the engine); this delegate keeps the import call sites unchanged.
     */
    internal fun buildCopyPlan(files: List<String>, family: ModelFamily = ModelFamily.TRANSDUCER): Map<String, String>? =
        ModelFamilySupport.forFamily(family).buildCopyPlan(files)

    /** Family-named role-set error shared by the local and URL planning sites. */
    private fun missingRolesError(family: ModelFamily, names: List<String>): IllegalArgumentException =
        IllegalArgumentException(
            "missing required files for $family (${ModelFamilySupport.forFamily(family).requiredRoles().joinToString("/")}); found: $names")

    /**
     * Family-aware modelType resolution, mirroring the entry-JSON defaults so the
     * importer entries can never persist a transducer modelType on a non-transducer
     * record: TRANSDUCER defaults to "nemo_transducer", WHISPER/SENSE_VOICE to "",
     * and CTC has no safe default (it selects the sherpa config subtype) so it must
     * be passed explicitly.
     */
    private fun resolveModelType(modelType: String?, family: ModelFamily): String = when {
        modelType != null -> modelType
        family == ModelFamily.TRANSDUCER -> "nemo_transducer"
        family == ModelFamily.CTC -> throw IllegalArgumentException(
            "CTC family requires an explicit modelType: nemo_ctc or zipformer_ctc")
        else -> ""
    }

    /**
     * ONNX split-file sidecars (AC #9): a sibling `<source>.data` (or `.weights`)
     * external-data file of any planned `.onnx` source joins the plan as an extra
     * entry. It is copied and pinned like a role but is NOT a role (the family
     * [ModelFamilySupport.requiredRoles] sets are unchanged): it keeps its source
     * base name so the runtime resolves it by co-location with the model file the
     * ONNX protobuf itself references.
     */
    private fun withSidecars(plan: Map<String, String>, names: List<String>): Map<String, String> {
        val result = LinkedHashMap(plan)
        for (sourceName in plan.values) {
            if (!sourceName.endsWith(".onnx")) continue
            for (suffix in listOf("data", "weights")) {
                val sidecar = "$sourceName.$suffix"
                if (names.contains(sidecar) && !result.containsKey(sidecar)) result[sidecar] = sidecar
            }
        }
        return result
    }

    /** True for filename-safe bytes ([A-Za-z0-9._-]), the name characters a sidecar
     *  reference can be walked back over. */
    private fun isFileNameByte(b: Byte): Boolean {
        val c = b.toInt() and 0xFF
        return c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
            c == '.'.code || c == '_'.code || c == '-'.code
    }

    /**
     * Streams [file] once and collects the split-ONNX sidecar names its protobuf
     * references (an external-data location string such as
     * "whisper_encoder.int8.onnx.data", preserved verbatim because sidecars keep
     * their source base name). Empty when the model is not split. The rolling
     * carry keeps chunk-straddling matches findable.
     */
    private fun referencedSidecarNames(file: File): Set<String> {
        val window = SIDECAR_MAX_NAME + ".onnx.weights".length
        val buf = ByteArray(COPY_BUFFER)
        val carry = ByteArray(window)
        var carryLen = 0
        val found = LinkedHashSet<String>()
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                val combined = ByteArray(carryLen + read)
                System.arraycopy(carry, 0, combined, 0, carryLen)
                System.arraycopy(buf, 0, combined, carryLen, read)
                for (marker in SIDECAR_MARKERS) {
                    var from = 0
                    while (true) {
                        val at = indexOfBytes(combined, marker, from) ?: break
                        var start = at
                        while (start > 0 && isFileNameByte(combined[start - 1])) start--
                        if (start < at) {
                            found.add(String(combined, start, at + marker.size - start, Charsets.US_ASCII))
                        }
                        from = at + 1
                    }
                }
                val keep = minOf(combined.size, window)
                System.arraycopy(combined, combined.size - keep, carry, 0, keep)
                carryLen = keep
            }
        }
        return found
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, from: Int): Int? {
        var i = maxOf(from, 0)
        outer@ while (i <= haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return null
    }

    /** SAF folder import: the primary v2a entry point. */
    suspend fun importFromTreeUri(
        context: Context,
        treeUri: Uri,
        modelType: String? = null,
        family: ModelFamily = ModelFamily.TRANSDUCER,
        options: Map<String, String> = emptyMap(),
        languages: List<String> = emptyList(),
    ): ExternalModelRecord {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("Cannot open the selected folder")
        val children = tree.listFiles()
            .filter { it.isFile }
            .map { SafSource(it, context.contentResolver) }
        val displayName = tree.name ?: "imported-model"
        return importCore(children, modelType, displayName, family, options, languages)
    }

    /** Direct-file import: tests and tooling. The Task 9 migration does NOT use this
     *  (it hand-computes pins over the already-copied TASK-313 directory). */
    suspend fun importFromDirectory(
        src: File,
        modelType: String? = null,
        family: ModelFamily = ModelFamily.TRANSDUCER,
        options: Map<String, String> = emptyMap(),
        languages: List<String> = emptyList(),
    ): ExternalModelRecord {
        val children = src.listFiles()?.filter { it.isFile }?.map(::FileSource) ?: emptyList()
        return importCore(children, modelType, src.name, family, options, languages)
    }

    /**
     * HuggingFace repo import (plan Task 8): file names map to canonical roles via
     * [buildCopyPlan] so downloads land under canonical names; LFS files carry a
     * server-side sha256 pin, plain files get a computed trust-on-first-use pin.
     */
    suspend fun importFromHuggingFaceRepo(
        repoUrl: String,
        modelType: String? = null,
        family: ModelFamily = ModelFamily.TRANSDUCER,
        options: Map<String, String> = emptyMap(),
        languages: List<String> = emptyList(),
    ): ExternalModelRecord {
        val repoId = HuggingFaceRepoListing.parseRepoId(repoUrl)
            ?: throw IllegalArgumentException("not a HuggingFace repository URL: $repoUrl")
        val files = repoListing.listFiles(repoId)
        val names = files.map { it.name }
        val plan = withSidecars(
            buildCopyPlan(names, family) ?: throw missingRolesError(family, names),
            names)
        val triples = plan.map { (canonical, sourceName) ->
            val source = files.first { it.name == sourceName }
            val url = repoListing.resolveUrl(repoId, sourceName)
            when (source) {
                is HuggingFaceRepoListing.HfFile.Lfs -> DownloadTriple(url, canonical, source.sha256, source.size)
                is HuggingFaceRepoListing.HfFile.Plain -> DownloadTriple(url, canonical, null, source.size)
            }
        }
        return downloadCore(
            triples, resolveModelType(modelType, family), repoId.substringAfter('/'),
            ExternalModelSource.URL, repoUrl, family, options, languages)
    }

    /** Catalog-entry JSON import: every file must carry a sha256 pin (hashless entries rejected).
     *  The record is driven entirely by the entry (family, modelType, languages, options). */
    suspend fun importFromEntryJson(entryUrl: String): ExternalModelRecord {
        val text = repoListing.fetchText(entryUrl)
        val entry = ExternalModelEntryJson.parse(text)
        val plan = buildCopyPlan(entry.files.map { it.name }, entry.family)
            ?: throw missingRolesError(entry.family, entry.files.map { it.name })
        val byName = entry.files.associateBy { it.name }
        val triples = withSidecars(plan, entry.files.map { it.name }).map { (canonical, sourceName) ->
            val f = byName[sourceName]
                // The map cannot contain a name absent from the entry's file list
                // (withSidecars only plans declared names), but a silent drop here
                // would surface as an engine-load failure instead of an import
                // error, so the invariant is enforced loudly.
                ?: throw IllegalArgumentException(
                    "entry ${entry.name} does not list the planned file $sourceName")
            DownloadTriple(f.url, canonical, f.sha256, f.size)
        }
        return downloadCore(
            triples, entry.modelType, entry.name, ExternalModelSource.URL, entryUrl,
            entry.family, entry.options, entry.languages)
    }

    /**
     * URL import: classifies the url (a HuggingFace repo URL, or a catalog-entry JSON
     * url otherwise) and delegates to the matching entry. The classification lives
     * here, next to the two entries it picks between, so callers pass the url through.
     * The family/options/languages parameters apply to repo imports only: entry JSON
     * is driven by the entry itself.
     */
    suspend fun importFromUrl(
        url: String,
        modelType: String? = null,
        family: ModelFamily = ModelFamily.TRANSDUCER,
        options: Map<String, String> = emptyMap(),
        languages: List<String> = emptyList(),
    ): ExternalModelRecord =
        if (url.trim().endsWith(".json") || HuggingFaceRepoListing.parseRepoId(url) == null) {
            importFromEntryJson(url)
        } else {
            importFromHuggingFaceRepo(url, modelType, family, options, languages)
        }

    private suspend fun importCore(
        children: List<SourceFile>,
        modelType: String?,
        displayName: String,
        family: ModelFamily,
        options: Map<String, String>,
        languages: List<String>,
    ): ExternalModelRecord {
        val resolvedModelType = resolveModelType(modelType, family)
        // 1. Copy plan by role, plus any ONNX split-file sidecars.
        val names = children.map { it.name }
        val plan = withSidecars(
            buildCopyPlan(names, family) ?: throw missingRolesError(family, names),
            names)

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

            registerImported(targetDir, pins, family, resolvedModelType, options, languages,
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
        family: ModelFamily = ModelFamily.TRANSDUCER,
        options: Map<String, String> = emptyMap(),
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
        val declaredNames = triples.map { it.canonicalName }.toSet()

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
                // Split-ONNX completeness: an external-data reference inside a
                // downloaded .onnx whose sidecar is not part of the download set
                // would only surface as an engine-load failure, so reject it here,
                // loud and named.
                if (triple.canonicalName.endsWith(".onnx")) {
                    val missing = referencedSidecarNames(file).filterNot { it in declaredNames }
                    if (missing.isNotEmpty()) {
                        throw IllegalArgumentException(
                            "$displayName references split-ONNX sidecar(s) ${missing.joinToString()} " +
                                "that the download set does not include; declare them in the file list")
                    }
                }
            }

            registerImported(targetDir, pins, family, modelType, options, languages, source, sourceUrl, displayName)
        }
    }

    /** Shared registration tail: metadata validation, same-hash dedupe, store.add. */
    private suspend fun registerImported(
        targetDir: File,
        pins: Map<String, FilePin>,
        family: ModelFamily,
        modelType: String,
        options: Map<String, String>,
        languages: List<String>,
        source: ExternalModelSource,
        sourceUrl: String?,
        displayName: String,
    ): ExternalModelRecord {
        // Pre-native metadata validation BEFORE persisting: a wrong family is an
        // import-time error, never a transcription-time exit(255). Both the checked
        // file and the required keys are family-routed ([ModelFamilySupport]); the
        // value-aware [ModelFamilySupport.validateImportedModel] call is mandatory
        // alongside the key-presence check, otherwise a generic-name transducer set
        // imported as WHISPER passes (NeMo transducer encoders also carry a
        // model_type key; only the VALUE discriminates them).
        val support = ModelFamilySupport.forFamily(family)
        val metadataFile = File(targetDir, support.metadataFileRole())
        val missingMeta = SherpaOnnxBackend.missingOnnxMetadata(metadataFile, support.metadataKeys(modelType))
        if (missingMeta.isNotEmpty()) {
            throw IllegalArgumentException(
                "the ${support.metadataFileRole()} is missing required ONNX metadata ($missingMeta): " +
                    "the files may be corrupt, an incompatible export, or the wrong family")
        }
        support.validateImportedModel(metadataFile)

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
                family = family,
                modelType = modelType,
                languages = languages,
                options = options,
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
            options = options,
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
