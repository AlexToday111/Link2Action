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

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    open val sourceType: TranscriptionSourceType = TranscriptionSourceType.URL,

    @Column(name = "source_url", columnDefinition = "text")
    open val sourceUrl: String? = null,

    @Column(name = "telegram_file_id", columnDefinition = "text")
    open val telegramFileId: String? = null,

    @Column(name = "telegram_file_unique_id", columnDefinition = "text")
    open val telegramFileUniqueId: String? = null,

    @Column(name = "original_file_name", columnDefinition = "text")
    open val originalFileName: String? = null,

    @Column(name = "mime_type", columnDefinition = "text")
    open val mimeType: String? = null,

    @Column(name = "file_size_bytes")
    open val fileSizeBytes: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    open var status: TranscriptionStatus,

    @Column(name = "requested_format", nullable = false, length = 32)
    open var requestedFormat: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_mode", nullable = false, length = 32)
    open var processingMode: ProcessingMode = ProcessingMode.TRANSCRIPT,

    @Column(name = "language", length = 16)
    open var language: String? = null,

    @Column(name = "idempotency_key", length = 128)
    open var idempotencyKey: String? = null,

    @Column(name = "title", columnDefinition = "text")
    open var title: String? = null,

    @Column(name = "duration_seconds")
    open var durationSeconds: Long? = null,

    @Column(name = "last_progress_status", length = 32)
    open var lastProgressStatus: String? = null,

    @Column(name = "progress_message_id")
    open var progressMessageId: Long? = null,

    @Column(name = "result_txt_path", columnDefinition = "text")
    open var resultTxtPath: String? = null,

    @Column(name = "result_md_path", columnDefinition = "text")
    open var resultMdPath: String? = null,

    @Column(name = "result_prompt_path", columnDefinition = "text")
    open var resultPromptPath: String? = null,

    @Column(name = "result_package_path", columnDefinition = "text")
    open var resultPackagePath: String? = null,

    @Column(name = "error_message", columnDefinition = "text")
    open var errorMessage: String? = null,

    @Column(name = "created_at", nullable = false)
    open val createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    open var updatedAt: Instant,

    @Column(name = "started_at")
    open var startedAt: Instant? = null,

    @Column(name = "finished_at")
    open var finishedAt: Instant? = null,

    @Column(name = "deleted_at")
    open var deletedAt: Instant? = null
) {

    fun markQueued(
        requestedFormats: Collection<String>,
        processingMode: ProcessingMode,
        idempotencyKey: String,
        now: Instant
    ) {
        status = TranscriptionStatus.QUEUED
        requestedFormat = requestedFormats.joinToString(",")
        this.processingMode = processingMode
        this.idempotencyKey = idempotencyKey
        updatedAt = now
    }

    fun markProcessing(now: Instant) {
        status = TranscriptionStatus.PROCESSING
        startedAt = now
        updatedAt = now
    }

    fun markCompleted(
        resultTxtPath: String?,
        resultMdPath: String?,
        resultPromptPath: String?,
        resultPackagePath: String?,
        title: String?,
        durationSeconds: Long?,
        detectedLanguage: String?,
        now: Instant
    ) {
        status = TranscriptionStatus.COMPLETED
        this.resultTxtPath = resultTxtPath
        this.resultMdPath = resultMdPath
        this.resultPromptPath = resultPromptPath
        this.resultPackagePath = resultPackagePath
        this.title = title ?: this.title
        this.durationSeconds = durationSeconds ?: this.durationSeconds
        this.language = detectedLanguage ?: this.language
        this.errorMessage = null
        this.lastProgressStatus = null
        this.finishedAt = now
        this.updatedAt = now
    }

    fun markFailed(
        errorMessage: String,
        now: Instant
    ) {
        status = TranscriptionStatus.FAILED
        this.errorMessage = errorMessage
        this.lastProgressStatus = null
        this.finishedAt = now
        this.updatedAt = now
    }

    fun markDeleted(now: Instant) {
        deletedAt = now
        updatedAt = now
    }

    fun isActive(): Boolean {
        return status == TranscriptionStatus.QUEUED ||
                status == TranscriptionStatus.PROCESSING
    }
}
