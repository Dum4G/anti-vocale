package com.antivocale.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: LogEntity)

    @Update
    suspend fun update(log: LogEntity)

    @Query("DELETE FROM logs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM logs")
    suspend fun deleteAll()

    /**
     * Bounded recent-first query for the Logs UI (TASK-340 Fix 2a): the previous
     * unbounded getAll let the whole history pile into the 256MB heap, and the
     * ViewModel remaps the full list into new objects on every interim Room write.
     * 500 = the bounded UI window; the search query below reaches FULL history
     * (SQL LIKE) so the bound does not silently hide older transcripts from search.
     */
    @Query("SELECT * FROM logs ORDER BY timestamp DESC LIMIT 500")
    fun getAll(): Flow<List<LogEntity>>

    @Query("SELECT * FROM logs WHERE result LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT 500")
    fun searchAll(query: String): Flow<List<LogEntity>>

    @Query("SELECT * FROM logs WHERE taskId = :taskId LIMIT 1")
    suspend fun getByTaskId(taskId: String): LogEntity?

    /** Records which model handled a task (GH #45); written as soon as the backend is loaded. */
    @Query("UPDATE logs SET modelName = :modelName WHERE taskId = :taskId")
    suspend fun setModelName(taskId: String, modelName: String)

    // ---- GH #51 status transitions ----
    // The SQL IN-lists below are the single source for the non-terminal set,
    // including the legacy "PENDING" spelling written before the QUEUED/PROCESSING
    // split; Kotlin callers must not keep their own copy.

    /** Promotes a row to PROCESSING when work starts; terminal rows (and absent rows) are untouched. */
    @Query("UPDATE logs SET status = 'PROCESSING' WHERE taskId = :taskId AND status IN ('QUEUED', 'PROCESSING', 'PENDING')")
    suspend fun promoteToProcessing(taskId: String)

    /** Fails a single non-terminal row (cancellation / interruption paths). */
    @Query("UPDATE logs SET status = 'ERROR', errorMessage = :errorMessage, durationMs = :durationMs WHERE taskId = :taskId AND status IN ('QUEUED', 'PROCESSING', 'PENDING')")
    suspend fun failNonTerminal(taskId: String, errorMessage: String, durationMs: Long)

    /** Fails the non-terminal rows of the given tasks in one round trip (batch cancel). */
    @Query("UPDATE logs SET status = 'ERROR', errorMessage = :errorMessage WHERE taskId IN (:taskIds) AND status IN ('QUEUED', 'PROCESSING', 'PENDING')")
    suspend fun failNonTerminalForTaskIds(taskIds: List<String>, errorMessage: String)

    /**
     * Fails every non-terminal row at once (cold-start sweep): rows left QUEUED or
     * PROCESSING (or the legacy "PENDING") by a process death can never complete.
     * Safe to run only at process start, before the transcription service can be
     * running in this same process.
     */
    @Query("UPDATE logs SET status = 'ERROR', errorMessage = :reason WHERE status IN ('QUEUED', 'PROCESSING', 'PENDING')")
    suspend fun failAllNonTerminal(reason: String)
}
