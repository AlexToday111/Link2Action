package com.link2action.bot.task

import com.link2action.bot.common.ClockProvider
import com.link2action.bot.config.AppProperties
import com.link2action.bot.observability.TranscriptionMetrics
import com.link2action.bot.queue.TranscriptionRequestPublisher
import com.link2action.bot.queue.TranscriptionRequestedEvent
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

@Service
class TranscriptionTaskService(
    private val repository: TranscriptionTaskRepository,
    private val requestPublisher: TranscriptionRequestPublisher,
    private val appProperties: AppProperties,
    private val clockProvider: ClockProvider,
    private val transcriptionMetrics: TranscriptionMetrics
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val activeStatuses = setOf(
        TranscriptionStatus.QUEUED,
        TranscriptionStatus.PROCESSING
    )

    @Transactional
    fun createWaitingFormatTask(command: CreateTranscriptionTaskCommand): UUID {
        validateSource(command)
        validateActiveTaskLimit(command.telegramUserId)

        val now = clockProvider.now()
        val taskId = UUID.randomUUID()

        val task = TranscriptionTask(
            id = taskId,
            telegramChatId = command.telegramChatId,
            telegramUserId = command.telegramUserId,
            sourceType = command.sourceType,
            sourceUrl = normalizedSourceUrl(command),
            telegramFileId = command.telegramFileId?.trim(),
            telegramFileUniqueId = command.telegramFileUniqueId?.trim(),
            originalFileName = command.originalFileName?.trim(),
            mimeType = command.mimeType?.trim(),
            fileSizeBytes = command.fileSizeBytes,
            status = TranscriptionStatus.WAITING_FORMAT,
            requestedFormat = "PENDING",
            language = command.language,
            createdAt = now,
            updatedAt = now
        )

        repository.save(task)
        transcriptionMetrics.recordTaskCreated()

        log.info(
            "Created waiting format transcription task: taskId={}, userId={}, chatId={}",
            task.id,
            task.telegramUserId,
            task.telegramChatId
        )

        return task.id
    }

    @Transactional
    fun createTask(
        command: CreateTranscriptionTaskCommand,
        enforceActiveTaskLimit: Boolean = true
    ): UUID {
        validateSource(command)
        val formats = normalizeFormats(command.requestedFormats)
        val idempotencyKey = buildIdempotencyKey(
            telegramUserId = command.telegramUserId,
            sourceType = command.sourceType,
            sourceUrl = normalizedSourceUrl(command),
            telegramFileUniqueId = command.telegramFileUniqueId,
            telegramFileId = command.telegramFileId,
            requestedFormat = formats.joinToString(","),
            language = command.language
        )
        val existingTask = findActiveTaskByIdempotencyKey(idempotencyKey)

        if (existingTask != null) {
            log.info(
                "Returning existing active transcription task: taskId={}, userId={}, idempotencyKey={}",
                existingTask.id,
                existingTask.telegramUserId,
                idempotencyKey
            )
            return existingTask.id
        }

        if (enforceActiveTaskLimit) {
            validateActiveTaskLimit(command.telegramUserId)
        }

        val now = clockProvider.now()
        val taskId = UUID.randomUUID()

        val task = TranscriptionTask(
            id = taskId,
            telegramChatId = command.telegramChatId,
            telegramUserId = command.telegramUserId,
            sourceType = command.sourceType,
            sourceUrl = normalizedSourceUrl(command),
            telegramFileId = command.telegramFileId?.trim(),
            telegramFileUniqueId = command.telegramFileUniqueId?.trim(),
            originalFileName = command.originalFileName?.trim(),
            mimeType = command.mimeType?.trim(),
            fileSizeBytes = command.fileSizeBytes,
            status = TranscriptionStatus.QUEUED,
            requestedFormat = formats.joinToString(","),
            language = command.language,
            idempotencyKey = idempotencyKey,
            createdAt = now,
            updatedAt = now
        )

        repository.save(task)
        transcriptionMetrics.recordTaskCreated()

        val event = TranscriptionRequestedEvent(
            taskId = task.id,
            sourceType = task.sourceType,
            sourceUrl = task.sourceUrl,
            telegramFileId = task.telegramFileId,
            telegramFileUniqueId = task.telegramFileUniqueId,
            originalFileName = task.originalFileName,
            mimeType = task.mimeType,
            fileSizeBytes = task.fileSizeBytes,
            language = task.language,
            formats = formats,
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

    @Transactional
    fun enqueueWaitingTask(
        taskId: UUID,
        telegramUserId: Long,
        requestedFormats: Collection<String>
    ): FormatSelectionResult? {
        val task = repository.findByIdAndTelegramUserIdAndDeletedAtIsNull(
            id = taskId,
            telegramUserId = telegramUserId
        ) ?: return null

        if (task.status != TranscriptionStatus.WAITING_FORMAT) {
            return FormatSelectionResult(
                task = task,
                queued = false,
                alreadySelected = true
            )
        }

        val formats = normalizeFormats(requestedFormats)
        val idempotencyKey = buildIdempotencyKey(
            telegramUserId = task.telegramUserId,
            sourceType = task.sourceType,
            sourceUrl = task.sourceUrl,
            telegramFileUniqueId = task.telegramFileUniqueId,
            telegramFileId = task.telegramFileId,
            requestedFormat = formats.joinToString(","),
            language = task.language
        )
        val existingTask = findActiveTaskByIdempotencyKey(idempotencyKey)

        if (existingTask != null) {
            task.markDeleted(clockProvider.now())

            log.info(
                "Returning existing active transcription task after duplicate format selection: taskId={}, duplicateTaskId={}, idempotencyKey={}",
                existingTask.id,
                task.id,
                idempotencyKey
            )

            return FormatSelectionResult(
                task = existingTask,
                queued = false,
                alreadySelected = false,
                duplicateActive = true
            )
        }

        validateActiveTaskLimit(telegramUserId)

        val now = clockProvider.now()
        task.markQueued(
            requestedFormats = formats,
            idempotencyKey = idempotencyKey,
            now = now
        )

        publishRequest(task, formats)

        log.info(
            "Queued transcription task after format selection: taskId={}, formats={}",
            task.id,
            formats
        )

        return FormatSelectionResult(
            task = task,
            queued = true,
            alreadySelected = false
        )
    }

    @Transactional
    fun cancelWaitingTask(
        taskId: UUID,
        telegramUserId: Long
    ): FormatCancelResult? {
        val task = repository.findByIdAndTelegramUserIdAndDeletedAtIsNull(
            id = taskId,
            telegramUserId = telegramUserId
        ) ?: return null

        if (task.status != TranscriptionStatus.WAITING_FORMAT) {
            return FormatCancelResult(
                task = task,
                cancelled = false
            )
        }

        task.markDeleted(clockProvider.now())

        log.info("Cancelled waiting format transcription task: taskId={}", task.id)

        return FormatCancelResult(
            task = task,
            cancelled = true
        )
    }

    @Transactional
    fun softDeleteTask(
        taskId: UUID,
        telegramUserId: Long
    ): TranscriptionTask? {
        val task = repository.findByIdAndTelegramUserIdAndDeletedAtIsNull(
            id = taskId,
            telegramUserId = telegramUserId
        ) ?: return null

        task.markDeleted(clockProvider.now())

        log.info("Soft deleted transcription task: taskId={}, userId={}", task.id, telegramUserId)

        return task
    }

    @Transactional(readOnly = true)
    fun getTask(taskId: UUID): TranscriptionTask? {
        return repository.findById(taskId).orElse(null)
    }

    @Transactional(readOnly = true)
    fun getUserTasks(telegramUserId: Long): List<TranscriptionTask> {
        return repository.findByTelegramUserIdOrderByCreatedAtDesc(telegramUserId)
    }

    @Transactional(readOnly = true)
    fun getLatestUserTasks(
        telegramUserId: Long,
        limit: Int
    ): List<TranscriptionTask> {
        return repository.findByTelegramUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            telegramUserId,
            PageRequest.of(0, limit.coerceAtLeast(1))
        )
    }

    @Transactional(readOnly = true)
    fun getActiveUserTasks(telegramUserId: Long): List<TranscriptionTask> {
        return repository.findByTelegramUserIdAndDeletedAtIsNullAndStatusInOrderByCreatedAtDesc(
            telegramUserId = telegramUserId,
            statuses = activeStatuses
        )
    }

    @Transactional(readOnly = true)
    fun getUserTask(
        taskId: UUID,
        telegramUserId: Long
    ): TranscriptionTask? {
        return repository.findByIdAndTelegramUserIdAndDeletedAtIsNull(
            id = taskId,
            telegramUserId = telegramUserId
        )
    }

    @Transactional
    fun repeatTask(
        taskId: UUID,
        telegramUserId: Long,
        telegramChatId: Long
    ): UUID? {
        val sourceTask = getUserTask(
            taskId = taskId,
            telegramUserId = telegramUserId
        ) ?: return null

        return createWaitingFormatTask(
            CreateTranscriptionTaskCommand(
                telegramChatId = telegramChatId,
                telegramUserId = telegramUserId,
                sourceType = sourceTask.sourceType,
                sourceUrl = sourceTask.sourceUrl,
                telegramFileId = sourceTask.telegramFileId,
                telegramFileUniqueId = sourceTask.telegramFileUniqueId,
                originalFileName = sourceTask.originalFileName,
                mimeType = sourceTask.mimeType,
                fileSizeBytes = sourceTask.fileSizeBytes,
                language = sourceTask.language
            )
        )
    }

    @Transactional
    fun clearTaskResultPaths(task: TranscriptionTask) {
        task.resultTxtPath = null
        task.resultMdPath = null
        task.updatedAt = clockProvider.now()
    }

    @Transactional
    fun updateProgressMessageId(
        taskId: UUID,
        messageId: Long
    ) {
        val task = getRequiredTask(taskId)
        task.progressMessageId = messageId
        task.updatedAt = clockProvider.now()
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
        title: String? = null,
        durationSeconds: Long? = null,
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
            title = title,
            durationSeconds = durationSeconds,
            detectedLanguage = detectedLanguage,
            now = now
        )
        transcriptionMetrics.recordTaskCompleted(taskProcessingDuration(task))

        log.info("Marked transcription task as COMPLETED: taskId={}", task.id)

        return task
    }

    @Transactional
    fun markProgress(
        taskId: UUID,
        progressStatus: String,
        title: String?,
        durationSeconds: Long?,
        language: String?
    ): ProgressUpdateResult {
        val task = getRequiredTask(taskId)
        val now = clockProvider.now()

        if (task.status == TranscriptionStatus.COMPLETED || task.status == TranscriptionStatus.FAILED) {
            log.info(
                "Ignoring progress event for finished task: taskId={}, progressStatus={}, currentStatus={}",
                task.id,
                progressStatus,
                task.status
            )
            return ProgressUpdateResult(task, false)
        }

        val shouldNotify = task.lastProgressStatus != progressStatus

        if (task.status == TranscriptionStatus.QUEUED) {
            task.status = TranscriptionStatus.PROCESSING
        }

        if (task.startedAt == null) {
            task.startedAt = now
        }

        task.title = title ?: task.title
        task.durationSeconds = durationSeconds ?: task.durationSeconds
        task.language = language ?: task.language
        task.lastProgressStatus = progressStatus
        task.updatedAt = now

        log.info(
            "Updated transcription task progress: taskId={}, progressStatus={}, shouldNotify={}",
            task.id,
            progressStatus,
            shouldNotify
        )

        return ProgressUpdateResult(task, shouldNotify)
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
        transcriptionMetrics.recordTaskFailed(taskProcessingDuration(task))

        log.info("Marked transcription task as FAILED: taskId={}", task.id)

        return task
    }

    private fun validateActiveTaskLimit(telegramUserId: Long) {
        val maxActiveTasks = appProperties.transcription.maxActiveTasksPerUser

        if (maxActiveTasks <= 0) {
            return
        }

        val activeTasksCount = repository.countByTelegramUserIdAndDeletedAtIsNullAndStatusIn(
            telegramUserId = telegramUserId,
            statuses = activeStatuses
        )

        if (activeTasksCount >= maxActiveTasks) {
            throw ActiveTaskLimitExceededException(
                "User $telegramUserId already has $activeTasksCount active transcription task(s)"
            )
        }
    }

    private fun publishRequest(
        task: TranscriptionTask,
        requestedFormats: Collection<String>
    ) {
        val event = TranscriptionRequestedEvent(
            taskId = task.id,
            sourceType = task.sourceType,
            sourceUrl = task.sourceUrl,
            telegramFileId = task.telegramFileId,
            telegramFileUniqueId = task.telegramFileUniqueId,
            originalFileName = task.originalFileName,
            mimeType = task.mimeType,
            fileSizeBytes = task.fileSizeBytes,
            language = task.language,
            formats = requestedFormats.toList(),
            createdAt = task.createdAt
        )

        requestPublisher.publish(event)
    }

    private fun normalizeFormats(requestedFormats: Collection<String>): List<String> {
        val formats = requestedFormats
            .map { it.trim().uppercase() }
            .filter { it == "TXT" || it == "MD" }
            .distinct()
            .sortedBy { FORMAT_ORDER[it] ?: Int.MAX_VALUE }

        if (formats.isEmpty()) {
            throw IllegalArgumentException("No supported transcription result formats were requested")
        }

        return formats
    }

    private fun findActiveTaskByIdempotencyKey(idempotencyKey: String): TranscriptionTask? {
        return repository.findFirstByIdempotencyKeyAndDeletedAtIsNullAndStatusInOrderByCreatedAtDesc(
            idempotencyKey = idempotencyKey,
            statuses = activeStatuses
        )
    }

    private fun taskProcessingDuration(task: TranscriptionTask): Duration? {
        val startedAt = task.startedAt ?: task.createdAt
        val finishedAt = task.finishedAt ?: return null

        return Duration.between(startedAt, finishedAt)
    }

    private fun buildIdempotencyKey(
        telegramUserId: Long,
        sourceType: TranscriptionSourceType,
        sourceUrl: String?,
        telegramFileUniqueId: String?,
        telegramFileId: String?,
        requestedFormat: String,
        language: String?
    ): String {
        val sourceIdentity = when (sourceType) {
            TranscriptionSourceType.URL -> sourceUrl?.trim().orEmpty()
            TranscriptionSourceType.TELEGRAM_FILE -> telegramFileUniqueId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: telegramFileId?.trim().orEmpty()
        }
        val rawKey = listOf(
            telegramUserId.toString(),
            sourceType.name,
            sourceIdentity,
            requestedFormat.trim().uppercase(),
            language?.trim()?.lowercase().orEmpty()
        ).joinToString("|")

        return MessageDigest
            .getInstance("MD5")
            .digest(rawKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun getRequiredTask(taskId: UUID): TranscriptionTask {
        return repository.findById(taskId)
            .orElseThrow {
                TranscriptionTaskNotFoundException("Transcription task not found: $taskId")
            }
    }

    private fun validateSource(command: CreateTranscriptionTaskCommand) {
        when (command.sourceType) {
            TranscriptionSourceType.URL -> {
                require(!command.sourceUrl.isNullOrBlank()) {
                    "sourceUrl is required for URL transcription source"
                }
            }

            TranscriptionSourceType.TELEGRAM_FILE -> {
                require(!command.telegramFileId.isNullOrBlank()) {
                    "telegramFileId is required for Telegram file transcription source"
                }
            }
        }
    }

    private fun normalizedSourceUrl(command: CreateTranscriptionTaskCommand): String? {
        return command.sourceUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
}

class ActiveTaskLimitExceededException(
    message: String
) : RuntimeException(message)

class TranscriptionTaskNotFoundException(
    message: String
) : RuntimeException(message)

data class ProgressUpdateResult(
    val task: TranscriptionTask,
    val shouldNotify: Boolean
)

data class FormatSelectionResult(
    val task: TranscriptionTask,
    val queued: Boolean,
    val alreadySelected: Boolean,
    val duplicateActive: Boolean = false
)

data class FormatCancelResult(
    val task: TranscriptionTask,
    val cancelled: Boolean
)

private val FORMAT_ORDER = mapOf(
    "TXT" to 0,
    "MD" to 1
)
