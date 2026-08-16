package com.antivocale.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * One-shot migration from the custom-transducer backend to the external-model store
 * (plan v2a, Task 9): marker-before-record ordering (crash-safe idempotence), hand-computed
 * pins over the legacy directory (no re-copy), active-backend pointer rewrite.
 */
class CustomTransducerMigratorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var fake: FakePreferencesManager
    private lateinit var store: ExternalModelStore
    private lateinit var migrator: CustomTransducerMigrator

    @Before
    fun setUp() {
        fake = FakePreferencesManager()
        store = ExternalModelStore(fake)
        migrator = CustomTransducerMigrator(fake, store)
    }

    /** A legacy custom-transducer dir: canonical names, encoder with metadata tail. */
    private fun legacyDir(name: String = "my-model"): File {
        val dir = tmp.newFolder(name)
        File(dir, "encoder.int8.onnx").writeBytes(
            ByteArray(32) { 1 } + "vocab_size=1024 subsampling_factor=8 model_type=nemo_transducer".toByteArray())
        File(dir, "decoder.int8.onnx").writeBytes(ByteArray(8) { 2 })
        File(dir, "joiner.int8.onnx").writeBytes(ByteArray(8) { 3 })
        File(dir, "tokens.txt").writeText("<unk> 0\n")
        return dir
    }

    @Test
    fun `migrates a valid custom-transducer preference into an external record`() = runTest {
        val dir = legacyDir()
        fake._customTransducerModelPath.value = dir.absolutePath
        fake._customTransducerModelType.value = ""
        fake._transcriptionBackend.value = "custom-transducer"

        migrator.migrate()

        val record = store.records().single()
        assertEquals(ModelFamily.TRANSDUCER, record.family)
        assertEquals("", record.modelType)
        assertEquals(dir.absolutePath, record.dir)          // legacy location kept, no re-copy
        assertEquals(4, record.files.size)
        assertTrue(record.files.values.all { it.verified })
        assertEquals(record.backendId, fake._transcriptionBackend.value)  // active pointer rewritten
        assertTrue(fake._externalMigrationDone.value == true)
    }

    @Test
    fun `done marker prevents re-migration and duplication`() = runTest {
        fake._externalMigrationDone.value = true
        fake._customTransducerModelPath.value = legacyDir().absolutePath

        migrator.migrate()

        assertEquals(0, store.records().size)
    }

    @Test
    fun `marker is written before the record is created`() = runTest {
        // A migrator that created the record first would duplicate on a crash between the
        // two writes. Pin the write ORDER at the preference layer with a recording delegate.
        val dir = legacyDir("crashy")
        fake._customTransducerModelPath.value = dir.absolutePath
        val calls = mutableListOf<String>()
        val recording = object : PreferencesManager by fake {
            override suspend fun saveExternalMigrationDone(done: Boolean) {
                calls += "marker"
                fake.saveExternalMigrationDone(done)
            }
            override suspend fun saveExternalModelsJson(json: String) {
                calls += "record"
                fake.saveExternalModelsJson(json)
            }
        }

        CustomTransducerMigrator(recording, ExternalModelStore(recording)).migrate()

        assertEquals(listOf("marker", "record"), calls)
    }

    @Test
    fun `absent preference is a no-op that still marks done`() = runTest {
        migrator.migrate()
        assertEquals(0, store.records().size)
        assertTrue(fake._externalMigrationDone.value == true)
    }

    @Test
    fun `invalid directory marks done and skips`() = runTest {
        fake._customTransducerModelPath.value = "/gone"
        migrator.migrate()
        assertEquals(0, store.records().size)
        assertTrue(fake._externalMigrationDone.value == true)
    }
}
