package com.antivocale.app.data

import android.util.Log
import com.antivocale.app.transcription.SherpaOnnxBackend
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import java.io.File
import java.security.MessageDigest

/**
 * One-shot migration absorbing the custom-transducer backend into the external-model
 * store (spec: external models platform v2a, decision 7). Marker-before-record
 * ordering: the done-marker is persisted BEFORE the record is created, so a crash
 * between the two writes cannot duplicate records on relaunch (the second disjunct of
 * the spec's idempotence wording is unreachable under this ordering and is NOT
 * implemented). The legacy `models/custom-transducer/` directory is kept in place
 * (pins are hand-computed over the already-copied TASK-313 files; no re-copy, no
 * doubling of ~326MB); the record's dir points there and everything downstream
 * (engine, validity, deletion) operates on record.dir.
 *
 * Runs in BridgeApplication.onCreate BEFORE ShareTargetManager.syncAll(): the
 * ModelViewModel is created lazily on first Model-tab visit, which would leave a
 * session where a persisted "custom-transducer" id resolves against a registry that
 * no longer has it, silently falling through to the LLM loader.
 */
class CustomTransducerMigrator(
    private val preferencesManager: PreferencesManager,
    private val store: ExternalModelStore,
) {

    companion object {
        private const val TAG = "CustomTransducerMigrator"
        private const val CUSTOM_TRANSDUCER_BACKEND_ID = "custom-transducer"
        private const val DEFAULT_CUSTOM_TRANSDUCER_MODEL_TYPE = "nemo_transducer"
    }

    suspend fun migrate() {
        if (preferencesManager.externalMigrationDone.first()) {
            Log.i(TAG, "External-model migration already done, skipping")
            return
        }
        // Marker FIRST: crash-safe idempotence (pinned by the ordering test).
        preferencesManager.saveExternalMigrationDone(true)

        val path = preferencesManager.customTransducerModelPath.firstOrNull() ?: run {
            Log.i(TAG, "No custom-transducer preference, migration complete")
            return
        }
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) {
            Log.w(TAG, "Custom-transducer directory gone ($path), marking migration done without a record")
            return
        }

        val canonical = SherpaOnnxBackend.REQUIRED_MODEL_FILES
        val missing = canonical.filterNot { File(dir, it).exists() }
        if (missing.isNotEmpty()) {
            Log.w(TAG, "Custom-transducer directory incomplete ($missing), skipping record creation")
            return
        }

        // Hand-computed pins over the already-copied files.
        val pins = canonical.associateWith { file ->
            FilePin(sha256OfFile(File(dir, file)), verified = true)
        }
        val modelType = preferencesManager.customTransducerModelType.firstOrNull()
            ?: DEFAULT_CUSTOM_TRANSDUCER_MODEL_TYPE

        val record = ExternalModelRecord(
            id = java.util.UUID.randomUUID().toString().replace("-", ""),
            displayName = dir.name,
            dir = dir.absolutePath,
            family = ModelFamily.TRANSDUCER,
            modelType = modelType,
            languages = emptyList(),
            source = ExternalModelSource.LOCAL,
            sourceUrl = null,
            files = pins,
            sizeBytes = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() },
            importedAt = System.currentTimeMillis(),
        )
        store.add(record)
        Log.i(TAG, "Migrated custom-transducer model to ${record.backendId} (${record.sizeBytes} bytes)")

        if (preferencesManager.transcriptionBackend.first() == CUSTOM_TRANSDUCER_BACKEND_ID) {
            preferencesManager.saveTranscriptionBackend(record.backendId)
            Log.i(TAG, "Active backend pointer rewritten to ${record.backendId}")
        }
    }

    private fun sha256OfFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
