package com.link2action.bot.task

data class CreateTranscriptionTaskCommand (
    val telegramChatId: Long,
    val telegramUserId: Long,
    val sourceUrl: String,
    val language: String? = null,
    val requestedFormats: Set<String> = setOf("TXT", "MD")
)