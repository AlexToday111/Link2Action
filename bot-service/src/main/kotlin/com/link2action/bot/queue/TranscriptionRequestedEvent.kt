package com.link2action.bot.queue

import java.time.Instant
import java.util.UUID

data class TranscriptionRequestedEvent(
    val taskId: UUID,
    val sourceUrl: String,
    val language: String?,
    val formats: List<String>,
    val createdAt: Instant
)