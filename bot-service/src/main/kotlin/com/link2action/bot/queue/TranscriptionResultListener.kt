package com.link2action.bot.queue

import com.link2action.bot.artifact.ArtifactType
import com.link2action.bot.artifact.TranscriptionTaskArtifactService
import com.link2action.bot.task.TranscriptionTask
import com.link2action.bot.task.TranscriptionTaskService
import com.link2action.bot.telegram.TelegramMessageSender
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component

@Component
class TranscriptionResultListener(
    private val taskService: TranscriptionTaskService,
    private val artifactService: TranscriptionTaskArtifactService,
    private val telegramMessageSender: TelegramMessageSender
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @RabbitListener(queues = ["\${app.rabbit.result-queue}"])
    fun handle(event: TranscriptionResultEvent) {
        log.info(
            "Received transcription result event: taskId={}, status={}",
            event.taskId,
            event.status
        )

        when (event.status.uppercase()) {
            TranscriptionResultStatus.COMPLETED -> handleCompleted(event)
            TranscriptionResultStatus.FAILED -> handleFailed(event)
            TranscriptionResultStatus.DOWNLOADING,
            TranscriptionResultStatus.TRANSCRIBING,
            TranscriptionResultStatus.EXPORTING -> handleProgress(event)

            else -> {
                log.warn(
                    "Unknown transcription result status: taskId={}, status={}",
                    event.taskId,
                    event.status
                )
            }
        }
    }

    private fun handleCompleted(event: TranscriptionResultEvent) {
        if (event.resultTxtPath.isNullOrBlank() && event.resultMdPath.isNullOrBlank()) {
            val task = taskService.markFailed(
                taskId = event.taskId,
                errorMessage = "Worker completed task but did not provide result files"
            )

            editTaskMessage(
                taskId = task.id,
                chatId = task.telegramChatId,
                messageId = task.progressMessageId,
                text = "Worker завершил задачу, но не передал файлы результата."
            )

            return
        }

        val task = taskService.markCompleted(
            taskId = event.taskId,
            resultTxtPath = event.resultTxtPath,
            resultMdPath = event.resultMdPath,
            title = event.title,
            durationSeconds = event.durationSeconds,
            detectedLanguage = event.language
        )

        editTaskMessage(
            taskId = task.id,
            chatId = task.telegramChatId,
            messageId = task.progressMessageId,
            text = """
                Готово.

                Отправляю выбранные файлы.
            """.trimIndent(),
            replyMarkup = resultNavigationKeyboard()
        )

        if (!event.resultMdPath.isNullOrBlank()) {
            val sent = telegramMessageSender.sendDocument(
                chatId = task.telegramChatId,
                filePath = event.resultMdPath,
                caption = buildTranscriptCaption(
                    task = task,
                    format = "Markdown",
                    icon = "📝"
                )
            )

            if (sent != null) {
                artifactService.recordArtifact(
                    taskId = task.id,
                    telegramChatId = task.telegramChatId,
                    telegramMessageId = sent.messageId,
                    artifactType = ArtifactType.MD,
                    filePath = event.resultMdPath
                )
            }
        }

        if (!event.resultTxtPath.isNullOrBlank()) {
            val sent = telegramMessageSender.sendDocument(
                chatId = task.telegramChatId,
                filePath = event.resultTxtPath,
                caption = buildTranscriptCaption(
                    task = task,
                    format = "TXT",
                    icon = "📄"
                )
            )

            if (sent != null) {
                artifactService.recordArtifact(
                    taskId = task.id,
                    telegramChatId = task.telegramChatId,
                    telegramMessageId = sent.messageId,
                    artifactType = ArtifactType.TXT,
                    filePath = event.resultTxtPath
                )
            }
        }

        log.info("Completed transcription task delivery: taskId={}", event.taskId)
    }

    private fun handleFailed(event: TranscriptionResultEvent) {
        val errorMessage = event.errorMessage
            ?: "Unknown transcription error"

        val task = taskService.markFailed(
            taskId = event.taskId,
            errorMessage = errorMessage
        )

        editTaskMessage(
            taskId = task.id,
            chatId = task.telegramChatId,
            messageId = task.progressMessageId,
            text = """
                Не получилось расшифровать видео.

                Причина:
                $errorMessage
            """.trimIndent(),
            replyMarkup = resultNavigationKeyboard()
        )

        log.info("Failed transcription task processed: taskId={}", event.taskId)
    }

    private fun handleProgress(event: TranscriptionResultEvent) {
        val progress = taskService.markProgress(
            taskId = event.taskId,
            progressStatus = event.status.uppercase(),
            title = event.title,
            durationSeconds = event.durationSeconds,
            language = event.language
        )

        if (!progress.shouldNotify) {
            return
        }

        val text = when (event.status.uppercase()) {
            TranscriptionResultStatus.DOWNLOADING -> """
                Задача в работе.

                Статус: скачиваю аудио...
            """.trimIndent()
            TranscriptionResultStatus.TRANSCRIBING -> """
                Задача в работе.

                Статус: расшифровываю видео...
            """.trimIndent()
            TranscriptionResultStatus.EXPORTING -> """
                Задача в работе.

                Статус: готовлю файлы...
            """.trimIndent()
            else -> return
        }

        editTaskMessage(
            taskId = progress.task.id,
            chatId = progress.task.telegramChatId,
            messageId = progress.task.progressMessageId,
            text = text,
            replyMarkup = resultNavigationKeyboard()
        )
    }

    private fun editTaskMessage(
        taskId: java.util.UUID,
        chatId: Long,
        messageId: Long?,
        text: String,
        replyMarkup: Map<String, Any>? = null
    ) {
        val sent = if (messageId != null) {
            telegramMessageSender.editMessageText(
                chatId = chatId,
                messageId = messageId,
                text = text,
                replyMarkup = replyMarkup,
                disableWebPagePreview = true
            )
        } else {
            telegramMessageSender.sendText(
                chatId = chatId,
                text = text,
                replyMarkup = replyMarkup,
                disableWebPagePreview = true
            )
        }

        if (sent != null) {
            taskService.updateProgressMessageId(taskId, sent.messageId)
        }
    }

    private fun resultNavigationKeyboard(): Map<String, Any> {
        return mapOf(
            "inline_keyboard" to listOf(
                listOf(mapOf("text" to "📜 Мои задачи", "callback_data" to "history")),
                listOf(mapOf("text" to "🏠 Меню", "callback_data" to "menu"))
            )
        )
    }

    private fun buildTranscriptCaption(
        task: TranscriptionTask,
        format: String,
        icon: String
    ): String {
        val lines = mutableListOf(
            "$icon $format расшифровка",
            "",
            "Задача: ${task.id.toString().take(8)}"
        )

        task.title
            ?.takeIf { it.isNotBlank() }
            ?.let { lines.add("Видео: ${it.take(CAPTION_TITLE_LIMIT)}") }

        task.durationSeconds
            ?.let { lines.add("Длительность: ${formatDuration(it)}") }

        task.language
            ?.takeIf { it.isNotBlank() }
            ?.let { lines.add("Язык: $it") }

        return lines.joinToString("\n")
    }

    private fun buildCompletedMessage(event: TranscriptionResultEvent): String {
        val titleLine = event.title
            ?.takeIf { it.isNotBlank() }
            ?.let { "Видео: $it\n" }
            ?: ""

        val languageLine = event.language
            ?.takeIf { it.isNotBlank() }
            ?.let { "Язык: $it\n" }
            ?: ""

        val durationLine = event.durationSeconds
            ?.let { "Длительность: ${formatDuration(it)}\n" }
            ?: ""

        return """
            Расшифровка готова.

            $titleLine$languageLine$durationLine
            Отправляю файлы.
        """.trimIndent()
    }

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return if (hours > 0) {
            "%02d:%02d:%02d".format(hours, minutes, secs)
        } else {
            "%02d:%02d".format(minutes, secs)
        }
    }

    private companion object {
        const val CAPTION_TITLE_LIMIT = 120
    }
}
