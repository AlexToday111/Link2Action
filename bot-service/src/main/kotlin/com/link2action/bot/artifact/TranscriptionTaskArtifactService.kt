package com.link2action.bot.artifact

import com.link2action.bot.common.ClockProvider
import com.link2action.bot.telegram.TelegramMessageSender
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class TranscriptionTaskArtifactService(
    private val repository: TranscriptionTaskArtifactRepository,
    private val telegramMessageSender: TelegramMessageSender,
    private val clockProvider: ClockProvider
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun recordArtifact(
        taskId: UUID,
        telegramChatId: Long,
        telegramMessageId: Long,
        artifactType: ArtifactType,
        filePath: String?
    ) {
        repository.save(
            TranscriptionTaskArtifact(
                id = UUID.randomUUID(),
                taskId = taskId,
                telegramChatId = telegramChatId,
                telegramMessageId = telegramMessageId,
                artifactType = artifactType,
                filePath = filePath,
                createdAt = clockProvider.now()
            )
        )
    }

    @Transactional
    fun deleteTelegramArtifacts(taskId: UUID): TelegramArtifactDeleteResult {
        val artifacts = repository.findByTaskId(taskId)
        var deletedMessagesCount = 0
        var failedDeletionsCount = 0

        artifacts.forEach { artifact ->
            val deleted = telegramMessageSender.deleteMessage(
                chatId = artifact.telegramChatId,
                messageId = artifact.telegramMessageId
            )

            if (deleted) {
                deletedMessagesCount += 1
            } else {
                failedDeletionsCount += 1
                log.warn(
                    "Failed to delete Telegram artifact message: taskId={}, chatId={}, messageId={}, artifactType={}",
                    taskId,
                    artifact.telegramChatId,
                    artifact.telegramMessageId,
                    artifact.artifactType
                )
            }
        }

        repository.deleteAll(artifacts)

        return TelegramArtifactDeleteResult(
            deletedMessagesCount = deletedMessagesCount,
            failedDeletionsCount = failedDeletionsCount
        )
    }
}

data class TelegramArtifactDeleteResult(
    val deletedMessagesCount: Int,
    val failedDeletionsCount: Int
)
