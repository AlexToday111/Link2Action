package com.link2action.bot.queue

import com.link2action.bot.task.TranscriptionTaskService
import com.link2action.bot.telegram.TelegramMessageSender
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component

@Component
class TranscriptionResultListener(
    private val taskService: TranscriptionTaskService,
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

            telegramMessageSender.sendText(
                chatId = task.telegramChatId,
                text = "Worker завершил задачу, но не передал файлы результата."
            )

            return
        }

        val task = taskService.markCompleted(
            taskId = event.taskId,
            resultTxtPath = event.resultTxtPath,
            resultMdPath = event.resultMdPath,
            detectedLanguage = event.language
        )

        telegramMessageSender.sendText(
            chatId = task.telegramChatId,
            text = buildCompletedMessage(event)
        )

        if (!event.resultMdPath.isNullOrBlank()) {
            telegramMessageSender.sendDocument(
                chatId = task.telegramChatId,
                filePath = event.resultMdPath,
                caption = "Расшифровка в Markdown"
            )
        }

        if (!event.resultTxtPath.isNullOrBlank()) {
            telegramMessageSender.sendDocument(
                chatId = task.telegramChatId,
                filePath = event.resultTxtPath,
                caption = "Расшифровка в TXT"
            )
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

        telegramMessageSender.sendText(
            chatId = task.telegramChatId,
            text = """
                Не получилось расшифровать видео.

                Причина:
                $errorMessage
            """.trimIndent()
        )

        log.info("Failed transcription task processed: taskId={}", event.taskId)
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
}