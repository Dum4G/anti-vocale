package com.antivocale.app.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExternalModelStoreTest {

    private lateinit var fake: FakePreferencesManager
    private lateinit var store: ExternalModelStore

    @Before
    fun setUp() {
        fake = FakePreferencesManager()
        store = ExternalModelStore(fake)
    }

    private fun record(id: String = "a1b2c3d4e5f6", name: String = "GigaAM v3") = ExternalModelRecord(
        id = id,
        displayName = name,
        dir = "/data/user/0/com.antivocale.app/files/models/external/gigaam-v3-a1b2c3",
        family = ModelFamily.TRANSDUCER,
        modelType = "nemo_transducer",
        languages = listOf("ru"),
        source = ExternalModelSource.LOCAL,
        sourceUrl = null,
        files = mapOf(
            "encoder.int8.onnx" to FilePin("2cac62d0c270bd128f898f2be1a2d34780d524a6e9483888ebac7b00f97410f1", verified = true),
            "tokens.txt" to FilePin("7ddf22514c42c531358182c81446a8159771e9921019f09ae743ea622d40221d", verified = false),
        ),
        sizeBytes = 326_322_304L,
        importedAt = 1_755_000_000_000L,
    )

    @Test
    fun `empty store lists nothing and json round-trips through the preference`() = runTest {
        assertEquals(emptyList<ExternalModelRecord>(), store.records())
        val rec = record()
        store.add(rec)
        assertEquals(listOf(rec), store.records())
        assertEquals(listOf(rec), ExternalModelStore(fake).records())
    }

    @Test
    fun `update replaces by id and delete removes only the target`() = runTest {
        val a = record(id = "aaaaaaaaaaaa", name = "A")
        val b = record(id = "bbbbbbbbbbbb", name = "B")
        store.add(a); store.add(b)
        store.update(a.copy(displayName = "A2"))
        assertEquals("A2", store.records().first { it.id == "aaaaaaaaaaaa" }.displayName)
        store.delete("aaaaaaaaaaaa")
        assertEquals(listOf("bbbbbbbbbbbb"), store.records().map { it.id })
    }

    @Test
    fun `validity requires the directory to exist`() = runTest {
        val rec = record()
        store.add(rec)
        val validity = ExternalModelStore(fake) { false }
        assertTrue(validity.invalidRecordIds().contains(rec.id))
        assertNull(validity.byId(rec.id))
    }

    @Test
    fun `validRecordsFlow filters records whose directory is missing`() = runTest {
        val rec = record()
        store.add(rec)
        val filtered = ExternalModelStore(fake) { false }
        assertEquals(emptyList<ExternalModelRecord>(), filtered.validRecordsFlow.first())
        assertEquals(listOf(rec), ExternalModelStore(fake) { true }.validRecordsFlow.first())
    }
}
