package com.antivocale.app.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * JSON-only external-import tests (spec decision): catalog-entry JSON parsing
 * (literal name/description, mandatory url+sha256+size) and the end-to-end URL
 * import path against a MockWebServer (fetch JSON, canonical landing, verified
 * pins, hashless-entry rejection).
 */
class HuggingFaceRepoListingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var listing: HuggingFaceRepoListing
    private lateinit var fake: FakePreferencesManager
    private lateinit var store: ExternalModelStore
    private lateinit var filesRoot: File
    private lateinit var importer: ExternalModelImporter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        listing = HuggingFaceRepoListing()
        fake = FakePreferencesManager()
        store = ExternalModelStore(fake)
        filesRoot = tmp.newFolder("external-root")
        importer = ExternalModelImporter(
            store = store,
            filesRoot = { filesRoot },
            uuid = { "fedcba9876543210" },
            repoListing = listing,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---- entry JSON parsing (unified catalog schema) ----

    @Test
    fun `entry json parses literal display and demands hashes and sizes`() {
        val entry = ExternalModelEntryJson.parse("""
            {"name":"GigaAM v3","description":"Sber RNNT","modelType":"nemo_transducer","languages":["ru"],
             "files":[{"name":"some_encoder.onnx","url":"https://x/e.onnx","sha256":"${"b".repeat(64)}","size":100},
                      {"name":"decoder.onnx","url":"https://x/d.onnx","sha256":"${"c".repeat(64)}","size":50},
                      {"name":"joiner.onnx","url":"https://x/j.onnx","sha256":"${"d".repeat(64)}","size":50},
                      {"name":"tokens.txt","url":"https://x/t.txt","sha256":"${"e".repeat(64)}","size":10}]}
        """.trimIndent())
        assertEquals("GigaAM v3", entry.name)
        assertEquals("Sber RNNT", entry.description)
        assertEquals("nemo_transducer", entry.modelType)
        assertEquals(listOf("ru"), entry.languages)
        assertEquals(4, entry.files.size)
        assertEquals(100L, entry.files.first().size)

        val hashless = runCatching {
            ExternalModelEntryJson.parse("""{"name":"x","files":[{"name":"a.onnx","url":"https://x/a"}]}""")
        }
        assertTrue(hashless.isFailure)

        // Sizes are mandatory too: they feed the unconditional disk pre-flight.
        val sizeless = runCatching {
            ExternalModelEntryJson.parse(
                """{"name":"x","files":[{"name":"a.onnx","url":"https://x/a","sha256":"${"f".repeat(64)}"}]}""")
        }
        assertTrue(sizeless.isFailure)

        // A url-less file is rejected as well.
        val urlless = runCatching {
            ExternalModelEntryJson.parse(
                """{"name":"x","files":[{"name":"a.onnx","sha256":"${"g".repeat(64)}","size":10}]}""")
        }
        assertTrue(urlless.isFailure)
    }

    @Test
    fun `external entry name must be literal, never a resource key`() {
        val resourceKeyed = runCatching {
            ExternalModelEntryJson.parse(
                """{"display":{"resourceKey":"parakeet_name"},"files":[{"name":"a.onnx","url":"https://x/a","sha256":"${"h".repeat(64)}","size":1}]}""")
        }
        assertTrue(resourceKeyed.isFailure)
    }

    @Test
    fun `entry json defaults modelType to nemo_transducer`() {
        val entry = ExternalModelEntryJson.parse("""
            {"name":"NoType",
             "files":[{"name":"e.onnx","url":"https://x/e","sha256":"${"b".repeat(64)}","size":1},
                      {"name":"d.onnx","url":"https://x/d","sha256":"${"c".repeat(64)}","size":1},
                      {"name":"j.onnx","url":"https://x/j","sha256":"${"d".repeat(64)}","size":1},
                      {"name":"t.txt","url":"https://x/t","sha256":"${"e".repeat(64)}","size":1}]}
        """.trimIndent())
        assertEquals("nemo_transducer", entry.modelType)
        assertNull(entry.description)
    }

    // ---- end-to-end URL import against the mock server ----

    private val encoderBytes = ByteArray(64) { 1 } + "vocab_size=1024 subsampling_factor=8 model_type=nemo_transducer".toByteArray()
    private val decoderBytes = ByteArray(16) { 2 }
    private val joinerBytes = ByteArray(16) { 3 }
    private val tokensBytes = "<unk> 0\n. 1\n".toByteArray()

    @Test
    fun `importFromEntryJson fetches the json, downloads all files and verifies their hashes`() = runTest {
        val base = server.url("/").toString().trimEnd('/')
        server.enqueue(MockResponse().setBody("""
            {"name":"GigaAM v3","modelType":"nemo_transducer",
             "files":[{"name":"my_encoder.onnx","url":"$base/e","sha256":"${sha256(encoderBytes)}","size":${encoderBytes.size}},
                      {"name":"decoder.onnx","url":"$base/d","sha256":"${sha256(decoderBytes)}","size":${decoderBytes.size}},
                      {"name":"joiner.onnx","url":"$base/j","sha256":"${sha256(joinerBytes)}","size":${joinerBytes.size}},
                      {"name":"tokens.txt","url":"$base/t","sha256":"${sha256(tokensBytes)}","size":${tokensBytes.size}}]}
        """.trimIndent()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(encoderBytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(decoderBytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(joinerBytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(tokensBytes)))

        val record = importer.importFromEntryJson("$base/entry.json")

        assertEquals("GigaAM v3", record.displayName)
        assertTrue(record.files.values.all { it.verified })
        assertTrue(File(record.dir, "encoder.int8.onnx").exists())
        assertTrue(File(record.dir, "tokens.txt").exists())
    }
}