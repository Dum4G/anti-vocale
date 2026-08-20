package com.antivocale.app.ui.tabs

import com.antivocale.app.ui.viewmodel.LogEntry
import org.junit.Assert.*
import org.junit.Test

/**
 * Long-press context menu (GH #52): which actions a log entry offers.
 * Mirrors the swipe-action gating: Copy/Re-transcribe need content, Delete always.
 */
class ContextMenuActionsTest {

    private fun entry(
        status: LogEntry.Status = LogEntry.Status.SUCCESS,
        result: String = "text",
        type: LogEntry.Type = LogEntry.Type.AUDIO,
        filePath: String? = "/tmp/a.wav",
    ) = LogEntry(
        taskId = "t", type = type, status = status, result = result, filePath = filePath
    )

    @Test
    fun `successful audio entry offers retranscribe copy delete`() {
        val actions = buildContextMenuActions(entry(), canRetranscribe = true)

        assertEquals(
            listOf(ContextMenuAction.RETRANSCRIBE, ContextMenuAction.COPY, ContextMenuAction.DELETE),
            actions
        )
    }

    @Test
    fun `entry without retranscribe handler omits retranscribe`() {
        val actions = buildContextMenuActions(entry(), canRetranscribe = false)

        assertEquals(listOf(ContextMenuAction.COPY, ContextMenuAction.DELETE), actions)
    }

    @Test
    fun `entry with empty result omits copy`() {
        val actions = buildContextMenuActions(entry(result = ""), canRetranscribe = true)

        assertEquals(listOf(ContextMenuAction.RETRANSCRIBE, ContextMenuAction.DELETE), actions)
    }

    @Test
    fun `processing entry with interim text omits copy`() {
        // Mirrors the swipe-action gating: interim text is not a final result
        val actions = buildContextMenuActions(
            entry(status = LogEntry.Status.PROCESSING, result = "partial..."),
            canRetranscribe = true,
        )

        assertEquals(listOf(ContextMenuAction.RETRANSCRIBE, ContextMenuAction.DELETE), actions)
    }

    @Test
    fun `error entry offers delete only`() {
        val actions = buildContextMenuActions(
            entry(status = LogEntry.Status.ERROR, result = ""),
            canRetranscribe = false,
        )

        assertEquals(listOf(ContextMenuAction.DELETE), actions)
    }
}
