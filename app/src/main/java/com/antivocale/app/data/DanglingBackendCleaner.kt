package com.antivocale.app.data

import kotlinx.coroutines.flow.first

/**
 * Startup hygiene pass over the persisted transcription backend (TASK-342
 * defect 2). The external-model delete path resets the preference when it
 * deletes the active record, but the preference can still end up dangling
 * (a record removed through another path, files deleted underneath a record,
 * or a crash between store delete and preference reset). A backend id nothing
 * can load makes every transcription request fail, so at startup an external
 * id that no longer resolves to a valid record is reset to the default
 * backend. Called from [com.antivocale.app.BridgeApplication.onCreate],
 * before the share-target sync, next to [CustomTransducerMigrator].
 */
class DanglingBackendCleaner(
    private val preferencesManager: PreferencesManager,
    private val externalModelStore: ExternalModelStore,
) {
    suspend fun cleanIfNeeded() {
        val backend = preferencesManager.transcriptionBackend.first()
        if (!backend.startsWith(ExternalModelRecord.BACKEND_ID_PREFIX)) return
        val id = backend.removePrefix(ExternalModelRecord.BACKEND_ID_PREFIX)
        // byId resolves valid records only (dir must exist), matching what the
        // orchestrator's loadExternalBackend can actually load.
        if (externalModelStore.byId(id) != null) return
        preferencesManager.saveTranscriptionBackend(PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND)
    }
}
