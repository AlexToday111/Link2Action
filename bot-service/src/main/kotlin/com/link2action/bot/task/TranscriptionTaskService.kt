package com.link2action.bot.task

import com.link2action.bot.common.ClockProvider
import com.link2action.bot.config.AppProperties
import com.link2action.bot.queue.TranscriptionRequestPublisher
import com.link2action.bot.queue.TranscriptionRequestedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class TranscriptionTaskService(
    private val repository: TranscriptionTaskRepository,
    private val requestPublisher: TranscriptionRequestPublisher,
    private val appProperties: AppProperties,
    private val clockProvider: ClockProvider
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val activeStatuses = setOf(
        TranscriptionStatus.QUEUED,
        TranscriptionStatus.PROCESSING
    )

    @Transactional
    fun createTask(command: CreateTranscriptionTaskCommand): UUID {
        validateActiveTaskLimit(command.telegramUserId)

        val now = clockProvider.now()
        val taskId = UUID.randomUUID()

        val task = TranscriptionTask(
            id = taskId,
            telegramChatId = command.telegramChatId,
            telegramUserId = command.telegramUserId,
            sourceUrl = command.sourceUrl,
            status = TranscriptionStatus.QUEUED,
            requestedFormat = command.requestedFormats.joinToString(","),
            language = command.language,
            createdAt = now,
            updatedAt = now
        )

        repository.save(task)

        val event = TranscriptionRequestedEvent(
            taskId = task.id,
            sourceUrl = task.sourceUrl,
            language = task.language,
            formats = command.requestedFormats.toList(),
            createdAt = task.createdAt
        )

        requestPublisher.publish(event)

        log.info(
            "Created transcription task: taskId={}, userId={}, chatId={}",
            task.id,
            task.telegramUserId,
            task.telegramChatId
        )

        return task.id
    }

    @Transactional(readOnly = true)
    fun getTask(taskId: UUID): TranscriptionTask? {
        return repository.findById(taskId).orElse(null)
    }

    @Transactional(readOnly = true)
    fun getUserTasks(telegramUserId: Long): List<TranscriptionTask> {
        return repository.findByTelegramUserIdOrderByCreatedAtDesc(telegramUserId)
    }

    @Transactional
    fun markProcessing(taskId: UUID): TranscriptionTask {
        val task = getRequiredTask(taskId)
        val now = clockProvider.now()

        if (task.status == TranscriptionStatus.COMPLETED || task.status == TranscriptionStatus.FAILED) {
            log.warn(
                "Ignoring markProcessing for finished task: taskId={}, currentStatus={}",
                task.id,
                task.status
            )
            return task
        }

        task.markProcessing(now)

        log.info("Marked transcription task as PROCESSING: taskId={}", task.id)

        return task
    }

    @Transactional
    fun markCompleted(
        taskId: UUID,
        resultTxtPath: String?,
        resultMdPath: String?,
        detectedLanguage: String? = null
    ): TranscriptionTask {
        val task = getRequiredTask(taskId)
        val now = clockProvider.now()

        if (task.status == TranscriptionStatus.COMPLETED) {
            log.info("Task is already COMPLETED, skipping duplicate completed event: taskId={}", task.id)
            return task
        }

        if (task.status == TranscriptionStatus.FAILED) {
            log.warn("Task is already FAILED, ignoring completed event: taskId={}", task.id)
            return task
        }

        task.markCompleted(
            resultTxtPath = resultTxtPath,
            resultMdPath = resultMdPath,
            detectedLanguage = detectedLanguage,
            now = now
        )

        log.info("Marked transcription task as COMPLETED: taskId={}", task.id)

        return task
    }

    @Transactional
    fun markFailed(
        taskId: UUID,
        errorMessage: String
    ): TranscriptionTask {
        val task = getRequiredTask(taskId)
        val now = clockProvider.now()

        if (task.status == TranscriptionStatus.COMPLETED) {
            log.warn("Task is already COMPLETED, ignoring failed event: taskId={}", task.id)
            return task
        }

        if (task.status == TranscriptionStatus.FAILED) {
            log.info("Task is already FAILED, skipping duplicate failed event: taskId={}", task.id)
            return task
        }

        task.markFailed(
            errorMessage = errorMessage,
            now = now
        )

        log.info("Marked transcription task as FAILED: taskId={}", task.id)

        return task
    }

    private fun validateActiveTaskLimit(telegramUserId: Long) {
        val maxActiveTasks = appProperties.transcription.maxActiveTasksPerUser

        if (maxActiveTasks <= 0) {
            return
        }

        val activeTasksCount = repository.countByTelegramUserIdAndStatusIn(
            telegramUserId = telegramUserId,
            statuses = activeStatuses
        )

        if (activeTasksCount >= maxActiveTasks) {
            throw ActiveTaskLimitExceededException(
                "User $telegramUserId already has $activeTasksCount active transcription task(s)"
            )
        }
    }

    private fun getRequiredTask(taskId: UUID): TranscriptionTask {
        return repository.findById(taskId)
            .orElseThrow {
                TranscriptionTaskNotFoundException("Transcription task not found: $taskId")
            }
    }
}

class ActiveTaskLimitExceededException(
    message: String
) : RuntimeException(message)

class TranscriptionTaskNotFoundException(
    message: String
) : RuntimeException(message)