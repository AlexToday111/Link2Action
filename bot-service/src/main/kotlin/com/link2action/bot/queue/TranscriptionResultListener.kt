package com.link2action.bot.queue

import com.link2action.bot.artifact.ArtifactType
import com.link2action.bot.artifact.TranscriptionTaskArtifactService
import com.link2action.bot.config.AppProperties
import com.link2action.bot.observability.TranscriptionMetrics
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
    private val telegramMessageSender: TelegramMessageSender,
    private val transcriptionMetrics: TranscriptionMetrics,
    private val appProperties: AppProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @RabbitListener(queues = ["\${app.rabbit.result-queue}"])
    fun handle(event: TranscriptionResultEvent) {
        log.info(
            "Received transcription result event: taskId={}, status={}",
            event.taskId,
            event.status
        )
        transcriptionMetrics.recordResultEvent(event.status.uppercase())

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
        if (
            event.resultTxtPath.isNullOrBlank() &&
            event.resultMdPath.isNullOrBlank() &&
            event.resultPromptPath.isNullOrBlank() &&
            event.resultPackagePath.isNullOrBlank()
        ) {
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
            resultPromptPath = event.resultPromptPath,
            resultPackagePath = event.resultPackagePath,
            title = event.title,
            durationSeconds = event.durationSeconds,
            detectedLanguage = event.language
        )

        editTaskMessage(
            taskId = task.id,
            chatId = task.telegramChatId,
            messageId = task.progressMessageId,
            text = """
                ${if (task.processingMode.name == "ACTION_ITEMS") "Action package готов" else "Готово."}

                ${if (task.processingMode.name == "ACTION_ITEMS") "Я подготовил transcript и prompt, чтобы быстро извлечь action items в выбранной LLM." else "Отправляю выбранные файлы."}
            """.trimIndent(),
            replyMarkup = resultNavigationKeyboard(task)
        )

        if (!event.resultPackagePath.isNullOrBlank()) {
            sendAndRecord(task, event.resultPackagePath, "📦 LLM Package", ArtifactType.PACKAGE)
        }

        if (!event.resultPromptPath.isNullOrBlank()) {
            sendAndRecord(task, event.resultPromptPath, "📋 LLM prompt", ArtifactType.PROMPT)
        }

        if (!event.resultMdPath.isNullOrBlank()) {
            sendAndRecord(task, event.resultMdPath, buildTranscriptCaption(task, "Markdown", "📝"), ArtifactType.MD)
        }

        if (!event.resultTxtPath.isNullOrBlank()) {
            sendAndRecord(task, event.resultTxtPath, buildTranscriptCaption(task, "TXT", "📄"), ArtifactType.TXT)
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

    private fun sendAndRecord(
        task: TranscriptionTask,
        filePath: String,
        caption: String,
        artifactType: ArtifactType
    ) {
        val sent = telegramMessageSender.sendDocument(
            chatId = task.telegramChatId,
            filePath = filePath,
            caption = caption
        )

        if (sent != null) {
            artifactService.recordArtifact(
                taskId = task.id,
                telegramChatId = task.telegramChatId,
                telegramMessageId = sent.messageId,
                artifactType = artifactType,
                filePath = filePath
            )
        }
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

    private fun resultNavigationKeyboard(task: TranscriptionTask? = null): Map<String, Any> {
        val rows = mutableListOf<List<Map<String, String>>>()

        if (task != null) {
            val downloads = mutableListOf<Map<String, String>>()
            if (!task.resultTxtPath.isNullOrBlank()) {
                downloads.add(callbackButton("📄 Скачать TXT", "get_txt:${task.id}"))
            }
            if (!task.resultMdPath.isNullOrBlank()) {
                downloads.add(callbackButton("📝 Скачать Markdown", "get_md:${task.id}"))
            }
            if (!task.resultPromptPath.isNullOrBlank()) {
                downloads.add(callbackButton("📋 Скачать Prompt", "get_prompt:${task.id}"))
            }
            if (!task.resultPackagePath.isNullOrBlank()) {
                downloads.add(callbackButton("📦 Скачать Package", "get_pkg:${task.id}"))
            }
            if (!task.resultPromptPath.isNullOrBlank() || !task.resultPackagePath.isNullOrBlank()) {
                downloads.add(callbackButton("📥 Скачать файлы", "get_result:${task.id}"))
            }
            downloads.chunked(2).forEach { row ->
                rows.add(row)
            }

            if (
                !task.resultTxtPath.isNullOrBlank() ||
                !task.resultMdPath.isNullOrBlank() ||
                !task.resultPromptPath.isNullOrBlank() ||
                !task.resultPackagePath.isNullOrBlank()
            ) {
                rows.add(listOf(callbackButton("🧠 LLM Launcher", "llm_task:${task.id}")))
            }

            if (appProperties.llmLauncher.enabled) {
                rows.add(
                    listOf(
                        urlButton("🤖 ChatGPT", appProperties.llmLauncher.chatgptUrl),
                        urlButton("🟣 Claude", appProperties.llmLauncher.claudeUrl)
                    )
                )
                rows.add(
                    listOf(
                        urlButton("🔷 Gemini", appProperties.llmLauncher.geminiUrl),
                        urlButton("🔎 Perplexity", appProperties.llmLauncher.perplexityUrl)
                    )
                )
            }
        }

        rows.add(listOf(callbackButton("📜 Мои задачи", "history")))
        rows.add(listOf(callbackButton("🏠 Меню", "menu")))

        return mapOf("inline_keyboard" to rows)
    }

    private fun callbackButton(text: String, callbackData: String): Map<String, String> {
        return mapOf(
            "text" to text,
            "callback_data" to callbackData
        )
    }

    private fun urlButton(text: String, url: String): Map<String, String> {
        return mapOf(
            "text" to text,
            "url" to url
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
