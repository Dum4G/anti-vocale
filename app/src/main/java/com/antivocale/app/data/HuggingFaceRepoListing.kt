package com.antivocale.app.data

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * HuggingFace repository listing for URL imports (spec: external models platform v2a,
 * Task 8). Files backed by LFS expose a server-side sha256 oid (a real integrity pin);
 * plain files expose only a git sha1, so their pin is computed on first download
 * (trust-on-first-use, the source is the user-chosen repo) and marked unverified.
 */
class HuggingFaceRepoListing(
    private val client: OkHttpClient = OkHttpClient(),
    private val apiBase: String = "https://huggingface.co",
) {

    sealed class HfFile {
        abstract val name: String
        data class Lfs(override val name: String, val sha256: String, val size: Long) : HfFile()
        data class Plain(override val name: String, val size: Long) : HfFile()
    }

    /** Lists the repo's main-branch files (files only, directories skipped). */
    fun listFiles(repoId: String): List<HfFile> {
        val request = okhttp3.Request.Builder()
            .url("$apiBase/api/models/$repoId/tree/main")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalArgumentException("HuggingFace listing failed: HTTP ${response.code} for $repoId")
            }
            val body = response.body?.string() ?: throw IllegalArgumentException("empty listing body")
            val entries = JSONArray(body)
            return buildList {
                for (i in 0 until entries.length()) {
                    val e = entries.getJSONObject(i)
                    if (e.optString("type") != "file") continue
                    val path = e.optString("path")
                    val size = e.optLong("size", 0L)
                    val lfs = e.optJSONObject("lfs")
                    if (lfs != null) {
                        val oid = lfs.optString("oid")
                        if (oid.length == 64) {
                            add(HfFile.Lfs(path, oid, lfs.optLong("size", size)))
                            continue
                        }
                    }
                    add(HfFile.Plain(path, size))
                }
            }
        }
    }

    /** Direct download URL for one repo file on the main branch. */
    fun resolveUrl(repoId: String, fileName: String): String =
        "$apiBase/$repoId/resolve/main/$fileName"

    /** Small GET for entry JSON and other textual metadata. */
    fun fetchText(url: String): String {
        val request = okhttp3.Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalArgumentException("fetch failed: HTTP ${response.code} for $url")
            }
            return response.body?.string() ?: throw IllegalArgumentException("empty body for $url")
        }
    }

    companion object {
        /** "https://huggingface.co/owner/repo(/tree/main)" or bare "owner/repo" -> "owner/repo". */
        fun parseRepoId(url: String): String? {
            val trimmed = url.trim().removeSuffix("/").removeSuffix("/tree/main").removeSuffix("/tree")
            val parts = if (trimmed.startsWith("http")) {
                val path = runCatching { java.net.URI(trimmed).path }.getOrNull() ?: return null
                path.trim('/').split('/')
            } else {
                trimmed.split('/')
            }
            if (parts.size < 2) return null
            val (owner, repo) = parts[0] to parts[1]
            if (owner.isBlank() || repo.isBlank()) return null
            if (trimmed.startsWith("http")) {
                // Host equality, not substring: "https://evil.com/huggingface.co/x"
                // would pass a contains() check.
                val host = runCatching { java.net.URI(trimmed).host }.getOrNull() ?: return null
                if (host != "huggingface.co" && host != "www.huggingface.co") return null
            }
            return "$owner/$repo"
        }
    }
}

/**
 * Single-model catalog-entry JSON (the same schema a catalog lists many of; a bare
 * entry URL is how third parties share one model with integrity). Every file MUST
 * carry a sha256; entries without one are rejected. "size" is optional and only
 * feeds the disk pre-flight.
 */
object ExternalModelEntryJson {

    data class EntryFile(val name: String, val url: String, val sha256: String, val size: Long)

    data class Entry(
        val name: String,
        val family: ModelFamily,
        val modelType: String,
        val languages: List<String>,
        val options: Map<String, String>,
        val files: List<EntryFile>,
    )

    fun parse(text: String): Entry {
        val o = JSONObject(text)
        val filesJson = o.getJSONArray("files")
        val files = buildList {
            for (i in 0 until filesJson.length()) {
                val f = filesJson.getJSONObject(i)
                val sha = f.optString("sha256", "")
                if (sha.length != 64) {
                    throw IllegalArgumentException(
                        "entry file ${f.optString("name")} is missing its sha256 pin; hashless entries are rejected")
                }
                if (!f.has("size") || f.isNull("size")) {
                    // The disk pre-flight is unconditional (spec binding): an entry
                    // without a declared size cannot be pre-flighted, so it is rejected.
                    throw IllegalArgumentException(
                        "entry file ${f.optString("name")} is missing its size; entries must declare it")
                }
                add(EntryFile(f.getString("name"), f.getString("url"), sha, f.getLong("size")))
            }
        }
        if (files.isEmpty()) throw IllegalArgumentException("entry has no files")

        // Parse family with default to TRANSDUCER for legacy entries. A bare
        // valueOf would throw an enum-internal message, so wrap it with the same
        // named unknown-family error shape ExternalCatalog.parseIndex documents.
        val familyStr = o.optString("family", "TRANSDUCER")
        val family = try {
            ModelFamily.valueOf(familyStr)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("entry '${o.optString("name")}' has unknown family: $familyStr")
        }

        // Parse options (null-tolerant, absent → empty map)
        val options = o.optStringMap("options")

        // Languages are mandatory for entries with explicit family, optional for legacy
        val languagesArray = o.optJSONArray("languages")
        val languages: List<String> = if (languagesArray != null) {
            buildList { for (i in 0 until languagesArray.length()) add(languagesArray.getString(i)) }
        } else {
            // Legacy entries without family: languages optional
            if (o.has("family")) {
                throw IllegalArgumentException("entry '${o.optString("name")}' must declare languages")
            } else emptyList()
        }

        // modelType defaults to family-specific values
        val modelType = if (o.has("modelType")) {
            o.getString("modelType")
        } else {
            when (family) {
                ModelFamily.TRANSDUCER -> "nemo_transducer"
                ModelFamily.CTC -> throw IllegalArgumentException(
                    "CTC family requires an explicit modelType: nemo_ctc or zipformer_ctc")
                ModelFamily.WHISPER, ModelFamily.SENSE_VOICE -> ""
            }
        }

        // Validate modelType matches family expectations
        when (family) {
            ModelFamily.TRANSDUCER -> {
                // Valid: nemo_transducer, conformer_transducer, or empty
                if (modelType.isNotEmpty() &&
                    modelType != "nemo_transducer" &&
                    modelType != "conformer_transducer") {
                    throw IllegalArgumentException(
                        "TRANSDUCER family has invalid modelType: $modelType (valid: nemo_transducer, conformer_transducer, or empty)")
                }
            }
            ModelFamily.CTC -> {
                // Valid: nemo_ctc, zipformer_ctc
                if (modelType != "nemo_ctc" && modelType != "zipformer_ctc") {
                    throw IllegalArgumentException(
                        "CTC family has invalid modelType: $modelType (valid: nemo_ctc, zipformer_ctc)")
                }
            }
            ModelFamily.WHISPER, ModelFamily.SENSE_VOICE -> {
                // Must be empty
                if (modelType.isNotEmpty()) {
                    throw IllegalArgumentException(
                        "${family.name} family requires empty modelType, got: $modelType")
                }
            }
        }

        return Entry(
            name = o.getString("name"),
            family = family,
            modelType = modelType,
            languages = languages,
            options = options,
            files = files,
        )
    }
}
