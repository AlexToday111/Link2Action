package com.link2action.bot.queue

import java.time.Instant
import java.util.UUID

data class TranscriptionResultEvent(
    val taskId: UUID,
    val status: String,
    val title: String? = null,
    val durationSeconds: Long? = null,
    val language: String? = null,
    val resultTxtPath: String? = null,
    val resultMdPath: String? = null,
    val errorMessage: String? = null,
    val completedAt: Instant? = null
)

object TranscriptionResultStatus {
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val DOWNLOADING = "DOWNLOADING"
    const val TRANSCRIBING = "TRANSCRIBING"
    const val EXPORTING = "EXPORTING"
}
