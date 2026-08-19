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
}
