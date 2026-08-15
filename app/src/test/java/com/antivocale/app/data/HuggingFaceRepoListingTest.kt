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
 * URL-import tests (plan v2a, Task 8): repo-id parsing, HF tree listing (LFS oid vs
 * plain file), catalog-entry JSON parsing with mandatory hashes, and the end-to-end
 * importer path against a MockWebServer (canonical landing, verified vs TOFU pins,
 * hashless-entry rejection).
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
        listing = HuggingFaceRepoListing(apiBase = server.url("/").toString().trimEnd('/'))
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

    // ---- parsing ----

    @Test
    fun `parseRepoId accepts repo urls and rejects others`() {
        assertEquals("pantinor/gigaam-v3", HuggingFaceRepoListing.parseRepoId("https://huggingface.co/pantinor/gigaam-v3"))
        assertEquals("pantinor/gigaam-v3", HuggingFaceRepoListing.parseRepoId("https://huggingface.co/pantinor/gigaam-v3/tree/main"))
        assertEquals("istupakov/gigaam-v3-onnx", HuggingFaceRepoListing.parseRepoId("istupakov/gigaam-v3-onnx"))
        assertNull(HuggingFaceRepoListing.parseRepoId("https://huggingface.co/only-owner"))
        assertNull(HuggingFaceRepoListing.parseRepoId("https://example.com/a/b"))
    }

    @Test
    fun `tree listing maps lfs and plain files`() = runTest {
        server.enqueue(MockResponse().setBody("""
            [
              {"type":"file","path":"gigaam_v3_e2e_rnnt_encoder_int8.onnx","lfs":{"oid":"${"a".repeat(64)}","size":318995997},"size":318995997},
              {"type":"file","path":"tokens.txt","size":13353},
              {"type":"directory","path":"subdir"}
            ]
        """.trimIndent()))

        val files = listing.listFiles("pantinor/gigaam-v3")

        assertEquals(2, files.size)
        val lfs = files[0] as HuggingFaceRepoListing.HfFile.Lfs
        assertEquals("gigaam_v3_e2e_rnnt_encoder_int8.onnx", lfs.name)
        assertEquals("a".repeat(64), lfs.sha256)
        assertEquals(318995997L, lfs.size)
        assertTrue(files[1] is HuggingFaceRepoListing.HfFile.Plain)
        assertEquals("tokens.txt", (files[1] as HuggingFaceRepoListing.HfFile.Plain).name)
    }

    @Test
    fun `entry json parses and demands hashes`() {
        val entry = ExternalModelEntryJson.parse("""
            {"name":"GigaAM v3","modelType":"nemo_transducer","languages":["ru"],
             "files":[{"name":"some_encoder.onnx","url":"https://x/e.onnx","sha256":"${"b".repeat(64)}"},
                      {"name":"decoder.onnx","url":"https://x/d.onnx","sha256":"${"c".repeat(64)}"},
                      {"name":"joiner.onnx","url":"https://x/j.onnx","sha256":"${"d".repeat(64)}"},
                      {"name":"tokens.txt","url":"https://x/t.txt","sha256":"${"e".repeat(64)}"}]}
        """.trimIndent())
        assertEquals("GigaAM v3", entry.name)
        assertEquals("nemo_transducer", entry.modelType)
        assertEquals(4, entry.files.size)

        val hashless = runCatching {
            ExternalModelEntryJson.parse("""{"name":"x","files":[{"name":"a.onnx","url":"https://x/a"}]}""")
        }
        assertTrue(hashless.isFailure)
    }

    // ---- end-to-end against the mock server ----

    private val encoderBytes = ByteArray(64) { 1 } + "vocab_size=1024 subsampling_factor=8 model_type=nemo_transducer".toByteArray()
    private val decoderBytes = ByteArray(16) { 2 }
    private val joinerBytes = ByteArray(16) { 3 }
    private val tokensBytes = "<unk> 0\n. 1\n".toByteArray()

    @Test
    fun `importFromHuggingFaceRepo downloads under canonical names with TOFU for non-LFS`() = runTest {
        server.enqueue(MockResponse().setBody("""
            [
              {"type":"file","path":"gigaam_encoder_int8.onnx","lfs":{"oid":"${sha256(encoderBytes)}","size":${encoderBytes.size}},"size":${encoderBytes.size}},
              {"type":"file","path":"gigaam_decoder.onnx","lfs":{"oid":"${sha256(decoderBytes)}","size":${decoderBytes.size}},"size":${decoderBytes.size}},
              {"type":"file","path":"gigaam_joiner.onnx","lfs":{"oid":"${sha256(joinerBytes)}","size":${joinerBytes.size}},"size":${joinerBytes.size}},
              {"type":"file","path":"tokens.txt","size":${tokensBytes.size}}
            ]
        """.trimIndent()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(encoderBytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(decoderBytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(joinerBytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(tokensBytes)))

        val record = importer.importFromHuggingFaceRepo("https://huggingface.co/pantinor/gigaam-v3")

        assertEquals(ExternalModelSource.URL, record.source)
        assertEquals(4, record.files.size)
        assertTrue(File(record.dir, "encoder.int8.onnx").exists())
        assertTrue(File(record.dir, "decoder.int8.onnx").exists())
        assertTrue(File(record.dir, "joiner.int8.onnx").exists())
        assertTrue(File(record.dir, "tokens.txt").exists())
        assertTrue("LFS pins verified", record.files["encoder.int8.onnx"]!!.verified)
        assertTrue("non-LFS pin computed (TOFU)", !record.files["tokens.txt"]!!.verified)
        assertEquals(sha256(tokensBytes), record.files["tokens.txt"]!!.sha256)
    }

    @Test
    fun `importFromEntryJson downloads all files and verifies their hashes`() = runTest {
        val base = server.url("/").toString().trimEnd('/')
        server.enqueue(MockResponse().setBody("""
            {"name":"GigaAM v3","modelType":"nemo_transducer",
             "files":[{"name":"my_encoder.onnx","url":"$base/e","sha256":"${sha256(encoderBytes)}"},
                      {"name":"decoder.onnx","url":"$base/d","sha256":"${sha256(decoderBytes)}"},
                      {"name":"joiner.onnx","url":"$base/j","sha256":"${sha256(joinerBytes)}"},
                      {"name":"tokens.txt","url":"$base/t","sha256":"${sha256(tokensBytes)}"}]}
        """.trimIndent()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(encoderBytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(decoderBytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(joinerBytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(tokensBytes)))

        val record = importer.importFromEntryJson("$base/entry.json")

        assertEquals("GigaAM v3", record.displayName)
        assertTrue(record.files.values.all { it.verified })
        assertTrue(File(record.dir, "encoder.int8.onnx").exists())
    }
}
