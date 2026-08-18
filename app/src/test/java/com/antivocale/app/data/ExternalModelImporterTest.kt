package com.antivocale.app.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * JSON-only importer tests (spec: external models platform v2a, JSON-only
 * revision): catalog-entry JSON text import (canonical-name landing, verified
 * pins, metadata validation before persisting, missing-role failure, same-hash
 * dedupe, disk pre-flight). All downloads run against a MockWebServer.
 */
class ExternalModelImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var fake: FakePreferencesManager
    private lateinit var store: ExternalModelStore
    private lateinit var filesRoot: File
    private lateinit var importer: ExternalModelImporter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        fake = FakePreferencesManager()
        store = ExternalModelStore(fake)
        filesRoot = tmp.newFolder("external-root")
        importer = ExternalModelImporter(
            store = store,
            filesRoot = { filesRoot },
            uuid = { "fedcba9876543210" },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val encoderBytes = ByteArray(64) { 1 } + "vocab_size=1024 subsampling_factor=8 model_type=nemo_transducer".toByteArray()
    private val decoderBytes = ByteArray(16) { 2 }
    private val joinerBytes = ByteArray(16) { 3 }
    private val tokensBytes = "<unk> 0\n. 1\n".toByteArray()

    /** A complete catalog-entry JSON referencing the four role files on the server. */
    private fun entryJson(name: String = "GigaAM v3", encoder: ByteArray = encoderBytes): String {
        val base = server.url("/").toString().trimEnd('/')
        return """{"name":"$name","description":"Test entry","modelType":"nemo_transducer","languages":["ru"],
             "files":[
               {"name":"my_encoder.onnx","url":"$base/encoder.bin","sha256":"${sha256(encoder)}","size":${encoder.size}},
               {"name":"decoder.onnx","url":"$base/decoder.bin","sha256":"${sha256(decoderBytes)}","size":${decoderBytes.size}},
               {"name":"joiner.onnx","url":"$base/joiner.bin","sha256":"${sha256(joinerBytes)}","size":${joinerBytes.size}},
               {"name":"tokens.txt","url":"$base/tokens.bin","sha256":"${sha256(tokensBytes)}","size":${tokensBytes.size}}]}"""
    }

    private fun enqueueFiles() {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(encoderBytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(decoderBytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(joinerBytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(tokensBytes)))
    }

    @Test
    fun `json text import downloads under canonical names and registers the record`() = runTest {
        enqueueFiles()
        val record = importer.importFromEntryJsonText(entryJson())

        assertEquals(4, record.files.size)
        assertTrue(record.files.values.all { it.verified })
        assertEquals("GigaAM v3", record.displayName)
        assertEquals("Test entry", record.description)
        assertEquals(listOf("ru"), record.languages)
        assertTrue(File(record.dir, "encoder.int8.onnx").exists())
        assertTrue(File(record.dir, "decoder.int8.onnx").exists())
        assertTrue(File(record.dir, "joiner.int8.onnx").exists())
        assertTrue(File(record.dir, "tokens.txt").exists())
        assertTrue("dir must embed the id fragment", record.dir.endsWith(record.id.take(6)))
        assertEquals(listOf(record), store.records())
    }

    @Test
    fun `buildCopyPlan maps roles by keyword and rejects missing roles`() {
        val plan = importer.buildCopyPlan(listOf("gigaam_encoder_int8.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"))
        assertNotNull(plan)
        assertEquals(4, plan!!.size)
        assertEquals("gigaam_encoder_int8.onnx", plan["encoder.int8.onnx"])
        assertEquals("decoder.onnx", plan["decoder.int8.onnx"])
        assertEquals("joiner.onnx", plan["joiner.int8.onnx"])
        assertEquals("tokens.txt", plan["tokens.txt"])

        assertEquals(null, importer.buildCopyPlan(listOf("tokens.txt")))
        assertEquals(null, importer.buildCopyPlan(emptyList()))
    }

    @Test
    fun `buildCopyPlan accepts gigaam joint naming and vocab tokens`() {
        // The real GigaAM v3 mirror: joint.onnx (not joiner) and tokens.txt.
        val gigaam = importer.buildCopyPlan(listOf(
            "gigaam_v3_e2e_rnnt_encoder_int8.onnx",
            "gigaam_v3_e2e_rnnt_decoder.onnx",
            "gigaam_v3_e2e_rnnt_joint.onnx",
            "gigaam_v3_e2e_rnnt_tokens.txt",
        ))
        assertEquals("gigaam_v3_e2e_rnnt_joint.onnx", gigaam!!["joiner.int8.onnx"])

        // istupakov's export naming: vocab.txt as the tokens file.
        val istupakov = importer.buildCopyPlan(listOf(
            "v3_e2e_rnnt_encoder.int8.onnx",
            "v3_e2e_rnnt_decoder.int8.onnx",
            "v3_e2e_rnnt_joint.int8.onnx",
            "v3_e2e_rnnt_vocab.txt",
        ))
        assertEquals("v3_e2e_rnnt_vocab.txt", istupakov!!["tokens.txt"])
    }

    @Test
    fun `missing role fails with a clean error and registers nothing`() = runTest {
        val base = server.url("/").toString().trimEnd('/')
        val json = """{"name":"incomplete","files":[
            {"name":"tokens.txt","url":"$base/t","sha256":"${"a".repeat(64)}","size":1}]}"""

        val result = runCatching { importer.importFromEntryJsonText(json) }

        assertTrue(result.isFailure)
        assertEquals(0, store.records().size)
        assertEquals(0, filesRoot.listFiles()!!.size)
    }

    @Test
    fun `encoder without metadata fails import cleanly`() = runTest {
        enqueueFiles()
        val badEncoder = ByteArray(32) { 7 }  // no required ONNX metadata keys
        val result = runCatching { importer.importFromEntryJsonText(entryJson(encoder = badEncoder)) }

        assertTrue(result.isFailure)
        assertEquals(0, store.records().size)
        // The fresh target dir is removed by the cleanup-on-failure tail.
        assertEquals(0, filesRoot.listFiles()!!.size)
    }

    @Test
    fun `same-hash reimport dedupes onto the existing record`() = runTest {
        enqueueFiles()
        val first = importer.importFromEntryJsonText(entryJson())
        enqueueFiles()
        val second = importer.importFromEntryJsonText(entryJson())

        assertEquals(first.id, second.id)
        assertEquals(1, store.records().size)
        assertEquals(1, filesRoot.listFiles()!!.size)
    }

    @Test
    fun `hashless or sizeless entry files are rejected at parse time`() = runTest {
        val hashless = """{"name":"x","files":[{"name":"a.onnx","url":"https://x/a"}]}"""
        assertTrue(runCatching { importer.importFromEntryJsonText(hashless) }.isFailure)

        val sizeless = """{"name":"x","files":[{"name":"a.onnx","url":"https://x/a","sha256":"${"f".repeat(64)}"}]}"""
        assertTrue(runCatching { importer.importFromEntryJsonText(sizeless) }.isFailure)
        assertEquals(0, store.records().size)
        assertEquals(0, filesRoot.listFiles()!!.size)
    }

    @Test
    fun `disk pre-flight blocks imports larger than available space`() = runTest {
        val smallRoot = tmp.newFolder("tiny-root")
        val tightImporter = ExternalModelImporter(store, filesRoot = { smallRoot }, uuid = { "0123456789abcdef" })
        val base = server.url("/").toString().trimEnd('/')
        val json = """{"name":"huge","files":[
            {"name":"e.onnx","url":"$base/e","sha256":"${"a".repeat(64)}","size":${Long.MAX_VALUE / 2}},
            {"name":"d.onnx","url":"$base/d","sha256":"${"b".repeat(64)}","size":1},
            {"name":"j.onnx","url":"$base/j","sha256":"${"c".repeat(64)}","size":1},
            {"name":"t.txt","url":"$base/t","sha256":"${"d".repeat(64)}","size":1}]}"""

        val result = runCatching { tightImporter.importFromEntryJsonText(json) }

        assertTrue(result.isFailure)
        // Pre-flight fires before any network round-trip.
        assertEquals(0, server.requestCount)
    }
}