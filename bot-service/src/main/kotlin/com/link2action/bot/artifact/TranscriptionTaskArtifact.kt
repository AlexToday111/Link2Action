package com.link2action.bot.artifact

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "transcription_task_artifacts")
open class TranscriptionTaskArtifact(

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    open val id: UUID,

    @Column(name = "task_id", nullable = false)
    open val taskId: UUID,

    @Column(name = "telegram_chat_id", nullable = false)
    open val telegramChatId: Long,

    @Column(name = "telegram_message_id", nullable = false)
    open val telegramMessageId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "artifact_type", nullable = false, length = 32)
    open val artifactType: ArtifactType,

    @Column(name = "file_path", columnDefinition = "text")
    open val filePath: String? = null,

    @Column(name = "created_at", nullable = false)
    open val createdAt: Instant
)
