package com.antivocale.app.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Valid external-model records as a [StateFlow] snapshot, for
 * [com.antivocale.app.transcription.BackendRegistry]'s dynamic descriptors.
 *
 * The seam exists for determinism: the registry must not collect a Flow on a
 * hidden scope (tests read `backends` immediately after a store mutation and
 * would race the collector), so it reads the snapshot while the default
 * implementation keeps it fresh on a background scope. Validity is evaluated
 * only when the JSON preference emits: a deleted model dir keeps deriving a
 * descriptor until the next preference write.
 */
interface ExternalModelRecordsProvider {
    val records: StateFlow<List<ExternalModelRecord>>
}

@Singleton
class DefaultExternalModelRecordsProvider @Inject constructor(
    store: ExternalModelStore,
) : ExternalModelRecordsProvider {
    private companion object {
        const val TAG = "ExternalModelRecords"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _records = MutableStateFlow<List<ExternalModelRecord>>(emptyList())
    override val records: StateFlow<List<ExternalModelRecord>> = _records

    init {
        scope.launch {
            // Keep the last snapshot on failure: an upstream error must not kill
            // the process-wide collector, it just stops refreshing until the next
            // successful emission.
            store.validRecordsFlow
                .catch { Log.w(TAG, "external records collector failed", it) }
                .collect { _records.value = it }
        }
    }
}
