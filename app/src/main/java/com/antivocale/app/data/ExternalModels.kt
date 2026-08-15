package com.antivocale.app.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

enum class ModelFamily { TRANSDUCER }  // CTC, PARAFORMER, SENSE_VOICE, WHISPER arrive in v2b

enum class ExternalModelSource { LOCAL, URL, CATALOG }

data class FilePin(val sha256: String, val verified: Boolean)

data class ExternalModelRecord(
    val id: String,                 // uuid; also the dir-fragment source
    val displayName: String,
    val dir: String,                // models/external/<sanitized-name>-<id-fragment>/
    val family: ModelFamily,
    val modelType: String,          // sherpa modelType: nemo_transducer, "", conformer_transducer
    val languages: List<String>,
    val source: ExternalModelSource,
    val sourceUrl: String?,
    val files: Map<String, FilePin>,
    val sizeBytes: Long,
    val importedAt: Long,
) {
    val backendId: String get() = "external:$id"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("displayName", displayName); put("dir", dir)
        put("family", family.name); put("modelType", modelType)
        put("languages", JSONArray(languages)); put("source", source.name)
        put("sourceUrl", sourceUrl ?: JSONObject.NULL)
        put("files", JSONObject().apply { files.forEach { (n, p) -> put(n, JSONObject().put("sha256", p.sha256).put("verified", p.verified)) } })
        put("sizeBytes", sizeBytes); put("importedAt", importedAt)
    }

    companion object {
        private const val TAG = "ExternalModelRecord"

        fun fromJson(o: JSONObject): ExternalModelRecord? = try {
            val filesObj = o.getJSONObject("files")
            val files = buildMap {
                for (name in filesObj.keys()) {
                    val p = filesObj.getJSONObject(name)
                    put(name, FilePin(p.getString("sha256"), p.getBoolean("verified")))
                }
            }
            ExternalModelRecord(
                id = o.getString("id"), displayName = o.getString("displayName"), dir = o.getString("dir"),
                family = ModelFamily.valueOf(o.getString("family")), modelType = o.getString("modelType"),
                languages = buildList { val a = o.getJSONArray("languages"); for (i in 0 until a.length()) add(a.getString(i)) },
                source = ExternalModelSource.valueOf(o.getString("source")),
                sourceUrl = if (o.isNull("sourceUrl")) null else o.getString("sourceUrl"),
                files = files, sizeBytes = o.getLong("sizeBytes"), importedAt = o.getLong("importedAt"),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Skipping malformed ExternalModelRecord", e)
            null
        }
    }
}

object ExternalModelListJson {
    private const val TAG = "ExternalModelListJson"

    fun encode(records: List<ExternalModelRecord>): String =
        JSONArray(records.map { it.toJson() }).toString()

    fun decode(raw: String?): List<ExternalModelRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            buildList { for (i in 0 until a.length()) add(ExternalModelRecord.fromJson(a.getJSONObject(i)) ?: return emptyList()) }
        }.onFailure { Log.w(TAG, "Failed to decode external models JSON", it) }
         .getOrDefault(emptyList())
    }
}
