package com.link2action.bot.task

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.domain.Pageable
import java.util.UUID

interface TranscriptionTaskRepository : JpaRepository<TranscriptionTask, UUID> {

    fun countByTelegramUserIdAndStatusIn(
        telegramUserId: Long,
        statuses: Collection<TranscriptionStatus>
    ): Long

    fun countByTelegramUserIdAndDeletedAtIsNullAndStatusIn(
        telegramUserId: Long,
        statuses: Collection<TranscriptionStatus>
    ): Long

    fun findByTelegramUserIdOrderByCreatedAtDesc(
        telegramUserId: Long
    ): List<TranscriptionTask>

    fun findByTelegramUserIdOrderByCreatedAtDesc(
        telegramUserId: Long,
        pageable: Pageable
    ): List<TranscriptionTask>

    fun findByTelegramUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(
        telegramUserId: Long,
        pageable: Pageable
    ): List<TranscriptionTask>

    fun findByTelegramUserIdAndStatusInOrderByCreatedAtDesc(
        telegramUserId: Long,
        statuses: Collection<TranscriptionStatus>
    ): List<TranscriptionTask>

    fun findByTelegramUserIdAndDeletedAtIsNullAndStatusInOrderByCreatedAtDesc(
        telegramUserId: Long,
        statuses: Collection<TranscriptionStatus>
    ): List<TranscriptionTask>

    fun findByIdAndTelegramUserId(
        id: UUID,
        telegramUserId: Long
    ): TranscriptionTask?

    fun findByIdAndTelegramUserIdAndDeletedAtIsNull(
        id: UUID,
        telegramUserId: Long
    ): TranscriptionTask?
}
