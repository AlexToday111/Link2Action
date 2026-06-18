package com.link2action.bot.task

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TranscriptionTaskRepository : JpaRepository<TranscriptionTask, UUID> {

    fun countByTelegramUserIdAndStatusIn(
        telegramUserId: Long,
        statuses: Collection<TranscriptionStatus>
    ): Long

    fun findByTelegramUserIdOrderByCreatedAtDesc(
        telegramUserId: Long
    ): List<TranscriptionTask>
}