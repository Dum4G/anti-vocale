package com.antivocale.app.transcription

import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelRecordsProvider
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.FakePreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A [BackendRegistry] over an empty external-model provider: the static seven
 * backends only. Tests that exercise dynamic external descriptors construct the
 * registry inline with their own provider (see BackendRegistryTest); tests that
 * only need the static metadata use this.
 */
fun staticRegistry(): BackendRegistry = BackendRegistry(
    ExternalModelStore(FakePreferencesManager()),
    object : ExternalModelRecordsProvider {
        override val records = MutableStateFlow(emptyList<ExternalModelRecord>())
    },
)
