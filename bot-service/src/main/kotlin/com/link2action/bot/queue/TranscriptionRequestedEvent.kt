package com.link2action.bot.queue

import com.link2action.bot.task.TranscriptionSourceType
import java.time.Instant
import java.util.UUID

data class TranscriptionRequestedEvent(
    val taskId: UUID,
    val sourceType: TranscriptionSourceType = TranscriptionSourceType.URL,
    val sourceUrl: String? = null,
    val telegramFileId: String? = null,
    val telegramFileUniqueId: String? = null,
    val originalFileName: String? = null,
    val mimeType: String? = null,
    val fileSizeBytes: Long? = null,
    val language: String?,
    val formats: List<String>,
    val createdAt: Instant
)
