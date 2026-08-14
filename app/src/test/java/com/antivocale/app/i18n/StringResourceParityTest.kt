package com.antivocale.app.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-319 guard: every string key in the default locale must exist in the
 * Italian locale (and vice versa: no dead translations). A key missing from
 * values-it renders English inside the Italian UI, which is exactly the bug
 * class this test exists to prevent ("Save transcripts to folder" shipped
 * untranslated; 70 keys were recovered in the first audit).
 *
 * Reads the res files from disk. Unit tests run with the module directory as
 * working directory; both that and the repo root are probed so the test also
 * works when launched from the root.
 */
class StringResourceParityTest {

    private fun stringsFile(path: String): File {
        val moduleRelative = File(path)
        val rootRelative = File("app/$path")
        return when {
            moduleRelative.exists() -> moduleRelative
            rootRelative.exists() -> rootRelative
            else -> throw IllegalStateException("Cannot locate $path from ${File(".").absolutePath}")
        }
    }

    private fun keys(file: File): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("name").nodeValue }.toSet()
    }

    @Test
    fun `every default-locale key has an Italian translation`() {
        val missing = keys(stringsFile("src/main/res/values/strings.xml")) -
            keys(stringsFile("src/main/res/values-it/strings.xml"))
        assertTrue(
            "Keys missing from values-it/strings.xml (Italian UI shows English): $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `no dead Italian translations without a default-locale key`() {
        val dead = keys(stringsFile("src/main/res/values-it/strings.xml")) -
            keys(stringsFile("src/main/res/values/strings.xml"))
        assertTrue("Dead translations in values-it (key absent from default locale): $dead", dead.isEmpty())
    }
}
