package com.antivocale.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.antivocale.app.ui.viewmodel.LogEntry

@Entity(
    tableName = "logs",
    indices = [Index("timestamp")]
)
data class LogEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val taskId: String,
    val type: String,
    val status: String,
    val prompt: String = "",
    val result: String = "",
    val errorMessage: String? = null,
    val durationMs: Long = 0,
    val filePath: String? = null,
    val audioDurationSeconds: Double = 0.0,
    val sourcePackageName: String? = null,
    /** True when transcription completed but one or more audio chunks were skipped (e.g. low-RAM OOM). */
    val isPartial: Boolean = false,
    /** Number of audio chunks that failed (only meaningful when isPartial == true). */
    val failedChunkCount: Int = 0
)

fun LogEntity.toLogEntry(): LogEntry = LogEntry(
    id = id,
    timestamp = timestamp,
    taskId = taskId,
    type = LogEntry.Type.valueOf(type),
    // Explicit legacy alias: rows written before the QUEUED/PROCESSING split (GH #51)
    // stored "PENDING" for both meanings. Anything else unknown fails loudly rather
    // than silently rendering as in-progress forever.
    status = if (status == "PENDING") LogEntry.Status.PROCESSING else LogEntry.Status.valueOf(status),
    prompt = prompt,
    result = result,
    errorMessage = errorMessage,
    durationMs = durationMs,
    filePath = filePath,
    audioDurationSeconds = audioDurationSeconds,
    sourcePackageName = sourcePackageName,
    isPartial = isPartial,
    failedChunkCount = failedChunkCount
)

fun LogEntry.toEntity(): LogEntity = LogEntity(
    id = id,
    timestamp = timestamp,
    taskId = taskId,
    type = type.name,
    status = status.name,
    prompt = prompt,
    result = result,
    errorMessage = errorMessage,
    durationMs = durationMs,
    filePath = filePath,
    audioDurationSeconds = audioDurationSeconds,
    sourcePackageName = sourcePackageName,
    isPartial = isPartial,
    failedChunkCount = failedChunkCount
)
