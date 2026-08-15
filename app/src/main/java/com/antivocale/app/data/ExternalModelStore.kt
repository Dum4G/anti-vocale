package com.antivocale.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExternalModelStore @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val dirExists: (String) -> Boolean = { java.io.File(it).exists() },
) {
    val recordsFlow: Flow<List<ExternalModelRecord>> =
        preferencesManager.externalModelsJson.map(ExternalModelListJson::decode)

    val validRecordsFlow: Flow<List<ExternalModelRecord>> =
        preferencesManager.externalModelsJson.map { js -> ExternalModelListJson.decode(js).filter { dirExists(it.dir) } }

    suspend fun records(): List<ExternalModelRecord> = recordsFlow.first()

    suspend fun validRecords(): List<ExternalModelRecord> = records().filter { dirExists(it.dir) }

    suspend fun byId(id: String): ExternalModelRecord? =
        validRecords().firstOrNull { it.id == id }

    suspend fun add(record: ExternalModelRecord) = mutate { it + record }
    suspend fun update(record: ExternalModelRecord) = mutate { list -> list.map { if (it.id == record.id) record else it } }
    suspend fun delete(id: String): ExternalModelRecord? {
        val removed = records().firstOrNull { it.id == id }
        mutate { list -> list.filterNot { it.id == id } }
        return removed
    }

    suspend fun invalidRecordIds(): List<String> = records().filterNot { dirExists(it.dir) }.map { it.id }

    private suspend fun mutate(transform: (List<ExternalModelRecord>) -> List<ExternalModelRecord>) {
        val current = records()
        preferencesManager.saveExternalModelsJson(ExternalModelListJson.encode(transform(current)))
    }
}
