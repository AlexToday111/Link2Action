package com.link2action.bot.task

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "transcription_tasks")
open class TranscriptionTask(

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    open val id: UUID,

    @Column(name = "telegram_chat_id", nullable = false)
    open val telegramChatId: Long,

    @Column(name = "telegram_user_id", nullable = false)
    open val telegramUserId: Long,

    @Column(name = "source_url", nullable = false, columnDefinition = "text")
    open val sourceUrl: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    open var status: TranscriptionStatus,

    @Column(name = "requested_format", nullable = false, length = 32)
    open val requestedFormat: String,

    @Column(name = "language", length = 16)
    open var language: String? = null,

    @Column(name = "result_txt_path", columnDefinition = "text")
    open var resultTxtPath: String? = null,

    @Column(name = "result_md_path", columnDefinition = "text")
    open var resultMdPath: String? = null,

    @Column(name = "error_message", columnDefinition = "text")
    open var errorMessage: String? = null,

    @Column(name = "created_at", nullable = false)
    open val createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    open var updatedAt: Instant,

    @Column(name = "started_at")
    open var startedAt: Instant? = null,

    @Column(name = "finished_at")
    open var finishedAt: Instant? = null
) {

    fun markProcessing(now: Instant) {
        status = TranscriptionStatus.PROCESSING
        startedAt = now
        updatedAt = now
    }

    fun markCompleted(
        resultTxtPath: String?,
        resultMdPath: String?,
        detectedLanguage: String?,
        now: Instant
    ) {
        status = TranscriptionStatus.COMPLETED
        this.resultTxtPath = resultTxtPath
        this.resultMdPath = resultMdPath
        this.language = detectedLanguage ?: this.language
        this.errorMessage = null
        this.finishedAt = now
        this.updatedAt = now
    }

    fun markFailed(
        errorMessage: String,
        now: Instant
    ) {
        status = TranscriptionStatus.FAILED
        this.errorMessage = errorMessage
        this.finishedAt = now
        this.updatedAt = now
    }

    fun isActive(): Boolean {
        return status == TranscriptionStatus.QUEUED ||
                status == TranscriptionStatus.PROCESSING
    }
}