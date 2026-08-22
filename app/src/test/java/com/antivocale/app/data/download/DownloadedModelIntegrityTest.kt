package com.antivocale.app.data.download

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * TASK-305: structural post-download validation catches truncated/corrupt
 * downloads before native graph construction.
 */
class DownloadedModelIntegrityTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun onnx(name: String, size: Int = 8192, validHeader: Boolean = true): File =
        File(tmp.newFolder(), name).apply {
            writeBytes(ByteArray(size) { if (it == 0 && validHeader) 0x08 else 0x00 })
        }

    @Test
    fun `healthy model dir passes`() {
        val dir = tmp.newFolder()
        onnx("encoder.int8.onnx").copyTo(File(dir, "encoder.int8.onnx"))
        onnx("decoder.onnx").copyTo(File(dir, "decoder.onnx"))
        File(dir, "tokens.txt").writeText((1..100).joinToString("\n") { "tok$it $it" })

        assertTrue(DownloadedModelIntegrity.validate(dir).isEmpty())
    }

    @Test
    fun `truncated onnx is flagged`() {
        val dir = tmp.newFolder()
        onnx("encoder.int8.onnx", size = 100).copyTo(File(dir, "encoder.int8.onnx"))

        val findings = DownloadedModelIntegrity.validate(dir)
        assertEquals(1, findings.size)
        assertTrue(findings[0].reason.contains("small"))
    }

    @Test
    fun `non-onnx payload in an onnx file is flagged`() {
        val dir = tmp.newFolder()
        onnx("encoder.int8.onnx", validHeader = false).copyTo(File(dir, "encoder.int8.onnx"))

        val findings = DownloadedModelIntegrity.validate(dir)
        assertEquals(1, findings.size)
        assertTrue(findings[0].reason.contains("ONNX header"))
    }

    @Test
    fun `stub token file is flagged`() {
        val dir = tmp.newFolder()
        onnx("encoder.int8.onnx").copyTo(File(dir, "encoder.int8.onnx"))
        File(dir, "tokens.txt").writeText("x")

        val findings = DownloadedModelIntegrity.validate(dir)
        assertEquals(1, findings.size)
        assertTrue(findings[0].reason.contains("token"))
    }

    @Test
    fun `missing directory and empty directory are flagged`() {
        assertEquals(1, DownloadedModelIntegrity.validate(File("/nonexistent")).size)
        assertEquals(1, DownloadedModelIntegrity.validate(tmp.newFolder()).size)
    }
}
