package com.antivocale.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Local-entry importer tests (plan v2a, Task 7): role-based copy plan, id-fragment
 * directory uniqueness, streaming pins, metadata validation before persisting,
 * missing-role failure, same-hash dedupe. The SAF entry (importFromTreeUri) is a
 * thin DocumentFile port of the same core and is device-verified (Task 12).
 */
class ExternalModelImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var fake: FakePreferencesManager
    private lateinit var store: ExternalModelStore
    private lateinit var filesRoot: File
    private lateinit var importer: ExternalModelImporter

    @Before
    fun setUp() {
        fake = FakePreferencesManager()
        store = ExternalModelStore(fake)
        filesRoot = tmp.newFolder("external-root")
        importer = ExternalModelImporter(
            store = store,
            filesRoot = { filesRoot },
            uuid = { "fedcba9876543210" },
        )
    }

    /** Source dir with the four role files; the encoder carries the metadata keys in its tail. */
    private fun sourceDir(name: String = "gigaam-v3"): File {
        val dir = tmp.newFolder(name)
        File(dir, "some_encoder_int8.onnx").writeBytes(
            ByteArray(64) { 1 } + "vocab_size=1024 subsampling_factor=8 model_type=nemo_transducer".toByteArray())
        File(dir, "some_decoder.onnx").writeBytes(ByteArray(16) { 2 })
        File(dir, "some_joiner.onnx").writeBytes(ByteArray(16) { 3 })
        File(dir, "tokens.txt").writeText("<unk> 0\n. 1\n")
        return dir
    }

    @Test
    fun `local import copies to id-fragment dir, records pins, registers the record`() = runTest {
        val src = sourceDir()
        val record = importer.importFromDirectory(src)

        assertEquals(4, record.files.size)
        assertTrue(record.files.values.all { it.verified })
        assertTrue(File(record.dir).isDirectory)
        assertEquals(4, File(record.dir).listFiles()!!.size)
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
    fun `missing role fails with a clean error and registers nothing`() = runTest {
        val src = tmp.newFolder("incomplete")
        File(src, "tokens.txt").writeText("x")

        val result = runCatching { importer.importFromDirectory(src) }

        assertTrue(result.isFailure)
        assertEquals(0, store.records().size)
        assertEquals(0, filesRoot.listFiles()!!.size)
    }

    @Test
    fun `encoder without metadata fails import cleanly`() = runTest {
        val src = tmp.newFolder("badmeta")
        File(src, "encoder.onnx").writeBytes(ByteArray(32) { 7 })
        File(src, "decoder.onnx").writeBytes(ByteArray(8) { 2 })
        File(src, "joiner.onnx").writeBytes(ByteArray(8) { 3 })
        File(src, "tokens.txt").writeText("x")

        val result = runCatching { importer.importFromDirectory(src) }

        assertTrue(result.isFailure)
        assertEquals(0, store.records().size)
    }

    @Test
    fun `same-hash reimport offers no second directory`() = runTest {
        val src = sourceDir()
        val first = importer.importFromDirectory(src)
        val second = importer.importFromDirectory(src)
        assertEquals(first.id, second.id)
        assertEquals(1, store.records().size)
        assertEquals(1, filesRoot.listFiles()!!.size)
    }

    @Test
    fun `disk pre-flight blocks imports larger than available space`() = runTest {
        val smallRoot = tmp.newFolder("tiny-root")
        val tightImporter = ExternalModelImporter(store, filesRoot = { smallRoot }, uuid = { "0123456789abcdef" })
        // TemporaryFolder cannot shrink usableSpace; instead verify the guard fires by
        // faking a huge source: a sparse file whose length exceeds the free space heuristic.
        val src = tmp.newFolder("huge")
        val huge = File(src, "encoder.onnx")
        // Length only, no allocation: the pre-flight reads lengths, RandomAccessFile sets them sparsely.
        java.io.RandomAccessFile(huge, "rw").use { it.setLength(Long.MAX_VALUE / 2) }
        File(src, "decoder.onnx").writeBytes(ByteArray(4))
        File(src, "joiner.onnx").writeBytes(ByteArray(4))
        File(src, "tokens.txt").writeText("x")

        val result = runCatching { tightImporter.importFromDirectory(src) }
        assertTrue(result.isFailure)
    }
}
