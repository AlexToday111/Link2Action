package com.link2action.bot.task

data class CreateTranscriptionTaskCommand(
    val telegramChatId: Long,
    val telegramUserId: Long,
    val sourceType: TranscriptionSourceType = TranscriptionSourceType.URL,
    val sourceUrl: String? = null,
    val telegramFileId: String? = null,
    val telegramFileUniqueId: String? = null,
    val originalFileName: String? = null,
    val mimeType: String? = null,
    val fileSizeBytes: Long? = null,
    val language: String? = null,
    val requestedFormats: Set<String> = setOf("TXT", "MD")
)
